package dev.localagent.workstation.computer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * The display protocol, against a server that only exists in this file.
 *
 * Worth the scaffolding: the real server is QEMU inside an emulated ARM64 VM on a phone, and
 * "the screen looked wrong" is close to undebuggable there. Every decode rule that can be stated
 * as bytes in, pixels out is stated here instead.
 */
class RfbTest {

    // ---- a server, in bytes ------------------------------------------------

    private class FakeServer {
        val bytes = ByteArrayOutputStream()
        private val out = DataOutputStream(bytes)

        fun handshake(width: Int = 4, height: Int = 3, securityTypes: ByteArray = byteArrayOf(1)) = apply {
            out.write("RFB 003.008\n".toByteArray(Charsets.US_ASCII))
            out.writeByte(securityTypes.size)
            out.write(securityTypes)
            out.writeInt(0) // security result: ok
            out.writeShort(width)
            out.writeShort(height)
            out.write(ByteArray(16)) // the server's preferred format, which the client replaces
            val name = "box".toByteArray(Charsets.UTF_8)
            out.writeInt(name.size)
            out.write(name)
        }

        fun update(vararg rectangles: DataOutputStream.() -> Unit) = apply {
            out.writeByte(0) // FramebufferUpdate
            out.writeByte(0) // padding
            out.writeShort(rectangles.size)
            rectangles.forEach { it(out) }
        }

        fun bell() = apply { out.writeByte(2) }

        fun cutText(text: String) = apply {
            out.writeByte(3)
            out.write(ByteArray(3))
            val bytes = text.toByteArray(Charsets.UTF_8)
            out.writeInt(bytes.size)
            out.write(bytes)
        }

        fun stream() = ByteArrayInputStream(bytes.toByteArray())
    }

    /** One raw rectangle. Pixels are supplied as ARGB and written out in the format Box asked for. */
    private fun raw(x: Int, y: Int, w: Int, h: Int, vararg argb: Int): DataOutputStream.() -> Unit = {
        writeShort(x); writeShort(y); writeShort(w); writeShort(h)
        writeInt(RfbConnection.ENCODING_RAW)
        argb.forEach { pixel ->
            writeByte(pixel and 0xFF) // blue
            writeByte((pixel shr 8) and 0xFF) // green
            writeByte((pixel shr 16) and 0xFF) // red
            writeByte(0) // padding, which the client must discard rather than read as alpha
        }
    }

    private fun copyRect(x: Int, y: Int, w: Int, h: Int, fromX: Int, fromY: Int): DataOutputStream.() -> Unit = {
        writeShort(x); writeShort(y); writeShort(w); writeShort(h)
        writeInt(RfbConnection.ENCODING_COPY_RECT)
        writeShort(fromX); writeShort(fromY)
    }

    private fun desktopSize(w: Int, h: Int): DataOutputStream.() -> Unit = {
        writeShort(0); writeShort(0); writeShort(w); writeShort(h)
        writeInt(RfbConnection.ENCODING_DESKTOP_SIZE)
    }

    private fun connect(server: FakeServer, sent: ByteArrayOutputStream = ByteArrayOutputStream()) =
        RfbConnection.connect(server.stream(), sent)

    // ---- tests -------------------------------------------------------------

    @Test
    fun `the handshake takes the screen size from the server`() {
        val connection = connect(FakeServer().handshake(width = 1280, height = 800))

        assertEquals(1280, connection.width)
        assertEquals(800, connection.height)
        assertEquals(1280 * 800, connection.framebuffer.size)
    }

    @Test
    fun `the client dictates a pixel format an Android bitmap already is`() {
        val sent = ByteArrayOutputStream()
        connect(FakeServer().handshake(), sent)

        // Past the version, the security choice and ClientInit: SetPixelFormat.
        val format = sent.toByteArray().drop(14)
        assertEquals(0, format[0].toInt()) // SetPixelFormat
        assertEquals(32, format[4].toInt()) // bits per pixel
        assertEquals(24, format[5].toInt()) // depth
        assertEquals(0, format[6].toInt()) // little endian
        assertEquals(1, format[7].toInt()) // true colour, so no palette ever arrives
        // Converting on the server costs nothing extra — it is already touching each dirty pixel.
        // Converting here would be a second walk over a million pixels per frame, on a phone.
        assertEquals(16, format[14].toInt()) // red shift
        assertEquals(8, format[15].toInt()) // green shift
        assertEquals(0, format[16].toInt()) // blue shift
    }

    @Test
    fun `only encodings this client can decode are offered`() {
        val sent = ByteArrayOutputStream()
        connect(FakeServer().handshake(), sent)

        // A server may use anything it is offered, so an unimplemented encoding in this list is a
        // promise that breaks the stream the first time the guest takes it up.
        val encodings = sent.toByteArray().drop(34)
        assertEquals(2, encodings[0].toInt()) // SetEncodings
        assertEquals(3, encodings[3].toInt()) // exactly three
    }

