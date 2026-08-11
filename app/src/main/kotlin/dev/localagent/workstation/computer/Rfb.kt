package dev.localagent.workstation.computer

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * The guest's screen, over RFB 3.8.
 *
 * QEMU already speaks this — the prebuilt `libqemu-system-aarch64.so` has a VNC server compiled in
 * — so Box does not have to reach into QEMU's display internals to see the framebuffer. It opens a
 * Unix socket QEMU is listening on and reads pixels off it like any other client.
 *
 * Why a protocol rather than the C display API, which this build also exports: QEMU is 5.1.0 and
 * Box has no headers matching it, so registering a `DisplayChangeListener` would mean hand-copying
 * a struct layout and hoping. RFB is versioned, documented, and cannot be silently wrong — a
 * mismatch is a decode error, not a native crash inside the process running the VM.
 *
 * No Android types on purpose. Everything here is streams and integers, which is what lets the
 * whole protocol be tested against a scripted server with no device and no VM.
 *
 * ### The framebuffer is owned, not returned
 *
 * A 1280x800 screen is a million pixels; handing back a fresh array per update would allocate 4 MB
 * per frame and spend more time in GC than in decoding. [framebuffer] is written in place and
 * [pump] reports only which part changed, so the caller repaints a rectangle rather than a screen.
 */
