package dev.localagent.workstation.computer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sizes here are the real ones, measured on a Galaxy Z Fold 7: the cover panel, the inner
 * panel, and a Box window in Samsung DeX on a 3440x1440 monitor. They are the three shapes the
 * product is actually met in, so they are what the policy is checked against.
 */
class GuestScreenFitTest {

    @Test
    fun `nothing attached asks for nothing`() {
        assertNull(GuestScreenFit.of(emptyList()))
    }

    @Test
    fun `a thumbnail never resizes the machine`() {
        // The task list's live computer row. Following it would shrink the guest to a postage
        // stamp every time the user left the computer for the list.
        assertNull(GuestScreenFit.of(listOf(GuestScreen(230, 145))))
    }

    @Test
    fun `the full pane wins over the thumbnail beside it`() {
        // Both are on screen together in the Wide layout, painted from one connection.
        val fit = GuestScreenFit.of(listOf(GuestScreen(230, 145), GuestScreen(1716, 1384)))
        assertEquals(GuestScreen(1712, 1384), fit)
    }

    @Test
    fun `a phone pane is taken at its own size`() {
        // 1080x2190 is 2.37 Mpx, under the ceiling, so the guest renders 1:1 with the panel --
        // which is the whole reason the terminal becomes legible.
        val fit = GuestScreenFit.of(listOf(GuestScreen(1080, 2190)))
        assertEquals(GuestScreen(1080, 2184), fit)
    }

    @Test
    fun `an oversized window keeps its shape and loses density`() {
        val monitor = GuestScreen(3440, 1440)
        val fit = requireNonNull(GuestScreenFit.of(listOf(monitor)))
        assertTrue("capped below the ceiling", fit.pixels <= 2_600_000L)
        // Within a rounding step of the window's own ratio: capping one side would put back the
        // letterboxing this whole change exists to remove.
        val wanted = monitor.width.toDouble() / monitor.height
        val got = fit.width.toDouble() / fit.height
        assertTrue("aspect $got should match $wanted", kotlin.math.abs(got - wanted) < 0.02)
    }

    @Test
    fun `sizes step down, so a drag settles and nothing is upscaled`() {
        val view = GuestScreen(1001, 802)
        val fit = requireNonNull(GuestScreenFit.of(listOf(view)))
        assertEquals(0, fit.width % 8)
        assertEquals(0, fit.height % 8)
        assertTrue("never wider than the view", fit.width <= view.width)
        assertTrue("never taller than the view", fit.height <= view.height)
    }

    @Test
    fun `the first size is always worth applying`() {
        assertTrue(GuestScreenFit.changeIsWorthIt(null, GuestScreen(1280, 800)))
    }

    @Test
    fun `a few pixels of movement is not worth a mode set`() {
        assertFalse(GuestScreenFit.changeIsWorthIt(GuestScreen(1280, 800), GuestScreen(1288, 800)))
    }

    @Test
    fun `turning the phone over is`() {
        assertTrue(GuestScreenFit.changeIsWorthIt(GuestScreen(1280, 800), GuestScreen(1080, 2184)))
    }

    private fun <T> requireNonNull(value: T?): T = value ?: error("expected a size")
}
