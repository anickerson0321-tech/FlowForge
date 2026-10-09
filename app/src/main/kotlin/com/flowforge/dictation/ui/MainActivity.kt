package com.flowforge.dictation.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.flowforge.core.layout.AdaptiveLayout
import com.flowforge.dictation.graph
import com.flowforge.dictation.ui.kit.Ui
import com.flowforge.dictation.ui.screens.CleanupScreen
import com.flowforge.dictation.ui.screens.DictationSettingsScreen
import com.flowforge.dictation.ui.screens.DictionaryScreen
import com.flowforge.dictation.ui.screens.EngineScreen
import com.flowforge.dictation.ui.screens.HelpScreen
import com.flowforge.dictation.ui.screens.HistoryScreen
import com.flowforge.dictation.ui.screens.HomeScreen
import com.flowforge.dictation.ui.screens.PermissionsScreen
import com.flowforge.dictation.ui.screens.PrivacyScreen
import com.flowforge.dictation.ui.screens.Screen

/**
 * Single activity hosting all screens. On wide windows (the Galaxy Z Fold inner display,
 * tablets, landscape) it shows a navigation pane next to the content; on narrow windows
 * (cover screen, phones) one screen at a time with a back bar. Folding/unfolding is handled as a
 * configuration change, so the current screen and its state are kept.
 */
class MainActivity : Activity() {

    enum class Dest(val key: String, val label: String, val icon: String) {
        HOME("home", "Home", "◉"),
        DICTATION("dictation", "Dictation", "🎙"),
        ENGINE("engine", "Speech engine", "⚙"),
        CLEANUP("cleanup", "Cleanup modes", "✎"),
        DICTIONARY("dictionary", "Dictionary", "📖"),
        PRIVACY("privacy", "Privacy", "🔒"),
        HISTORY("history", "History", "🕘"),
        PERMISSIONS("permissions", "Setup & permissions", "✓"),
        HELP("help", "Help", "?"),
    }

    var current: Dest = Dest.HOME
        private set
    private var screen: Screen? = null
    private var contentScroll: ScrollView? = null
    private var contentView: View? = null
    private val backCallback = OnBackInvokedCallback { navigate(Dest.HOME) }
    private var backRegistered = false
    private var firstResume = true

