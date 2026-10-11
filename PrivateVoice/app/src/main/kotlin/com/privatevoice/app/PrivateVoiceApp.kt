package com.privatevoice.app

import android.app.Application
import android.app.KeyguardManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.WindowManager
import android.widget.Toast
import com.privatevoice.app.audio.AudioRouteMonitor
import com.privatevoice.app.audio.PlaybackState
import com.privatevoice.app.audio.SpeechPlayer
import com.privatevoice.app.messaging.InMemoryMessageRepository
import com.privatevoice.app.messaging.NewMessageTracker
import com.privatevoice.app.notifications.ControlsNotifier
import com.privatevoice.app.notifications.MessagingNotificationParser
import com.privatevoice.app.notifications.SupportedApps
import com.privatevoice.app.overlay.FloatingButtonController
import com.privatevoice.app.privacy.DefaultPrivacyController
import com.privatevoice.app.reader.AudioRoute
import com.privatevoice.app.reader.DeviceState
import com.privatevoice.app.reader.ReaderController
import com.privatevoice.app.reader.ReaderListener
import com.privatevoice.app.reader.ReadingService
import com.privatevoice.app.settings.SettingsRepository
import com.privatevoice.app.speech.KokoroEngine
import com.privatevoice.app.speech.SystemTtsEngine
import com.privatevoice.app.voices.DefaultVoiceSelectionResolver
import com.privatevoice.app.voices.EncryptedFileAssignmentRepository
import com.privatevoice.app.voices.KeystoreSecretBox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

class PrivateVoiceApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.start()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Free the neural model under memory pressure when not speaking.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND && graph.player.state.value is PlaybackState.Idle) {
            graph.kokoro.release()
        }
    }

    companion object {
        fun graph(context: Context): AppGraph = (context.applicationContext as PrivateVoiceApp).graph
    }
}

/** Manual dependency wiring (small app; no DI framework needed). */
class AppGraph(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    val settings = SettingsRepository(app)
    val parser = MessagingNotificationParser()
    val repository = InMemoryMessageRepository(settings.current.pendingTtlMinutes * 60_000L, clock = { SystemClock.elapsedRealtime() })
    val tracker = NewMessageTracker()
    val assignments = EncryptedFileAssignmentRepository(File(app.noBackupFilesDir, "voice_assignments.bin"), KeystoreSecretBox())
    val resolver = DefaultVoiceSelectionResolver()
    val kokoro = KokoroEngine(app)
    val systemTts = SystemTtsEngine(app)
    val routes = AudioRouteMonitor(app, SpeechPlayer.ATTRIBUTES)
    val listenerConnected = MutableStateFlow(false)
    /** True while [ReadingService] is in the foreground. */
    val playbackForeground = MutableStateFlow(false)
    /** True while an activity of ours is resumed (top app). */
    val appVisible = MutableStateFlow(false)
    val notifier = ControlsNotifier(app)
    private val labels = ConcurrentHashMap<String, String>()

    val player = SpeechPlayer(app, kokoro, systemTts, routes, { settings.current }, scope, enterForeground = { ensureForegroundForAudio() })

    private val device = object : DeviceState {
        private val keyguard = app.getSystemService(KeyguardManager::class.java)
        override fun route(): AudioRoute = routes.currentRoute()
        override fun isLocked(): Boolean = keyguard.isKeyguardLocked
        override fun inCall(): Boolean = routes.inCall.value
        override fun minuteOfDay(): Int = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
        override fun nowMillis(): Long = System.currentTimeMillis()
        override fun elapsedMillis(): Long = SystemClock.elapsedRealtime()
    }

    val reader = ReaderController(
        repository = repository,
        tracker = tracker,
        player = player,
        resolver = resolver,
        settings = { settings.current },
        assignments = { assignments.assignments.value },
        device = device,
        warmUp = { warmUpEngine() },
    )

    val privacy = DefaultPrivacyController(app, reader, assignments) { repository.state.value.count }

    val overlay: FloatingButtonController by lazy {
        FloatingButtonController(
            context = overlayContext(),
            onTap = { startPlaybackService(); if (reader.readLatest() == com.privatevoice.app.reader.ReadResult.NOTHING_PENDING) toast("No messages waiting") },
            onStop = { reader.stop() },
            onPauseResume = { if (player.state.value is PlaybackState.Paused) reader.resume() else reader.pause() },
            onRepeat = { startPlaybackService(); if (!reader.repeat()) toast("Nothing to repeat") },
            onMoved = { x, y -> settings.update { it.copy(floatingX = x, floatingY = y) } },
        )
    }

    fun start() {
        routes.start()
        reader.listener = object : ReaderListener {
            override fun onPendingChanged() { mainHandler.post { refreshControls() } }
        }
        applyTextSettings()
        // Keep UI surfaces in sync with state (no message text flows through here).
        scope.launch {
            combine(repository.state, player.state, settings.settings) { p, s, st -> Triple(p, s, st) }.collectLatest { (p, s, st) ->
                notifier.update(p, s, st.showReadNotification)
                overlay.update(st, p, s)
            }
        }
        scope.launch {
            var lastAuto = settings.current.autoReadEnabled
            settings.settings.collectLatest { st ->
                applyTextSettings()
                repository.ttlMillis = st.pendingTtlMinutes * 60_000L
                if (lastAuto && !st.autoReadEnabled) reader.onAutoReadDisabled()
                lastAuto = st.autoReadEnabled
            }
        }
        // Expiry + idle model unloading.
        scope.launch {
            while (isActive) {
                delay(15_000)
                reader.tick()
                if (player.state.value is PlaybackState.Idle) kokoro.unloadIfIdle(MODEL_IDLE_MILLIS)
            }
        }
        scope.launch {
            player.events.collectLatest { e -> toast(e.message) }
        }
    }

    private fun applyTextSettings() {
        val st = settings.current
        kokoro.configureText(st.describeEmoji, st.expandShorthand)
    }

    fun refreshControls() {
        notifier.update(repository.state.value, player.state.value, settings.current.showReadNotification)
    }

    /** Loads the neural model in the background so the first message reads quickly. */
    fun warmUpEngine() {
        if (settings.current.preferSystemEngine) { systemTts.start(); return }
        scope.launch(Dispatchers.Default) { kokoro.ensureLoaded() }
    }

    /** Starts the playback foreground service. Safe to call when Android may refuse it. */
    fun startPlaybackService() {
        if (appVisible.value) return
        try {
            app.startForegroundService(Intent(app, ReadingService::class.java))
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException: reading still works, without audio focus.
        }
    }

    private suspend fun ensureForegroundForAudio(): Boolean {
        if (appVisible.value || playbackForeground.value) return true
        startPlaybackService()
        return withTimeoutOrNull(1500) { playbackForeground.first { it } } ?: false
    }

    fun appLabel(pkg: String): String = labels.getOrPut(pkg) {
        SupportedApps.DEFAULT[pkg] ?: try {
            app.packageManager.getApplicationLabel(app.packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))).toString()
        } catch (e: Exception) { pkg }
    }

    fun toast(msg: String) {
        mainHandler.post { Toast.makeText(app, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun overlayContext(): Context {
        val display = app.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        return app.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
    }

    companion object {
        const val MODEL_IDLE_MILLIS = 3 * 60_000L
    }
}
