package com.flowforge.dictation.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.LinearInterpolator
import com.flowforge.dictation.R
import com.flowforge.dictation.ui.kit.Palette
import com.flowforge.dictation.ui.kit.dp
import com.flowforge.dictation.ui.kit.dpf
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The floating microphone. Two ways to dictate, so it works however the user prefers:
 *  - Hold to talk: press and hold, speak, release to insert. Slide far away before releasing to cancel.
 *  - Tap to talk: tap once to start, speak, tap again to finish.
 * Moving the finger before the hold starts drags the bubble instead (it snaps to an edge), so a
 * drag never records. Long-pressing no longer opens a menu; the notification and app have the options.
 */
@SuppressLint("ViewConstructor")
class BubbleView(context: Context, private val callbacks: Callbacks) : View(context) {

    interface Callbacks {
        fun onBubbleTap()
        /** The finger has stayed down long enough: start hold-to-talk. */
        fun onHoldStart()
        /** The finger lifted after [onHoldStart]; [cancel] when it was released in the cancel zone. */
        fun onHoldEnd(cancel: Boolean)
        /** The held finger moved into (or back out of) the cancel zone. */
        fun onHoldCancelZone(inZone: Boolean)
        fun onDragStart()
        fun onDragBy(dx: Int, dy: Int, rawX: Float, rawY: Float)
        fun onDragEnd(rawX: Float, rawY: Float)
    }

    enum class Visual { IDLE, STARTING, LISTENING, PROCESSING, ERROR, DONE, DISABLED }

