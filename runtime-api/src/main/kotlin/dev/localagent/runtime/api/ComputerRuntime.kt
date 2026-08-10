package dev.localagent.runtime.api

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow

/** Stable boundary between product code and a local-computer backend. */
interface ComputerRuntime {
    suspend fun provision(): Result<Unit>
    suspend fun start(): Result<Unit>
    suspend fun stop(graceful: Boolean = true): Result<Unit>
    fun state(): StateFlow<RuntimeState>

    suspend fun exec(request: ExecRequest): ExecResult

    /**
     * The same command as [exec], delivered as it happens. Collecting the flow runs the command;
     * cancelling the collector cancels it in the guest, and a slow collector throttles the guest
     * process rather than buffering without limit.
     */
    fun execStream(request: ExecRequest): Flow<ExecEvent>

    /**
     * A long-lived guest process the host reads from *and writes to while it runs*.
     *
     * [execStream] is one-way once started: it streams output, but the command's stdin is closed,
     * so nothing can answer it. An agent harness is the opposite shape — it runs for minutes and
     * has to be told, mid-run, whether it may edit a file. That answer is the whole point of the
     * permission sheet, so it needs a channel that stays open in both directions.
     */
    suspend fun openSession(request: SessionRequest): GuestSession
    suspend fun createPty(request: PtyRequest): PtySession
    suspend fun readFile(path: String): ByteArray
    suspend fun writeFile(path: String, data: ByteArray)
    suspend fun listFiles(path: String): List<FileEntry>

    suspend fun desktopStart(): DesktopSession
    suspend fun desktopStop()

    suspend fun snapshot(): SnapshotId
    suspend fun restore(snapshot: SnapshotId)
    suspend fun forwardPort(request: PortForwardRequest): PortForward

    suspend fun suspendRuntime()
    suspend fun resumeRuntime()
}

data class ExecRequest(
    val command: List<String>,
    val workingDirectory: String = "/workspace",
    /** Guest-enforced wall-clock limit. The runtime adds a small transport grace period. */
    val timeoutSeconds: Int = 120,
    val environment: Map<String, String> = emptyMap(),
)

data class ExecResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String = "",
)

/** Incremental output from a running command. [Exited] is always last and always arrives. */
sealed interface ExecEvent {
    class Stdout(val bytes: ByteArray) : ExecEvent
    class Stderr(val bytes: ByteArray) : ExecEvent
    data class Exited(val exitCode: Int) : ExecEvent
}

data class SessionRequest(
    val command: List<String>,
    val workingDirectory: String = "/workspace",
    val environment: Map<String, String> = emptyMap(),
    /**
     * Guest-enforced wall-clock limit, or `0` for none.
     *
     * Unbounded is the default here and nowhere else. An agent working through a real task has no
     * honest upper bound, and a deadline that kills it mid-edit is worse than one that never
     * fires: the session is still bounded by [GuestSession.cancel], by the user interrupting, and
     * by the guest killing every child when the host disconnects.
     */
    val timeoutSeconds: Int = 0,
)

/**
 * A running guest process with both pipes open.
 *
 * [output] carries stdout and stderr as they arrive and ends with [ExecEvent.Exited]; [write]
 * reaches the process's stdin while it runs. Terminal state lives on the session, so [awaitExit]
 * answers whether or not anyone is collecting [output].
 */
interface GuestSession {
    val output: Flow<ExecEvent>
    suspend fun write(data: ByteArray)
    /** Half-close: the process sees EOF on stdin and can still finish writing its own output. */
    suspend fun closeInput()
    suspend fun awaitExit(): Int
    /** Stop the process now. Safe to call twice, and safe to call on an already-dead session. */
    suspend fun cancel()
}

data class PtyRequest(
    val command: List<String> = listOf("/bin/bash", "-l"),
    val workingDirectory: String = "/workspace",
    val columns: Int = 80,
    val rows: Int = 24,
    val environment: Map<String, String> = emptyMap(),
)

/** An interactive terminal in the guest. Output is merged, exactly as a terminal merges it. */
interface PtySession {
    val output: Flow<ByteArray>
    suspend fun write(data: ByteArray)
    suspend fun resize(columns: Int, rows: Int)
    /** Completes when the child exits, whether or not [output] is being collected. */
    suspend fun awaitExit(): Int
    suspend fun close()
}

data class FileEntry(val path: String, val name: String, val isDirectory: Boolean, val size: Long)
data class DesktopSession(val id: String, val width: Int, val height: Int)
@JvmInline value class SnapshotId(val value: String)
data class PortForwardRequest(val guestPort: Int, val purpose: String)
interface PortForward { val localPort: Int; suspend fun close() }

sealed interface RuntimeState {
    data object NotProvisioned : RuntimeState
    data class Provisioning(val progress: Float) : RuntimeState
    data object Stopped : RuntimeState
    data object Starting : RuntimeState
    data object Connecting : RuntimeState
    data object Ready : RuntimeState
    data object Stopping : RuntimeState
    data object Suspending : RuntimeState
    data object Suspended : RuntimeState
    data class Failed(val reason: RuntimeFailure) : RuntimeState
}

data class RuntimeFailure(val message: String, val recoverable: Boolean = true)
