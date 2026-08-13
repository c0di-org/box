package dev.localagent.workstation.ui

import android.os.SystemClock
import android.view.MotionEvent
import dev.localagent.workstation.computer.GuestPointer
import kotlin.math.hypot

/**
 * Fingers on glass, turned into a mouse.
 *
 * Two surfaces want to be a trackpad — the guest's screen, and the middle of the on-screen keyboard
 * once you start dragging across it — and the second is only worth having if it feels like the
 * first. Not "similar": the same. Sensitivity, what counts as a tap, how long the double-tap window
 * stays open, which way two fingers scroll. A pointer that behaves one way on one half of the
 * screen and another way on the other half is a pointer you can't build a habit with, so there is
 * exactly one definition of what a finger does, and this is it.
 *
 * The API is per-pointer rather than per-[MotionEvent] because the keyboard's surface does not own
 * every finger on it — a thumb can be on a click button, or on Escape, while another hand drags —
 * so the caller decides which pointers belong here and hands over only those. The desktop hands
 * over all of them.
 *
 * Deltas are in view pixels and are converted with [Host.pointerScale], so a swipe of a given
 * length moves the cursor the same distance whichever surface it happened on, even though the two
 * are different widths.
 *
 * ### No click counts on the wire
 *
 * macOS needs to be told that a click is the second of a pair; X11 does not, and works out
 * double-clicks from the timing of the presses it receives, exactly as it does for a real mouse. So
 * a tap here is one press and one release and nothing else, and a double-tap is two of those close
 * together — which is the whole of what a mouse would have sent.
 */
internal class Trackpad(private val host: Host) {

    interface Host {
        /** Where the pointer is. The one authority; see [GuestPointer]. */
        val pointer: GuestPointer

        /** View pixels → guest pixels. */
        val pointerScale: Float

        val naturalScroll: Boolean get() = true

        /**
         * A click this recognizer decided on by itself — a tap, or the press that begins a
         * tap-and-hold drag. Buttons the user pressed deliberately don't come through here; the
         * surface that drew them already knows. For local feedback only.
         */
        fun onTrackpadClick(button: Int, down: Boolean) {}
    }

    private class Finger(var x: Float, var y: Float)

    /** Pointer id → where it was last seen. Insertion-ordered, so the first finger is first. */
    private val fingers = LinkedHashMap<Int, Finger>()

    /** The centroid of every finger, as of the last event. Motion is measured from it. */
    private var anchorX = 0f
    private var anchorY = 0f

    private var downAt = 0L
    private var travelled = 0f

    /**
     * The most fingers this gesture ever had at once — not how many are left at the end. Two
     * fingers rarely leave the glass on the same millisecond, and a right-click that depends on
     * them doing so is a right-click that mostly doesn't happen.
     */
    private var maxFingers = 0

    private var dragging = false
    private var lastTapUpAt = 0L

    /** Scroll distance not yet worth a notch. See [SCROLL_PX_PER_NOTCH]. */
    private var scrollCarry = 0f

    /** True when no finger is on the surface. */
    val idle: Boolean get() = fingers.isEmpty()

    fun owns(pointerId: Int) = fingers.containsKey(pointerId)

    fun down(pointerId: Int, x: Float, y: Float) {
        if (fingers.isEmpty()) {
            downAt = SystemClock.uptimeMillis()
            travelled = 0f
            maxFingers = 0
            scrollCarry = 0f
            // A tap immediately followed by a press starts a drag, the way a trackpad's
            // tap-and-hold does. It is the only way to drag at all with one finger and no button:
            // dragging a window's title bar, a selection across a terminal, a file onto a folder.
            if (downAt - lastTapUpAt < TAP_CHAIN_MS) {
                dragging = true
                host.pointer.press(GuestPointer.LEFT)
                host.onTrackpadClick(GuestPointer.LEFT, true)
            }
        }
        fingers[pointerId] = Finger(x, y)
        maxFingers = maxOf(maxFingers, fingers.size)
        reanchor()
    }

