package dev.localagent.runtime.qemu

import java.io.IOException

/**
 * Wire constants and the frame codec for agentd protocol v2 (`protocol/agentd-v2.md`).
 *
 * Deliberately free of Android and JSON dependencies: this is the part that parses bytes from the
 * guest, so it is the part that most needs to be exercised by plain JVM unit tests.
 */
internal object AgentdProtocol {
    const val VERSION = 2
    const val HEADER_BYTES = 12
    const val MAX_FRAME_PAYLOAD = 64 * 1024
    const val INITIAL_WINDOW_BYTES = 128 * 1024
    const val MAX_CONCURRENT_STREAMS = 32

    const val HELLO = 0x01
    const val OPEN = 0x02
    const val DATA = 0x03
    const val END = 0x04
    const val CLOSE = 0x05
    const val WINDOW = 0x06
    const val CANCEL = 0x07
    const val CTRL = 0x08
    const val PING = 0x09
    const val PONG = 0x0A
    const val GOAWAY = 0x0B

    const val CHANNEL_CONTROL = 0
    const val CHANNEL_STDIN = 1
    const val CHANNEL_STDOUT = 2
    const val CHANNEL_STDERR = 3

    private val CONNECTION_FRAMES = setOf(HELLO, PING, PONG, GOAWAY)
    private val FRAME_TYPES = (HELLO..GOAWAY).toSet()
    private val CHANNELS = setOf(CHANNEL_CONTROL, CHANNEL_STDIN, CHANNEL_STDOUT, CHANNEL_STDERR)

    /** The first byte a v1 guest sends is `{`: it answers a v2 HELLO with a JSON error line. */
    private const val V1_JSON_FIRST_BYTE = '{'.code

    fun isConnectionFrame(type: Int): Boolean = type in CONNECTION_FRAMES

    fun encode(type: Int, streamId: Long, channel: Int, payload: ByteArray): ByteArray {
        require(payload.size <= MAX_FRAME_PAYLOAD) { "agentd frame payload exceeds the transport limit" }
        val frame = ByteArray(HEADER_BYTES + payload.size)
        frame[0] = VERSION.toByte()
        frame[1] = type.toByte()
        frame[2] = channel.toByte()
        frame[3] = 0
        frame.putInt(4, streamId)
        frame.putInt(8, payload.size.toLong())
        payload.copyInto(frame, HEADER_BYTES)
        return frame
    }

    /** Validates every field before any length from the wire is used as an allocation size. */
    fun decodeHeader(header: ByteArray): AgentdHeader {
        require(header.size >= HEADER_BYTES) { "agentd header is too short" }
        val version = header[0].toInt() and 0xff
        if (version != VERSION) {
            throw AgentdFramingException(
                if (version == V1_JSON_FIRST_BYTE) {
                    "the guest agent speaks protocol v1 and is older than this app"
                } else {
                    "agentd frame version $version is not $VERSION"
                },
            )
        }
        if ((header[3].toInt() and 0xff) != 0) {
            throw AgentdFramingException("agentd reserved header byte must be zero")
        }
        val type = header[1].toInt() and 0xff
        if (type !in FRAME_TYPES) throw AgentdFramingException("unknown agentd frame type $type")
        val channel = header[2].toInt() and 0xff
        if (channel !in CHANNELS) throw AgentdFramingException("unknown agentd channel $channel")
        val streamId = header.getInt(4)
        val length = header.getInt(8)
        if (length > MAX_FRAME_PAYLOAD) {
            throw AgentdFramingException("agentd frame payload of $length bytes exceeds the limit")
        }
        if ((streamId == 0L) != isConnectionFrame(type)) {
            throw AgentdFramingException("agentd frame type $type on stream $streamId")
        }
        return AgentdHeader(type, channel, streamId, length.toInt())
    }

    fun encodeWindow(credit: Int): ByteArray = ByteArray(4).also { it.putInt(0, credit.toLong()) }

    fun decodeWindow(payload: ByteArray): Long {
        if (payload.size != 4) throw AgentdFramingException("agentd WINDOW payload must be four bytes")
        return payload.getInt(0)
    }

    private fun ByteArray.putInt(offset: Int, value: Long) {
        this[offset] = (value ushr 24).toByte()
        this[offset + 1] = (value ushr 16).toByte()
        this[offset + 2] = (value ushr 8).toByte()
        this[offset + 3] = value.toByte()
    }

    private fun ByteArray.getInt(offset: Int): Long =
        ((this[offset].toLong() and 0xff) shl 24) or
            ((this[offset + 1].toLong() and 0xff) shl 16) or
            ((this[offset + 2].toLong() and 0xff) shl 8) or
            (this[offset + 3].toLong() and 0xff)
}

internal class AgentdHeader(
    val type: Int,
    val channel: Int,
    val streamId: Long,
    val payloadLength: Int,
)

/**
 * A length-prefixed byte stream cannot be resynchronised after a desync, so every framing fault is
 * fatal to the connection rather than to one stream.
 */
internal class AgentdFramingException(message: String) : IOException(message)
