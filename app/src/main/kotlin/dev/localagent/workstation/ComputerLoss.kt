package dev.localagent.workstation

import dev.localagent.runtime.api.RuntimeFailure
import dev.localagent.runtime.api.RuntimeState

/**
 * What to believe when the computer process disappears.
 *
 * `:computer` is a separate process precisely so a native VM failure cannot take the UI down with
 * it — but that only helps if the UI notices. A QEMU abort kills the process between state
 * broadcasts, so the last thing Box heard is "Connecting", and without this it goes on saying
 * "Debian is up, waiting for its private control channel" forever against a process that no longer
 * exists. That was observed on hardware: five minutes of a confident progress bar and no way
 * forward except a Stop button for something already dead.
 *
 * Not every disappearance is a failure. The computer process retires itself once its single QEMU
 * run is over, and that is announced as a state before the process ends — so a disconnect that
 * follows a settled state is the system working, and saying "the computer stopped unexpectedly"
 * there would be a lie that teaches users to ignore the message.
 */
internal object ComputerLoss {

    /**
     * The state to adopt when the connection to `:computer` drops while [last] was the last known
     * state, or null when the disconnect was expected and nothing should change.
     */
    fun after(last: RuntimeState): RuntimeState? = when (last) {
        // Nothing was running, or the end was already reported. The process going away next is
        // ordinary housekeeping.
        //
        // Suspended is here for a sharper reason than the others. Saving a box *is* the process
        // ending: QEMU writes the guest out and exits, and `:computer` retires behind it, so this
        // disconnect is not the aftermath of the operation — it is the operation. This branch used
        // to answer "the computer stopped unexpectedly", which was harmless only for as long as
        // nothing could reach the state. Now the idle timer reaches it every time it fires, and
        // the message would land on a box sitting saved and unharmed.
        RuntimeState.Stopped,
        RuntimeState.Suspended,
        RuntimeState.NotProvisioned,
        is RuntimeState.Failed,
        -> null

        // Box was told the computer was working. Its process is gone, so it is not.
        RuntimeState.Starting,
        RuntimeState.Connecting,
        -> RuntimeState.Failed(
            RuntimeFailure("The computer stopped while it was starting up.", recoverable = true),
        )

        // Suspending stays a failure: the process dying *during* a save means the box may not
        // have been saved, which is worth saying out loud.
        RuntimeState.Ready,
        RuntimeState.Suspending,
        -> RuntimeState.Failed(
            RuntimeFailure("The computer stopped unexpectedly.", recoverable = true),
        )

        // Already on the way down; the process ending is how that finishes.
        RuntimeState.Stopping -> RuntimeState.Stopped

        is RuntimeState.Provisioning -> RuntimeState.Failed(
            RuntimeFailure("Setting up the computer did not finish.", recoverable = true),
        )
    }

    /**
     * Whether Box should hold a connection to `:computer` in this state.
     *
     * Binding only once the computer is [RuntimeState.Ready] is what left the startup path
     * unwatched. Box holds the connection across the whole time the computer is meant to be alive,
     * so a death during boot is heard immediately rather than waited out.
     *
     * This is about *watching*, and it is not the only reason Box binds `:computer`. The agent
     * backend holds a connection of its own to read a closed box's session logs — the process, not
     * the VM. Answering false here never means "nobody may bind"; it means there is no VM whose
     * death this connection would be reporting.
     */
    fun shouldWatch(state: RuntimeState): Boolean = when (state) {
        RuntimeState.Starting, RuntimeState.Connecting, RuntimeState.Ready,
        RuntimeState.Stopping, RuntimeState.Suspending,
        -> true

        // A saved box has no VM left for this connection to report the death of, exactly as a
        // closed one does not.
        RuntimeState.Stopped, RuntimeState.Suspended,
        RuntimeState.NotProvisioned, is RuntimeState.Failed,
        -> false
        is RuntimeState.Provisioning -> true
    }
}
