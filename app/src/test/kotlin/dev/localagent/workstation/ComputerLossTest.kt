package dev.localagent.workstation

import dev.localagent.runtime.api.RuntimeFailure
import dev.localagent.runtime.api.RuntimeState
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse

/**
 * Noticing that the computer process has died.
 *
 * The bug this pins was observed on hardware: QEMU aborted during startup, and because Box only
 * held a connection to `:computer` once the computer was already `Ready`, nothing was watching at
 * the moment it mattered. The UI showed "Debian is up, waiting for its private control channel"
 * for five minutes against a process that no longer existed.
 */
class ComputerLossTest {

    @Test
    fun `a death during startup is reported rather than waited out`() {
        val lost = ComputerLoss.after(RuntimeState.Connecting) as RuntimeState.Failed
        assertTrue("the user must be able to try again", lost.reason.recoverable)
        assertTrue(lost.reason.message.contains("starting up"))
    }

    @Test
    fun `a death while ready is reported too`() {
        assertTrue(ComputerLoss.after(RuntimeState.Ready) is RuntimeState.Failed)
    }

    @Test
    fun `a process that goes away after stopping is not a failure`() {
        // The computer process retires itself once its single QEMU run is over, and announces the
        // stop first. Calling that "stopped unexpectedly" would cry wolf on the normal path.
        assertNull(ComputerLoss.after(RuntimeState.Stopped))
    }

    @Test
    fun `a saved box going away is not a failure`() {
        // The sharpest case, and the one this got wrong for as long as it could not happen.
        // Saving a box *is* the process ending — QEMU writes the guest out and exits, and
        // `:computer` retires behind it — so the disconnect is the operation succeeding. Since the
        // idle timer started saving untouched boxes, "the computer stopped unexpectedly" would
        // land on a box sitting safe on disk every fifteen minutes.
        assertNull(ComputerLoss.after(RuntimeState.Suspended))
    }

    @Test
    fun `a saved box is not watched`() {
        // Nothing left for the connection to report the death of; see the note above.
        assertFalse(ComputerLoss.shouldWatch(RuntimeState.Suspended))
    }

    @Test
    fun `dying part way through a save is still a failure`() {
        // The other side of it: the process going away *during* the save means the box may never
        // have been written out, and the user should be told rather than left to find out.
        assertTrue(ComputerLoss.after(RuntimeState.Suspending) is RuntimeState.Failed)
        assertTrue(ComputerLoss.shouldWatch(RuntimeState.Suspending))
    }

    @Test
    fun `a failure already reported is not reported twice`() {
        assertNull(ComputerLoss.after(RuntimeState.Failed(RuntimeFailure("already said so"))))
    }

    @Test
    fun `a death midway through stopping simply completes the stop`() {
        assertEquals(RuntimeState.Stopped, ComputerLoss.after(RuntimeState.Stopping))
    }

    @Test
    fun `a death during provisioning is reported`() {
        assertTrue(ComputerLoss.after(RuntimeState.Provisioning(0.4f)) is RuntimeState.Failed)
    }

    @Test
    fun `box watches the computer for the whole time it should be alive`() {
        // Watching only `Ready` is precisely what left the startup crash unobserved.
        listOf(
            RuntimeState.Starting,
            RuntimeState.Connecting,
            RuntimeState.Ready,
            RuntimeState.Stopping,
            RuntimeState.Provisioning(0f),
        ).forEach { assertTrue("$it must be watched", ComputerLoss.shouldWatch(it)) }
    }

    @Test
    fun `box does not hold a connection to a computer that is not meant to be up`() {
        listOf(
            RuntimeState.Stopped,
            RuntimeState.NotProvisioned,
            RuntimeState.Failed(RuntimeFailure("done")),
        ).forEach { assertFalse("$it must not be watched", ComputerLoss.shouldWatch(it)) }
    }

    @Test
    fun `every state Box can be told about has an answer`() {
        // `after` and `shouldWatch` both branch exhaustively; this is here so that adding a state
        // to RuntimeState fails loudly rather than silently defaulting to "nothing is wrong".
        listOf(
            RuntimeState.NotProvisioned,
            RuntimeState.Provisioning(0.5f),
            RuntimeState.Stopped,
            RuntimeState.Starting,
            RuntimeState.Connecting,
            RuntimeState.Ready,
            RuntimeState.Stopping,
            RuntimeState.Suspending,
            RuntimeState.Suspended,
            RuntimeState.Failed(RuntimeFailure("x")),
        ).forEach { ComputerLoss.after(it); ComputerLoss.shouldWatch(it) }
    }
}
