package com.flowforge.dictation.ui.kit

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView

/**
 * Tiny programmatic view toolkit with Material 3-like styling (rounded cards, tonal buttons,
 * 48dp minimum touch targets). Framework widgets only, so the UI needs no AndroidX.
 */
class Ui(val context: Context, val palette: Palette = Palette(context)) {
    fun dp(v: Int) = context.dp(v)

    fun rounded(color: Int, radiusDp: Int, strokeColor: Int? = null): GradientDrawable = GradientDrawable().apply {
        cornerRadius = context.dpf(radiusDp.toFloat())
        setColor(color)
        if (strokeColor != null) setStroke(dp(1), strokeColor)
    }

    /** Ripple over [content]; an explicit mask keeps the ripple visible on transparent backgrounds. */
    fun ripple(content: GradientDrawable, rippleColor: Int = Palette.withAlpha(palette.onSurface, 0.12f)): RippleDrawable {
        val mask = GradientDrawable().apply {
            cornerRadii = content.cornerRadii ?: FloatArray(8) { content.cornerRadius }
            setColor(0xFFFFFFFF.toInt())
        }
        return RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask)
    }

    fun column(paddingDp: Int = 0, spacingDp: Int = 0): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(paddingDp), dp(paddingDp), dp(paddingDp), dp(paddingDp))
        if (spacingDp > 0) {
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            dividerDrawable = GradientDrawable().apply { setSize(1, dp(spacingDp)); setColor(0) }
        }
    }

    fun row(spacingDp: Int = 8): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        if (spacingDp > 0) {
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            dividerDrawable = GradientDrawable().apply { setSize(dp(spacingDp), 1); setColor(0) }
        }
    }

    fun text(
        value: CharSequence,
        sizeSp: Float = 15f,
        color: Int = palette.onSurface,
        bold: Boolean = false,
        maxLines: Int = Int.MAX_VALUE,
    ): TextView = TextView(context).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setLineSpacing(0f, 1.15f)
        if (maxLines != Int.MAX_VALUE) {
            this.maxLines = maxLines
            ellipsize = TextUtils.TruncateAt.END
        }
    }

    fun title(value: CharSequence) = text(value, 22f, bold = true).apply { isFocusable = true; isAccessibilityHeading = true }
    fun heading(value: CharSequence) = text(value, 13f, palette.accent, bold = true).apply {
        isAllCaps = true
        letterSpacing = 0.06f
        isAccessibilityHeading = true
        setPadding(dp(4), dp(12), dp(4), dp(2))
    }
    fun body(value: CharSequence) = text(value, 15f, palette.onSurface)
    fun caption(value: CharSequence) = text(value, 13f, palette.onSurfaceVariant)

    fun card(content: View, color: Int = palette.surface): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(color, 20, if (color == palette.surface) palette.outline else null)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    enum class ButtonStyle { FILLED, TONAL, OUTLINED, TEXT }

    fun button(label: CharSequence, style: ButtonStyle = ButtonStyle.FILLED, onClick: () -> Unit): Button = Button(context).apply {
        text = label
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        minHeight = dp(48)
        minimumHeight = dp(48)
        setPadding(dp(20), 0, dp(20), 0)
        stateListAnimator = null
        val (bg, fg, stroke) = when (style) {
            ButtonStyle.FILLED -> Triple(palette.accent, palette.onAccent, null)
            ButtonStyle.TONAL -> Triple(palette.accentContainer, palette.onAccentContainer, null)
            ButtonStyle.OUTLINED -> Triple(0, palette.accent, palette.outline)
            ButtonStyle.TEXT -> Triple(0, palette.accent, null)
        }
        background = ripple(rounded(bg, 24, stroke))
        setTextColor(fg)
        setOnClickListener { onClick() }
    }

    /** A full-width row with title, optional subtitle and a switch; the whole row is the touch target. */
    fun switchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val sw = Switch(context).apply {
            isChecked = checked
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(palette.accent, palette.onSurfaceVariant),
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(palette.accentContainer, palette.outline),
            )
            contentDescription = title
        }
        sw.setOnCheckedChangeListener { _: CompoundButton, v: Boolean -> onChange(v) }
        return labeledRow(title, subtitle, sw) { sw.toggle() }
    }

    fun labeledRow(title: String, subtitle: String?, trailing: View?, onClick: (() -> Unit)?): LinearLayout {
        val texts = column().apply {
            addView(text(title, 16f))
            if (!subtitle.isNullOrBlank()) addView(caption(subtitle))
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(4), dp(8), dp(4), dp(8))
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (trailing != null) addView(trailing)
            if (onClick != null) {
                background = ripple(rounded(0, 12))
                isClickable = true
                setOnClickListener { onClick() }
            }
        }
    }

    /** Radio choices; [describe] returns (title, subtitle) for each option. */
    fun <T> radioGroup(options: List<T>, selected: T, describe: (T) -> Pair<String, String?>, onSelect: (T) -> Unit): RadioGroup {
        val group = RadioGroup(context).apply { orientation = RadioGroup.VERTICAL }
        options.forEachIndexed { i, option ->
            val (t, s) = describe(option)
            val rb = RadioButton(context).apply {
                id = View.generateViewId()
                text = if (s == null) t else "$t\n$s"
                setTextColor(palette.onSurface)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                buttonTintList = ColorStateList.valueOf(palette.accent)
                minHeight = dp(56)
                setPadding(dp(8), dp(8), 0, dp(8))
                isChecked = option == selected
                tag = i
            }
            group.addView(rb)
        }
        group.setOnCheckedChangeListener { g, checkedId ->
            val idx = g.findViewById<RadioButton>(checkedId)?.tag as? Int ?: return@setOnCheckedChangeListener
            onSelect(options[idx])
        }
        return group
    }

    fun input(hint: String, value: String = "", password: Boolean = false, multiLine: Boolean = false): EditText = EditText(context).apply {
        this.hint = hint
        setText(value)
        setTextColor(palette.onSurface)
        setHintTextColor(palette.onSurfaceVariant)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        background = rounded(palette.surfaceVariant, 12)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        minHeight = dp(48)
        inputType = when {
            password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            multiLine -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
    }

    enum class Tone { INFO, SUCCESS, WARNING, ERROR }

    fun banner(message: CharSequence, tone: Tone = Tone.INFO, actionLabel: String? = null, action: (() -> Unit)? = null): View {
        val color = when (tone) {
            Tone.INFO -> palette.accentContainer
            Tone.SUCCESS -> Palette.withAlpha(palette.success, 0.18f)
            Tone.WARNING -> Palette.withAlpha(palette.warning, 0.20f)
            Tone.ERROR -> Palette.withAlpha(palette.error, 0.20f)
        }
        val box = column(spacingDp = 8).apply {
            background = rounded(color, 16)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(text(message, 14f, palette.onSurface))
            if (actionLabel != null && action != null) {
                addView(button(actionLabel, ButtonStyle.TEXT, action), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.END })
            }
        }
        box.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        return box
    }

    fun chip(label: String, selected: Boolean, onClick: () -> Unit): TextView = text(label, 14f, if (selected) palette.onAccentContainer else palette.onSurface).apply {
        background = ripple(rounded(if (selected) palette.accentContainer else 0, 16, if (selected) null else palette.outline))
        setPadding(dp(14), dp(8), dp(14), dp(8))
        minHeight = dp(40)
        gravity = Gravity.CENTER
        isClickable = true
        isSelected = selected
        setOnClickListener { onClick() }
    }

    fun spacer(heightDp: Int) = View(context).apply { minimumHeight = dp(heightDp) }

    fun matchWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}
