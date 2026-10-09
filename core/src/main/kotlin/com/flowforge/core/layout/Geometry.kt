package com.flowforge.core.layout

/** Integer rectangle in window pixels (left/top inclusive, right/bottom exclusive). */
data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

data class BubblePlacement(val x: Int, val y: Int, val fractionX: Float, val fractionY: Float)

/**
 * Bubble position math. The position is stored as fractions of the usable area (0 = left/top,
 * 1 = right/bottom) so it survives rotation and fold/unfold, where the area changes size and shape.
 */
object BubbleGeometry {
    /** Absolute position for saved fractions; always fully inside [area] when it fits. */
    fun place(fractionX: Float, fractionY: Float, area: Bounds, size: Int): BubblePlacement {
        val fx = fractionX.coerceIn(0f, 1f)
        val fy = fractionY.coerceIn(0f, 1f)
        val x = area.left + (fx * maxOf(0, area.width - size)).toInt()
        val y = area.top + (fy * maxOf(0, area.height - size)).toInt()
        return BubblePlacement(x, y, fx, fy)
    }

    /** After a drag: snap to the nearer side edge, clamp vertically, and return the fractions to save. */
    fun snap(x: Int, y: Int, area: Bounds, size: Int): BubblePlacement {
        val toRight = x + size / 2 > area.left + area.width / 2
        val targetX = if (toRight) area.right - size else area.left
        val maxY = maxOf(area.top, area.bottom - size)
        val targetY = y.coerceIn(area.top, maxY)
        val fy = if (area.height > size) (targetY - area.top).toFloat() / (area.height - size) else 0f
        return BubblePlacement(maxOf(area.left, targetX), targetY, if (toRight) 1f else 0f, fy)
    }
}

/** Window-size breakpoints (Material guidance: expanded/medium width starts at 600dp). */
object AdaptiveLayout {
    const val TWO_PANE_MIN_WIDTH_DP = 600

    fun isTwoPane(widthDp: Int): Boolean = widthDp >= TWO_PANE_MIN_WIDTH_DP
}