    @Test
    fun `raw pixels land in the framebuffer as opaque ARGB`() {
        val red = 0xFFFF0000.toInt()
        val green = 0xFF00FF00.toInt()
        val server = FakeServer().handshake(width = 2, height = 1)
            .update(raw(0, 0, 2, 1, red, green))
        val connection = connect(server)

        val damage = connection.pump()

        assertEquals(RfbConnection.Damage(0, 0, 2, 1), damage)
        // The fourth byte of each pixel is padding, not alpha. Trusting it would make every pixel
        // of a screen fully transparent.
        assertArrayEquals(intArrayOf(red, green), connection.framebuffer)
    }

    @Test
    fun `a rectangle only touches its own part of the screen`() {
        val white = 0xFFFFFFFF.toInt()
        val server = FakeServer().handshake(width = 3, height = 2)
            .update(raw(1, 1, 1, 1, white))
        val connection = connect(server)

        connection.pump()

        assertArrayEquals(intArrayOf(0, 0, 0, 0, white, 0), connection.framebuffer)
    }

    @Test
    fun `copying a region downward does not smear the first row`() {
        val a = 0xFF010101.toInt()
        val b = 0xFF020202.toInt()
        // A 1x2 column, copied down by one so source and destination overlap. Copied in the wrong
        // order the destination reads rows it has already overwritten, and the top row fills the
        // whole rectangle -- which on a real screen looks like a scroll that dragged.
        val server = FakeServer().handshake(width = 1, height = 3)
            .update(raw(0, 0, 1, 2, a, b))
            .update(copyRect(0, 1, 1, 2, 0, 0))
        val connection = connect(server)

        connection.pump()
        connection.pump()

        assertArrayEquals(intArrayOf(a, a, b), connection.framebuffer)
    }

    @Test
    fun `copying a region upward does not smear either`() {
        val a = 0xFF010101.toInt()
        val b = 0xFF020202.toInt()
        val server = FakeServer().handshake(width = 1, height = 3)
            .update(raw(0, 1, 1, 2, a, b))
            .update(copyRect(0, 0, 1, 2, 0, 1))
        val connection = connect(server)

        connection.pump()
        connection.pump()

        assertArrayEquals(intArrayOf(a, b, b), connection.framebuffer)
    }

    @Test
    fun `damage from several rectangles is reported as one region`() {
        val white = 0xFFFFFFFF.toInt()
        val server = FakeServer().handshake(width = 4, height = 4)
            .update(raw(0, 0, 1, 1, white), raw(3, 3, 1, 1, white))
        val connection = connect(server)

        // The caller repaints one rectangle per update, so two corners have to become the box that
        // contains both rather than the last one seen.
        assertEquals(RfbConnection.Damage(0, 0, 4, 4), connection.pump())
    }

    @Test
    fun `a resize replaces the framebuffer and invalidates everything`() {
        val server = FakeServer().handshake(width = 2, height = 2)
            .update(desktopSize(8, 4))
        val connection = connect(server)

        val damage = connection.pump()

        assertEquals(8, connection.width)
        assertEquals(4, connection.height)
        assertEquals(32, connection.framebuffer.size)
        // Everything drawn before a resize is meaningless afterwards.
        assertEquals(RfbConnection.Damage(0, 0, 8, 4), damage)
    }

    @Test
    fun `messages that change no pixels keep the stream aligned`() {
        val white = 0xFFFFFFFF.toInt()
        val server = FakeServer().handshake(width = 1, height = 1)
            .bell()
            .cutText("something the guest copied")
            .update(raw(0, 0, 1, 1, white))
        val connection = connect(server)

        assertNull(connection.pump()) // bell
        assertNull(connection.pump()) // clipboard
        // If either had been skipped by the wrong number of bytes, this would decode garbage.
        assertEquals(RfbConnection.Damage(0, 0, 1, 1), connection.pump())
        assertArrayEquals(intArrayOf(white), connection.framebuffer)
    }

    @Test
    fun `an encoding that was never offered is refused, not guessed at`() {
        val unsupported: DataOutputStream.() -> Unit = {
            writeShort(0); writeShort(0); writeShort(1); writeShort(1)
            writeInt(16) // ZRLE, which this client cannot decode
        }
        val server = FakeServer().handshake(width = 1, height = 1).update(unsupported)
        val connection = connect(server)

        // There is no way to skip a rectangle of unknown encoding: its length is defined by the
        // codec. Continuing would read the rest of the session as pixels.
        assertThrows(RfbException::class.java) { connection.pump() }
    }

    @Test
    fun `a server demanding authentication is refused`() {
        // The socket is app-private, so the filesystem already did the authenticating and QEMU runs
        // without a password. A challenge means this is not the VM Box launched.
        val server = FakeServer().handshake(securityTypes = byteArrayOf(2))

        val error = assertThrows(RfbException::class.java) { connect(server) }
        assertTrue(error.message!!, error.message!!.contains("authentication"))
    }

    @Test
    fun `a pointer outside the screen is clamped rather than sent`() {
        val sent = ByteArrayOutputStream()
        val connection = connect(FakeServer().handshake(width = 10, height = 10), sent)
        sent.reset()

        connection.sendPointer(buttons = 1, x = 99, y = -5)

        val message = sent.toByteArray()
        assertEquals(5, message[0].toInt()) // PointerEvent
        assertEquals(1, message[1].toInt()) // left button
        assertEquals(9, message[3].toInt()) // clamped to the last column
        assertEquals(0, message[5].toInt()) // clamped to the first row
    }
}
