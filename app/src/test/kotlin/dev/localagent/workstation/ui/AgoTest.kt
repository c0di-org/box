package dev.localagent.workstation.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AgoTest {

    private val now = 1_760_000_000_000L

    private fun ago(millis: Long) = Ago.of(now - millis, now)

    @Test
    fun `seconds are just now`() {
        assertEquals("just now", ago(0))
        assertEquals("just now", ago(59_000))
    }

    @Test
    fun `minutes, hours, days, weeks`() {
        assertEquals("1m ago", ago(60_000))
        assertEquals("59m ago", ago(59 * 60_000L))
        assertEquals("1h ago", ago(60 * 60_000L))
        assertEquals("23h ago", ago(23 * 3_600_000L))
        assertEquals("1d ago", ago(24 * 3_600_000L))
        assertEquals("6d ago", ago(6 * 86_400_000L))
        assertEquals("1w ago", ago(7 * 86_400_000L))
        assertEquals("3w ago", ago(21 * 86_400_000L))
    }

    @Test
    fun `a clock that went backwards does not report the future`() {
        // A timezone change, an NTP correction, or a summary written by a process since restarted.
        assertEquals("just now", Ago.of(now + 60_000, now))
    }
}
