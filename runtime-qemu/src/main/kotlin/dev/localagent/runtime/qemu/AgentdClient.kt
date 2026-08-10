package dev.localagent.runtime.qemu

import android.net.LocalSocket
import android.net.LocalSocketAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Long-lived, request/response client for the unprivileged guest agent. Its filesystem socket
 * is reachable only inside the dedicated Android runtime process.
 */
internal class AgentdClient(private val socketFile: File) {
    private val lock = Any()
    private val sequence = AtomicLong()
    private var socket: LocalSocket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null

    suspend fun call(method: String, params: JSONObject = JSONObject()): JSONObject =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                try {
                    connectIfNeeded()
                    val id = sequence.incrementAndGet().toString()
                    writer!!.apply {
                        write(JSONObject().put("version", 1).put("id", id).put("method", method).put("params", params).toString())
                        newLine()
                        flush()
                    }
                    val response = JSONObject(reader!!.readLine() ?: error("agentd closed its control channel"))
                    check(response.getString("id") == id) { "agentd response id mismatch" }
                    response.optJSONObject("error")?.let { error("agentd ${it.optString("code")}: ${it.optString("message")}") }
                    response.getJSONObject("result")
                } catch (error: Throwable) {
                    closeLocked()
                    throw error
                }
            }
        }

    suspend fun close() = withContext(Dispatchers.IO) {
        synchronized(lock) {
            closeLocked()
        }
    }

    private fun connectIfNeeded() {
        if (socket?.isConnected == true) return
        val connectedSocket = LocalSocket()
        connectedSocket.connect(LocalSocketAddress(socketFile.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
        connectedSocket.setSoTimeout(1_000)
        socket = connectedSocket
        reader = connectedSocket.inputStream.bufferedReader()
        writer = connectedSocket.outputStream.bufferedWriter()
    }

    private fun closeLocked() {
        runCatching { socket?.close() }
        socket = null
        reader = null
        writer = null
    }
}
