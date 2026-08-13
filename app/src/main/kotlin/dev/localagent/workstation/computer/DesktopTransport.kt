package dev.localagent.workstation.computer

import android.view.Surface
import kotlinx.coroutines.flow.StateFlow

/**
 * What the UI needs from the runtime layer to show the agent's live desktop.
 *
 * Nothing implements this yet — the display transport does not exist. It is written down here,
 * in app code, so the pane has a real shape to slot into and so the runtime side has a target:
 * frames are handed off through an Android [Surface] rather than streamed as bitmaps, because
 * copying 60 fps of ARGB across a process boundary would cost more than the VM does.
 *
 * The control split is the part that matters for the product. The agent drives the desktop by
 * default; the user "takes over" and input from the guest agent is suspended until they hand it
 * back. That is a runtime-enforced mode, not a UI convention, so it lives in this interface.
 */
interface DesktopTransport {
    val state: StateFlow<DesktopState>

    /**
     * The screen size the guest ought to have, from whichever attached view is largest.
     *
     * Reported rather than acted on, because the transport cannot act on it: the guest is resized
     * from inside itself, over a channel that only `:computer` holds. This is the one thing that
     * knows how big every view of the desktop is, so it is where the question is answered; who
     * carries the answer across the process boundary is [dev.localagent.workstation.BoxViewModel].
     *
     * Null until a view large enough to be worth resizing for has attached. See [GuestScreenFit].
     */
    val wantedGuestScreen: StateFlow<GuestScreen?>

    /**
     * Renders guest output into [surface] until [detach].
     *
     * More than one surface may be attached at once, and they all show the same screen. That is not
     * a luxury: the box's header on the home column, the inline pane and the full window are three
     * views of one machine, and on a Fold two of them are on screen together. A transport that held
     * a single surface would have them stealing the picture from each other.
     *
     * @param preview a view that is only ever looked at, and must not be allowed to decide how big
     * the guest's screen is. The minimap in the box's header is one: it is a real surface at a real
     * size, and on a phone it is comfortably larger than a desktop pane on a small window — so
     * counting it would resize the guest's display every time the user walked back to their tasks.
     * [GuestScreenFit] never sees these. See [wantedGuestScreen].
     */
    suspend fun attach(surface: Surface, widthPx: Int, heightPx: Int, preview: Boolean = false)

    /** Stops drawing into [surface]. The stream ends when the last surface has gone. */
    suspend fun detach(surface: Surface)

    /** Only delivered while the user holds control. */
    suspend fun send(input: DesktopInput)

    suspend fun setControl(holder: ControlHolder)
}

sealed interface DesktopState {
    data object Unavailable : DesktopState
    data object Starting : DesktopState
    data class Live(val widthPx: Int, val heightPx: Int, val control: ControlHolder) : DesktopState
    data class Failed(val message: String) : DesktopState
}

enum class ControlHolder { Agent, User }

/**
 * One input the guest can be told about, in guest pixels.
 *
 * This started as `Tap`, `Drag`, `Scroll`, `Text`, `Key` — gestures, written when the desktop was
 * assumed to be something you poke at through a phone screen. A real mouse cannot be described that
 * way. Most of what a mouse does is *move with no button held*: hover states, menus opening on
 * pointer-enter, drags that begin only once a threshold is crossed. A `Tap` is already two pointer
 * events by the time it exists, and a gesture vocabulary has no way to say "the cursor is here and
 * nothing is pressed", which is the majority of what X11 wants to hear about.
 *
 * So position and button state are reported as they are, and anything higher-level is the guest's
 * to infer — which is what it does anyway.
 */
sealed interface DesktopInput {
    /** Where the pointer is, and what is held: bit 0 left, bit 1 middle, bit 2 right. */
    data class Pointer(val x: Int, val y: Int, val buttons: Int) : DesktopInput

    /** Wheel notches, positive away from the user. Sent as button presses, which is how RFB says it. */
    data class Scroll(val x: Int, val y: Int, val notches: Int) : DesktopInput

    /**
     * One key transition, as an X11 keysym rather than an Android key code.
     *
     * The translation happens where the `KeyEvent` still exists, because that is the only place
     * that knows which character the key produced under the modifiers actually held — a keyboard
     * layout is not recoverable from a key code further down.
     */
    data class Key(val keysym: Int, val down: Boolean) : DesktopInput

    /** A run of text, for paste and for soft keyboards that commit whole strings. */
    data class Text(val value: String) : DesktopInput
}