    val isWide: Boolean get() = AdaptiveLayout.isTwoPane(resources.configuration.screenWidthDp)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15+ draws edge-to-edge automatically for apps targeting API 35+; opt in on 13-14.
        if (android.os.Build.VERSION.SDK_INT < 35) {
            @Suppress("DEPRECATION")
            window.setDecorFitsSystemWindows(false)
        }
        current = savedInstanceState?.getString(KEY_DEST)?.let(::destFor)
            ?: intent?.getStringExtra(EXTRA_SCREEN)?.let(::destFor)
            ?: if (graph.settings.onboardingDone) Dest.HOME else Dest.PERMISSIONS
        rebuild()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_SCREEN)?.let(::destFor)?.let(::navigate)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_DEST, current.key)
    }

    override fun onResume() {
        super.onResume()
        // Screens that show setup status refresh, since permissions or system settings may have
        // changed while the user was away. Others keep their state (e.g. a half-typed API key).
        if (firstResume) firstResume = false else if (screen?.refreshOnResume == true) refresh()
    }

    override fun onPause() {
        screen?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        screen?.onDestroy()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Fold/unfold, rotation or a size change: rebuild the surrounding layout for the new width
        // but keep the current screen's views, so typed text and an in-app test session survive.
        val scrollY = contentScroll?.scrollY ?: 0
        rebuild(keepContent = true)
        contentScroll?.post { contentScroll?.scrollTo(0, scrollY) }
    }

    fun navigate(dest: Dest) {
        if (dest == current && screen != null) {
            refresh()
            return
        }
        current = dest
        rebuild()
    }

    /** Re-renders the current screen (e.g. after a setting changed) keeping the scroll position. */
    fun refresh() {
        val scrollY = contentScroll?.scrollY ?: 0
        rebuild()
        contentScroll?.post { contentScroll?.scrollTo(0, scrollY) }
    }

    private fun rebuild(keepContent: Boolean = false) {
        val ui = Ui(this)
        val kept = contentView?.takeIf { keepContent && screen != null }
        val s: Screen
        val content: View
        if (kept != null) {
            s = screen!!
            content = kept
            (kept.parent as? ViewGroup)?.removeView(kept)
        } else {
            screen?.onDestroy()
            s = createScreen(current)
            screen = s
            content = s.build(ui)
            contentView = content
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            val column = ui.column(spacingDp = 12).apply {
                setPadding(ui.dp(if (isWide) 28 else 16), ui.dp(16), ui.dp(if (isWide) 28 else 16), ui.dp(32))
                addView(content, ui.matchWidth())
            }
            // Keep a comfortable reading width on very wide windows (centered, never wider than 760dp).
            addView(MaxWidthLayout(this@MainActivity, ui.dp(760)).apply { addView(column) })
        }
        contentScroll = scroll

        val root: View = if (isWide) {
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(navPane(ui), LinearLayout.LayoutParams(ui.dp(280), ViewGroup.LayoutParams.MATCH_PARENT))
                addView(View(this@MainActivity).apply { setBackgroundColor(ui.palette.outline) }, LinearLayout.LayoutParams(ui.dp(1), ViewGroup.LayoutParams.MATCH_PARENT))
                addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            }
        } else {
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                if (current != Dest.HOME) addView(topBar(ui, s.title))
                addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            }
        }
        root.setBackgroundColor(ui.palette.background)
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
        setContentView(root)
        window.decorView.setBackgroundColor(ui.palette.background)
        updateBackHandling()
    }

    private fun navPane(ui: Ui): View {
        val list = ui.column(spacingDp = 2).apply {
            setPadding(ui.dp(12), ui.dp(20), ui.dp(12), ui.dp(20))
            addView(ui.text("FlowForge", 24f, ui.palette.onSurface, bold = true).apply { setPadding(ui.dp(12), 0, 0, ui.dp(16)) })
            Dest.values().forEach { d ->
                val selected = d == current
                val item = ui.text("${d.icon}   ${d.label}", 16f, if (selected) ui.palette.onAccentContainer else ui.palette.onSurface, bold = selected).apply {
                    minHeight = ui.dp(52)
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(ui.dp(16), 0, ui.dp(12), 0)
                    background = ui.ripple(ui.rounded(if (selected) ui.palette.accentContainer else 0, 26))
                    isClickable = true
                    isSelected = selected
                    contentDescription = d.label + if (selected) ", selected" else ""
                    setOnClickListener { navigate(d) }
                }
                addView(item, ui.matchWidth())
            }
        }
        return ScrollView(this).apply {
            setBackgroundColor(ui.palette.surface)
            addView(list)
        }
    }

    private fun topBar(ui: Ui, title: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = ui.dp(56)
        setPadding(ui.dp(4), 0, ui.dp(16), 0)
        setBackgroundColor(ui.palette.background)
        addView(TextView(this@MainActivity).apply {
            text = "←"
            textSize = 22f
            setTextColor(ui.palette.onSurface)
            gravity = Gravity.CENTER
            contentDescription = "Back to Home"
            background = ui.ripple(ui.rounded(0, 24))
            isClickable = true
            setOnClickListener { navigate(Dest.HOME) }
        }, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
        addView(ui.text(title, 20f, bold = true).apply { isAccessibilityHeading = true; setPadding(ui.dp(8), 0, 0, 0) })
    }

    private fun updateBackHandling() {
        val wantsBack = current != Dest.HOME
        if (wantsBack && !backRegistered) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback)
            backRegistered = true
        } else if (!wantsBack && backRegistered) {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backCallback)
            backRegistered = false
        }
    }

    private fun createScreen(d: Dest): Screen = when (d) {
        Dest.HOME -> HomeScreen(this)
        Dest.DICTATION -> DictationSettingsScreen(this)
        Dest.ENGINE -> EngineScreen(this)
        Dest.CLEANUP -> CleanupScreen(this)
        Dest.DICTIONARY -> DictionaryScreen(this)
        Dest.PRIVACY -> PrivacyScreen(this)
        Dest.HISTORY -> HistoryScreen(this)
        Dest.PERMISSIONS -> PermissionsScreen(this)
        Dest.HELP -> HelpScreen(this)
    }

    private fun destFor(key: String): Dest? = Dest.values().firstOrNull { it.key == key }

    // --- Helpers for screens ------------------------------------------------------------------------

    fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            toast("This settings page isn't available on your device.")
        }
    }

    fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    fun requestMic() = requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
    fun requestNotifications() = requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC && grantResults.firstOrNull() != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        ) {
            AlertDialog.Builder(this)
                .setTitle("Microphone access is off")
                .setMessage("FlowForge can't dictate without the microphone. Android won't ask again, so enable it under App info → Permissions → Microphone.")
                .setPositiveButton("Open App info") { _, _ -> open(Setup.appDetails(this)) }
                .setNegativeButton("Not now", null)
                .show()
        }
        refresh()
    }

    fun confirm(title: String, message: String, positive: String, onConfirm: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positive) { _, _ -> onConfirm() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        const val EXTRA_SCREEN = "com.flowforge.dictation.extra.SCREEN"
        private const val KEY_DEST = "dest"
        private const val REQ_MIC = 10
        private const val REQ_NOTIF = 11
    }
}

/** Centers a single child and caps its width, so text stays readable on large unfolded screens. */
private class MaxWidthLayout(context: android.content.Context, private val maxWidthPx: Int) : ViewGroup(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec)
        val childWidth = minOf(available, maxWidthPx)
        var height = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            c.measure(MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            height = maxOf(height, c.measuredHeight)
        }
        setMeasuredDimension(available, resolveSize(height, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val left = (width - c.measuredWidth) / 2
            c.layout(left, 0, left + c.measuredWidth, c.measuredHeight)
        }
    }
}
