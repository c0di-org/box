package dev.localagent.runtime.qemu

import kotlinx.coroutines.CompletableDeferred
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The guest half of the v2 protocol, written by hand so the tests exercise the shipping host code
 * rather than a mirror of it. No device, no socket, no booted VM.
 */
internal class FakeGuest {
    private val toGuest = BlockingPipe()
    private val toHost = BlockingPipe()
    private val received = LinkedBlockingQueue<Frame>()

    val transportClosed = CompletableDeferred<Unit>()

    val connection = AgentdConnection(toHost.input, toGuest.output) {
        transportClosed.complete(Unit)
        toGuest.close()
        toHost.close()
    }

    /**
     * A dedicated thread, not a cancellable coroutine: a blocked stream read does not observe
     * coroutine cancellation, so a timeout around one would report the next frame whenever it
     * happened to arrive rather than reporting silence.
     */
    private val reader = Thread({
        runCatching {
            while (true) {
                val header = ByteArray(AgentdProtocol.HEADER_BYTES)
                if (!toGuest.input.readFully(header)) return@runCatching
                val decoded = AgentdProtocol.decodeHeader(header)
                val payload = ByteArray(decoded.payloadLength)
                if (decoded.payloadLength > 0 && !toGuest.input.readFully(payload)) {
                    return@runCatching
                }
                received.put(Frame(decoded.type, decoded.channel, decoded.streamId, payload))
            }
        }
    }, "fake-guest-reader").apply { isDaemon = true; start() }

    suspend fun handshake(hello: String = GUEST_HELLO) {
        connection.start(AgentdClient.clientHello())
        read()
        write(AgentdProtocol.HELLO, 0, AgentdProtocol.CHANNEL_CONTROL, hello.toByteArray())
        connection.awaitPeerHello(TIMEOUT_MILLIS)
    }

    /** Reads one frame the host wrote. */
    fun read(): Frame = requireNotNull(readOrNull(TIMEOUT_MILLIS)) { "the host sent no frame" }

    fun readOrNull(timeoutMillis: Long): Frame? =
        received.poll(timeoutMillis, TimeUnit.MILLISECONDS)

    /** Reads until a frame of [type] arrives, skipping the flow-control chatter around it. */
    fun readUntil(type: Int): Frame {
        while (true) {
            val frame = read()
            if (frame.type == type) return frame
        }
    }

    fun write(type: Int, streamId: Long, channel: Int, payload: ByteArray = ByteArray(0)) {
        toHost.output.write(AgentdProtocol.encode(type, streamId, channel, payload))
    }

    fun writeText(type: Int, streamId: Long, channel: Int, payload: String) =
        write(type, streamId, channel, payload.toByteArray())

    /** Ends a stream the way the guest does: a terminal CLOSE carrying the status. */
    fun close(streamId: Long, status: String) =
        writeText(AgentdProtocol.CLOSE, streamId, AgentdProtocol.CHANNEL_CONTROL, status)

    fun corruptTheStream() {
        // A version the host does not speak: length-prefixed framing cannot resynchronise.
        toHost.output.write(ByteArray(AgentdProtocol.HEADER_BYTES).also { it[0] = 9; it[1] = 3; it[7] = 1 })
    }

    private fun InputStream.readFully(buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val read = read(buffer, offset, buffer.size - offset)
            if (read < 0) return false
            offset += read
        }
        return true
    }

    companion object {
        const val TIMEOUT_MILLIS = 5_000L

        /** Long enough to prove nothing was sent, short enough not to slow the suite down. */
        const val SILENCE_MILLIS = 300L

        const val GUEST_HELLO =
            """{"version":2,"agent":"agentd/2","maxFramePayloadBytes":65536,""" +
                """"initialWindowBytes":131072,"maxConcurrentStreams":32}"""
    }
}

internal class Frame(
    val type: Int,
    val channel: Int,
    val streamId: Long,
    val payload: ByteArray,
) {
    val text: String get() = payload.toString(Charsets.UTF_8)

    /** So an unexpected frame names itself in an assertion failure. */
    override fun toString() = "Frame(type=0x%02x, channel=%d, stream=%d, payload=%d bytes)"
        .format(type, channel, streamId, payload.size)
}

/**
 * A blocking in-memory pipe. `PipedInputStream` is unusable here: it fails a read once the thread
 * that last wrote has exited, which a coroutine dispatcher makes unpredictable.
 */
internal class BlockingPipe {
    private val lock = Object()
    private var buffer = ByteArray(0)
    private var closed = false

    val output: OutputStream = object : OutputStream() {
        override fun write(byte: Int) = write(byteArrayOf(byte.toByte()), 0, 1)

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            synchronized(lock) {
                buffer += bytes.copyOfRange(offset, offset + length)
                lock.notifyAll()
            }
        }
    }

    val input: InputStream = object : InputStream() {
        override fun read(): Int {
            val single = ByteArray(1)
            return if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 0xff
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int = synchronized(lock) {
            while (buffer.isEmpty() && !closed) lock.wait()
            if (buffer.isEmpty()) return -1
            val count = minOf(length, buffer.size)
            buffer.copyInto(bytes, offset, 0, count)
            buffer = buffer.copyOfRange(count, buffer.size)
            count
        }
    }

    fun close() {
        synchronized(lock) {
            closed = true
            lock.notifyAll()
        }
    }
}
