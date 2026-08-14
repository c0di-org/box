package dev.localagent.workstation.computer

/**
 * Where the pointer is, in guest pixels, and the only thing allowed to say so.
 *
 * RFB has no relative mode: every pointer message carries an absolute coordinate and the guest
 * warps its cursor to whatever arrives. A trackpad is relative by definition, so somebody has to
 * hold the integral of the deltas — and exactly one somebody, because three surfaces send motion at
 * the same machine: touch on the desktop, touch on the keyboard once it has become a trackpad, and
 * a real mouse under DeX.
 *
 * Integrating deltas into an absolute position sounds like the drift trap [DesktopView] warns
 * about and is the opposite: drift needs two authorities, one guessing what the other did. Here
 * the guest has no opinion to drift from — it is told, every time.
 *
 * Sub-pixel state is kept in floats. A slow drag on a panel showing the guest at roughly 1:1
 * produces deltas well under a pixel, and rounding each on its own throws all of them away, which
 * reads to the hand doing it as a pointer with a dead zone.
 */
internal class GuestPointer(private val send: (DesktopInput) -> Unit) {

    /** The guest's own resolution. Null while nothing is streaming; motion is dropped until then. */
    var screen: GuestScreen? = null
        set(value) {
            if (field == value) return
            field = value
            if (value == null) return
            // A resize would otherwise leave the cursor parked off the edge of the new screen, and
            // the first tap after it would land somewhere nobody aimed at. Centre on the first
            // screen there is, and clamp to every one after.
            //
            // Once, and tracked with a flag rather than by whether there was a previous screen: the
            // surface re-states its size on every recomposition, and "no screen yet" is a state it
            // passes back through constantly. Read that as a first screen and the cursor snaps to
            // the middle every time anything on the pane redraws.
            if (placed) {
                clamp()
            } else {
                placed = true
                x = value.width / 2f
                y = value.height / 2f
            }
        }

    /** Whether the cursor has ever been anywhere. See the setter above. */
    private var placed = false

    private var x = 0f
    private var y = 0f

    /** Which buttons are held: bit 0 left, bit 1 middle, bit 2 right. */
    var buttons = 0
        private set

    /** Rounded, for anything that has to draw the cursor Box is steering. */
    val guestX: Int get() = x.toInt()
    val guestY: Int get() = y.toInt()

    /**
     * Called after anything moves the pointer, so a local cursor can be repainted without polling.
     * Set by the surface that draws one.
     */
    var onMoved: (() -> Unit)? = null

    /** A real mouse, reporting where it is. Nothing to integrate; the position *is* the message. */
    fun moveTo(guestX: Float, guestY: Float) {
        val size = screen ?: return
        x = guestX.coerceIn(0f, size.width - 1f)
        y = guestY.coerceIn(0f, size.height - 1f)
        report()
    }

    /** A finger, reporting how far it went. Already in guest pixels — see `Trackpad.pointerScale`. */
    fun moveBy(dx: Float, dy: Float) {
        if (screen == null) return
        x += dx
        y += dy
        clamp()
        report()
    }

    fun press(button: Int) {
        if (button == 0) return
        buttons = buttons or button
        report()
    }

    fun release(button: Int) {
        if (button == 0) return
        buttons = buttons and button.inv()
        report()
    }

    /**
     * A whole click, both edges, without waiting for a frame between them.
     *
     * The two messages have to carry the same coordinate or the guest sees a press, a move and a
     * release, which is a drag of zero length to anything watching for one — and Openbox's menus
     * watch for one.
     */
    fun click(button: Int) {
        press(button)
        release(button)
    }

    /** Let go of everything. The surface going away must not leave a button down inside the guest. */
    fun releaseAll() {
        if (buttons == 0) return
        buttons = 0
        report()
    }

    fun scroll(notches: Int) {
        if (screen == null || notches == 0) return
        send(DesktopInput.Scroll(guestX, guestY, notches))
    }

    private fun clamp() {
        val size = screen ?: return
        x = x.coerceIn(0f, size.width - 1f)
        y = y.coerceIn(0f, size.height - 1f)
    }

    private fun report() {
        if (screen == null) return
        send(DesktopInput.Pointer(guestX, guestY, buttons))
        onMoved?.invoke()
    }

    companion object {
        const val LEFT = 1
        const val MIDDLE = 1 shl 1
        const val RIGHT = 1 shl 2
    }
}
