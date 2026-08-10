package dev.localagent.runtime.qemu;

import dev.localagent.runtime.qemu.IAgentSession;

/**
 * What the UI process hears from a session it opened or re-attached to.
 *
 * Everything the harness writes is appended to a log file owned by `:computer` *before* it is
 * delivered here, and [onData] carries the offset at which its chunk begins in that log. That is
 * what makes re-attaching race-free: a UI that attaches, then reads the log to length L, can drop
 * every live chunk below L and keep the rest, with no window in which an event is lost or seen
 * twice. Android can kill the UI process mid-session; the agent never notices.
 */
oneway interface IAgentSessionCallback {
    /** The session is live and [logPath] holds everything it has said so far. */
    void onAttached(IAgentSession session, String logPath);

    /** Harness output, verbatim, starting at [offset] bytes into the log. */
    void onData(long offset, in byte[] chunk);

    /** Anything the harness wrote to stderr. Never part of the event log. */
    void onDiagnostic(String text);

    /** Terminal. [error] is null on a clean exit. */
    void onClosed(int exitCode, String error);
}
