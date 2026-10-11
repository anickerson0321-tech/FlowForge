package com.privatevoice.app.ui

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.privatevoice.app.AppGraph
import com.privatevoice.app.PrivateVoiceApp
import com.privatevoice.app.notifications.PrivateVoiceListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class Screen(val glyph: String, val title: String) {
    DASHBOARD("⌂", "Home"),
    VOICES("♪", "Voice Studio"),
    PEOPLE("☺", "People & Voices"),
    AUTO("⟳", "Automatic Reading"),
    CONTROLS("◉", "Floating Button & Shortcuts"),
    PRIVACY("⛨", "Privacy Center"),
    ABOUT("ⓘ", "About"),
}

/**
 * Single activity hosting all screens. Built with framework views; adapts to the
 * Galaxy Z Fold cover screen (single pane) and inner screen (navigation rail +
 * content) and handles fold/unfold/rotation without recreation.
 */
class MainActivity : Activity() {
    lateinit var graph: AppGraph
        private set
    lateinit var ui: Ui
        private set
    var screen: Screen = Screen.DASHBOARD
        private set
    private var uiScope: CoroutineScope? = null
    private var scroll: ScrollView? = null
    private val scrollPositions = HashMap<Screen, Int>()
    /** Live status bindings for the current screen, refreshed without rebuilding the view tree. */
    val live = ArrayList<() -> Unit>()
    private var backCallback: OnBackInvokedCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        graph = PrivateVoiceApp.graph(this)
        ui = Ui(this)
        window.decorView.setBackgroundColor(Palette.BG)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        savedInstanceState?.getString("screen")?.let { n -> Screen.entries.firstOrNull { it.name == n }?.let { screen = it } }
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("screen", screen.name)
    }

    override fun onResume() {
        super.onResume()
        graph.appVisible.value = true
        graph.routes.refresh()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        uiScope = scope
        scope.launch {
            combine(graph.repository.state, graph.player.state, graph.kokoro.state, graph.listenerConnected, graph.routes.route) { _, _, _, _, _ -> Unit }
                .collectLatest { refreshLive() }
        }
        scope.launch {
            graph.assignments.assignments.collectLatest { if (screen == Screen.PEOPLE) render() }
        }
        // Countdown labels (pending message expiry) and permission changes made in Settings.
        scope.launch { while (isActive) { delay(5_000); refreshLive() } }
        render()
    }

    override fun onPause() {
        graph.appVisible.value = false
        uiScope?.cancel()
        uiScope = null
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Fold/unfold or rotation: rebuild for the new width, keeping the current screen and scroll.
        render()
    }

    fun refreshLive() { live.forEach { runCatching { it() } } }

    fun navigate(to: Screen) {
        scroll?.let { scrollPositions[screen] = it.scrollY }
        screen = to
        render()
        scroll?.post { scroll?.scrollTo(0, if (to == Screen.DASHBOARD) scrollPositions[to] ?: 0 else 0) }
    }

    /** Rebuilds the current screen. */
    fun render() {
        val keepScroll = scroll?.scrollY ?: 0
        live.clear()
        val wide = resources.configuration.screenWidthDp >= 600
        val content = ui.column(if (wide) 28 else 16)
        content.clipToPadding = false
        when (screen) {
            Screen.DASHBOARD -> Screens.dashboard(this, content)
            Screen.VOICES -> Screens.voiceStudio(this, content)
            Screen.PEOPLE -> Screens.people(this, content)
            Screen.AUTO -> Screens.autoReading(this, content)
            Screen.CONTROLS -> Screens.controls(this, content)
            Screen.PRIVACY -> Screens.privacy(this, content)
            Screen.ABOUT -> Screens.about(this, content)
        }
        val sv = ScrollView(this).apply {
            isFillViewport = true
            addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        scroll = sv
        val body: View = if (wide) {
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(navRail(), LinearLayout.LayoutParams(ui.dp(260), ViewGroup.LayoutParams.MATCH_PARENT))
                // Keep reading width comfortable on the large inner display.
                val holder = FrameLayout(this@MainActivity)
                holder.addView(sv, FrameLayout.LayoutParams(minOf(ui.dp(760), resources.displayMetrics.widthPixels - ui.dp(260)), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL))
                addView(holder, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            }
        } else {
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                if (screen != Screen.DASHBOARD) addView(topBar())
                addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            }
        }
        body.setBackgroundColor(Palette.BG)
        body.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(body)
        sv.post { sv.scrollTo(0, keepScroll) }
        updateBackHandling()
        refreshLive()
    }

    private fun topBar(): View = ui.row {
        minimumHeight = ui.dp(64)
        setPadding(ui.dp(4), 0, ui.dp(16), 0)
        addView(TextView(this@MainActivity).apply {
            text = "←"; textSize = 22f; setTextColor(Palette.TEXT); gravity = Gravity.CENTER
            contentDescription = "Back"
            background = ui.ripple(ui.rounded(Color.TRANSPARENT, 24))
            layoutParams = LinearLayout.LayoutParams(ui.dp(56), ui.dp(56))
            setOnClickListener { navigate(Screen.DASHBOARD) }
        })
        addView(TextView(this@MainActivity).apply { text = screen.title; textSize = 20f; setTextColor(Palette.TEXT); typeface = android.graphics.Typeface.create("sans-serif-medium", 0) })
    }

    private fun navRail(): View {
        val rail = ui.column(16)
        rail.setBackgroundColor(Palette.SURFACE)
        with(ui) {
            rail.text("PrivateVoice", 22f, Palette.TEXT, bold = true).layoutParams = lp(top = 8, bottom = 4, start = 8)
            rail.caption("Private · Offline").layoutParams = lp(bottom = 20, start = 8)
        }
        Screen.entries.forEach { s ->
            val selected = s == screen
            rail.addView(ui.row {
                minimumHeight = ui.dp(52)
                setPadding(ui.dp(14), 0, ui.dp(12), 0)
                background = ui.ripple(ui.rounded(if (selected) Palette.PRIMARY_CONTAINER else Color.TRANSPARENT, 26))
                addView(TextView(this@MainActivity).apply { text = s.glyph; textSize = 18f; setTextColor(if (selected) Palette.PRIMARY else Palette.TEXT_DIM); layoutParams = LinearLayout.LayoutParams(ui.dp(32), ViewGroup.LayoutParams.WRAP_CONTENT) })
                addView(TextView(this@MainActivity).apply { text = s.title; textSize = 15f; setTextColor(if (selected) Palette.TEXT else Palette.TEXT_DIM) })
                setOnClickListener { navigate(s) }
                contentDescription = s.title
                layoutParams = ui.lp(bottom = 4)
            })
        }
        return ScrollView(this).apply { addView(rail); setBackgroundColor(Palette.SURFACE) }
    }

    private fun updateBackHandling() {
        if (Build.VERSION.SDK_INT < 33) return
        val needed = screen != Screen.DASHBOARD
        val existing = backCallback
        if (needed && existing == null) {
            val cb = OnBackInvokedCallback { navigate(Screen.DASHBOARD) }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
            backCallback = cb
        } else if (!needed && existing != null) {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(existing)
            backCallback = null
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (screen != Screen.DASHBOARD) navigate(Screen.DASHBOARD) else @Suppress("DEPRECATION") super.onBackPressed()
    }

    // ---- permission helpers

    fun notificationAccessGranted() = PrivateVoiceListenerService.isEnabled(this)

    fun openNotificationAccess() {
        val component = ComponentName(this, PrivateVoiceListenerService::class.java)
        val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
        try { startActivity(detail) } catch (e: Exception) {
            runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }
    }

    fun openAppInfo() {
        runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
    }

    fun postNotificationsGranted() = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun requestPostNotifications() {
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
    }

    fun openOverlaySettings() {
        runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
    }

    fun requestBatteryUnrestricted() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    companion object { const val REQ_NOTIFICATIONS = 7 }
}