internal class RfbConnection private constructor(
    private val input: DataInputStream,
    private val output: OutputStream,
    width: Int,
    height: Int,
) {
    var width: Int = width
        private set
    var height: Int = height
        private set

    /** ARGB_8888, row-major, `width * height` long. Replaced only when the guest resizes. */
    var framebuffer: IntArray = IntArray(width * height)
        private set

    /** What changed, in framebuffer coordinates. */
    data class Damage(val x: Int, val y: Int, val width: Int, val height: Int)

    // ---- reading -----------------------------------------------------------

    /**
     * Read one server message and apply it.
     *
     * Returns the damaged region, or null for a message that changed no pixels. Blocks until a
     * whole message has arrived, so this belongs on its own thread.
     */
    fun pump(): Damage? = when (val type = input.read()) {
        -1 -> throw EOFException("the guest closed its display")
        MESSAGE_FRAMEBUFFER_UPDATE -> readFramebufferUpdate()
        MESSAGE_SET_COLOUR_MAP -> readColourMap().let { null }
        MESSAGE_BELL -> null
        MESSAGE_SERVER_CUT_TEXT -> readServerCutText().let { null }
        // An unknown message has an unknown length, so there is no way to skip it and stay in sync.
        // Failing here is honest; carrying on would decode the rest of the stream as garbage.
        else -> throw RfbException("unknown server message $type")
    }

    private fun readFramebufferUpdate(): Damage? {
        input.skipFully(1) // padding
        val count = input.readUnsignedShort()
        var damage: Damage? = null
        repeat(count) {
            val x = input.readUnsignedShort()
            val y = input.readUnsignedShort()
            val rectWidth = input.readUnsignedShort()
            val rectHeight = input.readUnsignedShort()
            when (val encoding = input.readInt()) {
                ENCODING_RAW -> readRaw(x, y, rectWidth, rectHeight)
                ENCODING_COPY_RECT -> readCopyRect(x, y, rectWidth, rectHeight)
                // A resize arrives as a rectangle with no pixels behind it: the geometry in the
                // header *is* the message. Everything already drawn is void, so the caller is told
                // the whole screen changed.
                ENCODING_DESKTOP_SIZE -> {
                    resize(rectWidth, rectHeight)
                    damage = Damage(0, 0, rectWidth, rectHeight)
                    return@repeat
                }
                else -> throw RfbException("server used encoding $encoding, which was never offered")
            }
            damage = damage.union(Damage(x, y, rectWidth, rectHeight))
        }
        return damage
    }

    /**
     * Pixels, in the format asked for in [sendPixelFormat]: four bytes each, blue first.
     *
     * Read a row at a time rather than a pixel at a time. The difference is not stylistic — a
     * per-pixel read on a socket stream is a million calls through the buffer for one screen.
     */
    private fun readRaw(x: Int, y: Int, rectWidth: Int, rectHeight: Int) {
        if (rectWidth == 0 || rectHeight == 0) return
        require(x >= 0 && y >= 0 && x + rectWidth <= width && y + rectHeight <= height) {
            "rectangle ${rectWidth}x$rectHeight at $x,$y falls outside a ${width}x$height screen"
        }
        val row = ByteArray(rectWidth * BYTES_PER_PIXEL)
        for (line in 0 until rectHeight) {
            input.readFully(row)
            var at = (y + line) * width + x
            var byte = 0
            repeat(rectWidth) {
                val blue = row[byte].toInt() and 0xFF
                val green = row[byte + 1].toInt() and 0xFF
                val red = row[byte + 2].toInt() and 0xFF
                // The fourth byte is the format's padding. The guest has no alpha to give, and a
                // screen is opaque, so it is dropped and full alpha written instead.
                framebuffer[at] = OPAQUE or (red shl 16) or (green shl 8) or blue
                at++
                byte += BYTES_PER_PIXEL
            }
        }
    }

    /**
     * A rectangle that already exists somewhere else on screen — how a server describes scrolling.
     *
     * Copied bottom-up or top-down depending on which way it moved, because source and destination
     * overlap whenever the scroll is smaller than the viewport, and copying the wrong way smears
     * the first row down the whole rectangle.
     */
    private fun readCopyRect(x: Int, y: Int, rectWidth: Int, rectHeight: Int) {
        val sourceX = input.readUnsignedShort()
        val sourceY = input.readUnsignedShort()
        if (rectWidth == 0 || rectHeight == 0) return
        require(
            sourceX >= 0 && sourceY >= 0 &&
                sourceX + rectWidth <= width && sourceY + rectHeight <= height &&
                x + rectWidth <= width && y + rectHeight <= height,
        ) { "copy of ${rectWidth}x$rectHeight from $sourceX,$sourceY to $x,$y leaves the screen" }

        val downwards = y > sourceY
        for (step in 0 until rectHeight) {
            val line = if (downwards) rectHeight - 1 - step else step
            System.arraycopy(
                framebuffer, (sourceY + line) * width + sourceX,
                framebuffer, (y + line) * width + x,
                rectWidth,
            )
        }
    }

    private fun readColourMap() {
        input.skipFully(3)
        val count = input.readUnsignedShort()
        // True colour was demanded in the pixel format, so a palette should never arrive. Skipping
        // it keeps the stream in sync instead of turning a server quirk into a dead screen.
        input.skipFully(count.toLong() * 6)
    }

    private fun readServerCutText() {
        input.skipFully(3)
        val length = input.readInt().toLong()
        // The guest's clipboard. Nothing consumes it yet; dropping it is what keeps the stream
        // aligned until something does.
        input.skipFully(length)
    }

    private fun resize(newWidth: Int, newHeight: Int) {
        width = newWidth
        height = newHeight
        framebuffer = IntArray(newWidth * newHeight)
    }

    // ---- writing -----------------------------------------------------------

    /** Ask for everything ([incremental] false) or only what changed since the last request. */
    fun requestUpdate(incremental: Boolean) {
        val message = ByteArray(10)
        message[0] = MESSAGE_UPDATE_REQUEST.toByte()
        message[1] = if (incremental) 1 else 0
        message.putShort(2, 0)
        message.putShort(4, 0)
        message.putShort(6, width)
        message.putShort(8, height)
        write(message)
    }

    /**
     * A pointer at [x], [y] with [buttons] as a bitmask — bit 0 left, 1 middle, 2 right, 3 and 4
     * the wheel. A wheel notch is a press and release of its bit, which is why scrolling is two
     * calls and not a delta.
     */
    fun sendPointer(buttons: Int, x: Int, y: Int) {
        val message = ByteArray(6)
        message[0] = MESSAGE_POINTER.toByte()
        message[1] = buttons.toByte()
        message.putShort(2, x.coerceIn(0, width - 1))
        message.putShort(4, y.coerceIn(0, height - 1))
        write(message)
    }

    /** One key transition, as an X11 keysym. */
    fun sendKey(down: Boolean, keysym: Int) {
        val message = ByteArray(8)
        message[0] = MESSAGE_KEY.toByte()
        message[1] = if (down) 1 else 0
        message.putShort(2, 0)
        message.putInt(4, keysym)
        write(message)
    }

    private fun write(bytes: ByteArray) {
        synchronized(output) {
            output.write(bytes)
            output.flush()
        }
    }

    companion object {
        /**
         * Handshake, and the pixel format Box wants rather than whatever the guest prefers.
         *
         * Asking the server to convert is the cheap direction: QEMU is already touching every dirty
         * pixel to encode it, whereas a conversion pass on this side would be a second walk over a
         * million pixels per frame on a phone. The format asked for is the one an Android ARGB_8888
         * bitmap already is, so decoding is a shift and an or.
         */
        fun connect(input: InputStream, output: OutputStream): RfbConnection {
            val stream = DataInputStream(input.buffered(BUFFER_BYTES))

            val greeting = ByteArray(12)
            stream.readFully(greeting)
            val version = String(greeting, Charsets.US_ASCII)
            if (!version.startsWith("RFB 003.")) throw RfbException("not an RFB server: $version")
            output.write("RFB 003.008\n".toByteArray(Charsets.US_ASCII))
            output.flush()

            val securityCount = stream.read()
            if (securityCount <= 0) {
                // Zero means the server is refusing, and says why in a string that follows.
                val reason = if (securityCount == 0) stream.readReason() else "the display closed"
                throw RfbException("the guest refused the connection: $reason")
            }
            val offered = ByteArray(securityCount).also { stream.readFully(it) }
            // The socket is app-private, so the filesystem has already done the authenticating and
            // QEMU is started without a password. Anything else means the VM was not launched by
            // this build, and guessing at a challenge is worse than saying so.
            if (offered.none { it.toInt() == SECURITY_NONE }) {
                throw RfbException("the guest asked for authentication Box does not use")
            }
            output.write(byteArrayOf(SECURITY_NONE.toByte()))
            output.flush()
            val result = stream.readInt()
            if (result != 0) throw RfbException("the guest rejected the connection: ${stream.readReason()}")

            output.write(byteArrayOf(1)) // ClientInit, shared: never evict another viewer
            output.flush()

            val width = stream.readUnsignedShort()
            val height = stream.readUnsignedShort()
            stream.skipFully(16) // the server's preferred pixel format, replaced below
            stream.skipFully(stream.readInt().toLong()) // desktop name

            val connection = RfbConnection(stream, output, width, height)
            connection.sendPixelFormat()
            connection.sendEncodings()
            return connection
        }

        private fun RfbConnection.sendPixelFormat() {
            val message = ByteArray(20)
            message[0] = MESSAGE_SET_PIXEL_FORMAT.toByte()
            message[4] = 32 // bits per pixel
            message[5] = 24 // depth
            message[6] = 0 // little endian
            message[7] = 1 // true colour
            message.putShort(8, 255) // red max
            message.putShort(10, 255) // green max
            message.putShort(12, 255) // blue max
            message[14] = 16 // red shift
            message[15] = 8 // green shift
            message[16] = 0 // blue shift
            write(message)
        }

        private fun RfbConnection.sendEncodings() {
            // Only what this client can actually decode. A server may use anything it is offered,
            // so offering an encoding that is not implemented is a promise that breaks the stream.
            val encodings = intArrayOf(ENCODING_COPY_RECT, ENCODING_RAW, ENCODING_DESKTOP_SIZE)
            val message = ByteArray(4 + encodings.size * 4)
            message[0] = MESSAGE_SET_ENCODINGS.toByte()
            message.putShort(2, encodings.size)
            encodings.forEachIndexed { index, encoding -> message.putInt(4 + index * 4, encoding) }
            write(message)
        }

        private fun DataInputStream.readReason(): String {
            val length = runCatching { readInt() }.getOrDefault(0)
            if (length <= 0 || length > MAX_REASON_BYTES) return "no reason given"
            val bytes = ByteArray(length)
            return runCatching { readFully(bytes); String(bytes, Charsets.UTF_8) }
                .getOrDefault("no reason given")
        }

        private const val BUFFER_BYTES = 1 shl 16
        private const val MAX_REASON_BYTES = 4096
        private const val BYTES_PER_PIXEL = 4
        private const val OPAQUE = 0xFF shl 24
        private const val SECURITY_NONE = 1

        private const val MESSAGE_FRAMEBUFFER_UPDATE = 0
        private const val MESSAGE_SET_COLOUR_MAP = 1
        private const val MESSAGE_BELL = 2
        private const val MESSAGE_SERVER_CUT_TEXT = 3

        private const val MESSAGE_SET_PIXEL_FORMAT = 0
        private const val MESSAGE_SET_ENCODINGS = 2
        private const val MESSAGE_UPDATE_REQUEST = 3
        private const val MESSAGE_KEY = 4
        private const val MESSAGE_POINTER = 5

        const val ENCODING_RAW = 0
        const val ENCODING_COPY_RECT = 1
        const val ENCODING_DESKTOP_SIZE = -223
    }
}

