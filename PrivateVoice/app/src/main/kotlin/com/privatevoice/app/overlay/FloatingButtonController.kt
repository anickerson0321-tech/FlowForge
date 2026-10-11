package com.privatevoice.app.overlay

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.privatevoice.app.audio.PlaybackState
import com.privatevoice.app.messaging.PendingState
import com.privatevoice.app.settings.AppSettings
import kotlin.math.abs

/**
 * Optional floating "read" button drawn with the supported overlay API
 * (TYPE_APPLICATION_OVERLAY). Exactly one instance exists; it repositions
 * itself on rotation and fold/unfold using a fractional position, hides on
 * the lock screen when configured, and is fully removed when disabled.
 */
class FloatingButtonController(
    private val context: Context,
    private val onTap: () -> Unit,
    private val onStop: () -> Unit,
    private val onPauseResume: () -> Unit,
    private val onRepeat: () -> Unit,
    private val onMoved: (Float, Float) -> Unit,
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var controls: LinearLayout? = null
    private var pauseLabel: TextView? = null
    private var settings: AppSettings = AppSettings()
    private var pending = PendingState()
    private var playback: PlaybackState = PlaybackState.Idle
    private var screenOn = true
    private var receiverRegistered = false

    private val density get() = context.resources.displayMetrics.density

    fun canDraw(): Boolean = Settings.canDrawOverlays(context)

    val isShowing: Boolean get() = root != null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> screenOn = false
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> screenOn = true
            }
            refresh()
        }
    }

    private val configCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) { reposition() }
        @Deprecated("Deprecated in Java")
        override fun onLowMemory() {}
    }

    fun update(settings: AppSettings, pending: PendingState, playback: PlaybackState) {
        this.settings = settings
        this.pending = pending
        this.playback = playback
        refresh()
    }

    private fun shouldShow(): Boolean {
        if (!settings.floatingEnabled || !canDraw()) return false
        if (settings.floatingHideOnLockScreen && (!screenOn || keyguard.isKeyguardLocked)) return false
        val busy = playback !is PlaybackState.Idle
        if (settings.floatingHideWhenEmpty && pending.count == 0 && !busy) return false
        return true
    }

    fun refresh() {
        if (settings.floatingEnabled) registerReceivers() else unregisterReceivers()
        if (shouldShow()) show() else hide()
        root?.alpha = settings.floatingAlpha.coerceIn(0.3f, 1f)
        pauseLabel?.text = if (playback is PlaybackState.Paused) "▶" else "❚❚"
        (root?.getChildAt(0) as? BubbleView)?.let { it.badge = pending.count; it.speaking = playback is PlaybackState.Speaking; it.invalidate() }
    }

    private fun registerReceivers() {
        if (receiverRegistered) return
        receiverRegistered = true
        val f = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT) }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(screenReceiver, f, Context.RECEIVER_NOT_EXPORTED) else context.registerReceiver(screenReceiver, f)
        context.registerComponentCallbacks(configCallbacks)
    }

    private fun unregisterReceivers() {
        if (!receiverRegistered) return
        receiverRegistered = false
        runCatching { context.unregisterReceiver(screenReceiver) }
        runCatching { context.unregisterComponentCallbacks(configCallbacks) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun show() {
        if (root != null) return
        val size = (56 * density).toInt()
        val bubble = BubbleView(context).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
            contentDescription = "Read latest message"
        }
        val ctl = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            background = GradientDrawable().apply { cornerRadius = 28 * density; setColor(0xEE1E2230.toInt()) }
            setPadding((6 * density).toInt(), 0, (6 * density).toInt(), 0)
        }
        fun ctlButton(label: String, desc: String, action: () -> Unit) = TextView(context).apply {
            text = label
            contentDescription = desc
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(size, size)
            setOnClickListener { action(); ctl.visibility = View.GONE; relayout() }
        }
        ctl.addView(ctlButton("■", "Stop") { onStop() })
        val pl = ctlButton("❚❚", "Pause or resume") { onPauseResume() }
        pauseLabel = pl
        ctl.addView(pl)
        ctl.addView(ctlButton("↻", "Repeat") { onRepeat() })
        controls = ctl

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(bubble)
            addView(ctl)
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; title = "PrivateVoice floating button" }
        params = lp
        root = layout
        attachTouch(bubble)
        try {
            wm.addView(layout, lp)
            reposition()
        } catch (e: Exception) {
            root = null; params = null
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachTouch(bubble: View) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        val longPress = ViewConfiguration.getLongPressTimeout().toLong()
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        var dragging = false; var downTime = 0L; var longFired = false
        val longRunnable = Runnable {
            longFired = true
            controls?.let { it.visibility = if (it.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
            relayout()
        }
        bubble.setOnTouchListener { v, e ->
            val lp = params ?: return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y
                    dragging = false; longFired = false; downTime = e.eventTime
                    v.postDelayed(longRunnable, longPress)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) { dragging = true; v.removeCallbacks(longRunnable) }
                    if (dragging) {
                        lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt()
                        clamp(lp); runCatching { wm.updateViewLayout(root, lp) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(longRunnable)
                    if (dragging) savePosition(lp)
                    else if (!longFired && e.eventTime - downTime < longPress) { v.performClick(); onTap() }
                }
                MotionEvent.ACTION_CANCEL -> v.removeCallbacks(longRunnable)
            }
            true
        }
    }

    private fun bounds(): Pair<Int, Int> {
        val b = wm.currentWindowMetrics.bounds
        return b.width() to b.height()
    }

    private fun clamp(lp: WindowManager.LayoutParams) {
        val (w, h) = bounds()
        val view = root ?: return
        val vw = view.width.takeIf { it > 0 } ?: (56 * density).toInt()
        val vh = view.height.takeIf { it > 0 } ?: (56 * density).toInt()
        lp.x = lp.x.coerceIn(0, (w - vw).coerceAtLeast(0))
        lp.y = lp.y.coerceIn((24 * density).toInt(), (h - vh - 24 * density).toInt().coerceAtLeast(0))
    }

    private fun savePosition(lp: WindowManager.LayoutParams) {
        val (w, h) = bounds()
        onMoved((lp.x.toFloat() / w.coerceAtLeast(1)).coerceIn(0f, 1f), (lp.y.toFloat() / h.coerceAtLeast(1)).coerceIn(0f, 1f))
    }

    /** Places the button from its saved fractional position (survives rotation and fold/unfold). */
    fun reposition() {
        val lp = params ?: return
        val (w, h) = bounds()
        lp.x = (settings.floatingX * w).toInt()
        lp.y = (settings.floatingY * h).toInt()
        clamp(lp)
        relayout()
    }

    private fun relayout() {
        val lp = params ?: return
        val r = root ?: return
        runCatching { wm.updateViewLayout(r, lp) }
    }

    fun hide() {
        val r = root ?: return
        root = null; params = null; controls = null; pauseLabel = null
        runCatching { wm.removeViewImmediate(r) }
    }

    fun release() {
        hide()
        unregisterReceivers()
    }

    /** Round button with a speaker glyph and a pending-count badge. */
    private class BubbleView(context: Context) : View(context) {
        var badge = 0
        var speaking = false
        private val d = context.resources.displayMetrics.density
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF7C5CFF.toInt() }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x667C5CFF; style = Paint.Style.STROKE; strokeWidth = 3 * context.resources.displayMetrics.density }
        private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        private val wave = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2.2f * context.resources.displayMetrics.density; strokeCap = Paint.Cap.ROUND }
        private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF5C7A.toInt() }
        private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = 11 * context.resources.displayMetrics.density; isFakeBoldText = true }
        private val path = Path()

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f; val cy = height / 2f; val r = minOf(cx, cy) - 3 * d
            canvas.drawCircle(cx, cy, r, fill)
            if (speaking) canvas.drawCircle(cx, cy, r + 1.5f * d, ring)
            path.reset()
            val s = r * 0.42f
            path.moveTo(cx - s, cy - s * 0.45f); path.lineTo(cx - s * 0.45f, cy - s * 0.45f); path.lineTo(cx + s * 0.1f, cy - s)
            path.lineTo(cx + s * 0.1f, cy + s); path.lineTo(cx - s * 0.45f, cy + s * 0.45f); path.lineTo(cx - s, cy + s * 0.45f); path.close()
            canvas.drawPath(path, glyph)
            canvas.drawArc(cx - s * 0.2f, cy - s * 0.6f, cx + s * 0.8f, cy + s * 0.6f, -50f, 100f, false, wave)
            canvas.drawArc(cx - s * 0.5f, cy - s * 1.0f, cx + s * 1.2f, cy + s * 1.0f, -50f, 100f, false, wave)
            if (badge > 0) {
                val bx = width - 9 * d; val by = 9 * d
                canvas.drawCircle(bx, by, 8 * d, badgePaint)
                canvas.drawText(if (badge > 9) "9+" else badge.toString(), bx, by + 4 * d, badgeText)
            }
        }
    }
}
