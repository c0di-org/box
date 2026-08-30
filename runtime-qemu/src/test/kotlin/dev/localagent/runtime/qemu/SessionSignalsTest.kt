package dev.localagent.runtime.qemu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The few facts `:computer` reads out of a wire it deliberately does not parse.
 *
 * Worth pinning because both directions are costly and neither is loud. A signal missed is a
 * notification never posted, or files that never leave the box; a signal invented is a phone that
 * buzzes at nothing. And the guest image upgrades independently of the APK, so lines this build
 * has never seen have to stay silent rather than become errors.
 */
class SessionSignalsTest {

    @Test
    fun `a turn ending is quiet`() {
        assertEquals(
            SessionSignals.Signal.Quiet,
            SessionSignals.read("""{"type":"activity","activity":{"kind":"idle"}}"""),
        )
    }

    /** The agent mid-sentence. Its files are still being written, so nothing may be carried out. */
    @Test
    fun `an agent still working is not quiet`() {
        assertNull(SessionSignals.read("""{"type":"activity","activity":{"kind":"working","label":"Building"}}"""))
        assertNull(SessionSignals.read("""{"type":"activity","activity":{"kind":"thinking"}}"""))
        assertNull(SessionSignals.read("""{"type":"activity","activity":{"kind":"starting"}}"""))
    }

    /** An activity kind this build has never heard of is silence, not a guess at quiet. */
    @Test
    fun `an unknown activity kind says nothing`() {
        assertNull(SessionSignals.read("""{"type":"activity","activity":{"kind":"pondering"}}"""))
        assertNull(SessionSignals.read("""{"type":"activity"}"""))
    }

    @Test
    fun `a finished session still reports its outcome`() {
        val signal = SessionSignals.read(
            """{"type":"session_ended","outcome":{"status":"completed","summary":"All done"}}""",
        )

        assertEquals(SessionSignals.Signal.Finished("All done", failed = false), signal)
    }

    @Test
    fun `a failure is reported as one`() {
        val signal = SessionSignals.read(
            """{"type":"session_ended","outcome":{"status":"failed","message":"It broke"}}""",
        ) as SessionSignals.Signal.Finished

        assertTrue(signal.failed)
        assertEquals("It broke", signal.summary)
    }

    @Test
    fun `a permission request still needs you`() {
        val signal = SessionSignals.read(
            """{"type":"permission_requested","ask":{"kind":"run_command"}}""",
        )

        assertTrue(signal is SessionSignals.Signal.NeedsYou)
    }

    @Test
    fun `an answered request is taken back`() {
        assertEquals(
            SessionSignals.Signal.Answered,
            SessionSignals.read("""{"type":"permission_resolved","requestId":"r1"}"""),
        )
    }

    @Test
    fun `noise is silence rather than an error`() {
        assertNull(SessionSignals.read(""))
        assertNull(SessionSignals.read("   "))
        assertNull(SessionSignals.read("not json at all"))
        assertNull(SessionSignals.read("""{"type":"something_from_a_newer_image"}"""))
    }
}
