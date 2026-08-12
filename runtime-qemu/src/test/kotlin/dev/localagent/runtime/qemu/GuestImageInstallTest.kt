package dev.localagent.runtime.qemu

import dev.localagent.runtime.qemu.GuestImageInstall.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Already installed" versus "a different image", which used to be the same answer.
 *
 * The old rule installed the disks only when they were absent. It was right about what it was
 * protecting — an app update must never wipe the user's Linux machine — and it had no way to say
 * anything else, so it also swallowed every rebuilt guest image. A developer changed
 * `guest/agentd.py`, rebuilt, deployed, and got a protocol error at handshake from the old image
 * still on the phone, with nothing anywhere reporting that the new one had been skipped.
 *
 * Each case below is one of the two halves that used to be indistinguishable.
 */
class GuestImageInstallTest {

    private val current = GuestImageIdentity("box-minimal-claude", "aaaaaaaaaaaaaaaa")
    private val rebuilt = GuestImageIdentity("box-minimal-claude", "bbbbbbbbbbbbbbbb")
    private val otherImage = GuestImageIdentity("bare-ubuntu", "aaaaaaaaaaaaaaaa")

    private fun decide(
        role: GuestImageRole,
        installed: GuestImageIdentity?,
        present: Boolean = true,
        intact: Boolean = true,
        replaceImage: Boolean = false,
        bundled: GuestImageIdentity = current,
    ) = GuestImageInstall.decide(role, bundled, installed, present, intact, replaceImage)

    private fun decideAll(
        installed: GuestImageIdentity?,
        present: Boolean = true,
        replaceImage: Boolean = false,
        bundled: GuestImageIdentity = current,
    ) = GuestImageRole.entries.associateWith {
        decide(it, installed, present, replaceImage = replaceImage, bundled = bundled)
    }

    @Test
    fun `a device already running this exact image is left completely alone`() {
        assertEquals(
            GuestImageRole.entries.associateWith { Decision.KEEP },
            decideAll(installed = current),
        )
    }

    @Test
    fun `a rebuilt image replaces the machine and keeps the work`() {
        // The headline case, and the one the old code got wrong in silence.
        val decisions = decideAll(installed = current, bundled = rebuilt)

        assertEquals(Decision.INSTALL, decisions[GuestImageRole.KERNEL])
        assertEquals(Decision.INSTALL, decisions[GuestImageRole.INITRD])
        assertEquals(Decision.INSTALL, decisions[GuestImageRole.SYSTEM])
        assertEquals(Decision.KEEP, decisions[GuestImageRole.WORKSPACE])
    }

    @Test
    fun `a first install writes everything`() {
        assertEquals(
            GuestImageRole.entries.associateWith { Decision.INSTALL },
            decideAll(installed = null, present = false),
        )
    }

    @Test
    fun `a device migrated from the flat layout keeps its workspace and gets a fresh image`() {
        // Migration moves the old files into the keyed layout but leaves no record, so the image
        // reads as unknown. That is the honest answer: nothing on that device says what those
        // bytes were. The workspace is still the user's, and still survives.
        val decisions = decideAll(installed = null, present = true)

        assertEquals(Decision.INSTALL, decisions[GuestImageRole.SYSTEM])
        assertEquals(Decision.KEEP, decisions[GuestImageRole.WORKSPACE])
    }

    @Test
    fun `an explicit reinstall replaces the image and still cannot touch the workspace`() {
        val decisions = decideAll(installed = current, replaceImage = true)

        assertEquals(Decision.INSTALL, decisions[GuestImageRole.KERNEL])
        assertEquals(Decision.INSTALL, decisions[GuestImageRole.INITRD])
        assertEquals(Decision.INSTALL, decisions[GuestImageRole.SYSTEM])
        // Not a gate that a caller can pass a flag through. "Give me a clean system disk" has
        // never meant "and delete my work", so there is no argument that makes this INSTALL.
        assertEquals(Decision.KEEP, decisions[GuestImageRole.WORKSPACE])
    }

    @Test
    fun `a workspace that has gone missing is created even by a reinstall`() {
        assertEquals(
            Decision.INSTALL,
            decide(GuestImageRole.WORKSPACE, installed = current, present = false, replaceImage = true),
        )
    }

    @Test
    fun `a missing payload is installed even when the identity matches`() {
        assertEquals(
            Decision.INSTALL,
            decide(GuestImageRole.SYSTEM, installed = current, present = false),
        )
    }

    @Test
    fun `a corrupted kernel is repaired, because qemu only ever reads it`() {
        assertEquals(
            Decision.INSTALL,
            decide(GuestImageRole.KERNEL, installed = current, intact = false),
        )
        assertEquals(
            Decision.INSTALL,
            decide(GuestImageRole.INITRD, installed = current, intact = false),
        )
    }

    @Test
    fun `a system disk that no longer matches its digest is not a corrupted one`() {
        // The guest writes to this disk from its first boot, so its bytes stop matching the
        // manifest immediately. Treating that as damage would reinstall the image — and throw
        // away everything the box had done — on every single provision.
        assertEquals(
            Decision.KEEP,
            decide(GuestImageRole.SYSTEM, installed = current, intact = false),
        )
        assertEquals(
            Decision.KEEP,
            decide(GuestImageRole.WORKSPACE, installed = current, intact = false),
        )
    }

    @Test
    fun `a different image installs without consulting another image's workspace`() {
        // Keying makes this two directories, so "install bare-ubuntu" never reaches the Claude
        // box's disks. What is asserted here is only the decision: with no record under the new
        // key, everything belonging to the new image is written, and its own workspace is created.
        assertEquals(
            GuestImageRole.entries.associateWith { Decision.INSTALL },
            decideAll(installed = null, present = false, bundled = otherImage),
        )
    }

    @Test
    fun `up to date means this exact image with all of its files present`() {
        assertTrue(
            GuestImageInstall.isUpToDate(current, current, GuestImageRole.entries.toSet()),
        )
    }

    @Test
    fun `an older version of the same image is not up to date`() {
        assertFalse(
            GuestImageInstall.isUpToDate(rebuilt, current, GuestImageRole.entries.toSet()),
        )
    }

    @Test
    fun `a device that never finished installing is not up to date`() {
        assertFalse(
            GuestImageInstall.isUpToDate(current, null, GuestImageRole.entries.toSet()),
        )
    }

    @Test
    fun `a current image missing its workspace disk still has work to do`() {
        assertFalse(
            GuestImageInstall.isUpToDate(
                current,
                current,
                GuestImageRole.entries.toSet() - GuestImageRole.WORKSPACE,
            ),
        )
    }

    @Test
    fun `the workspace is the only payload the user owns`() {
        // Stated as a test because every rule in this file hangs off it, and a future role added
        // to the enum should have to think about which side it falls on.
        assertEquals(
            listOf(GuestImageRole.WORKSPACE),
            GuestImageRole.entries.filter { it.owner == GuestImageOwner.USER },
        )
    }
}
