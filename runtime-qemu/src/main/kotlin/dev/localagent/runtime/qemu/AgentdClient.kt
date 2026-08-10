package dev.localagent.runtime.qemu

import android.net.LocalSocket
import android.net.LocalSocketAddress
import dev.localagent.runtime.api.ExecEvent
import dev.localagent.runtime.api.ExecRequest
import dev.localagent.runtime.api.GuestSession
import dev.localagent.runtime.api.PtyRequest
import dev.localagent.runtime.api.PtySession
import dev.localagent.runtime.api.SessionRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * Multiplexed, streaming client for the private guest virtio channel (`protocol/agentd-v2.md`).
 *
 * Owns protocol vocabulary and JSON; the framing and flow control below it are in
 * [AgentdConnection]. One connection carries every concurrent call, command and PTY, so a file
 * listing no longer waits behind a build.
 */
internal class AgentdClient private constructor(
    private val openTransport: suspend () -> AgentdConnection,
) {
    constructor(socketFile: File) : this({ connectLocalSocket(socketFile) })

    private val connectionMutex = Mutex()
    private var connection: AgentdConnection? = null

    /** Round-trips `health`, which is also how a stale guest agent is detected at startup. */
    suspend fun health(timeoutMillis: Long = HEALTH_TIMEOUT_MILLIS): JSONObject =
        JSONObject(callJson("health", JSONObject(), timeoutMillis))

    suspend fun callJson(
        method: String,
        params: JSONObject = JSONObject(),
        timeoutMillis: Long = DEFAULT_CALL_TIMEOUT_MILLIS,
        maxResultBytes: Int = DEFAULT_MAX_RESULT_BYTES,
    ): String = call(method, params, timeoutMillis, maxResultBytes).toString(Charsets.UTF_8)

    /** The result body: UTF-8 JSON for most methods, raw file bytes for `read_file`. */
    suspend fun call(
        method: String,
        params: JSONObject = JSONObject(),
        timeoutMillis: Long = DEFAULT_CALL_TIMEOUT_MILLIS,
        maxResultBytes: Int = DEFAULT_MAX_RESULT_BYTES,
        body: ByteArray? = null,
    ): ByteArray {
        require(timeoutMillis in 1..MAX_CALL_TIMEOUT_MILLIS) { "Invalid agentd call timeout" }
        val open = JSONObject()
            .put("kind", "call")
            .put("method", method)
            .put("params", params)
        val stream = connection().openStream(open.toString().toByteArray(Charsets.UTF_8))
        return try {
            withTimeout(timeoutMillis) {
                if (body != null) {
                    stream.send(AgentdProtocol.CHANNEL_STDIN, body)
                    stream.endChannel(AgentdProtocol.CHANNEL_STDIN)
                }
                collectResult(stream, maxResultBytes)
            }
        } finally {
            withContext(NonCancellable) { stream.cancel(EMPTY_JSON) }
        }
    }

    fun exec(request: ExecRequest): Flow<ExecEvent> = flow {
        val open = JSONObject()
            .put("kind", "exec")
            .put("command", JSONArray().apply { request.command.forEach(::put) })
            .put("cwd", request.workingDirectory)
            .put("timeoutSeconds", request.timeoutSeconds)
            .put("env", jsonOf(request.environment))
        val stream = connection().openStream(open.toString().toByteArray(Charsets.UTF_8))
        try {
            for (event in stream.incoming) {
                if (event !is AgentdEvent.Data) continue
                when (event.channel) {
                    AgentdProtocol.CHANNEL_STDOUT -> emit(ExecEvent.Stdout(event.bytes))
                    AgentdProtocol.CHANNEL_STDERR -> emit(ExecEvent.Stderr(event.bytes))
                    else -> Unit
                }
                // Credit is returned only now, after the collector took the bytes: that is what
                // makes a chatty command throttle itself instead of filling the app's heap.
                stream.acknowledge(event.bytes.size)
            }
            val close = requireSuccess(stream.closePayload.await())
            emit(ExecEvent.Exited(close.optInt("exitCode", -1)))
        } finally {
            withContext(NonCancellable) { stream.cancel(EMPTY_JSON) }
        }
    }

    /**
     * The same `exec` stream as [exec], opened with `stdin` so the host can answer it mid-run.
     *
     * The flow returned by [exec] is deliberately not reused: it owns its stream and closes it
     * when the collector leaves, which is exactly wrong for a session the UI process may stop
     * collecting — and later re-attach to — without the agent noticing.
     */
    suspend fun openSession(request: SessionRequest): GuestSession {
        val open = JSONObject()
            .put("kind", "exec")
            .put("command", JSONArray().apply { request.command.forEach(::put) })
            .put("cwd", request.workingDirectory)
            .put("timeoutSeconds", request.timeoutSeconds)
            .put("stdin", true)
            .put("env", jsonOf(request.environment))
        return AgentdGuestSession(
            connection().openStream(open.toString().toByteArray(Charsets.UTF_8)),
        )
    }

    suspend fun openPty(request: PtyRequest): PtySession {
        val open = JSONObject()
            .put("kind", "pty")
            .put("command", JSONArray().apply { request.command.forEach(::put) })
            .put("cwd", request.workingDirectory)
            .put("columns", request.columns)
            .put("rows", request.rows)
            .put("env", jsonOf(request.environment))
        return AgentdPtySession(connection().openStream(open.toString().toByteArray(Charsets.UTF_8)))
    }

    suspend fun close() = connectionMutex.withLock {
        connection?.close()
        connection = null
    }

    private suspend fun collectResult(stream: AgentdStream, maxResultBytes: Int): ByteArray {
        val body = ByteArrayOutputStream()
        for (event in stream.incoming) {
            if (event !is AgentdEvent.Data || event.channel != AgentdProtocol.CHANNEL_STDOUT) continue
            check(body.size() + event.bytes.size <= maxResultBytes) {
                "agentd result exceeds the transport limit"
            }
            body.write(event.bytes)
            stream.acknowledge(event.bytes.size)
        }
        requireSuccess(stream.closePayload.await())
        return body.toByteArray()
    }

    /** A failed operation is data, never a silently successful result. */
    private fun requireSuccess(payload: ByteArray): JSONObject {
        val close = JSONObject(payload.toString(Charsets.UTF_8))
        return when (close.optString("status")) {
            "ok" -> close
            "cancelled" -> error("agentd cancelled the operation")
            else -> {
                val failure = close.optJSONObject("error")
                error(
                    "agentd ${failure?.optString("code")?.ifBlank { null } ?: "error"}: " +
                        (failure?.optString("message")?.ifBlank { null } ?: "request failed"),
                )
            }
        }
    }

    private fun jsonOf(values: Map<String, String>): JSONObject =
        JSONObject().apply { values.forEach { (key, value) -> put(key, value) } }

    private suspend fun connection(): AgentdConnection = connectionMutex.withLock {
        connection?.takeIf { it.isActive }?.let { return@withLock it }
        connection = null
        withContext(Dispatchers.IO) { openTransport() }.also { connection = it }
    }

    companion object {
        /** Test seam: a client over an already-handshaken connection, with no LocalSocket. */
        internal fun over(connection: AgentdConnection) = AgentdClient({ connection })

        private suspend fun connectLocalSocket(socketFile: File): AgentdConnection {
            val socket = LocalSocket()
            var connected: AgentdConnection? = null
            try {
                // LocalSocketImpl throws UnsupportedOperationException for the timeout overload of
                // connect(); the caller's retry loop owns the deadline instead.
                socket.connect(
                    LocalSocketAddress(
                        socketFile.absolutePath,
                        LocalSocketAddress.Namespace.FILESYSTEM,
                    ),
                )
                // Polling lets coroutine cancellation and the overall operation deadline win.
                socket.setSoTimeout(READ_POLL_MILLIS)
                val active = AgentdConnection(
                    BufferedInputStream(socket.inputStream),
                    BufferedOutputStream(socket.outputStream),
                ) { runCatching { socket.close() } }
                connected = active
                active.start(clientHello())
                val peer = JSONObject(
                    active.awaitPeerHello(HANDSHAKE_TIMEOUT_MILLIS).toString(Charsets.UTF_8),
                )
                check(peer.optInt("version", -1) == PROTOCOL_VERSION) {
                    "The guest agent speaks protocol ${peer.optInt("version", -1)}, " +
                        "not $PROTOCOL_VERSION"
                }
                active.applyPeerLimits(
                    peer.optInt("initialWindowBytes", AgentdProtocol.INITIAL_WINDOW_BYTES),
                    peer.optInt("maxConcurrentStreams", AgentdProtocol.MAX_CONCURRENT_STREAMS),
                    peer.optInt("maxFramePayloadBytes", AgentdProtocol.MAX_FRAME_PAYLOAD),
                )
                return active
            } catch (error: Throwable) {
                connected?.close()
                runCatching { socket.close() }
                throw if (error is IOException || error is IllegalStateException) {
                    error
                } else {
                    IOException("Could not reach the guest agent", error)
                }
            }
        }

        internal fun clientHello(): ByteArray = JSONObject()
            .put("version", PROTOCOL_VERSION)
            .put("client", "box-android")
            .put("maxFramePayloadBytes", AgentdProtocol.MAX_FRAME_PAYLOAD)
            .put("initialWindowBytes", AgentdProtocol.INITIAL_WINDOW_BYTES)
            .put("maxConcurrentStreams", AgentdProtocol.MAX_CONCURRENT_STREAMS)
            .toString()
            .toByteArray(Charsets.UTF_8)

        const val PROTOCOL_VERSION = AgentdProtocol.VERSION
        const val DEFAULT_CALL_TIMEOUT_MILLIS = 15_000L
        const val FILE_CALL_TIMEOUT_MILLIS = 60_000L
        const val HEALTH_TIMEOUT_MILLIS = 2_500L
        const val DEFAULT_MAX_RESULT_BYTES = 1024 * 1024
        const val FILE_MAX_RESULT_BYTES = 32 * 1024 * 1024
        const val MAX_CALL_TIMEOUT_MILLIS = 905_000L
        private const val HANDSHAKE_TIMEOUT_MILLIS = 2_000L
        private const val READ_POLL_MILLIS = 1_000
        internal val EMPTY_JSON = "{}".toByteArray(Charsets.UTF_8)
    }
}

