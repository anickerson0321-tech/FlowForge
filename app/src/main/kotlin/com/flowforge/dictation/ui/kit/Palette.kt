package com.flowforge.dictation.ui.kit

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import com.flowforge.dictation.R
import com.flowforge.dictation.graph
import kotlin.math.roundToInt

/**
 * Colors for the current light/dark configuration of [context]. Uses FlowForge's brand palette,
 * or the wallpaper-based Material You palette when the user enables "Match system colors".
 */
class Palette(private val context: Context) {
    val dark: Boolean = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val dynamic: Boolean = runCatching { context.graph.settings.useDynamicColor }.getOrDefault(false)

    private fun c(id: Int) = context.getColor(id)

    val background: Int
    val surface: Int
    val surfaceVariant: Int
    val onSurface: Int
    val onSurfaceVariant: Int
    val outline: Int
    val accent: Int
    val onAccent: Int
    val accentContainer: Int
    val onAccentContainer: Int
    val recording = c(R.color.ff_recording)
    val success = c(R.color.ff_success)
    val warning = c(R.color.ff_warning)
    val error = c(R.color.ff_error)

    init {
        if (dynamic) {
            if (dark) {
                background = c(android.R.color.system_neutral1_900)
                surface = c(android.R.color.system_neutral1_800)
                surfaceVariant = c(android.R.color.system_neutral2_700)
                onSurface = c(android.R.color.system_neutral1_50)
                onSurfaceVariant = c(android.R.color.system_neutral2_200)
                outline = c(android.R.color.system_neutral2_600)
                accent = c(android.R.color.system_accent1_200)
                onAccent = c(android.R.color.system_accent1_800)
                accentContainer = c(android.R.color.system_accent1_700)
                onAccentContainer = c(android.R.color.system_accent1_100)
            } else {
                background = c(android.R.color.system_neutral1_10)
                surface = c(android.R.color.system_neutral1_50)
                surfaceVariant = c(android.R.color.system_neutral2_100)
                onSurface = c(android.R.color.system_neutral1_900)
                onSurfaceVariant = c(android.R.color.system_neutral2_700)
                outline = c(android.R.color.system_neutral2_300)
                accent = c(android.R.color.system_accent1_600)
                onAccent = c(android.R.color.system_accent1_0)
                accentContainer = c(android.R.color.system_accent1_100)
                onAccentContainer = c(android.R.color.system_accent1_900)
            }
        } else {
            background = c(R.color.ff_background)
            surface = c(R.color.ff_surface)
            surfaceVariant = c(R.color.ff_surface_variant)
            onSurface = c(R.color.ff_on_surface)
            onSurfaceVariant = c(R.color.ff_on_surface_variant)
            outline = c(R.color.ff_outline)
            accent = c(R.color.ff_accent)
            onAccent = c(R.color.ff_on_accent)
            accentContainer = c(R.color.ff_accent_container)
            onAccentContainer = c(R.color.ff_on_accent_container)
        }
    }

    companion object {
        fun withAlpha(color: Int, alpha: Float): Int =
            Color.argb((alpha * 255).roundToInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
    }
}

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
fun Context.dpf(value: Float): Float = value * resources.displayMetrics.density
