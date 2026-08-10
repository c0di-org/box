package dev.localagent.runtime.qemu

import android.net.LocalSocket
import android.net.LocalSocketAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicLong

/** Serialized, bounded request/response client for the private guest virtio channel. */
internal class AgentdClient(private val socketFile: File) {
    private data class Connection(
        val socket: LocalSocket,
        val input: BufferedInputStream,
        val output: BufferedOutputStream,
    )

    private val callMutex = Mutex()
    private val connectionLock = Any()
    private val sequence = AtomicLong()
    private var connection: Connection? = null

    suspend fun call(
        method: String,
        params: JSONObject = JSONObject(),
        timeoutMillis: Long = DEFAULT_CALL_TIMEOUT_MILLIS,
        maxResponseBytes: Int = DEFAULT_MAX_RESPONSE_BYTES,
    ): JSONObject = callMutex.withLock {
        require(timeoutMillis in 1..MAX_CALL_TIMEOUT_MILLIS) { "Invalid agentd call timeout" }
        require(maxResponseBytes in 1..MAX_FRAME_BYTES) { "Invalid agentd response limit" }
        withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            val active = connectIfNeeded()
            try {
                val id = sequence.incrementAndGet().toString()
                val request = JSONObject()
                    .put("version", PROTOCOL_VERSION)
                    .put("id", id)
                    .put("method", method)
                    .put("params", params)
                    .toString()
                    .toByteArray(Charsets.UTF_8)
                check(request.size <= MAX_FRAME_BYTES) { "agentd request exceeds transport limit" }

                active.output.write(request)
                active.output.write('\n'.code)
                active.output.flush()

                val response = JSONObject(
                    readFrame(
                        connection = active,
                        timeoutMillis = timeoutMillis,
                        maxBytes = maxResponseBytes,
                        job = currentCoroutineContext()[Job],
                    ),
                )
                check(response.optInt("version", -1) == PROTOCOL_VERSION) {
                    "agentd response version mismatch"
                }
                check(response.optString("id") == id) { "agentd response id mismatch" }
                response.optJSONObject("error")?.let { remoteError ->
                    error("agentd ${remoteError.optString("code", "error")}: ${remoteError.optString("message", "request failed")}")
                }
                response.optJSONObject("result") ?: error("agentd response did not contain an object result")
            } catch (cancelled: CancellationException) {
                invalidate(active)
                throw cancelled
            } catch (error: Exception) {
                invalidate(active)
                throw error
            }
        }
    }

    /** Does not wait for an in-flight call; closing the socket wakes its blocking read. */
    suspend fun close() = withContext(Dispatchers.IO) { closeActiveConnection() }

    private fun connectIfNeeded(): Connection = synchronized(connectionLock) {
        connection?.takeIf { it.socket.isConnected } ?: LocalSocket().let { socket ->
            try {
                socket.connect(
                    LocalSocketAddress(socketFile.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM),
                    CONNECT_TIMEOUT_MILLIS,
                )
                // Polling lets coroutine cancellation and the overall operation deadline win.
                socket.setSoTimeout(READ_POLL_MILLIS)
                Connection(
                    socket,
                    BufferedInputStream(socket.inputStream),
                    BufferedOutputStream(socket.outputStream),
                ).also { connection = it }
            } catch (error: Exception) {
                runCatching { socket.close() }
                throw error
            }
        }
    }

    private fun readFrame(
        connection: Connection,
        timeoutMillis: Long,
        maxBytes: Int,
        job: Job?,
    ): String {
        val deadlineNanos = System.nanoTime() + timeoutMillis * NANOS_PER_MILLI
        val bytes = ByteArrayOutputStream(minOf(maxBytes, INITIAL_RESPONSE_CAPACITY))
        while (true) {
            job?.ensureActive()
            if (System.nanoTime() >= deadlineNanos) throw SocketTimeoutException("agentd response timed out")
            val value = try {
                connection.input.read()
            } catch (_: SocketTimeoutException) {
                continue
            }
            if (value < 0) error("agentd closed its control channel")
            if (value == '\n'.code) return bytes.toString(Charsets.UTF_8.name())
            check(bytes.size() < maxBytes) { "agentd response exceeds transport limit" }
            bytes.write(value)
        }
    }

    private fun invalidate(expected: Connection) {
        synchronized(connectionLock) {
            if (connection === expected) connection = null
        }
        runCatching { expected.socket.close() }
    }

    private fun closeActiveConnection() {
        val active = synchronized(connectionLock) {
            connection.also { connection = null }
        }
        active?.let { runCatching { it.socket.close() } }
    }

    companion object {
        const val PROTOCOL_VERSION = 1
        const val DEFAULT_CALL_TIMEOUT_MILLIS = 15_000L
        const val FILE_CALL_TIMEOUT_MILLIS = 30_000L
        const val DEFAULT_MAX_RESPONSE_BYTES = 1024 * 1024
        const val FILE_MAX_RESPONSE_BYTES = 12 * 1024 * 1024
        const val MAX_FRAME_BYTES = 12 * 1024 * 1024
        const val MAX_CALL_TIMEOUT_MILLIS = 905_000L
        private const val CONNECT_TIMEOUT_MILLIS = 2_000
        private const val READ_POLL_MILLIS = 1_000
        private const val INITIAL_RESPONSE_CAPACITY = 16 * 1024
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