/**
 * A session is an `exec` stream that nobody closes early: output up, decisions down.
 *
 * The [AgentdPtySession] shape does not fit — a PTY merges stdout and stderr the way a terminal
 * does, and a harness that narrates structured events on stdout must not have its lines
 * interleaved with anything else.
 */
internal class AgentdGuestSession(private val stream: AgentdStream) : GuestSession {
    override val output: Flow<ExecEvent> = flow {
        for (event in stream.incoming) {
            if (event !is AgentdEvent.Data) continue
            when (event.channel) {
                AgentdProtocol.CHANNEL_STDOUT -> emit(ExecEvent.Stdout(event.bytes))
                AgentdProtocol.CHANNEL_STDERR -> emit(ExecEvent.Stderr(event.bytes))
                else -> Unit
            }
            // Credit returns only once the collector took the bytes, so a chatty harness throttles
            // itself against the app rather than filling its heap.
            stream.acknowledge(event.bytes.size)
        }
        emit(ExecEvent.Exited(awaitExit()))
    }

    override suspend fun write(data: ByteArray) = stream.send(AgentdProtocol.CHANNEL_STDIN, data)

    override suspend fun closeInput() = stream.endChannel(AgentdProtocol.CHANNEL_STDIN)

    override suspend fun awaitExit(): Int =
        JSONObject(stream.closePayload.await().toString(Charsets.UTF_8)).optInt("exitCode", -1)

