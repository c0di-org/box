package dev.localagent.workstation.computer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * The agent's screen, on a Surface.
 *
 * Connects to the Unix socket QEMU's VNC server is listening on, reads the framebuffer with
 * [RfbConnection], and paints it. The socket is inside the app's own storage, so this runs in the
 * UI process and draws straight into its own [Surface] — no frames cross a process boundary, and
 * `:computer` is not involved in drawing at all. It only had to put `-vnc` on QEMU's command line.
 *
 * That is a change from what [DesktopTransport] originally assumed, which was that `:computer`
 * would render into a Surface passed to it over Binder. It would work, but there is nothing to buy
 * with it: the pixels are already reachable from here, and a Surface handed across processes is one
 * more thing to get wrong when the UI process dies.
 */
class VncDesktop(
    private val socketPath: String,
    private val scope: CoroutineScope,
) : DesktopTransport {

    private val desktopState = MutableStateFlow<DesktopState>(DesktopState.Unavailable)
    override val state: StateFlow<DesktopState> = desktopState.asStateFlow()

    private val lock = Any()
    private var surface: Surface? = null
    private var connection: RfbConnection? = null
    private var pump: Job? = null
    private var bitmap: Bitmap? = null
    private var control: ControlHolder = ControlHolder.Agent

    /** Filtering matters here: the guest is 1280x800 and the pane it lands in rarely is. */
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

    override suspend fun attach(surface: Surface, widthPx: Int, heightPx: Int) {
        synchronized(lock) {
            this.surface = surface
            if (pump != null) {
                // Already streaming; the pane was just resized or recreated. Repaint into the new
                // Surface rather than reconnecting, which would cost a full framebuffer resend.
                connection?.let { redraw(it) }
                return
            }
        }
        desktopState.value = DesktopState.Starting
        pump = scope.launch(Dispatchers.IO) { stream() }
    }

    override suspend fun detach() {
        val running = synchronized(lock) {
            surface = null
            val job = pump
            pump = null
            job
        }
        running?.cancel()
        desktopState.value = DesktopState.Unavailable
    }

    override suspend fun send(input: DesktopInput) {
        // The agent holds the desktop unless the user has taken it. Dropping input here rather than
        // in the UI keeps the rule in one place, where it is also true for anything that reaches
        // this object by another route.
        if (control != ControlHolder.User) return
        val rfb = synchronized(lock) { connection } ?: return
        runCatching {
            when (input) {
                is DesktopInput.Pointer -> rfb.sendPointer(input.buttons, input.x, input.y)
                is DesktopInput.Scroll -> {
                    // A wheel notch is a button press and release, not a delta: bit 3 is up, bit 4
                    // is down. Sent one notch at a time so a fast flick scrolls by the right amount.
                    val bit = if (input.notches > 0) WHEEL_UP else WHEEL_DOWN
                    repeat(kotlin.math.abs(input.notches).coerceAtMost(MAX_NOTCHES)) {
                        rfb.sendPointer(bit, input.x, input.y)
                        rfb.sendPointer(0, input.x, input.y)
                    }
                }
                is DesktopInput.Key -> rfb.sendKey(input.down, input.keysym)
                is DesktopInput.Text -> input.codePoints().forEach { codePoint ->
                    val keysym = Keysyms.ofCharacter(codePoint)
                    rfb.sendKey(true, keysym)
                    rfb.sendKey(false, keysym)
                }
            }
        }.onFailure { Log.w(TAG, "could not deliver input to the guest", it) }
    }

    override suspend fun setControl(holder: ControlHolder) {
        control = holder
        val live = desktopState.value as? DesktopState.Live ?: return
        desktopState.value = live.copy(control = holder)
    }

    // ---- the stream --------------------------------------------------------

    private fun stream() {
        val socket = LocalSocket()
        try {
            // FILESYSTEM, not the abstract namespace: an abstract socket is reachable by any
            // process on the device, and the guest's screen is not something to put there. A path
            // inside app storage is protected by the filesystem.
            socket.connect(LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM))
            val rfb = RfbConnection.connect(socket.inputStream, socket.outputStream)
            synchronized(lock) {
                connection = rfb
                bitmap = Bitmap.createBitmap(rfb.width, rfb.height, Bitmap.Config.ARGB_8888)
            }
            desktopState.value = DesktopState.Live(rfb.width, rfb.height, control)

            // The first request is non-incremental: there is no previous frame to be a delta from.
            rfb.requestUpdate(incremental = false)
            while (!Thread.currentThread().isInterrupted) {
                val damage = rfb.pump()
                if (damage != null) {
                    if (bitmapMatches(rfb)) {
                        paint(rfb, damage)
                    } else {
                        // The guest changed resolution. Rebuild at the new size and redraw whole.
                        synchronized(lock) {
                            bitmap = Bitmap.createBitmap(rfb.width, rfb.height, Bitmap.Config.ARGB_8888)
                        }
                        desktopState.value = DesktopState.Live(rfb.width, rfb.height, control)
                        paint(rfb, RfbConnection.Damage(0, 0, rfb.width, rfb.height))
                    }
                    // Asked for only after the last frame was drawn, so a slow phone asks for
                    // frames at the rate it can actually paint them instead of building a backlog.
                    rfb.requestUpdate(incremental = true)
                }
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Exception) {
            Log.w(TAG, "the desktop stream ended", error)
            desktopState.value = DesktopState.Failed(
                error.message ?: "Box lost the picture from the computer.",
            )
        } finally {
            runCatching { socket.close() }
            synchronized(lock) {
                connection = null
                bitmap = null
            }
        }
    }

    private fun bitmapMatches(rfb: RfbConnection): Boolean = synchronized(lock) {
        val current = bitmap ?: return false
        current.width == rfb.width && current.height == rfb.height
    }

    private fun paint(rfb: RfbConnection, damage: RfbConnection.Damage) = synchronized(lock) {
        val target = bitmap ?: return
        // Only the changed rows are copied out of the framebuffer; the rest of the bitmap is still
        // the previous frame, which is exactly what an incremental update means.
        target.setPixels(
            rfb.framebuffer,
            damage.y * rfb.width + damage.x,
            rfb.width,
            damage.x,
            damage.y,
            damage.width,
            damage.height,
        )
        redraw(rfb)
    }

    /**
     * Blit the whole bitmap, even for a one-pixel change.
     *
     * A Surface is multiple buffers: the one locked now is not the one drawn last time, so painting
     * only the damaged rectangle leaves the rest of the frame showing whatever that buffer held two
     * frames ago. Tracking that properly means keeping per-buffer damage, and the guest is only a
     * megapixel — the scaled blit is cheaper than the bookkeeping.
     */
    private fun redraw(rfb: RfbConnection) {
        val target = bitmap ?: return
        val output = surface?.takeIf { it.isValid } ?: return
        val canvas: Canvas = runCatching { output.lockCanvas(null) }.getOrNull() ?: return
        try {
            val destination = fit(rfb.width, rfb.height, canvas.width, canvas.height)
            canvas.drawColor(android.graphics.Color.BLACK)
            canvas.drawBitmap(target, null, destination, paint)
        } finally {
            runCatching { output.unlockCanvasAndPost(canvas) }
        }
    }

    /** Letterboxed rather than stretched: a desktop with the wrong aspect ratio reads as broken. */
    private fun fit(sourceWidth: Int, sourceHeight: Int, intoWidth: Int, intoHeight: Int): Rect {
        val scale = minOf(
            intoWidth.toFloat() / sourceWidth,
            intoHeight.toFloat() / sourceHeight,
        )
        val width = (sourceWidth * scale).toInt()
        val height = (sourceHeight * scale).toInt()
        val left = (intoWidth - width) / 2
        val top = (intoHeight - height) / 2
        return Rect(left, top, left + width, top + height)
    }

    private fun DesktopInput.Text.codePoints(): List<Int> {
        val result = mutableListOf<Int>()
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            result += codePoint
            index += Character.charCount(codePoint)
        }
        return result
    }

    companion object {
        private const val TAG = "BoxDesktop"
        private const val WHEEL_UP = 1 shl 3
        private const val WHEEL_DOWN = 1 shl 4

        /** A flick can report a large delta; the guest does not need hundreds of notches. */
        private const val MAX_NOTCHES = 8

        /** Where `:computer` told QEMU to listen. Both processes derive it the same way. */
        fun socketPath(filesDir: File): String =
            File(File(File(filesDir, "computer"), "sockets"), "vnc.sock").absolutePath
    }
}
