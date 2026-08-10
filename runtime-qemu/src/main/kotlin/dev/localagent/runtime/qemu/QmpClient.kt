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

    private fun execute(writer: java.io.BufferedWriter, command: String, id: String) {
        writer.write(JSONObject().put("execute", command).put("id", id).toString())
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
    }
}