    override suspend fun cancel() {
        withContext(NonCancellable) {
            stream.cancel(JSONObject().put("signal", "TERM").toString().toByteArray(Charsets.UTF_8))
            // The guest answers CLOSE even for a cancel; do not wait forever if it cannot.
            withTimeoutOrNull(CANCEL_TIMEOUT_MILLIS) { runCatching { stream.closePayload.await() } }
        }
    }

    private companion object {
        const val CANCEL_TIMEOUT_MILLIS = 5_000L
    }
}

/** A PTY is just another stream: keystrokes on stdin, terminal output on stdout, resize on CTRL. */
internal class AgentdPtySession(private val stream: AgentdStream) : PtySession {
    override val output: Flow<ByteArray> = flow {
        for (event in stream.incoming) {
            if (event !is AgentdEvent.Data) continue
            emit(event.bytes)
            stream.acknowledge(event.bytes.size)
        }
    }

    override suspend fun write(data: ByteArray) = stream.send(AgentdProtocol.CHANNEL_STDIN, data)

    override suspend fun resize(columns: Int, rows: Int) = stream.sendControl(
        JSONObject()
            .put("op", "resize")
            .put("columns", columns)
            .put("rows", rows)
            .toString()
            .toByteArray(Charsets.UTF_8),
    )

    /** Terminal state lives on the stream, so this works whether or not [output] is collected. */
    override suspend fun awaitExit(): Int =
        JSONObject(stream.closePayload.await().toString(Charsets.UTF_8)).optInt("exitCode", -1)

    override suspend fun close() {
        withContext(NonCancellable) {
            stream.cancel(JSONObject().put("signal", "TERM").toString().toByteArray(Charsets.UTF_8))
            // The guest answers CLOSE even for a cancel; do not wait forever if it cannot.
            withTimeoutOrNull(CLOSE_TIMEOUT_MILLIS) { runCatching { stream.closePayload.await() } }
        }
    }

    private companion object {
        const val CLOSE_TIMEOUT_MILLIS = 5_000L
    }
}
