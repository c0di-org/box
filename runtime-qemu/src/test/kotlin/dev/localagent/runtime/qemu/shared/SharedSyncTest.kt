package dev.localagent.runtime.qemu.shared

import dev.localagent.runtime.qemu.shared.SharedSync.Record
import dev.localagent.runtime.qemu.shared.SharedSync.Stamp
import dev.localagent.runtime.qemu.shared.SharedSync.SyncAction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The nine cases the shared folder can be in, one answer each.
 *
 * Every one of these is a decision somebody could reasonably have made differently, so they are
 * pinned here rather than left to be re-derived from the code. The conflict case is the one that
 * matters most: it is the only path where two real versions of a file exist at once, and the only
 * one where getting it wrong loses work rather than delaying it.
 */
class SharedSyncTest {

    private val early = Stamp(size = 10, modifiedMillis = 1_000)
    private val later = Stamp(size = 12, modifiedMillis = 2_000)

    @Test
    fun `a file only on the phone goes into the box`() {
        val plan = SharedSync.plan(
            phone = mapOf("notes.md" to early),
            box = emptyMap(),
            known = emptyMap(),
        )
        assertEquals(listOf(SyncAction.Push("notes.md")), plan)
    }

    @Test
    fun `a file the agent made is brought out to the phone`() {
        val plan = SharedSync.plan(
            phone = emptyMap(),
            box = mapOf("report.md" to early),
            known = emptyMap(),
        )
        assertEquals(listOf(SyncAction.Pull("report.md")), plan)
    }

    @Test
    fun `two sides that agree are left alone`() {
        val plan = SharedSync.plan(
            phone = mapOf("notes.md" to early),
            box = mapOf("notes.md" to early),
            known = mapOf("notes.md" to Record(early, early)),
        )
        assertEquals(emptyList<SyncAction>(), plan)
    }

    @Test
    fun `an edit on the phone is pushed`() {
        val plan = SharedSync.plan(
            phone = mapOf("notes.md" to later),
            box = mapOf("notes.md" to early),
            known = mapOf("notes.md" to Record(early, early)),
        )
        assertEquals(listOf(SyncAction.Push("notes.md")), plan)
    }

    @Test
    fun `an edit in the box is pulled`() {
        val plan = SharedSync.plan(
            phone = mapOf("notes.md" to early),
            box = mapOf("notes.md" to later),
            known = mapOf("notes.md" to Record(early, early)),
        )
        assertEquals(listOf(SyncAction.Pull("notes.md")), plan)
    }

    @Test
    fun `when both sides changed the phone wins and the box's version is kept beside it`() {
        val plan = SharedSync.plan(
            phone = mapOf("notes.md" to later),
            box = mapOf("notes.md" to Stamp(size = 99, modifiedMillis = 3_000)),
            known = mapOf("notes.md" to Record(early, early)),
        )
        assertEquals(listOf(SyncAction.Resolve("notes.md", "notes.md.from-box")), plan)
    }

    @Test
    fun `a second disagreement about the same file does not overwrite the first copy`() {
        val plan = SharedSync.plan(
            phone = mapOf("notes.md" to later, "notes.md.from-box" to early),
            box = mapOf("notes.md" to Stamp(99, 3_000), "notes.md.from-box" to early),
            known = mapOf(
                "notes.md" to Record(early, early),
                "notes.md.from-box" to Record(early, early),
            ),
        )
        assertEquals(listOf(SyncAction.Resolve("notes.md", "notes.md.from-box.2")), plan)
    }

    @Test
    fun `a file never seen before on both sides at once is a disagreement`() {
        val plan = SharedSync.plan(
            phone = mapOf("notes.md" to early),
            box = mapOf("notes.md" to later),
            known = emptyMap(),
        )
        assertEquals(listOf(SyncAction.Resolve("notes.md", "notes.md.from-box")), plan)
    }

    @Test
    fun `deleting on the phone does not bring the box's copy back`() {
        val plan = SharedSync.plan(
            phone = emptyMap(),
            box = mapOf("notes.md" to early),
            known = mapOf("notes.md" to Record(early, early)),
        )
        assertEquals(emptyList<SyncAction>(), plan)
    }

    @Test
    fun `a file deleted on the phone stays deleted however the box's copy changes`() {
        // The record is what remembers the deletion. Without it the next pass would read the
        // box's copy as a file it had never seen and copy it out again, undoing the deletion once
        // per sync forever.
        val plan = SharedSync.plan(
            phone = emptyMap(),
            box = mapOf("notes.md" to later),
            known = mapOf("notes.md" to Record(early, early)),
        )
        assertEquals(emptyList<SyncAction>(), plan)
    }

    @Test
    fun `putting a deleted file back on the phone pushes it over the box's copy`() {
        val plan = SharedSync.plan(
            phone = mapOf("notes.md" to later),
            box = mapOf("notes.md" to early),
            known = mapOf("notes.md" to Record(early, early)),
        )
        assertEquals(listOf(SyncAction.Push("notes.md")), plan)
    }

    @Test
    fun `a file the box lost comes back from the phone`() {
        val plan = SharedSync.plan(
            phone = mapOf("notes.md" to early),
            box = emptyMap(),
            known = mapOf("notes.md" to Record(early, early)),
        )
        assertEquals(listOf(SyncAction.Push("notes.md")), plan)
    }

    @Test
    fun `a record for a file that is nowhere is dropped`() {
        val plan = SharedSync.plan(
            phone = emptyMap(),
            box = emptyMap(),
            known = mapOf("notes.md" to Record(early, early)),
        )
        assertEquals(listOf(SyncAction.Untrack("notes.md")), plan)
    }

    @Test
    fun `nothing in the plan ever deletes a file`() {
        // Stated as a test because it is the invariant the whole conflict rule rests on: the worst
        // this design can do to a user is leave a spare file, never remove one.
        val plan = SharedSync.plan(
            phone = mapOf("a" to later, "b" to early, "d" to early),
            box = mapOf("a" to later, "c" to early, "d" to early),
            known = mapOf("a" to Record(early, early), "d" to Record(early, early)),
        )
        assertEquals(
            listOf(
                SyncAction.Resolve("a", "a.from-box"),
                SyncAction.Push("b"),
                SyncAction.Pull("c"),
            ),
            plan,
        )
    }
}
