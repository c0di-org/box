package dev.localagent.runtime.qemu

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The codec is the part that parses bytes from the guest, so every field is checked. */
class AgentdProtocolTest {
    @Test
    fun `encodes and decodes a frame header`() {
        val frame = AgentdProtocol.encode(
            AgentdProtocol.DATA,
            streamId = 7,
            channel = AgentdProtocol.CHANNEL_STDERR,
            payload = "boom".toByteArray(),
        )

        val header = AgentdProtocol.decodeHeader(frame)

        assertEquals(12, AgentdProtocol.HEADER_BYTES)
        assertEquals(AgentdProtocol.DATA, header.type)
        assertEquals(AgentdProtocol.CHANNEL_STDERR, header.channel)
        assertEquals(7L, header.streamId)
        assertEquals(4, header.payloadLength)
        assertArrayEquals("boom".toByteArray(), frame.copyOfRange(12, frame.size))
    }

    @Test
    fun `encodes stream ids across the whole unsigned range`() {
        val frame = AgentdProtocol.encode(AgentdProtocol.DATA, 0xFFFF_FFFEL, 2, ByteArray(0))

        assertEquals(0xFFFF_FFFEL, AgentdProtocol.decodeHeader(frame).streamId)
    }

    @Test
    fun `rejects a frame version it does not speak`() {
        val header = header(version = 3, type = AgentdProtocol.DATA, streamId = 1)

        val failure = failureOf { AgentdProtocol.decodeHeader(header) }

        assertTrue(failure.message!!, failure.message!!.contains("version 3"))
    }

    @Test
    fun `names a v1 guest instead of reporting a meaningless version`() {
        // A v1 agentd answers a v2 HELLO with a JSON error line, so the version byte reads as '{'.
        val header = header(version = '{'.code, type = AgentdProtocol.DATA, streamId = 1)

        val failure = failureOf { AgentdProtocol.decodeHeader(header) }

        assertTrue(failure.message!!, failure.message!!.contains("protocol v1"))
    }

    @Test
    fun `refuses an oversized length before it becomes an allocation`() {
        val header = header(type = AgentdProtocol.DATA, streamId = 1, length = 1L shl 30)

        val failure = failureOf { AgentdProtocol.decodeHeader(header) }

        assertTrue(failure.message!!, failure.message!!.contains("exceeds the limit"))
    }

    @Test
    fun `requires the reserved byte to be zero`() {
        val header = header(type = AgentdProtocol.DATA, streamId = 1).also { it[3] = 1 }

        val failure = failureOf { AgentdProtocol.decodeHeader(header) }

        assertTrue(failure.message!!, failure.message!!.contains("reserved"))
    }

    @Test
    fun `keeps connection frames on stream zero and stream frames off it`() {
        val dataOnZero = failureOf {
            AgentdProtocol.decodeHeader(header(type = AgentdProtocol.DATA, streamId = 0))
        }
        val pingOnStream = failureOf {
            AgentdProtocol.decodeHeader(header(type = AgentdProtocol.PING, streamId = 3))
        }

        assertTrue(dataOnZero.message!!, dataOnZero.message!!.contains("on stream 0"))
        assertTrue(pingOnStream.message!!, pingOnStream.message!!.contains("on stream 3"))
    }

    @Test
    fun `rejects unknown types and channels`() {
        assertTrue(
            failureOf { AgentdProtocol.decodeHeader(header(type = 0x40, streamId = 1)) }
                .message!!.contains("frame type"),
        )
        assertTrue(
            failureOf {
                AgentdProtocol.decodeHeader(
                    header(type = AgentdProtocol.DATA, streamId = 1).also { it[2] = 9 },
                )
            }.message!!.contains("channel"),
        )
    }

    @Test
    fun `round trips a window credit larger than a signed short`() {
        assertEquals(
            AgentdProtocol.INITIAL_WINDOW_BYTES.toLong(),
            AgentdProtocol.decodeWindow(
                AgentdProtocol.encodeWindow(AgentdProtocol.INITIAL_WINDOW_BYTES),
            ),
        )
    }

    @Test
    fun `rejects a window payload that is not four bytes`() {
        assertTrue(
            failureOf { AgentdProtocol.decodeWindow(ByteArray(3)) }
                .message!!.contains("four bytes"),
        )
    }

    private fun header(
        version: Int = AgentdProtocol.VERSION,
        type: Int,
        streamId: Long,
        length: Long = 0,
    ): ByteArray = ByteArray(AgentdProtocol.HEADER_BYTES).also {
        it[0] = version.toByte()
        it[1] = type.toByte()
        for (index in 0 until 4) {
            it[4 + index] = (streamId ushr ((3 - index) * 8)).toByte()
            it[8 + index] = (length ushr ((3 - index) * 8)).toByte()
        }
    }

    private fun failureOf(block: () -> Unit): AgentdFramingException = try {
        block()
        throw AssertionError("expected a framing error")
    } catch (expected: AgentdFramingException) {
        expected
    }
}
