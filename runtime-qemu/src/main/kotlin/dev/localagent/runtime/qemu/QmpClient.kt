package dev.localagent.runtime.qemu

import android.net.LocalSocket
import android.net.LocalSocketAddress
import org.json.JSONObject
import java.io.File

/** Minimal bounded QMP client. QMP stays on an app-private Unix socket. */
internal class QmpClient(private val socketFile: File) {
    data class Status(val running: Boolean, val status: String)

    fun queryStatus(): Status {
        val socket = LocalSocket()
        try {
            // LocalSocketImpl does not implement the timeout overload of connect(); it throws
            // UnsupportedOperationException. A Unix socket connect resolves immediately anyway,
            // and awaitQmp() supplies the overall deadline.
            socket.connect(
                LocalSocketAddress(socketFile.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM),
            )
            socket.setSoTimeout(READ_TIMEOUT_MILLIS)
            val reader = socket.inputStream.bufferedReader(Charsets.UTF_8)
            val writer = socket.outputStream.bufferedWriter(Charsets.UTF_8)
            val greeting = JSONObject(reader.readLine() ?: error("QMP greeting was not received"))
            check(greeting.has("QMP")) { "Invalid QMP greeting" }

            execute(writer, "qmp_capabilities", CAPABILITIES_ID)
            awaitResponse(reader, CAPABILITIES_ID)
            execute(writer, "query-status", STATUS_ID)
            val result = awaitResponse(reader, STATUS_ID).getJSONObject("return")
            return Status(
                running = result.getBoolean("running"),
                status = result.optString("status", "unknown"),
            )
        } finally {
            runCatching { socket.close() }
        }
    }

    /**
     * Runs one monitor command and returns what the monitor printed.
     *
     * `human-monitor-command` rather than a typed QMP command because the thing Box needs —
     * adding and removing a host forward on a running user-mode netdev — has no QMP equivalent;
     * `hostfwd_add` and `hostfwd_remove` exist only on the human monitor. The cost is that success
     * is reported as an empty string and failure as *printed text* rather than a QMP error, which
     * is why the caller has to read the output instead of trusting the absence of an exception.
     */
    fun monitorCommand(command: String): String {
        val socket = LocalSocket()
        try {
            socket.connect(
                LocalSocketAddress(socketFile.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM),
            )
            socket.setSoTimeout(READ_TIMEOUT_MILLIS)
            val reader = socket.inputStream.bufferedReader(Charsets.UTF_8)
            val writer = socket.outputStream.bufferedWriter(Charsets.UTF_8)
            val greeting = JSONObject(reader.readLine() ?: error("QMP greeting was not received"))
            check(greeting.has("QMP")) { "Invalid QMP greeting" }

            execute(writer, "qmp_capabilities", CAPABILITIES_ID)
            awaitResponse(reader, CAPABILITIES_ID)
            execute(
                writer,
                "human-monitor-command",
                MONITOR_ID,
                JSONObject().put("command-line", command),
            )
            return awaitResponse(reader, MONITOR_ID).optString("return").trim()
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun execute(
        writer: java.io.BufferedWriter,
        command: String,
        id: String,
        arguments: JSONObject? = null,
    ) {
        val message = JSONObject().put("execute", command).put("id", id)
        arguments?.let { message.put("arguments", it) }
        writer.write(message.toString())
        writer.newLine()
        writer.flush()
    }

    private fun awaitResponse(reader: java.io.BufferedReader, id: String): JSONObject {
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

    private companion object {
        const val READ_TIMEOUT_MILLIS = 2_000
        const val MAX_MESSAGES_PER_COMMAND = 32
        const val CAPABILITIES_ID = "box-capabilities"
        const val STATUS_ID = "box-status"
        const val MONITOR_ID = "box-monitor"
    }
}
