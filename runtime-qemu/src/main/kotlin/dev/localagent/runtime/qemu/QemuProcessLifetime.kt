package dev.localagent.runtime.qemu

import dev.localagent.runtime.api.RuntimeState

/**
 * One QEMU run per process, and the rules that follow from it.
 *
 * QEMU's `qemu_init` writes process-wide globals that `qemu_cleanup` never undoes; the first of
 * them aborts the process outright if it is called twice. So `:computer` cannot host a second VM
 * run, and "stop the computer, start it again" — an ordinary thing to do — is a native crash
 * rather than a restart.
 *
 * The way out is to treat the process as the unit that gets consumed: once QEMU has exited,
 * `:computer` retires, and the next start arrives in a process that has never touched QEMU.
 * `RuntimeService` is not sticky and is started on demand, so there is nothing to resume — the
 * next `startService` simply gets a clean one.
 *
 * This is deliberately a plain object over two predicates rather than a call into [NativeQemu], so
 * the policy can be tested without a JNI library.
 */
internal class QemuProcessLifetime(
    private val hasRun: () -> Boolean,
    private val isRunning: () -> Boolean,
) {
    constructor() : this(NativeQemu::hasRun, NativeQemu::isRunning)

    /**
     * Whether a start attempted now could reach `qemu_init` safely.
     *
     * A VM that is still running is not a second start — the caller is expected to treat that as
     * "already up" — so only a *finished* run closes the door.
     */
    fun canStart(): Boolean = !hasRun() || isRunning()

    /**
     * Whether this process has done its one job and should now make way.
     *
     * Only true once QEMU has actually run and has since exited. A process that failed before
     * reaching `qemu_init` still has its run left and must stay: retiring it would turn a
     * recoverable error, like a missing image, into a pointless process churn.
     */
    fun isSpent(state: RuntimeState): Boolean =
        hasRun() && !isRunning() && state.isSettled

    private companion object {
        /**
         * States that mean nothing further is happening. `Failed` counts: the VM is not coming
         * back on its own, and the user's next move is a fresh start.
         */
        val RuntimeState.isSettled: Boolean
            get() = when (this) {
                RuntimeState.Stopped, is RuntimeState.Failed -> true
                RuntimeState.NotProvisioned, RuntimeState.Ready, RuntimeState.Starting,
                RuntimeState.Connecting, RuntimeState.Stopping, RuntimeState.Suspending,
                RuntimeState.Suspended,
                -> false

                is RuntimeState.Provisioning -> false
            }
    }
}
