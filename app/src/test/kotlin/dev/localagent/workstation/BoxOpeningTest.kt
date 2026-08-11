package dev.localagent.workstation

import dev.localagent.runtime.api.RuntimeFailure
import dev.localagent.runtime.api.RuntimeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The progress indicator's arithmetic.
 *
 * Worth pinning because the whole point of it is to be honest about a wait Box cannot observe: the
 * guest is silent for two and a half minutes, so every property that keeps the bar truthful is a
 * property of this class and nothing else.
 */
class BoxOpeningTest {

    private val expected = 100_000L

    private fun at(millis: Long, state: RuntimeState = RuntimeState.Connecting) =
        BoxProgress.of(state, millis, expected)

    @Test
    fun `the bar never reaches the end before the box says it is open`() {
        // Ten times the estimate. Still not done, and still not claiming to be.
        assertTrue(at(1_000_000L).fraction < 1f)
        assertEquals(1f, BoxProgress.of(RuntimeState.Ready, 1_000_000L, expected).fraction, 0.0001f)
    }

    @Test
    fun `it advances the whole way through the silence`() {
        // The failure mode this replaces: a bar that only moves on runtime states sits still for
        // the entire boot, because the guest spends all of it inside Connecting.
        val quarter = at(25_000L).fraction
        val half = at(50_000L).fraction
        val most = at(90_000L).fraction
        assertTrue("$quarter should be below $half", quarter < half)
        assertTrue("$half should be below $most", most > half)
        assertTrue("the bar should be most of the way at the expected moment", most > 0.8f)
    }

    @Test
    fun `time never runs the bar backwards`() {
        var previous = 0f
        for (millis in 0L..400_000L step 1_000L) {
            val fraction = at(millis).fraction
            assertTrue("went backwards at ${millis}ms", fraction >= previous)
            previous = fraction
        }
    }

    @Test
    fun `a confirmed checkpoint pulls a slow-reading bar forward`() {
        // A phone quicker than its own estimate gets to QEMU-is-up while the clock still says 2%.
        // What the runtime has confirmed outranks what the clock guesses.
        assertTrue(BoxProgress.of(RuntimeState.Connecting, 2_000L, expected).fraction >= 0.15f)
        assertTrue(BoxProgress.of(RuntimeState.Starting, 0L, expected).fraction >= 0.09f)
    }

    @Test
    fun `unpacking the image reports its own real progress`() {
        val early = BoxProgress.of(RuntimeState.Provisioning(0.1f), 500L, expected)
        val late = BoxProgress.of(RuntimeState.Provisioning(0.9f), 900L, expected)
        assertTrue(late.fraction > early.fraction)
        assertTrue("unpacking is a small share of the whole wait", late.fraction < 0.15f)
    }

    @Test
    fun `time left counts down and then admits it does not know`() {
        assertEquals(75, at(25_000L).remainingSeconds)
        assertEquals(1, at(99_500L).remainingSeconds)
        // Past the estimate, quoting a number would mean inventing one.
        assertNull(at(140_000L).remainingSeconds)
        assertTrue(at(140_000L).overdue)
        assertFalse(at(25_000L).overdue)
    }

    @Test
    fun `an opening whose clock was lost is shown as indeterminate`() {
        // Android replaced the UI process mid-boot. The VM is still coming up in its own process,
        // but nothing knows when it started, and a bar drawn from a made-up start time is worse
        // than a spinner.
        val orphan = BoxProgress.of(RuntimeState.Connecting, null, expected)
        assertFalse(orphan.determinate)
        assertNull(orphan.remainingSeconds)
    }

    @Test
    fun `the pause between unpacking and booting does not read as failure`() {
        // Provisioning finishes by reporting Stopped, one broadcast before Starting arrives.
        val between = BoxProgress.of(RuntimeState.Stopped, 30_000L, expected)
        assertTrue(between.fraction > 0f)
        assertTrue(between.phase.isNotBlank())
    }

    @Test
    fun `a failure keeps whatever the bar had reached`() {
        val failed = BoxProgress.of(RuntimeState.Failed(RuntimeFailure("QEMU died")), 40_000L, expected)
        assertTrue(failed.fraction > 0f)
        assertTrue(failed.fraction < 1f)
    }

    @Test
    fun `the estimate follows this phone rather than the newest boot`() {
        val first = BoxProgress.learn(null, 170_000L)
        assertEquals(170_000L, first)

        // One hot, slow opening should nudge the estimate, not become it.
        val afterHot = BoxProgress.learn(first, 252_000L)
        assertTrue(afterHot > first)
        assertTrue("a single slow boot must not take over", afterHot < 220_000L)

        // And it converges when the phone is consistently quicker.
        var learned = afterHot
        repeat(6) { learned = BoxProgress.learn(learned, 90_000L) }
        assertTrue(learned < 120_000L)
    }

    @Test
    fun `an absurd measurement cannot poison the estimate`() {
        // A device that slept through its own boot, or a clock that jumped.
        assertTrue(BoxProgress.learn(null, 5L) >= BoxProgress.MIN_LEARNED_MILLIS)
        assertTrue(BoxProgress.learn(null, 90_000_000L) <= BoxProgress.MAX_LEARNED_MILLIS)
    }
}
