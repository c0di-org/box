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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * The agent's screen, on a Surface.
 *
 * Connects to the Unix socket QEMU's VNC server is listening on, reads the framebuffer with
 * [RfbConnection], and paints it. The socket is inside the app's own storage, so this runs in the
 * UI process and draws straight into its own [Surface] — no frames cross a process boundary, and
 * `:computer` is not involved in drawing at all. It only had to put `-vnc` on QEMU's command line.
 *
 * Rendering inside `:computer` and passing the Surface over Binder would also work, and buys
 * nothing: the pixels are already reachable from here, and a Surface handed across processes is
 * one more thing to get wrong when the UI process dies.
 */
class VncDesktop(
    private val socketPath: String,
    private val scope: CoroutineScope,
) : DesktopTransport {

    private val desktopState = MutableStateFlow<DesktopState>(DesktopState.Unavailable)
    override val state: StateFlow<DesktopState> = desktopState.asStateFlow()

    private val lock = Any()

    /**
     * Every view currently showing the guest, in attach order.
     *
     * A set rather than one surface because the same screen legitimately appears in several places
     * at once — the box's header on the home column while the full window is open over it, or the
     * inline pane beside a conversation on a Fold. One RFB connection feeds all of them; the cost
     * of an extra view is one scaled blit per frame, not another framebuffer crossing the emulated
     * link.
     */
    private val surfaces = LinkedHashMap<Surface, Attached>()

    /** A view of the guest, and whether its size is allowed to be an opinion about the guest's. */
    private data class Attached(val screen: GuestScreen, val preview: Boolean)

    private val wantedScreen = MutableStateFlow<GuestScreen?>(null)
    override val wantedGuestScreen: StateFlow<GuestScreen?> = wantedScreen.asStateFlow()

    private var connection: RfbConnection? = null
    private var pump: Job? = null
    private var bitmap: Bitmap? = null
    private var control: ControlHolder = ControlHolder.Agent

    /** Filtering matters here: the guest is 1280x800 and the pane it lands in rarely is. */
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

    override suspend fun attach(surface: Surface, widthPx: Int, heightPx: Int, preview: Boolean) {
        synchronized(lock) {
            // Re-measured, not merely added: `surfaceChanged` is how a rotation, a fold and a DeX
            // window drag all arrive, and each of those is a resize of a surface already here.
            surfaces[surface] = Attached(GuestScreen(widthPx, heightPx), preview)
            publishWantedScreen()
            if (pump?.isActive == true) {
                // Already streaming; a view was resized, recreated, or newly opened. Repaint into
                // it rather than reconnecting, which would cost a full framebuffer resend.
                connection?.let { redraw(it) }
                return
            }
            // `isActive`, not `!= null`. The test used to be "is there a pump", and a pump whose
            // stream had already died passed it: `connection` was null so the repaint above was
            // skipped, and this method returned having done nothing at all. Nothing relaunched
            // `stream`, and the field was only ever cleared by `detach` once the *last* surface had
            // gone — which is why backing all the way out and coming back in was the one thing that
            // brought the picture back. The comment above was right about the resize it was written
            // for and wrong about the dead stream it also caught.
            startStream()
        }
    }

    /**
     * Opens the stream again after it has ended, for a desktop somebody is still looking at.
     *
     * A stream that dies takes the picture with it and there is no way back into it from the pane
     * — no retry, no reconnect. This is the way back. Deliberately something the user asks for
     * rather than a timer: the failures seen so far are not transient, and a client reconnecting
     * to a guest that has stopped serving frames would spend the battery to redraw the same black.
     */
    override suspend fun reconnect() {
        synchronized(lock) {
            // Nothing to draw into, or already drawing. Both make this a no-op rather than a
            // second connection.
            if (surfaces.isEmpty() || pump?.isActive == true) return
            startStream()
        }
    }

    /**
     * Starts the pump. Only ever called with [lock] held, and that is what makes it safe.
     *
     * [stream]'s own `finally` clears `pump` under this same lock, so a stream that fails before
     * the assignment below has run cannot clear a field that has not been set yet — it blocks
     * until this returns. Assigning outside the lock would leave a completed job in `pump` with
     * nothing to clear it, which is the original bug wearing a different hat.
     */
    private fun startStream() {
        desktopState.value = DesktopState.Starting
        pump = scope.launch(Dispatchers.IO) { stream() }
    }

    /**
     * The stream outlives any one view, and only ends when the last one has gone.
     *
     * Closing the full window while the box's row is still on screen must not drop the connection:
     * reconnecting costs a whole framebuffer over an emulated link, and the row would go black for
     * as long as that takes.
     */
    override suspend fun detach(surface: Surface) {
        val running = synchronized(lock) {
            surfaces -= surface
            publishWantedScreen()
            if (surfaces.isNotEmpty()) return
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

    private suspend fun stream() {
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
            // The coroutine's own liveness, not the thread's. `cancel` does not interrupt a
            // thread parked in a blocking read, so a pump cancelled by `detach` used to run on
            // until the socket happened to fail — painting frames nobody was looking at, and
            // outliving the surfaces it was started for.
            while (coroutineContext.isActive) {
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
                // Cleared here, not only in `detach`. This is the field `attach` and `reconnect`
                // test to decide whether a stream is running, and leaving a finished job in it
                // said "yes" to both of them forever.
                //
                // Guarded on identity because a `detach` that has already cancelled this job may
                // have started nothing, while a `reconnect` racing it may have started a *new*
                // pump: this coroutine may only retire its own.
                if (pump === coroutineContext[Job]) pump = null
            }
        }
    }

    /**
     * Only ever called while [lock] is held: the answer is derived from [surfaces], and a size
     * published from a half-updated set of views would be a size the guest then actually took.
     */
    private fun publishWantedScreen() {
        // Previews are not opinions about the guest's screen; see [DesktopTransport.attach].
        wantedScreen.value = GuestScreenFit.of(
            surfaces.values.filterNot { it.preview }.map { it.screen },
        )
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
        // Each view letterboxes the same bitmap into its own size, so a thumbnail and a full window
        // are the same picture at two scales rather than two streams.
        for (output in surfaces.keys.toList()) {
            if (!output.isValid) continue
            val canvas: Canvas = runCatching { output.lockCanvas(null) }.getOrNull() ?: continue
            try {
                val destination = fit(rfb.width, rfb.height, canvas.width, canvas.height)
                canvas.drawColor(android.graphics.Color.BLACK)
                canvas.drawBitmap(target, null, destination, paint)
            } finally {
                runCatching { output.unlockCanvasAndPost(canvas) }
            }
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
