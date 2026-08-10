package dev.localagent.runtime.qemu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `:computer` reads the harness stream for two facts only. These pin that it stays two facts —
 * and, more importantly, that everything else is silence rather than a crash in the process that
 * is running the VM.
 */
class SessionSignalsTest {

    @Test
    fun `a finished session carries the agent's closing line`() {
        val signal = SessionSignals.read(
            """{"v":1,"type":"session_ended","outcome":{"status":"completed","summary":"Cloned and running on :3000"}}""",
        )
        assertEquals(
            SessionSignals.Signal.Finished("Cloned and running on :3000", failed = false),
            signal,
        )
    }

    @Test
    fun `a failed session is still a finish, and says so`() {
        val signal = SessionSignals.read(
            """{"type":"session_ended","outcome":{"status":"failed","message":"Not signed in"}}""",
        )
        assertEquals(SessionSignals.Signal.Finished("Not signed in", failed = true), signal)
    }

    @Test
    fun `a session that ends with no outcome still notifies`() {
        val signal = SessionSignals.read("""{"type":"session_ended"}""")
        assertEquals(SessionSignals.Signal.Finished(null, failed = false), signal)
    }

    @Test
    fun `a permission ask names the kind of decision, never the payload`() {
        val signal = SessionSignals.read(
            """{"type":"permission_requested","requestId":"r1","ask":{"kind":"run_command",""" +
                """"command":"rm -rf /workspace/secret-project"}}""",
        )
        val needsYou = signal as SessionSignals.Signal.NeedsYou
        assertEquals("It wants to run a command", needsYou.label)
        assertTrue(
            "the command must not reach a lock screen",
            !needsYou.label.contains("rm"),
        )
    }

    @Test
    fun `an unmodelled ask kind still asks`() {
        val signal = SessionSignals.read(
            """{"type":"permission_requested","ask":{"kind":"something_new","title":"Approve this"}}""",
        )
        assertEquals(SessionSignals.Signal.NeedsYou("Approve this"), signal)
    }

    @Test
    fun `everything else is silence`() {
        // A newer guest image against an older APK is the normal case, not the exception.
        assertNull(SessionSignals.read("""{"type":"tool_started","callId":"c1"}"""))
        assertNull(SessionSignals.read("""{"type":"message","text":"working on it"}"""))
        assertNull(SessionSignals.read("""{"type":"a_kind_from_a_later_build"}"""))
        assertNull(SessionSignals.read("not json at all"))
        assertNull(SessionSignals.read(""))
        assertNull(SessionSignals.read("   "))
    }
}
