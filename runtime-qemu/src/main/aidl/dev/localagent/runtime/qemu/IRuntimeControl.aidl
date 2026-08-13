package dev.localagent.runtime.qemu;

import dev.localagent.runtime.qemu.IAgentSessionCallback;
import dev.localagent.runtime.qemu.IExecCallback;
import dev.localagent.runtime.qemu.IFileListCallback;
import dev.localagent.runtime.qemu.IFileReadCallback;
import dev.localagent.runtime.qemu.IPortForwardCallback;
import dev.localagent.runtime.qemu.IWriteCallback;

/**
 * Guest operations exposed to the UI process. The VM itself stays inside `:computer`; only these
 * results cross the process boundary.
 */
interface IRuntimeControl {
    oneway void exec(in String[] command, String workingDirectory, int timeoutSeconds, IExecCallback callback);
    oneway void listFiles(String path, IFileListCallback callback);
    oneway void readFile(String path, IFileReadCallback callback);
    oneway void writeFile(String path, in byte[] data, IWriteCallback callback);

    /**
     * Open a loopback port on the phone that reaches [guestPort] inside the guest, and report the
     * URL a WebView can load. Asking twice for the same guest port returns the same forward.
     */
    oneway void forwardPort(int guestPort, IPortForwardCallback callback);

    /**
     * Close a forward opened by [forwardPort]. Deliberately fire-and-forget: this is cleanup, and
     * a failure to tidy up must never surface as a failure of whatever the user actually did.
     */
    oneway void releasePort(int guestPort);

    /**
     * Start a harness under [sessionId] and stream it back. The session belongs to `:computer`
     * from here on: it keeps running, and keeps logging, if the UI process dies.
     */
    oneway void openAgentSession(String sessionId, in String[] command, String workingDirectory, in Bundle environment, IAgentSessionCallback callback);

    /**
     * Re-attach to a session started earlier. If it is no longer running the callback is closed
     * immediately — its log still holds everything it did, so an agent that finished while the UI
     * was dead is read, not lost.
     */
    oneway void attachAgentSession(String sessionId, IAgentSessionCallback callback);

    /**
     * A session whose output is never written to disk.
     *
     * Separate from [openAgentSession] rather than a flag on it, because the difference is a
     * security property and a forgotten `false` would silently persist a credential. Sign-in runs
     * here: the exchange streams to the UI and is gone the moment it ends. Nothing to replay, and
     * nothing left behind for anything else to read.
     */
    oneway void openEphemeralSession(String sessionId, in String[] command, String workingDirectory, in Bundle environment, IAgentSessionCallback callback);

    /** Stop a session and forget it. An agent session's log stays on disk. */
    oneway void closeAgentSession(String sessionId);

    /**
     * Put the guest's screen at [width] x [height], so the desktop fills the window showing it
     * instead of being letterboxed into it.
     *
     * Fire-and-forget, and deliberately without a callback: the answer already arrives by another
     * route. Resizing the guest changes the shape of QEMU's console, and QEMU tells the VNC client
     * about that itself — so the UI learns the new size from the picture, which is the only source
     * that cannot disagree with what is on screen. A callback here would be a second, slower
     * account of the same fact.
     */
    oneway void setDisplaySize(int width, int height);
}
