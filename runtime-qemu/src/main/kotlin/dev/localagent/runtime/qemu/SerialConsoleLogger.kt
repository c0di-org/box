package dev.localagent.runtime.qemu

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import java.io.Closeable
import java.io.File
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** Owned debug diagnostic. Callers must gate this with ApplicationInfo.FLAG_DEBUGGABLE. */
internal class SerialConsoleLogger private constructor(private val socketFile: File) : Closeable {
    private val open = AtomicBoolean(true)
    @Volatile private var socket: LocalSocket? = null
    private val thread = Thread(::run, "box-guest-serial").apply { isDaemon = true }

    fun start() = thread.start()

    override fun close() {
        if (!open.getAndSet(false)) return
        thread.interrupt()
        runCatching { socket?.close() }
    }

    private fun run() {
        val connected = LocalSocket()
        socket = connected
        try {
            connected.connect(
                LocalSocketAddress(socketFile.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM),
                CONNECT_TIMEOUT_MILLIS,
            )
            connected.setSoTimeout(READ_TIMEOUT_MILLIS)
            val buffer = ByteArray(4 * 1024)
            while (open.get()) {
                val count = try {
                    connected.inputStream.read(buffer)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                if (count < 0) break
                if (count > 0) Log.d(TAG, String(buffer, 0, count, Charsets.UTF_8).trimEnd())
            }
        } catch (error: Exception) {
            if (open.get()) Log.w(TAG, "Guest serial console ended", error)
        } finally {
            runCatching { connected.close() }
            socket = null
            open.set(false)
        }
    }

    companion object {
        private const val TAG = "BoxGuestSerial"
        private const val CONNECT_TIMEOUT_MILLIS = 2_000
        private const val READ_TIMEOUT_MILLIS = 1_000

        fun launch(socketFile: File): SerialConsoleLogger =
            SerialConsoleLogger(socketFile).also { it.start() }
    }
}
