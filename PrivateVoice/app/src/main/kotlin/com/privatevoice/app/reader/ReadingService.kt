package com.privatevoice.app.reader

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.privatevoice.app.PrivateVoiceApp
import com.privatevoice.app.audio.PlaybackState
import com.privatevoice.app.notifications.ControlsNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service (type mediaPlayback) that runs only while a message is
 * being read. Android 15+ grants audio focus only to the top app or an app
 * with a foreground service, so this exists for the duration of playback and
 * stops itself a few seconds after playback ends. It does not keep the app
 * running in the background otherwise.
 */
class ReadingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val g = PrivateVoiceApp.graph(this)
        val notification = g.notifier.build(g.repository.state.value, g.player.state.value.let { if (it is PlaybackState.Idle) PlaybackState.Preparing("message") else it })
        try {
            startForeground(ControlsNotifier.ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            g.playbackForeground.value = true
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException etc.: playback continues without focus.
            g.playbackForeground.value = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (watcher == null) {
            watcher = scope.launch {
                g.player.state.collectLatest { st ->
                    if (st is PlaybackState.Idle) {
                        delay(IDLE_STOP_MILLIS)
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        g.playbackForeground.value = false
                        stopSelf()
                        g.refreshControls()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Defensive: if the system ever times this service out, stop cleanly.
        PrivateVoiceApp.graph(this).playbackForeground.value = false
        stopSelf()
    }

    override fun onDestroy() {
        PrivateVoiceApp.graph(this).playbackForeground.value = false
        scope.cancel()
        super.onDestroy()
    }

    companion object { const val IDLE_STOP_MILLIS = 4_000L }
}
