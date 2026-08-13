package dev.localagent.runtime.qemu

import dev.localagent.runtime.api.RuntimeFailure
import dev.localagent.runtime.api.RuntimeState
import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * One QEMU run per process.
 *
 * This exists because the alternative is not a bad error message, it is a SIGABRT: `qemu_init`
 * asserts on process-wide state that `qemu_cleanup` leaves behind, so calling it twice takes the
 * whole computer process down. That was reached on hardware by the most ordinary route there is —
 * start the computer, have it fail, press start again.
 */
class QemuProcessLifetimeTest {

    private fun lifetime(hasRun: Boolean, isRunning: Boolean) =
        QemuProcessLifetime(hasRun = { hasRun }, isRunning = { isRunning })

    @Test
    fun `a fresh process may start qemu`() {
        assertTrue(lifetime(hasRun = false, isRunning = false).canStart())
    }

    @Test
    fun `a process that already ran qemu may never start it again`() {
        assertFalse(lifetime(hasRun = true, isRunning = false).canStart())
    }

    @Test
    fun `a running vm is not treated as a second start`() {
        // The caller handles "already up" itself; refusing here would break an idempotent start.
        assertTrue(lifetime(hasRun = true, isRunning = true).canStart())
    }

    @Test
    fun `a process is spent once its vm has stopped`() {
        assertTrue(lifetime(hasRun = true, isRunning = false).isSpent(RuntimeState.Stopped))
    }

    @Test
    fun `a failed start also spends the process`() {
        // QEMU reached init and then cleaned up, so the damage is done either way.
        val failed = RuntimeState.Failed(RuntimeFailure("Linux workspace failed to start"))
        assertTrue(lifetime(hasRun = true, isRunning = false).isSpent(failed))
    }

    @Test
    fun `a suspended box spends the process too`() {
        // Suspending is not pausing something that is still there. QEMU wrote the guest into its
        // own disk and exited, so this process has used its one run and must make way — the saved
        // box is reopened by the next process, which is the whole point of saving it.
        assertTrue(lifetime(hasRun = true, isRunning = false).isSpent(RuntimeState.Suspended))
    }

    @Test
    fun `saving a box does not retire the process mid-save`() {
        // Suspending still has a VM in it, and the snapshot is written by that VM.
        assertFalse(lifetime(hasRun = true, isRunning = true).isSpent(RuntimeState.Suspending))
    }

    @Test
    fun `a process that never reached qemu keeps its run`() {
        // Provisioning ends in Stopped without ever touching QEMU. Retiring here would throw away
        // a perfectly good process between "set up" and "start" — which is one gesture in the UI.
        assertFalse(lifetime(hasRun = false, isRunning = false).isSpent(RuntimeState.Stopped))
    }

    @Test
    fun `a live vm is never spent`() {
        val lifetime = lifetime(hasRun = true, isRunning = true)
        listOf(
            RuntimeState.Ready,
            RuntimeState.Starting,
            RuntimeState.Connecting,
            RuntimeState.Stopping,
        ).forEach { assertFalse("$it must not retire a live process", lifetime.isSpent(it)) }
    }

    @Test
    fun `provisioning progress never retires the process`() {
        val lifetime = lifetime(hasRun = false, isRunning = false)
        assertFalse(lifetime.isSpent(RuntimeState.Provisioning(0f)))
        assertFalse(lifetime.isSpent(RuntimeState.Provisioning(1f)))
    }
}
