package dev.localagent.runtime.qemu

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.File

/** Tiny QMP client kept inside the isolated runtime process. QMP never crosses the LAN. */
internal class QmpClient(private val socketFile: File) {
    fun queryStatus(): String {
        val socket = LocalSocket()
        try {
            socket.connect(LocalSocketAddress(socketFile.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
            val reader = socket.inputStream.bufferedReader()
            val writer = socket.outputStream.bufferedWriter()
            check(reader.readLine()?.contains("QMP") == true) { "QMP greeting was not received" }
            writer.write("{\"execute\":\"qmp_capabilities\"}\n")
            writer.flush()
            check(reader.readLine()?.contains("return") == true) { "QMP capability negotiation failed" }
            writer.write("{\"execute\":\"query-status\"}\n")
            writer.flush()
            repeat(8) {
                val response = reader.readLine() ?: error("QMP closed before status response")
                if (response.contains("\"return\"")) return response
            }
            error("QMP did not return a status response")
        } finally {
            socket.close()
        }
    }
}
