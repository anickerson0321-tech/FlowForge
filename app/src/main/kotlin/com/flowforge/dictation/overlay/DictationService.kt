package com.flowforge.dictation.overlay

import android.Manifest
import android.animation.ValueAnimator
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import com.flowforge.core.cleanup.CleanupMode
import com.flowforge.core.cleanup.InsertionContext
import com.flowforge.core.insertion.InsertResult
import com.flowforge.core.layout.BubbleGeometry
import com.flowforge.core.settings.InsertionPreference
import com.flowforge.core.layout.Bounds
import com.flowforge.core.insertion.InsertionPolicy
import com.flowforge.core.recognition.RecognitionErrorKind
import com.flowforge.core.session.Phase
import com.flowforge.core.session.SessionState
import com.flowforge.dictation.accessibility.FlowForgeAccessibilityService
import com.flowforge.dictation.dictation.DeliveryReport
import com.flowforge.dictation.dictation.DictationController
import com.flowforge.dictation.dictation.DictationOutput
import com.flowforge.dictation.graph
import com.flowforge.dictation.ui.MainActivity
import com.flowforge.dictation.ui.kit.Palette
import com.flowforge.dictation.ui.kit.Ui
import com.flowforge.dictation.ui.kit.dp
import com.flowforge.dictation.util.Notifications

/**
 * Foreground service (type microphone) that owns the floating bubble.
 *
 * Android rules followed here:
 *  - It is only started from a visible activity (app or Quick Settings trampoline), because a
 *    microphone foreground service may not be started from the background.
 *  - The microphone is used only between the user's start and stop taps; the notification and the
 *    system privacy indicator say so.
 *  - START_NOT_STICKY: after a crash or kill the bubble does not silently come back; the app offers
 *    to restore it next time it is opened.
 */
class DictationService : Service(), DictationController.Surface, BubbleView.Callbacks {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var windowContext: Context
    private lateinit var wm: WindowManager
    private lateinit var controller: DictationController
    private lateinit var panel: OverlayPanel
    private var bubble: BubbleView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var dismissTarget: TextView? = null
    private var inForeground = false
    private var lastRecording = false
    private var packageAtStart: String? = null
    private var lastOutput: DictationOutput? = null
    private var showingOriginal = false
    /** The current recording was started by press-and-hold (release finishes it). */
    private var holdMode = false
    private var holdInCancelZone = false
    private var holdStartedAt = 0L
    /** Last (phase, text, error) the panel showed; level-only updates must not rebuild it. */
    private var lastPanelKey: Any? = null
    private var lastPhase: Phase = Phase.IDLE
    /** Keep the green check visible briefly after a successful insertion. */
    private var doneUntil = 0L

