package dev.localagent.runtime.qemu

import android.net.LocalSocket
import android.net.LocalSocketAddress
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File

/**
 * Minimal bounded QMP client. QMP stays on an app-private Unix socket.
 *
 * It used to be a single question asked once — "is the guest running" — and closed. Suspending
 * needs a conversation instead: pause the CPUs, write the memory out, confirm it landed, quit.
 * Those have to be the same connection, because QEMU applies them in the order it receives them
 * and a second connection is a second ordering.
 */
internal class QmpClient(private val socketFile: File) {
    data class Status(val running: Boolean, val status: String)

    /** The one-shot form, unchanged for callers that only want to know whether the guest runs. */
    fun queryStatus(): Status = open().use { it.status() }

    /**
     * A negotiated QMP session. Always close it: QEMU holds the monitor open otherwise, and the
     * next connection would be talking to a second monitor while the first still owns the guest.
     */
    fun open(): Session {
        val socket = LocalSocket()
        try {
            // LocalSocketImpl does not implement the timeout overload of connect(); it throws
            // UnsupportedOperationException. A Unix socket connect resolves immediately anyway,
            // and the caller supplies the overall deadline.
            socket.connect(
                LocalSocketAddress(socketFile.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM),
            )
            socket.setSoTimeout(READ_TIMEOUT_MILLIS)
            return Session(socket)
        } catch (error: Throwable) {
            runCatching { socket.close() }
            throw error
        }
    }

    internal class Session(private val socket: LocalSocket) : Closeable {
        private val reader: BufferedReader = socket.inputStream.bufferedReader(Charsets.UTF_8)
        private val writer: BufferedWriter = socket.outputStream.bufferedWriter(Charsets.UTF_8)
        private var nextId = 0

        init {
            val greeting = JSONObject(reader.readLine() ?: error("QMP greeting was not received"))
            check(greeting.has("QMP")) { "Invalid QMP greeting" }
            command("qmp_capabilities")
        }

        fun status(): Status = command("query-status").getJSONObject("return").let { result ->
            Status(
                running = result.getBoolean("running"),
                status = result.optString("status", "unknown"),
            )
        }

        /**
         * Runs a QMP command and returns the whole response object.
         *
         * [timeoutMillis] is per command rather than per session because the range is enormous:
         * `query-status` answers instantly, and `savevm` holds QEMU's main loop for as long as it
         * takes to write the guest's memory to flash — during which nothing at all arrives here.
         */
        fun command(
            name: String,
            arguments: JSONObject? = null,
            timeoutMillis: Int = READ_TIMEOUT_MILLIS,
        ): JSONObject {
            val id = "box-${nextId++}"
            val request = JSONObject().put("execute", name).put("id", id)
            arguments?.let { request.put("arguments", it) }
            socket.setSoTimeout(timeoutMillis)
            writer.write(request.toString())
            writer.newLine()
            writer.flush()
            return awaitResponse(id)
        }

        /**
         * Runs a human-monitor command, which is how QEMU 5.1 is asked for a snapshot at all:
         * `savevm` and `loadvm` have no QMP form in this version, only `snapshot-save`, which
         * arrived in 6.0. The build in this APK is 5.1.
         *
         * The human monitor reports failure by *printing* it and still returning successfully, so
         * output is the result: anything at all means the command did not do what was asked.
         */
        fun monitor(commandLine: String, timeoutMillis: Int = READ_TIMEOUT_MILLIS): String =
            command(
                "human-monitor-command",
                JSONObject().put("command-line", commandLine),
                timeoutMillis,
            ).optString("return").trim()

        /** Ends the guest. QEMU may exit before its reply is read, which is as good an answer. */
        fun quit() {
            runCatching { command("quit") }
        }

        override fun close() {
            runCatching { socket.close() }
        }

        private fun awaitResponse(id: String): JSONObject {
            repeat(MAX_MESSAGES_PER_COMMAND) {
                val response = JSONObject(reader.readLine() ?: error("QMP closed before response $id"))
                if (response.optString("id") != id) return@repeat // asynchronous QMP event
                response.optJSONObject("error")?.let { error ->
                    error("QMP ${error.optString("class", "error")}: ${error.optString("desc", "command failed")}")
                }
                check(response.has("return")) { "QMP response $id had no return value" }
                return response
            }
            error("QMP did not return response $id")
        }
    }

    private companion object {
        const val READ_TIMEOUT_MILLIS = 2_000
        const val MAX_MESSAGES_PER_COMMAND = 64
    }
}
