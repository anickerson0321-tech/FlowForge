package com.flowforge.core.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeometryTest {
    private val size = 200

    private fun inside(p: BubblePlacement, a: Bounds) =
        p.x >= a.left && p.y >= a.top && p.x + size <= a.right && p.y + size <= a.bottom

    // Representative usable areas (pixels): a phone-like portrait screen, the same rotated, and a
    // wide near-square unfolded inner display. Not exact Fold7 measurements.
    private val portrait = Bounds(0, 100, 1080, 2400)
    private val landscape = Bounds(100, 0, 2400, 1000)
    private val unfolded = Bounds(0, 90, 1968, 2100)

    @Test
    fun `saved fractions keep the bubble on the same edge and relative height after rotation`() {
        val before = BubbleGeometry.place(1f, 0.5f, portrait, size)
        assertEquals(portrait.right - size, before.x)
        val after = BubbleGeometry.place(before.fractionX, before.fractionY, landscape, size)
        assertEquals(landscape.right - size, after.x)
        assertEquals(landscape.top + (landscape.height - size) / 2, after.y)
        assertTrue(inside(after, landscape))
    }

    @Test
    fun `fold and unfold keep the bubble fully visible`() {
        for (fy in listOf(0f, 0.3f, 0.99f, 1f)) {
            for (fx in listOf(0f, 1f)) {
                assertTrue(inside(BubbleGeometry.place(fx, fy, portrait, size), portrait))
                assertTrue(inside(BubbleGeometry.place(fx, fy, unfolded, size), unfolded))
            }
        }
    }

    @Test
    fun `out of range fractions are clamped`() {
        val p = BubbleGeometry.place(3f, -2f, portrait, size)
        assertEquals(portrait.right - size, p.x)
        assertEquals(portrait.top, p.y)
    }

    @Test
    fun `drag end snaps to the nearest edge and clamps vertically`() {
        val left = BubbleGeometry.snap(300, 5000, portrait, size)
        assertEquals(portrait.left, left.x)
        assertEquals(portrait.bottom - size, left.y)
        assertEquals(0f, left.fractionX, 0f)
        assertEquals(1f, left.fractionY, 0.0001f)
        val right = BubbleGeometry.snap(700, -50, portrait, size)
        assertEquals(portrait.right - size, right.x)
        assertEquals(portrait.top, right.y)
        assertEquals(1f, right.fractionX, 0f)
    }

    @Test
    fun `snap then place round trips`() {
        val s = BubbleGeometry.snap(900, 1234, portrait, size)
        val p = BubbleGeometry.place(s.fractionX, s.fractionY, portrait, size)
        assertEquals(s.x, p.x)
        assertTrue(kotlin.math.abs(s.y - p.y) <= 1)
    }

    @Test
    fun `tiny area never produces negative offsets`() {
        val tiny = Bounds(0, 0, 150, 150)
        val p = BubbleGeometry.place(1f, 1f, tiny, size)
        assertEquals(0, p.x)
        assertEquals(0, p.y)
        assertEquals(0, BubbleGeometry.snap(100, 100, tiny, size).x)
    }

    @Test
    fun `two-pane breakpoint`() {
        assertFalse(AdaptiveLayout.isTwoPane(411)) // typical folded/cover or phone width
        assertTrue(AdaptiveLayout.isTwoPane(600))
        assertTrue(AdaptiveLayout.isTwoPane(880)) // typical unfolded inner display width
    }
}