internal class RfbException(message: String) : RuntimeException(message)

// ---------------------------------------------------------------------------
// Byte plumbing
// ---------------------------------------------------------------------------

private fun ByteArray.putShort(at: Int, value: Int) {
    this[at] = (value ushr 8).toByte()
    this[at + 1] = value.toByte()
}

private fun ByteArray.putInt(at: Int, value: Int) {
    this[at] = (value ushr 24).toByte()
    this[at + 1] = (value ushr 16).toByte()
    this[at + 2] = (value ushr 8).toByte()
    this[at + 3] = value.toByte()
}

/** `skip` may do less than asked and still be correct; a protocol reader cannot accept that. */
private fun DataInputStream.skipFully(count: Long) {
    var left = count
    while (left > 0) {
        val skipped = skip(left)
        if (skipped > 0) {
            left -= skipped
        } else {
            if (read() < 0) throw EOFException("the display ended mid-message")
            left--
        }
    }
}

private fun DataInputStream.skipFully(count: Int) = skipFully(count.toLong())

private fun RfbConnection.Damage?.union(other: RfbConnection.Damage): RfbConnection.Damage {
    if (this == null) return other
    val left = minOf(x, other.x)
    val top = minOf(y, other.y)
    val right = maxOf(x + width, other.x + other.width)
    val bottom = maxOf(y + height, other.y + other.height)
    return RfbConnection.Damage(left, top, right - left, bottom - top)
}
