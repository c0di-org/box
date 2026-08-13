package dev.localagent.runtime.qemu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The note a suspended box leaves behind.
 *
 * Worth its own test because of what acting on a bad one costs. `loadvm` reverts the guest's disks
 * to the snapshot it loads, so a note that survives the thing it describes — a truncated write, an
 * image replaced by an app update — does not fail politely. It rolls the user's `/workspace` back.
 */
class SuspendedVmTest {
    @get:Rule val folder = TemporaryFolder()

    private fun file() = File(folder.root, "suspend.json")

    @Test
    fun `a saved box is read back exactly`() {
        val saved = SuspendedVm(
            "box-suspend",
            "box-minimal-claude@92ab9fc3",
            1_760_000_000_000L,
            4_200L,
            machine = "7f3a91c40be2d518",
        )
        saved.writeTo(file())
        assertEquals(saved, SuspendedVm.read(file()))
    }

    @Test
    fun `a note from before machines were recorded names no machine`() {
        // Such a note was written by a build with a different device set than any build that
        // records one, so reading it as "no machine" is what makes the resume check refuse it —
        // see QemuCommand.machine. Blank has to survive the read for that to happen.
        file().writeText(
            """{"tag":"box-suspend","image":"box-minimal-claude@92ab9fc3","savedAt":1,"saveMillis":2}""",
        )
        assertEquals("", SuspendedVm.read(file())?.machine)
    }

    @Test
    fun `no note means boot cold`() {
        assertNull(SuspendedVm.read(file()))
    }

    @Test
    fun `a truncated note is no note`() {
        // The failure this stands for is a process killed mid-write. Half a note names a snapshot
        // that may never have finished being written, and loading that reverts the disks to match
        // memory that does not exist.
        file().writeText("""{"tag":"box-suspend","ima""")
        assertNull(SuspendedVm.read(file()))
    }

    @Test
    fun `a note that names no image is refused`() {
        // Which image it belongs to is the only thing standing between a resume and a snapshot
        // taken from a different Debian, so a note without one is not usable.
        file().writeText("""{"tag":"box-suspend","savedAt":1}""")
        assertNull(SuspendedVm.read(file()))
    }

    @Test
    fun `a blank tag is refused`() {
        file().writeText("""{"tag":"","image":"box@1"}""")
        assertNull(SuspendedVm.read(file()))
    }

    @Test
    fun `writing replaces an older note whole`() {
        SuspendedVm("box-suspend", "box@1", 1L, 1L).writeTo(file())
        SuspendedVm("box-suspend", "box@2", 2L, 99L).writeTo(file())
        assertEquals("box@2", SuspendedVm.read(file())?.image)
        // The temporary the atomic write goes through must not be left behind next to the note.
        assertEquals(listOf("suspend.json"), folder.root.list()!!.sorted())
    }
}