    /**
     * Pointers this recognizer doesn't own are ignored, so the caller can hand the whole event over
     * without filtering it first.
     */
    fun move(event: MotionEvent) {
        if (fingers.isEmpty()) return
        var moved = false
        for (i in 0 until event.pointerCount) {
            val finger = fingers[event.getPointerId(i)] ?: continue
            finger.x = event.getX(i)
            finger.y = event.getY(i)
            moved = true
        }
        if (!moved) return

        val previousX = anchorX
        val previousY = anchorY
        reanchor()
        val dx = anchorX - previousX
        val dy = anchorY - previousY
        if (dx == 0f && dy == 0f) return
        travelled += hypot(dx, dy)

        if (fingers.size >= 2) scroll(dy) else host.pointer.moveBy(dx * gain, dy * gain)
    }

    private val gain: Float get() = host.pointerScale * SENSITIVITY

    /**
     * Two fingers, into wheel notches.
     *
     * A wheel is discrete and a finger is not, so the leftovers are carried rather than dropped:
     * rounding each event's handful of pixels to zero on its own is how a slow, deliberate scroll
     * ends up doing nothing at all.
     *
     * Only the vertical axis. X11 does have horizontal wheel buttons, but the guest's Openbox
     * desktop has nothing that reads them, and a sideways twitch during a vertical scroll would be
     * the only thing they ever delivered.
     */
    private fun scroll(dy: Float) {
        scrollCarry += if (host.naturalScroll) dy else -dy
        val notches = (scrollCarry / SCROLL_PX_PER_NOTCH).toInt()
        if (notches == 0) return
        scrollCarry -= notches * SCROLL_PX_PER_NOTCH
        host.pointer.scroll(notches)
    }

    /**
     * @param cancelled the gesture is being taken away rather than finished, so whatever it was, it
     * wasn't a tap.
     */
    fun up(pointerId: Int, cancelled: Boolean = false) {
        if (fingers.remove(pointerId) == null) return
        if (fingers.isNotEmpty()) {
            // Re-anchor rather than measure across the gap: lifting one finger of a two-finger
            // scroll moves the centroid to where the remaining finger is, and that jump is not
            // motion anybody made.
            reanchor()
            return
        }

        val now = SystemClock.uptimeMillis()
        val tap = !cancelled && travelled < TAP_SLOP_PX && now - downAt < TAP_MS
        if (dragging) {
            host.pointer.release(GuestPointer.LEFT)
            host.onTrackpadClick(GuestPointer.LEFT, false)
            dragging = false
            // Released like a tap, it *was* one — the second of a double — so the window stays open
            // for a third.
            if (tap) lastTapUpAt = now
        } else if (tap) {
            val button = if (maxFingers >= 2) GuestPointer.RIGHT else GuestPointer.LEFT
            host.pointer.click(button)
            host.onTrackpadClick(button, true)
            lastTapUpAt = now
        }
        maxFingers = 0
    }

    /** Drop every finger and release anything held. Nothing here becomes a click. */
    fun cancel() {
        fingers.clear()
        if (dragging) {
            host.pointer.release(GuestPointer.LEFT)
            host.onTrackpadClick(GuestPointer.LEFT, false)
            dragging = false
        }
        maxFingers = 0
        lastTapUpAt = 0
    }

    private fun reanchor() {
        if (fingers.isEmpty()) return
        var x = 0f
        var y = 0f
        fingers.values.forEach {
            x += it.x
            y += it.y
        }
        anchorX = x / fingers.size
        anchorY = y / fingers.size
    }

    private companion object {
        const val SENSITIVITY = 1.4f

        /** How far a finger may travel and still have been a tap, in raw pixels. */
        const val TAP_SLOP_PX = 18f

        /** How long it may stay down and still have been a tap. */
        const val TAP_MS = 250L

        /** How long a tap keeps the door open for the next one — double-click, tap-drag. */
        const val TAP_CHAIN_MS = 300L

        /** How far two fingers travel per wheel notch, in view pixels. */
        const val SCROLL_PX_PER_NOTCH = 34f
    }
}
