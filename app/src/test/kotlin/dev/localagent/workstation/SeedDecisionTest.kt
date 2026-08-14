package dev.localagent.workstation

import dev.localagent.runtime.qemu.GuestImageIdentity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeedDecisionTest {

    private val old = GuestImageIdentity("box-minimal-claude", "1111111111111111")
    private val new = GuestImageIdentity("box-minimal-claude", "2222222222222222")

    private fun decide(
        openFaster: Boolean = true,
        installed: GuestImageIdentity? = old,
        bundled: GuestImageIdentity? = new,
        lastAttempted: String? = null,
        batterySaver: Boolean = false,
        batteryPercent: Int = 80,
    ) = SeedDecision.shouldSeed(
        openFaster = openFaster,
        installed = installed,
        bundled = bundled,
        lastAttempted = lastAttempted,
        batterySaver = batterySaver,
        batteryPercent = batteryPercent,
        minimumBatteryPercent = 25,
    )

    @Test
    fun `seeds when an update is pending`() {
        assertTrue(decide())
    }

    @Test
    fun `does not seed when Open faster is off`() {
        // The snapshot is the storage that setting declines, so there is nothing to boot for.
        assertFalse(decide(openFaster = false))
    }

    @Test
    fun `does not seed when the bundled image is already installed`() {
        assertFalse(decide(installed = new))
    }

    @Test
    fun `does not seed on a device that has never had an image`() {
        assertFalse(decide(installed = null))
    }

    @Test
    fun `does not seed the same image twice`() {
        assertFalse(decide(lastAttempted = new.toString()))
    }

    @Test
    fun `seeds again when a newer image arrives after a failed attempt`() {
        val newer = GuestImageIdentity("box-minimal-claude", "3333333333333333")
        assertTrue(decide(bundled = newer, lastAttempted = new.toString()))
    }

    @Test
    fun `does not seed in battery saver`() {
        assertFalse(decide(batterySaver = true))
    }

    @Test
    fun `does not seed on a nearly flat phone`() {
        assertFalse(decide(batteryPercent = 9))
    }

    @Test
    fun `seeds when the device will not say what the battery is`() {
        // A device that declines to answer is not a device that said no.
        assertTrue(decide(batteryPercent = 0))
        assertTrue(decide(batteryPercent = -1))
    }

    @Test
    fun `does not seed without a bundled image to seed from`() {
        assertFalse(decide(bundled = null))
    }

    @Test
    fun `a different image id is an update like any other`() {
        // Ids differing rather than versions is a second image, which gets its own workspace --
        // and so its own cold first open, which is the thing being avoided.
        assertTrue(decide(bundled = GuestImageIdentity("box-desktop", "1111111111111111")))
    }
}
