package com.privatevoice.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/** Dark Material 3–style color tokens. */
object Palette {
    const val BG = 0xFF0E1015.toInt()
    const val SURFACE = 0xFF171A22.toInt()
    const val SURFACE_HIGH = 0xFF1F2330.toInt()
    const val OUTLINE = 0xFF2C3242.toInt()
    const val PRIMARY = 0xFF8B6CFF.toInt()
    const val PRIMARY_CONTAINER = 0xFF2B2452.toInt()
    const val ON_PRIMARY = 0xFFFFFFFF.toInt()
    const val TEXT = 0xFFECEEF4.toInt()
    const val TEXT_DIM = 0xFF9BA3B4.toInt()
    const val SUCCESS = 0xFF3DDC97.toInt()
    const val WARNING = 0xFFFFB547.toInt()
    const val DANGER = 0xFFFF5C7A.toInt()
}

/** Tiny view-building DSL over framework widgets (AndroidX/Compose are unavailable in this build). */
class Ui(val ctx: Context) {
    private val density = ctx.resources.displayMetrics.density
    fun dp(v: Int): Int = (v * density + 0.5f).toInt()
    fun dpf(v: Float): Float = v * density

    fun rounded(color: Int, radiusDp: Int, strokeColor: Int? = null): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dpf(radiusDp.toFloat())
        setColor(color)
        if (strokeColor != null) setStroke(dp(1), strokeColor)
    }

    fun ripple(base: Drawable, rippleColor: Int = 0x33FFFFFF): Drawable =
        RippleDrawable(ColorStateList.valueOf(rippleColor), base, base)

    fun column(padding: Int = 0, block: LinearLayout.() -> Unit = {}): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
        block()
    }

    fun row(block: LinearLayout.() -> Unit = {}): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        block()
    }

    fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f, top: Int = 0, bottom: Int = 0, start: Int = 0, end: Int = 0) =
        LinearLayout.LayoutParams(w, h, weight).apply { setMargins(dp(start), dp(top), dp(end), dp(bottom)) }

    /** Horizontal row of equal-width buttons, attached to this container. */
    fun ViewGroup.buttonRow(block: LinearLayout.() -> Unit): LinearLayout {
        val r = row(block)
        r.layoutParams = lp(top = 8)
        addView(r)
        return r
    }

    // ---- text
    fun ViewGroup.title(s: String): TextView = text(s, 26f, Palette.TEXT, bold = true)
    fun ViewGroup.headline(s: String): TextView = text(s, 19f, Palette.TEXT, bold = true)
    fun ViewGroup.body(s: String, color: Int = Palette.TEXT): TextView = text(s, 15f, color)
    fun ViewGroup.caption(s: String, color: Int = Palette.TEXT_DIM): TextView = text(s, 13f, color)

    fun ViewGroup.text(s: String, size: Float, color: Int, bold: Boolean = false): TextView = TextView(ctx).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setLineSpacing(0f, 1.15f)
        this@text.addView(this)
    }

    fun ViewGroup.sectionHeader(s: String) {
        text(s.uppercase(), 12f, Palette.PRIMARY, bold = true).apply {
            letterSpacing = 0.08f
            layoutParams = lp(top = 20, bottom = 8, start = 4)
        }
    }

    // ---- containers
    fun ViewGroup.card(block: LinearLayout.() -> Unit): LinearLayout {
        val c = column(16) {
            background = rounded(Palette.SURFACE, 20, Palette.OUTLINE)
            block()
        }
        c.layoutParams = lp(bottom = 12)
        addView(c)
        return c
    }

    fun ViewGroup.divider() {
        addView(View(ctx).apply { setBackgroundColor(Palette.OUTLINE); layoutParams = lp(h = dp(1), top = 8, bottom = 8) })
    }

    fun ViewGroup.spacer(h: Int) { addView(View(ctx).apply { layoutParams = lp(h = dp(h)) }) }

    // ---- buttons
    fun ViewGroup.primaryButton(label: String, onClick: () -> Unit): Button = button(label, Palette.PRIMARY, Palette.ON_PRIMARY, onClick, 56)

    fun ViewGroup.tonalButton(label: String, onClick: () -> Unit): Button = button(label, Palette.PRIMARY_CONTAINER, Palette.TEXT, onClick, 48)

    fun ViewGroup.outlineButton(label: String, color: Int = Palette.TEXT, onClick: () -> Unit): Button =
        button(label, Palette.SURFACE, color, onClick, 48, stroke = Palette.OUTLINE)

    fun ViewGroup.button(label: String, bg: Int, fg: Int, onClick: () -> Unit, heightDp: Int, stroke: Int? = null): Button {
        val b = Button(ctx).apply {
            text = label
            isAllCaps = false
            textSize = if (heightDp >= 56) 17f else 15f
            setTextColor(fg)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = ripple(rounded(bg, heightDp / 2, stroke))
            stateListAnimator = null
            minHeight = dp(heightDp)
            minimumHeight = dp(heightDp)
            setPadding(dp(20), 0, dp(20), 0)
            setOnClickListener { onClick() }
        }
        b.layoutParams = if (this is LinearLayout && orientation == LinearLayout.HORIZONTAL) lp(w = 0, h = dp(heightDp), weight = 1f, start = 4, end = 4)
        else lp(h = dp(heightDp), top = 4, bottom = 4)
        addView(b)
        return b
    }

    // ---- rows
    fun ViewGroup.switchRow(title: String, subtitle: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit): Switch {
        val sw = Switch(ctx).apply {
            isChecked = checked
            isEnabled = enabled
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Color.WHITE, 0xFFB8BFCC.toInt()))
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Palette.PRIMARY, Palette.OUTLINE))
            contentDescription = title
        }
        val r = row {
            minimumHeight = dp(56)
            setPadding(0, dp(6), 0, dp(6))
            val texts = column { layoutParams = lp(w = 0, weight = 1f, end = 12) }
            texts.body(title)
            if (subtitle != null) texts.caption(subtitle)
            addView(texts)
            addView(sw)
            background = ripple(ColorDrawable(Color.TRANSPARENT))
            setOnClickListener { if (sw.isEnabled) sw.toggle() }
        }
        sw.setOnCheckedChangeListener { _, v -> onChange(v) }
        r.alpha = if (enabled) 1f else 0.5f
        addView(r)
        return sw
    }

    /** Tappable row with a value on the right (opens a picker/dialog). */
    fun ViewGroup.valueRow(title: String, value: String, subtitle: String? = null, onClick: () -> Unit): TextView {
        val v = TextView(ctx).apply {
            text = value
            textSize = 14f
            setTextColor(Palette.PRIMARY)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.END
        }
        val r = row {
            minimumHeight = dp(56)
            setPadding(0, dp(6), 0, dp(6))
            val texts = column { layoutParams = lp(w = 0, weight = 1f, end = 12) }
            texts.body(title)
            if (subtitle != null) texts.caption(subtitle)
            addView(texts)
            v.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { width = dp(150) }
            addView(v)
            background = ripple(ColorDrawable(Color.TRANSPARENT))
            setOnClickListener { onClick() }
            contentDescription = "$title: $value"
        }
        addView(r)
        return v
    }

    /** Navigation row with leading glyph and chevron. */
    fun ViewGroup.navRow(glyph: String, title: String, subtitle: String, onClick: () -> Unit) {
        val r = row {
            minimumHeight = dp(64)
            setPadding(dp(4), dp(8), dp(4), dp(8))
            val icon = TextView(ctx).apply {
                text = glyph; textSize = 20f; gravity = Gravity.CENTER; setTextColor(Palette.PRIMARY)
                background = rounded(Palette.PRIMARY_CONTAINER, 14)
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(14) }
            }
            addView(icon)
            val texts = column { layoutParams = lp(w = 0, weight = 1f) }
            texts.body(title)
            texts.caption(subtitle)
            addView(texts)
            addView(TextView(ctx).apply { text = "›"; textSize = 24f; setTextColor(Palette.TEXT_DIM) })
            background = ripple(ColorDrawable(Color.TRANSPARENT))
            setOnClickListener { onClick() }
            contentDescription = title
        }
        addView(r)
    }

    /** Status line: label, value, colored dot. Returns the value TextView for live updates. */
    fun ViewGroup.statusRow(label: String, value: String, state: Boolean?): Pair<TextView, View> {
        val dot = View(ctx).apply {
            background = rounded(statusColor(state), 6)
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(12) }
        }
        val v = TextView(ctx).apply { text = value; textSize = 14f; setTextColor(Palette.TEXT); gravity = Gravity.END }
        val r = row {
            minimumHeight = dp(40)
            addView(dot)
            addView(TextView(ctx).apply { text = label; textSize = 14f; setTextColor(Palette.TEXT_DIM); layoutParams = lp(w = 0, weight = 1f) })
            addView(v)
        }
        addView(r)
        return v to dot
    }

    fun statusColor(state: Boolean?) = when (state) { true -> Palette.SUCCESS; false -> Palette.WARNING; null -> Palette.TEXT_DIM }

    fun ViewGroup.slider(title: String, min: Float, max: Float, value: Float, step: Float, format: (Float) -> String, onChange: (Float) -> Unit) {
        val label = TextView(ctx).apply { textSize = 14f; setTextColor(Palette.PRIMARY) }
        val steps = ((max - min) / step).toInt()
        val bar = SeekBar(ctx).apply {
            this.max = steps
            progress = ((value - min) / step).toInt().coerceIn(0, steps)
            progressTintList = ColorStateList.valueOf(Palette.PRIMARY)
            thumbTintList = ColorStateList.valueOf(Palette.PRIMARY)
            progressBackgroundTintList = ColorStateList.valueOf(Palette.OUTLINE)
            minimumHeight = dp(48)
            contentDescription = title
        }
        label.text = format(value)
        val header = row {
            setPadding(0, dp(8), 0, 0)
            addView(TextView(ctx).apply { text = title; textSize = 15f; setTextColor(Palette.TEXT); layoutParams = lp(w = 0, weight = 1f) })
            addView(label)
        }
        addView(header)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { label.text = format(min + p * step) }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) { onChange(min + (sb?.progress ?: 0) * step) }
        })
        bar.layoutParams = lp(h = dp(48))
        addView(bar)
    }

    /** Pill-shaped chip. */
    fun ViewGroup.chip(label: String, selected: Boolean, onClick: () -> Unit): TextView {
        val t = TextView(ctx).apply {
            text = label
            textSize = 14f
            setTextColor(if (selected) Palette.ON_PRIMARY else Palette.TEXT)
            gravity = Gravity.CENTER
            setPadding(dp(16), 0, dp(16), 0)
            background = ripple(rounded(if (selected) Palette.PRIMARY else Palette.SURFACE_HIGH, 18, if (selected) null else Palette.OUTLINE))
            minHeight = dp(40)
            setOnClickListener { onClick() }
        }
        t.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { marginEnd = dp(8); bottomMargin = dp(8) }
        addView(t)
        return t
    }

    /** Wraps children into rows (simple flow layout for chips). */
    fun flow(): FlowLayout = FlowLayout(ctx)
}