    private val configCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) = onWindowConfigChanged()
        @Suppress("OVERRIDE_DEPRECATION") // required abstract member of ComponentCallbacks
        override fun onLowMemory() = Unit
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        windowContext = createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        wm = windowContext.getSystemService(WindowManager::class.java)
        windowContext.registerComponentCallbacks(configCallbacks)
        panel = OverlayPanel(windowContext, wm)
        controller = DictationController(this, this, keepModelLoaded = true)
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> {
                hideAndStop(userRequested = true)
                return START_NOT_STICKY
            }
            ACTION_STOP_RECORDING -> controller.stop()
            ACTION_CANCEL_RECORDING -> controller.cancel()
            else -> if (!enterForeground()) return START_NOT_STICKY
        }
        if (!inForeground) {
            // Started only to deliver an action while not showing: nothing else to do.
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_SHOW || intent?.action == null) showBubble()
        return START_NOT_STICKY
    }

    private fun enterForeground(): Boolean {
        if (inForeground) return true
        // startForeground() must come first: a service started with startForegroundService() that
        // stops without calling it crashes the app. If the call throws (microphone permission
        // revoked, or started from the background), Android has already released that obligation,
        // so stopping afterwards is safe.
        try {
            startForeground(
                Notifications.ID_SERVICE, Notifications.serviceNotification(this, recording = false),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
            inForeground = true
        } catch (e: RuntimeException) {
            Log.w(TAG, "startForeground refused", e)
            val reason = if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                "Microphone permission is needed. Open FlowForge to finish setup."
            } else {
                "Android only allows the microphone bubble to start from the FlowForge app or its Quick Settings tile. Tap to open FlowForge."
            }
            Notifications.alert(this, "Couldn't start the FlowForge bubble", reason)
            stopSelf()
            return false
        }
        if (!Settings.canDrawOverlays(this)) {
            Notifications.alert(this, "Couldn't show the FlowForge bubble", "Permission to display over other apps is needed. Open FlowForge to finish setup.")
            stopForeground(STOP_FOREGROUND_REMOVE)
            inForeground = false
            stopSelf()
            return false
        }
        return true
    }

    // ---------------------------------------------------------------------------------------------
    // Bubble window
    // ---------------------------------------------------------------------------------------------

    private fun showBubble() {
        if (bubble != null) return
        graph.settings.bubbleWanted = true
        val view = BubbleView(windowContext, this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "FlowForge bubble"
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        view.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        applySavedPosition(lp, view.measuredWidth)
        try {
            wm.addView(view, lp)
        } catch (e: RuntimeException) {
            Log.w(TAG, "addView failed", e)
            Notifications.alert(this, "Couldn't show the FlowForge bubble", "Check that FlowForge may display over other apps.")
            hideAndStop(userRequested = false)
            return
        }
        bubble = view
        bubbleParams = lp
        render(controller.state)
    }

    /** Usable area: the window bounds minus system bars and display cutouts. */
    private fun usableBounds(): Rect {
        val metrics = wm.currentWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
        )
        val b = metrics.bounds
        return Rect(b.left + insets.left, b.top + insets.top, b.right - insets.right, b.bottom - insets.bottom)
    }

    private fun applySavedPosition(lp: WindowManager.LayoutParams, size: Int) {
        val s = graph.settings
        val p = BubbleGeometry.place(s.bubbleX, s.bubbleY, usableBounds().toBounds(), size)
        lp.x = p.x
        lp.y = p.y
    }

    private fun Rect.toBounds() = Bounds(left, top, right, bottom)

    private fun bubbleRect(): Rect {
        val lp = bubbleParams ?: return Rect()
        val v = bubble ?: return Rect()
        return Rect(lp.x, lp.y, lp.x + v.width.coerceAtLeast(v.measuredWidth), lp.y + v.height.coerceAtLeast(v.measuredHeight))
    }

    /** Rotation, fold/unfold, density or theme change: keep the bubble at the same relative spot. */
    private fun onWindowConfigChanged() {
        val v = bubble ?: return
        val lp = bubbleParams ?: return
        handler.post {
            panel.refreshTheme()
            v.invalidate()
            v.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            applySavedPosition(lp, v.measuredWidth)
            runCatching { wm.updateViewLayout(v, lp) }
            if (panel.isShowing) panel.reposition(bubbleRect(), usableBounds())
        }
    }

    override fun onDragStart() {
        panel.hide()
        showDismissTarget()
    }

    override fun onDragBy(dx: Int, dy: Int, rawX: Float, rawY: Float) {
        val v = bubble ?: return
        val lp = bubbleParams ?: return
        lp.x += dx
        lp.y += dy
        runCatching { wm.updateViewLayout(v, lp) }
        dismissTarget?.isSelected = isOverDismiss(rawX, rawY)
        dismissTarget?.alpha = if (isOverDismiss(rawX, rawY)) 1f else 0.75f
    }

    override fun onDragEnd(rawX: Float, rawY: Float) {
        val overDismiss = isOverDismiss(rawX, rawY)
        hideDismissTarget()
        if (overDismiss) {
            bubble?.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            hideAndStop(userRequested = true)
            return
        }
        snapToEdge()
    }

    private fun snapToEdge() {
        val v = bubble ?: return
        val lp = bubbleParams ?: return
        val target = BubbleGeometry.snap(lp.x, lp.y, usableBounds().toBounds(), v.width)
        val startX = lp.x
        val startY = lp.y
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                lp.x = (startX + (target.x - startX) * f).toInt()
                lp.y = (startY + (target.y - startY) * f).toInt()
                if (v.isAttachedToWindow) runCatching { wm.updateViewLayout(v, lp) }
            }
            start()
        }
        graph.settings.bubbleX = target.fractionX
        graph.settings.bubbleY = target.fractionY
    }

    private fun showDismissTarget() {
        if (dismissTarget != null) return
        val ui = Ui(windowContext)
        val target = TextView(windowContext).apply {
            text = "✕"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(ui.palette.onSurface)
            background = ui.rounded(Palette.withAlpha(ui.palette.surface, 0.92f), 36, ui.palette.outline)
            contentDescription = "Drop here to hide the bubble"
            alpha = 0.75f
        }
        val size = windowContext.dp(72)
        val area = usableBounds()
        val lp = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = area.centerX() - size / 2
            y = area.bottom - size - windowContext.dp(24)
            title = "FlowForge dismiss target"
        }
        runCatching { wm.addView(target, lp) }.onSuccess { dismissTarget = target }
    }

    private fun hideDismissTarget() {
        dismissTarget?.let { runCatching { wm.removeView(it) } }
        dismissTarget = null
    }

    private fun isOverDismiss(rawX: Float, rawY: Float): Boolean {
        val area = usableBounds()
        val cx = area.centerX().toFloat()
        val cy = (area.bottom - windowContext.dp(24) - windowContext.dp(36)).toFloat()
        val r = windowContext.dp(64).toFloat()
        return (rawX - cx) * (rawX - cx) + (rawY - cy) * (rawY - cy) < r * r
    }

    // ---------------------------------------------------------------------------------------------
    // Bubble interactions
    // ---------------------------------------------------------------------------------------------

    override fun onBubbleTap() {
        val state = controller.state
        if (state.phase == Phase.PROCESSING) return
        if (state.phase == Phase.ERROR) controller.dismiss()
        holdMode = false
        if (!state.isRecording) packageAtStart = FlowForgeAccessibilityService.instance?.focusedPackage()
        controller.toggle()
    }

    override fun onHoldStart() {
        val state = controller.state
        // Holding while a tap-started recording runs just finishes it on release.
        if (state.isRecording || state.phase == Phase.PROCESSING) return
        if (state.phase == Phase.ERROR) controller.dismiss()
        packageAtStart = FlowForgeAccessibilityService.instance?.focusedPackage()
        holdMode = true
        holdInCancelZone = false
        holdStartedAt = SystemClock.uptimeMillis()
        controller.start()
    }

    override fun onHoldEnd(cancel: Boolean) {
        val wasHold = holdMode
        holdMode = false
        holdInCancelZone = false
        if (!controller.state.isRecording) return
        val heldMs = SystemClock.uptimeMillis() - holdStartedAt
        when {
            cancel -> {
                controller.cancel()
                showMessage("Cancelled. Nothing was typed.")
            }
            wasHold && heldMs < MIN_HOLD_MS -> {
                // Too short to be hold-to-talk: treat it as a (slow) tap and keep recording.
                lastPanelKey = null
                render(controller.state)
            }
            else -> controller.stop()
        }
    }

    override fun onHoldCancelZone(inZone: Boolean) {
        holdInCancelZone = inZone
        lastPanelKey = null
        render(controller.state)
    }

    private fun showPanel(
        title: String,
        body: String?,
        actions: List<OverlayPanel.Action>,
        autoHideMs: Long = 0,
        transcript: Boolean = false,
        live: Boolean = false,
    ) {
        if (bubble == null) return
        panel.show(title, body, actions, bubbleRect(), usableBounds(), autoHideMs, transcript, live)
    }

    private fun showMessage(message: String) =
        showPanel("FlowForge", message, listOf(OverlayPanel.Action("OK", Ui.ButtonStyle.TEXT) { panel.hide() }), autoHideMs = 5000)

    // ---------------------------------------------------------------------------------------------
    // DictationController.Surface
    // ---------------------------------------------------------------------------------------------

    override val surfaceName = "bubble"

    override fun onStateChanged(state: SessionState) = render(state)

    private fun render(state: SessionState) {
        val v = bubble ?: return
        v.visual = when (state.phase) {
            Phase.IDLE -> if (SystemClock.uptimeMillis() < doneUntil) BubbleView.Visual.DONE else BubbleView.Visual.IDLE
            Phase.STARTING -> BubbleView.Visual.STARTING
            Phase.LISTENING -> BubbleView.Visual.LISTENING
            Phase.PROCESSING -> BubbleView.Visual.PROCESSING
            Phase.ERROR -> BubbleView.Visual.ERROR
        }
        v.level = state.level
        val recording = state.isRecording
        if (recording != lastRecording) {
            lastRecording = recording
            if (graph.settings.dictation.haptics) {
                v.performHapticFeedback(if (recording) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CONTEXT_CLICK)
            }
            getSystemService(NotificationManager::class.java)
                .notify(Notifications.ID_SERVICE, Notifications.serviceNotification(this, recording))
        }
        val previousPhase = lastPhase
        lastPhase = state.phase
        val panelKey = listOf(state.phase, state.displayText, state.error, holdMode, holdInCancelZone)
        if (panelKey == lastPanelKey) return
        lastPanelKey = panelKey
        // Recording -> idle means the user cancelled: close the live panel. (Processing -> idle is a
        // delivery, whose result panel deliver() has just shown.)
        if (state.phase == Phase.IDLE && (previousPhase == Phase.STARTING || previousPhase == Phase.LISTENING)) panel.hide()
        when (state.phase) {
            Phase.STARTING -> showPanel("Starting microphone…", null, emptyList())
            Phase.LISTENING -> if (holdMode) {
                showPanel(
                    if (holdInCancelZone) "Release to cancel" else "Listening — release to finish",
                    state.displayText.ifBlank { if (holdInCancelZone) "Slide back to keep recording." else "Speak now… slide away to cancel." },
                    emptyList(),
                    transcript = true,
                    live = true,
                )
            } else showPanel(
                "Listening — tap the bubble when done",
                state.displayText.ifBlank { if (FlowForgeAccessibilityService.isRunning) "Speak now…" else "Speak now… (text insertion is off; you'll be able to copy the result)" },
                listOf(
                    OverlayPanel.Action("Cancel", Ui.ButtonStyle.OUTLINED) { controller.cancel() },
                    OverlayPanel.Action("Done", Ui.ButtonStyle.FILLED) { controller.stop() },
                ),
                transcript = true,
                live = true,
            )
            Phase.PROCESSING -> showPanel(
                "Processing…", state.displayText.takeIf { it.isNotBlank() },
                listOf(OverlayPanel.Action("Cancel", Ui.ButtonStyle.OUTLINED) { controller.cancel(); panel.hide() }),
                transcript = true,
            )
            Phase.ERROR -> showError(state)
            Phase.IDLE -> Unit
        }
    }

    private fun showError(state: SessionState) {
        val error = state.error ?: return
        val actions = ArrayList<OverlayPanel.Action>()
        if (state.recoveredText.isNotBlank()) {
            actions += OverlayPanel.Action("Copy what I said", Ui.ButtonStyle.FILLED) {
                copy(state.recoveredText)
                controller.dismiss()
                showMessage("Copied. Long-press the text field and choose Paste.")
            }
        }
        if (error.kind == RecognitionErrorKind.PERMISSION || error.kind == RecognitionErrorKind.CONSENT_REQUIRED ||
            error.kind == RecognitionErrorKind.NOT_CONFIGURED || error.kind == RecognitionErrorKind.ENGINE_UNAVAILABLE ||
            error.kind == RecognitionErrorKind.LANGUAGE_UNAVAILABLE
        ) {
            actions += OverlayPanel.Action("Open settings") {
                controller.dismiss()
                panel.hide()
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } else if (error.retryable) {
            actions += OverlayPanel.Action("Try again") { controller.dismiss(); panel.hide(); onBubbleTap() }
        }
        actions += OverlayPanel.Action("Close", Ui.ButtonStyle.TEXT) { controller.dismiss(); panel.hide() }
        val body = buildString {
            append(error.suggestion)
            if (state.recoveredText.isNotBlank()) append("\n\nCaptured: “${state.recoveredText}”")
        }
        showPanel(error.message, body, actions, autoHideMs = if (error.isSilence) 4000 else 0)
    }

    override fun insertionContext(): InsertionContext =
        FlowForgeAccessibilityService.instance?.insertionContext() ?: InsertionContext.EMPTY

    override fun onNotice(message: String) {
        handler.post { showPanel("FlowForge", message, emptyList(), autoHideMs = 4000) }
    }

    override fun deliver(output: DictationOutput, done: (DeliveryReport) -> Unit) {
        lastOutput = output
        showingOriginal = false
        val a11y = FlowForgeAccessibilityService.instance
        val settings = graph.settings.dictation
        if (output.wantsUndoOfPrevious) {
            val message = a11y?.undoLast()?.message ?: "Turn on FlowForge text insertion to undo by voice."
            if (output.text.isBlank()) {
                showMessage(message)
                done(DeliveryReport("undo", ""))
                return
            }
        }
        if (output.text.isBlank()) {
            showMessage("Nothing to insert.")
            done(DeliveryReport("empty", ""))
            return
        }
        if (a11y == null) {
            showResult(output, "Text insertion is off. Copy the text, or turn on FlowForge text insertion in the app.", canInsert = false)
            done(DeliveryReport("preview", ""))
            return
        }
        val now = a11y.focusedPackage()
        if (InsertionPolicy.requiresConfirmation(packageAtStart, now)) {
            showResult(output, "You switched apps while dictating. Insert into this app?", canInsert = true)
            done(DeliveryReport("confirm", now.orEmpty()))
            return
        }
        when (val r = a11y.insert(output.text, settings.smartSpacing)) {
            is InsertResult.Inserted -> {
                doneUntil = SystemClock.uptimeMillis() + 800
                bubble?.visual = BubbleView.Visual.DONE
                handler.postDelayed({ lastPanelKey = null; bubble?.let { render(controller.state) } }, 850)
                showInserted(output, r.undoable)
                done(DeliveryReport("inserted:${r.method.name.lowercase()}", r.app))
            }
            is InsertResult.Manual -> {
                showResult(output, r.reason, canInsert = r.reason == null)
                done(DeliveryReport("preview", now.orEmpty()))
            }
            is InsertResult.Refused -> {
                showResult(output, r.reason.message, canInsert = false)
                done(DeliveryReport("refused:${r.reason.name.lowercase()}", now.orEmpty()))
            }
        }
    }

    private fun displayed(output: DictationOutput) = if (showingOriginal) output.original.trim() else output.text

    private fun showResult(output: DictationOutput, reason: String?, canInsert: Boolean) {
        val actions = ArrayList<OverlayPanel.Action>()
        if (canInsert && FlowForgeAccessibilityService.isRunning) {
            actions += OverlayPanel.Action("Insert", Ui.ButtonStyle.FILLED) {
                // The user explicitly asked to insert, so ignore the "Preview first" preference here.
                val r = FlowForgeAccessibilityService.instance?.insert(
                    displayed(output), graph.settings.dictation.smartSpacing, preference = InsertionPreference.AUTOMATIC,
                )
                when (r) {
                    is InsertResult.Inserted -> showInserted(output, r.undoable)
                    is InsertResult.Refused -> showMessage(r.reason.message)
                    else -> showMessage("Couldn't insert here. Use Copy instead.")
                }
            }
        }
        actions += OverlayPanel.Action("Copy", if (actions.isEmpty()) Ui.ButtonStyle.FILLED else Ui.ButtonStyle.TONAL) {
            copy(displayed(output))
            showMessage("Copied. Long-press the text field and choose Paste.")
        }
        if (output.original.trim() != output.text) {
            actions += OverlayPanel.Action(if (showingOriginal) "Show cleaned" else "Show original") {
                showingOriginal = !showingOriginal
                showResult(output, reason, canInsert)
            }
        }
        actions += OverlayPanel.Action("Close", Ui.ButtonStyle.TEXT) { panel.hide() }
        val title = reason ?: "Your dictation"
        showPanel(title, displayed(output), actions, transcript = true)
    }

    private fun showInserted(output: DictationOutput, undoable: Boolean) {
        val actions = ArrayList<OverlayPanel.Action>()
        val a11y = FlowForgeAccessibilityService.instance
        if (undoable && a11y != null) {
            actions += OverlayPanel.Action("Undo") { showMessage(a11y.undoLast().message) }
            if (output.original.trim() != output.text && output.original.isNotBlank()) {
                actions += OverlayPanel.Action("Use original") {
                    val u = a11y.undoLast()
                    if (u.success) {
                        when (val r = a11y.insert(output.original.trim(), graph.settings.dictation.smartSpacing)) {
                            is InsertResult.Inserted -> showMessage("Inserted the original transcript.")
                            else -> { copy(output.original.trim()); showMessage("Couldn't re-insert; the original is on your clipboard. ($r)") }
                        }
                    } else {
                        showMessage(u.message)
                    }
                }
            }
        }
        actions += OverlayPanel.Action("Close", Ui.ButtonStyle.TEXT) { panel.hide() }
        val note = if (output.aiApplied) "Inserted (AI cleanup)" else "Inserted"
        showPanel(note, output.text, actions, autoHideMs = 5000, transcript = true)
    }

    private fun copy(text: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("FlowForge dictation", text))
    }

    // ---------------------------------------------------------------------------------------------
    // Teardown
    // ---------------------------------------------------------------------------------------------

    private fun hideAndStop(userRequested: Boolean) {
        if (userRequested) graph.settings.bubbleWanted = false
        controller.cancel()
        panel.hide()
        hideDismissTarget()
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        if (inForeground) stopForeground(STOP_FOREGROUND_REMOVE)
        inForeground = false
        stopSelf()
    }

    override fun onDestroy() {
        isRunning = false
        controller.release()
        panel.hide()
        hideDismissTarget()
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        runCatching { windowContext.unregisterComponentCallbacks(configCallbacks) }
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "FlowForgeBubble"
        /** A hold released sooner than this was probably meant as a tap: recording continues in tap mode. */
        private const val MIN_HOLD_MS = 450L
        const val ACTION_SHOW = "com.flowforge.dictation.action.SHOW_BUBBLE"
        const val ACTION_HIDE = "com.flowforge.dictation.action.HIDE_BUBBLE"
        const val ACTION_STOP_RECORDING = "com.flowforge.dictation.action.STOP_RECORDING"
        const val ACTION_CANCEL_RECORDING = "com.flowforge.dictation.action.CANCEL_RECORDING"

        @Volatile
        var isRunning: Boolean = false
            private set

        /** Must be called from a visible activity. Returns a problem description, or null on success. */
        fun show(context: Context): String? {
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                return "Allow microphone access first."
            }
            if (!Settings.canDrawOverlays(context)) return "Allow FlowForge to display over other apps first."
            return try {
                context.startForegroundService(Intent(context, DictationService::class.java).setAction(ACTION_SHOW))
                null
            } catch (e: RuntimeException) {
                "Android didn't allow the bubble to start: ${e.javaClass.simpleName}"
            }
        }

        fun hide(context: Context) {
            if (!isRunning) return
            context.startService(Intent(context, DictationService::class.java).setAction(ACTION_HIDE))
        }
    }
}
