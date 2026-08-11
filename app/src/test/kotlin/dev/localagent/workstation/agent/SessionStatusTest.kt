package dev.localagent.workstation.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the session list reads out of a harness stream.
 *
 * The premise of the list is that several agents work at once and the user needs to know which one
 * wants something. That is the only fact the list cannot derive from a session's own lifecycle, so
 * these pin it: the request raises "needs you", the answer clears it, and nothing else moves it.
 */
class SessionStatusTest {
    private val context = HarnessWire.Context(
        sessionId = "s-1",
        harnessId = "claude-code",
        title = "Clone project and run",
    )

    private fun status(line: String) = sessionStatusFor(line, context)

    @Test
    fun `a permission request puts the session in needs-you`() {
        val result = status(
            """{"v":1,"at":1,"type":"permission_requested","requestId":"r1",
               "ask":{"kind":"run_command","command":"npm install"}}""",
        )

        assertEquals(SessionStatus.NeedsYou("It wants to run a command"), result)
    }

    @Test
    fun `answering it puts the session back to active`() {
        val result = status(
            """{"v":1,"at":2,"type":"permission_resolved","requestId":"r1","decision":"allow"}""",
        )

        // Including an "always allow" that was applied without ever raising a sheet: the list must
        // not keep pointing at a question nobody is being asked any more.
        assertEquals(SessionStatus.Active, result)
        assertEquals(
            SessionStatus.Active,
            status("""{"type":"permission_resolved","requestId":"r1","decision":"allow_always"}"""),
        )
    }

    @Test
    fun `the reason never carries the payload`() {
        val result = status(
            """{"type":"permission_requested","requestId":"r1",
               "ask":{"kind":"run_command","command":"git push --force origin main"}}""",
        )

        // This string is persisted by SessionStore. The command itself belongs in the transcript,
        // where the user opened it deliberately.
        val reason = (result as SessionStatus.NeedsYou).reason
        assertTrue(reason, !reason.contains("git push"))
    }

    @Test
    fun `every ask kind produces a reason`() {
        val kinds = listOf(
            """{"kind":"edit_file","path":"/workspace/a.kt","patch":"@@ -1 +1 @@\n-a\n+b\n"}""",
            """{"kind":"run_command","command":"ls"}""",
            """{"kind":"network_access","host":"example.com"}""",
            """{"kind":"something_new_from_a_newer_harness"}""",
        )

        kinds.forEach { ask ->
            val result = status("""{"type":"permission_requested","requestId":"r1","ask":$ask}""")
            assertTrue(ask, result is SessionStatus.NeedsYou)
            assertTrue(ask, (result as SessionStatus.NeedsYou).reason.isNotBlank())
        }
    }

    @Test
    fun `ordinary traffic says nothing about the list`() {
        assertNull(status("""{"type":"message","messageId":"m1","text":"Installing dependencies"}"""))
        assertNull(status("""{"type":"tool_started","callId":"c1","tool":{"kind":"shell","command":"ls"}}"""))
        assertNull(status("""{"type":"session_ended","outcome":{"status":"completed"}}"""))
        assertNull(status("npm warn deprecated something@1.0.0"))
        assertNull(status(""))
    }

    @Test
    fun `prose that merely mentions a permission is not a permission`() {
        // The substring gate is what keeps this off the parse path for most lines, so the line it
        // does let through still has to survive being read properly.
        assertNull(
            status("""{"type":"message","messageId":"m1","text":"chmod failed: permission_denied"}"""),
        )
    }

    @Test
    fun `session_ended is left alone`() {
        // The harness emits it on every turn, not only at the end, and the list takes Finished from
        // the process actually exiting. Reading it here would retire a session still being used.
        assertNull(status("""{"type":"session_ended","outcome":{"status":"failed","message":"x"}}"""))
    }
}