    private val palette = Palette(context)
    private val sizePx = context.dp(SIZE_DP)
    private val ringPx = context.dpf(RING_DP.toFloat())
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = context.dpf(3f)
    }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = context.dpf(2.6f)
    }
    private val micIcon = context.getDrawable(R.drawable.ic_mic)!!.mutate()
    private val arcRect = RectF()
    private val glyphPath = Path()

    var visual: Visual = Visual.IDLE
        set(value) {
            if (field == value) return
            field = value
            updateAnimation()
            updateDescription()
            invalidate()
        }

    /** Input level 0..1 while listening. */
    var level: Float = 0f
        set(value) {
            field = value
            if (visual == Visual.LISTENING) invalidate()
        }

    private var spin = 0f
    private val spinner = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 900
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { spin = it.animatedValue as Float; invalidate() }
    }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val cancelDistancePx = context.dpf(CANCEL_DISTANCE_DP.toFloat())
    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var dragging = false
    private var holding = false
    private var inCancelZone = false
    private val holdRunnable = Runnable {
        if (!dragging) {
            holding = true
            inCancelZone = false
            // Inside scrolling layouts (the in-app test box), keep the finger's moves for the hold.
            parent?.requestDisallowInterceptTouchEvent(true)
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            callbacks.onHoldStart()
        }
    }

    init {
        isClickable = true
        isFocusable = true
        elevation = context.dpf(6f)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val inset = ((width - sizePx) / 2)
                outline.setOval(inset, inset, inset + sizePx, inset + sizePx)
            }
        }
        updateDescription()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val total = sizePx + (ringPx * 2).toInt()
        setMeasuredDimension(total, total)
    }

    override fun onDetachedFromWindow() {
        spinner.cancel()
        removeCallbacks(holdRunnable)
        if (holding) {
            holding = false
            callbacks.onHoldEnd(cancel = false)
        }
        super.onDetachedFromWindow()
    }

    private fun updateAnimation() {
        if (visual == Visual.PROCESSING || visual == Visual.STARTING) {
            if (!spinner.isStarted) spinner.start()
        } else {
            spinner.cancel()
        }
    }

    private fun baseColor(): Int = when (visual) {
        Visual.IDLE -> palette.accent
        Visual.STARTING, Visual.LISTENING -> palette.recording
        Visual.PROCESSING -> palette.surfaceVariant
        Visual.ERROR -> palette.warning
        Visual.DONE -> palette.success
        Visual.DISABLED -> palette.outline
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = sizePx / 2f
        if (visual == Visual.LISTENING || visual == Visual.STARTING) {
            halo.color = Palette.withAlpha(palette.recording, 0.22f)
            canvas.drawCircle(cx, cy, r + ringPx * (0.25f + 0.75f * level), halo)
        }
        fill.color = baseColor()
        canvas.drawCircle(cx, cy, r, fill)
        when (visual) {
            Visual.PROCESSING, Visual.STARTING -> {
                stroke.color = if (visual == Visual.PROCESSING) palette.accent else palette.onAccent
                val inset = context.dpf(6f)
                arcRect.set(cx - r + inset, cy - r + inset, cx + r - inset, cy + r - inset)
                canvas.drawArc(arcRect, spin, 270f, false, stroke)
                if (visual == Visual.STARTING) drawMic(canvas, cx, cy, palette.onAccent)
            }
            Visual.ERROR -> {
                glyph.color = 0xFF1B1C22.toInt()
                val h = r * 0.42f
                canvas.drawLine(cx, cy - h, cx, cy + h * 0.25f, glyph)
                canvas.drawPoint(cx, cy + h * 0.75f, glyph.apply { strokeWidth = context.dpf(3.4f) })
                glyph.strokeWidth = context.dpf(2.6f)
            }
            Visual.DONE -> {
                glyph.color = 0xFFFFFFFF.toInt()
                glyphPath.reset()
                glyphPath.moveTo(cx - r * 0.35f, cy)
                glyphPath.lineTo(cx - r * 0.08f, cy + r * 0.28f)
                glyphPath.lineTo(cx + r * 0.38f, cy - r * 0.25f)
                canvas.drawPath(glyphPath, glyph)
            }
            else -> drawMic(canvas, cx, cy, if (visual == Visual.DISABLED) palette.onSurfaceVariant else palette.onAccent)
        }
    }

    private fun drawMic(canvas: Canvas, cx: Float, cy: Float, tint: Int) {
        val half = context.dp(13)
        micIcon.setTint(tint)
        micIcon.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
        micIcon.draw(canvas)
    }

    @SuppressLint("ClickableViewAccessibility") // performClick is called for taps; see onTouchEvent
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                lastRawX = event.rawX
                lastRawY = event.rawY
                dragging = false
                holding = false
                inCancelZone = false
                isPressed = true
                postDelayed(holdRunnable, HOLD_MS)
            }
            MotionEvent.ACTION_MOVE -> {
                val moved = hypot(event.rawX - downRawX, event.rawY - downRawY)
                when {
                    holding -> {
                        val zone = moved > cancelDistancePx
                        if (zone != inCancelZone) {
                            inCancelZone = zone
                            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            callbacks.onHoldCancelZone(zone)
                        }
                    }
                    !dragging && moved > touchSlop -> {
                        dragging = true
                        removeCallbacks(holdRunnable)
                        callbacks.onDragStart()
                    }
                }
                if (dragging) {
                    callbacks.onDragBy((event.rawX - lastRawX).toInt(), (event.rawY - lastRawY).toInt(), event.rawX, event.rawY)
                    lastRawX += (event.rawX - lastRawX).toInt()
                    lastRawY += (event.rawY - lastRawY).toInt()
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(holdRunnable)
                isPressed = false
                when {
                    dragging -> callbacks.onDragEnd(event.rawX, event.rawY)
                    holding -> callbacks.onHoldEnd(cancel = inCancelZone)
                    abs(event.rawX - downRawX) <= touchSlop && abs(event.rawY - downRawY) <= touchSlop -> performClick()
                }
                dragging = false
                holding = false
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(holdRunnable)
                isPressed = false
                if (dragging) callbacks.onDragEnd(event.rawX, event.rawY)
                // The system took the touch away: keep what was said rather than losing it.
                if (holding) callbacks.onHoldEnd(cancel = false)
                dragging = false
                holding = false
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        callbacks.onBubbleTap()
        return true
    }

    private fun updateDescription() {
        contentDescription = when (visual) {
            Visual.IDLE -> "FlowForge dictation. Double-tap to start dictating, or double-tap and hold to talk."
            Visual.STARTING -> "FlowForge is starting the microphone."
            Visual.LISTENING -> "FlowForge is listening. Double-tap to finish."
            Visual.PROCESSING -> "FlowForge is processing your dictation."
            Visual.ERROR -> "FlowForge dictation problem. Double-tap to try again."
            Visual.DONE -> "Dictation inserted."
            Visual.DISABLED -> "FlowForge dictation unavailable."
        }
        stateDescription = when (visual) {
            Visual.LISTENING, Visual.STARTING -> "Recording"
            Visual.PROCESSING -> "Processing"
            else -> "Not recording"
        }
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Button"
    }

    companion object {
        const val SIZE_DP = 56
        const val RING_DP = 12
        /** A press longer than this is hold-to-talk; shorter is a tap. */
        const val HOLD_MS = 280L
        /** Sliding this far from where the hold started, then releasing, cancels. */
        const val CANCEL_DISTANCE_DP = 96
    }
}
