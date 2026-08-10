package dev.localagent.runtime.qemu

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import java.io.File
import java.io.IOException

/** Debug-only visibility into the guest's private serial console during device bring-up. */
internal object SerialConsoleLogger {
    private const val TAG = "LocalAgentSerial"

    fun start(socketFile: File) {
        Thread({
            val socket = LocalSocket()
            try {
                socket.connect(LocalSocketAddress(socketFile.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
                socket.setSoTimeout(1_000)
                val buffer = ByteArray(4 * 1024)
                while (!Thread.currentThread().isInterrupted) {
                    try {
                        val count = socket.inputStream.read(buffer)
                        if (count < 0) return@Thread
                        if (count > 0) Log.i(TAG, String(buffer, 0, count, Charsets.UTF_8).trimEnd())
                    } catch (_: IOException) {
                        // A read timeout merely means the boot console is quiet; keep observing.
                    }
                }
            } catch (error: IOException) {
                Log.w(TAG, "Guest serial console was unavailable", error)
            } finally {
                runCatching { socket.close() }
            }
        }, "local-agent-serial").apply { isDaemon = true }.start()
    }
}
