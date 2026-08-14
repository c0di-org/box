package dev.localagent.runtime.qemu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clamp, because everything past it is a QEMU that refuses to start on a phone with no
 * console attached. A preference file is user-writable state that outlives the build that wrote
 * it: a device carrying `guest_memory_mb = 8192` from an experiment must still open its box.
 */
class GuestSizingTest {

    @Test
    fun `memory above what the board can address is brought back to the ceiling`() {
        assertEquals(
            GuestSizing.MAX_MEMORY_MB,
            GuestSizing(memoryMb = 8192, processors = 2).clamped().memoryMb,
        )
    }

    @Test
    fun `memory below what Debian needs is brought up to the floor`() {
        assertEquals(
            GuestSizing.MIN_MEMORY_MB,
            GuestSizing(memoryMb = 64, processors = 2).clamped().memoryMb,
        )
    }

    @Test
    fun `processors are bounded on both sides`() {
        assertEquals(GuestSizing.MIN_PROCESSORS, GuestSizing(2048, 0).clamped().processors)
        assertEquals(GuestSizing.MAX_PROCESSORS, GuestSizing(2048, 64).clamped().processors)
    }

    @Test
    fun `a sizing already inside the ceilings is left alone`() {
        val chosen = GuestSizing(memoryMb = 3072, processors = 4)
        assertEquals(chosen, chosen.clamped())
    }

    @Test
    fun `the default is one Box would build without being asked`() {
        assertEquals(GuestSizing.DEFAULT, GuestSizing.DEFAULT.clamped())
        assertTrue(GuestSizing.DEFAULT.memoryMb in GuestSizing.MIN_MEMORY_MB..GuestSizing.MAX_MEMORY_MB)
    }
}
