package dev.localagent.workstation.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * The name a shared file gets, which is the one place the inbox trusts nothing.
 *
 * A display name comes from whichever app opened the share sheet, and it is the only field a
 * sender controls completely. The stamp is the other half: the sync keeps the phone's copy on a
 * collision and never deletes anything to settle one, so two screenshots an hour apart must not
 * arrive under the same name and start an argument inside the user's own folder.
 */
class InboxTest {
    private val noon = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse("2026-08-12 21:47:55")!!

    @Test
    fun `an ordinary name keeps its shape behind the stamp`() {
        assertEquals("20260812-214755-holiday.png", Inbox.stamped("holiday.png", noon))
    }

    @Test
    fun `a name that tries to climb out of the folder cannot`() {
        val name = Inbox.stamped("../../.config/box/credentials.json", noon)

        assertFalse(name.contains('/'))
        assertFalse(name.contains(".."))
        assertTrue(name.startsWith("20260812-214755-"))
    }

    @Test
    fun `a windows path separator is not a way round it either`() {
        assertFalse(Inbox.stamped("""..\..\secrets.txt""", noon).contains('\\'))
    }

    @Test
    fun `a name of nothing usable still produces a file`() {
        assertEquals("20260812-214755-file", Inbox.stamped("///", noon))
        assertEquals("20260812-214755-file", Inbox.stamped("", noon))
    }

    @Test
    fun `a very long name is cut rather than refused`() {
        val name = Inbox.stamped("x".repeat(400) + ".png", noon)

        // Some filesystems stop at 255 bytes, and a name nobody can read is no better than a short
        // one. The stamp is what makes it unique, so the tail is the part that can go.
        assertTrue(name.length < 120)
        assertTrue(name.startsWith("20260812-214755-x"))
    }

    @Test
    fun `two files sent in the same second do not have to collide for the sync to be safe`() {
        // Same second, same name: this is the case the stamp cannot solve, and it is left alone on
        // purpose. The sync keeps the phone's copy and parks the other as `.from-box`, so nothing
        // is lost — the stamp is here to make that rare, not to promise it never happens.
        assertEquals(Inbox.stamped("shot.png", noon), Inbox.stamped("shot.png", noon))
    }

    @Test
    fun `the guest path is the one the agent is told, and it is inside the shared folder`() {
        assertEquals(
            "/workspace/shared/inbox/20260812-214755-holiday.png",
            Inbox.guestPath(Inbox.stamped("holiday.png", noon)),
        )
    }
}
