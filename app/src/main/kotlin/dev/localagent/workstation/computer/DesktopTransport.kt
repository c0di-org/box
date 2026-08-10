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

    /** Renders guest output into [surface] until [detach]. Resizes trigger a guest mode change. */
    suspend fun attach(surface: Surface, widthPx: Int, heightPx: Int)

    suspend fun detach()

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

sealed interface DesktopInput {
    data class Tap(val x: Float, val y: Float) : DesktopInput
    data class Drag(val fromX: Float, val fromY: Float, val toX: Float, val toY: Float) : DesktopInput
    data class Scroll(val x: Float, val y: Float, val deltaY: Float) : DesktopInput
    data class Text(val value: String) : DesktopInput
    data class Key(val keyCode: Int, val down: Boolean) : DesktopInput
}

/**
 * What the UI needs to show a live preview of something the agent is serving in the guest.
 *
 * Also unimplemented: port forwarding currently throws. The contract Box wants is a loopback URL
 * a WebView can load, plus an explicit release so a forwarded port never outlives the session
 * that asked for it.
 */
interface PreviewTransport {
    suspend fun forward(guestPort: Int): Result<String>
    suspend fun release(guestPort: Int)
}