/** Minimal wrapping layout for chips. */
class FlowLayout(context: Context) : ViewGroup(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            measureChild(c, widthMeasureSpec, heightMeasureSpec)
            val p = c.layoutParams as? MarginLayoutParams
            val cw = c.measuredWidth + (p?.marginEnd ?: 0)
            val ch = c.measuredHeight + (p?.bottomMargin ?: 0)
            if (x + cw > maxW && x > 0) { x = 0; y += rowH; rowH = 0 }
            x += cw; rowH = maxOf(rowH, ch)
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), y + rowH + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val p = c.layoutParams as? MarginLayoutParams
            val cw = c.measuredWidth + (p?.marginEnd ?: 0)
            val ch = c.measuredHeight + (p?.bottomMargin ?: 0)
            if (x + cw > maxW && x > 0) { x = 0; y += rowH; rowH = 0 }
            c.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + c.measuredWidth, paddingTop + y + c.measuredHeight)
            x += cw; rowH = maxOf(rowH, ch)
        }
    }

    override fun generateLayoutParams(attrs: android.util.AttributeSet?): LayoutParams = MarginLayoutParams(context, attrs)
    override fun generateDefaultLayoutParams(): LayoutParams = MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    override fun generateLayoutParams(p: LayoutParams?): LayoutParams = MarginLayoutParams(p)
    override fun checkLayoutParams(p: LayoutParams?): Boolean = p is MarginLayoutParams
}
