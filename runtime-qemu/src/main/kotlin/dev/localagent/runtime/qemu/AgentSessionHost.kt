package dev.localagent.runtime.qemu

import android.util.Log
import dev.localagent.runtime.api.ComputerRuntime
import dev.localagent.runtime.api.ExecEvent
import dev.localagent.runtime.api.GuestSession
import dev.localagent.runtime.api.SessionRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

/**
 * One agent session, owned by `:computer` rather than by whoever is watching it.
 *
 * The ownership is the point. Android kills the UI process whenever it likes, and the product
 * promise is that you can start work, pocket the phone, and come back to it. So the session's
 * output is appended to a log here, on the VM's side of the process boundary, and only then handed
 * to an attached listener. If nobody is listening the agent keeps working and the log keeps
 * growing; when a new UI process attaches it reads the log for what it missed and picks up live.
 */
internal class AgentSessionHost(
    private val sessionId: String,
    /** Null for a session that must leave no trace — see `openEphemeralSession`. */
    private val logFile: File?,
    private val scope: CoroutineScope,
    /**
     * Told when the session reaches something worth interrupting the user for. Never called for an
     * ephemeral session: sign-in output is exactly the stream that must not be inspected.
     */
    private val onSignal: (String, SessionSignals.Signal) -> Unit = { _, _ -> },
) {
    private data class Ending(val exitCode: Int, val error: String?)

    private val lock = Any()
    private var session: GuestSession? = null
    private var listener: IAgentSessionCallback? = null
    private var pump: Job? = null

    /**
     * Bytes appended to the log so far. Doubles as the offset of the next chunk.
     *
     * Starts at whatever the log already holds, not at zero. A session re-opened after the UI
     * process died appends to the same file, and a fresh counter would stamp new chunks with
     * offsets a reader has already passed — which is exactly the signal the reader uses to drop
     * things it has seen. Every line of the resumed conversation would be discarded on arrival.
     */
    private var written: Long = logFile?.takeIf { it.isFile }?.length() ?: 0L
    private var ending: Ending? = null

    val isRunning: Boolean get() = synchronized(lock) { ending == null && session != null }

    private val handle = object : IAgentSession.Stub() {
        override fun write(data: ByteArray) {
            val live = synchronized(lock) { session } ?: run {
                // The one trace these bytes will ever leave. `session` is assigned only after the
                // suspending `openSession` returns, so this window is real, and what travels
                // through here is a user's turn or a permission decision.
                //
                // The caller cannot be told: the AIDL write is `oneway`, so it returned long
                // before this ran, and the `Log.e` below has the same problem. Box happens to be
                // careful on the other side — `Record.outbox` exists for exactly this — but a host
                // cannot rely on its caller, and when that assumption breaks there has to be
                // something in the log to show that it did. The byte count, not the bytes.
                Log.w(TAG, "Session $sessionId dropped a ${data.size}-byte write: no live session")
                return
            }
            scope.launch {
                runCatching { live.write(data) }
                    .onFailure { Log.e(TAG, "Could not reach session $sessionId", it) }
            }
        }

        override fun closeInput() {
            val live = synchronized(lock) { session } ?: return
            scope.launch { runCatching { live.closeInput() } }
        }

        override fun cancel() {
            val live = synchronized(lock) { session } ?: return
            scope.launch { runCatching { live.cancel() } }
        }
    }

    fun start(
        runtime: ComputerRuntime,
        command: List<String>,
        workingDirectory: String,
        environment: Map<String, String>,
        callback: IAgentSessionCallback,
    ) {
        synchronized(lock) { listener = callback }
        pump = scope.launch {
            val opened = try {
                runtime.openSession(SessionRequest(command, workingDirectory, environment))
            } catch (error: Exception) {
                Log.e(TAG, "Could not start session $sessionId", error)
                finish(-1, error.readableMessage("The agent could not be started"))
                return@launch
            }
            synchronized(lock) { session = opened }
            // Announced only once the session exists, so a decision written straight back has
            // something to reach. An empty path says there is no log to replay.
            deliver { it.onAttached(handle, logFile?.absolutePath.orEmpty()) }
            consume(opened)
        }
    }

    private suspend fun consume(opened: GuestSession) {
        val sink = logFile?.let { FileOutputStream(it, true) }
        try {
            opened.output.collect { event ->
                when (event) {
                    is ExecEvent.Stdout -> {
                        // Append-then-notify, in that order: a listener attaching between the two
                        // sees the chunk in the log instead of missing it.
                        val offset = synchronized(lock) {
                            sink?.write(event.bytes)
                            sink?.flush()
                            written.also { written += event.bytes.size }
                        }
                        deliver { it.onData(offset, event.bytes) }
                        if (sink != null) readSignals(event.bytes)
                    }
                    is ExecEvent.Stderr -> {
                        val text = event.bytes.toString(Charsets.UTF_8)
                        // An ephemeral session's stderr can carry credential prompts, so it is
                        // passed on but never written to the log.
                        if (sink != null) Log.w(TAG, "Session $sessionId: $text")
                        deliver { it.onDiagnostic(text) }
                    }
                    is ExecEvent.Exited -> finish(event.exitCode, null)
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "Session $sessionId ended badly", error)
            finish(-1, error.readableMessage("The agent stopped unexpectedly"))
        } finally {
            runCatching { sink?.close() }
        }
    }

    /** Partial trailing line from the last chunk. A chunk boundary is not a line boundary. */
    private val unread = StringBuilder()

    /**
     * Watches the same bytes that went to the log for the two events worth a notification.
     *
     * Reading the stream here rather than asking the UI to report back is what makes the promise
     * work: the Compose process may be long dead by the time the agent finishes, and this one is
     * still alive because it is the one running the VM.
     */
    private fun readSignals(bytes: ByteArray) {
        unread.append(bytes.toString(Charsets.UTF_8))
        while (true) {
            val newline = unread.indexOf("\n")
            if (newline < 0) break
            val line = unread.substring(0, newline)
            unread.delete(0, newline + 1)
            val signal = runCatching { SessionSignals.read(line) }.getOrNull() ?: continue
            runCatching { onSignal(sessionId, signal) }
                .onFailure { Log.w(TAG, "Could not raise a session signal", it) }
        }
        // A harness that never emits a newline must not grow this without bound.
        if (unread.length > MAX_PARTIAL_LINE) unread.setLength(0)
    }

    /**
     * Point a new UI process at this session. [IAgentSessionCallback.onAttached] always comes
     * first, so the caller can read the log before deciding what it already knows.
     */
    fun attach(callback: IAgentSessionCallback) {
        val ended = synchronized(lock) {
            listener = callback
            ending
        }
        runCatching {
            callback.onAttached(handle.takeIf { ended == null }, logFile?.absolutePath.orEmpty())
        }
        if (ended != null) runCatching { callback.onClosed(ended.exitCode, ended.error) }
    }

    fun detach(callback: IAgentSessionCallback) {
        synchronized(lock) { if (listener?.asBinder() == callback.asBinder()) listener = null }
    }

    fun cancel() {
        val live = synchronized(lock) { session } ?: return
        scope.launch { runCatching { live.cancel() } }
        pump?.cancel()
    }

    private fun finish(exitCode: Int, error: String?) {
        synchronized(lock) {
            if (ending != null) return
            ending = Ending(exitCode, error)
            session = null
        }
        deliver { it.onClosed(exitCode, error) }
    }

    /** A dead UI process must never take `:computer` with it, so every delivery can fail. */
    private fun deliver(block: (IAgentSessionCallback) -> Unit) {
        val target = synchronized(lock) { listener } ?: return
        runCatching { block(target) }.onFailure {
            Log.w(TAG, "Dropping a listener that went away", it)
            synchronized(lock) { if (listener?.asBinder() == target.asBinder()) listener = null }
        }
    }

    private companion object {
        const val TAG = "BoxAgentSession"

        /** Generous next to a real event, small next to the damage an unbounded buffer does. */
        const val MAX_PARTIAL_LINE = 256 * 1024
    }
}
