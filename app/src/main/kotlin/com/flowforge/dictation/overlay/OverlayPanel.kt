package com.flowforge.dictation.overlay

import android.content.Context
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.flowforge.dictation.ui.kit.Ui
import com.flowforge.dictation.ui.kit.dp

/**
 * Card shown next to the bubble: live transcript, results that need a decision, errors and the
 * long-press menu. The window is FLAG_NOT_FOCUSABLE, so the app being typed into keeps its cursor.
 */
class OverlayPanel(private val context: Context, private val wm: WindowManager) {
    class Action(val label: String, val style: Ui.ButtonStyle = Ui.ButtonStyle.TONAL, val run: () -> Unit)

    private var ui = Ui(context)
    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private val autoHide = Runnable { hide() }

    val isShowing: Boolean get() = root?.isAttachedToWindow == true

    /** Recreate views after a theme/density change. */
    fun refreshTheme() {
        ui = Ui(context)
    }

    fun show(
        title: String,
        body: String?,
        actions: List<Action>,
        anchor: Rect,
        screen: Rect,
        autoHideMs: Long = 0,
        bodyIsTranscript: Boolean = false,
    ) {
        val card = ui.column(spacingDp = 10).apply {
            background = ui.rounded(ui.palette.surface, 20, ui.palette.outline)
            elevation = context.dp(8).toFloat()
            setPadding(context.dp(16), context.dp(14), context.dp(16), context.dp(12))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            addView(ui.text(title, 14f, ui.palette.onSurfaceVariant, bold = true))
            if (!body.isNullOrBlank()) {
                val bodyView = ui.text(body, if (bodyIsTranscript) 17f else 15f, ui.palette.onSurface)
                val scroll = MaxHeightScrollView(context, context.dp(180)).apply { addView(bodyView) }
                addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                if (bodyIsTranscript) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            }
            if (actions.isNotEmpty()) {
                val buttons = ui.row(8)
                actions.forEach { a -> buttons.addView(ui.button(a.label, a.style) { a.run() }) }
                addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(buttons) })
            }
        }
        val width = minOf(screen.width() - context.dp(24), context.dp(420))
        val existing = root?.takeIf { it.isAttachedToWindow }
        val container = existing ?: LinearLayout(context).apply {
            setPadding(context.dp(4), context.dp(4), context.dp(4), context.dp(8))
            clipToPadding = false
        }
        container.removeAllViews()
        container.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val lp = (params ?: WindowManager.LayoutParams(
            width, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT,
        )).apply {
            this.width = width
            gravity = Gravity.TOP or Gravity.START
            setTitle("FlowForge panel")
        }
        position(container, lp, anchor, screen, width)
        if (existing != null) {
            runCatching { wm.updateViewLayout(existing, lp) }
        } else {
            runCatching { wm.addView(container, lp) }.onSuccess { root = container }
        }
        params = lp
        root?.removeCallbacks(autoHide)
        if (autoHideMs > 0) root?.postDelayed(autoHide, autoHideMs)
    }

    /** Places the card above the bubble when it sits in the lower half, otherwise below it. */
    private fun position(container: View, lp: WindowManager.LayoutParams, anchor: Rect, screen: Rect, width: Int) {
        val margin = context.dp(8)
        container.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(screen.height(), View.MeasureSpec.AT_MOST),
        )
        val h = container.measuredHeight
        lp.x = (anchor.centerX() - width / 2).coerceIn(screen.left + margin, maxOf(screen.left + margin, screen.right - width - margin))
        val above = anchor.centerY() > screen.centerY()
        val y = if (above) anchor.top - margin - h else anchor.bottom + margin
        lp.y = y.coerceIn(screen.top, maxOf(screen.top, screen.bottom - h))
    }

    fun reposition(anchor: Rect, screen: Rect) {
        val r = root ?: return
        val lp = params ?: return
        val width = minOf(screen.width() - context.dp(24), context.dp(420))
        lp.width = width
        position(r, lp, anchor, screen, width)
        runCatching { wm.updateViewLayout(r, lp) }
    }

    fun hide() {
        val r = root ?: return
        r.removeCallbacks(autoHide)
        runCatching { wm.removeView(r) }
        root = null
    }
}

/** ScrollView that wraps its content up to [maxHeightPx], then scrolls. */
private class MaxHeightScrollView(context: Context, private val maxHeightPx: Int) : ScrollView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(maxHeightPx, View.MeasureSpec.AT_MOST))
    }
}
