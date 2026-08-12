package dev.localagent.workstation.ui

import android.content.Context
import android.graphics.Rect
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import dev.localagent.workstation.computer.DesktopInput
import dev.localagent.workstation.computer.Keysyms

/**
 * The surface the guest's screen is drawn on, and everything a keyboard and mouse do to it.
 *
 * A plain `SurfaceView` rather than Compose drawing: the frames come from a background thread that
 * owns a `Surface` directly, and handing that thread a Compose canvas would mean marshalling every
 * frame onto the main thread for no benefit.
 *
 * ### Absolute pointer, not captured pointer
 *
 * Android offers pointer capture, which yields relative deltas and hides the system cursor — the
 * obvious choice for a remote desktop, and the wrong one here. RFB pointer events are *absolute*,
 * and the guest's X server draws its own cursor, so relative deltas would have to be integrated
 * into a position that then drifts from the guest's idea of it. Reporting the real coordinate every
 * time means the two can never disagree.
 *
 * The system cursor is hidden over this view instead, so there is one arrow on screen rather than
 * two: the guest's.
 */
class DesktopView(context: Context) : SurfaceView(context) {

    /** Set by the composable. Null while nothing is streaming. */
    var onInput: ((DesktopInput) -> Unit)? = null
    var onSurfaceReady: ((android.view.Surface, Int, Int) -> Unit)? = null
    var onSurfaceGone: ((android.view.Surface) -> Unit)? = null

    /** The guest's own resolution, needed to turn a touch into a guest coordinate. */
    var guestSize: Pair<Int, Int>? = null

    /** Whether input is delivered at all. The agent holds the desktop until the user takes it. */
    var interactive: Boolean = false
        set(value) {
            field = value
            isFocusable = value
            isFocusableInTouchMode = value
            if (value) requestFocus()
        }

    private var buttons = 0

    init {
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = Unit

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                onSurfaceReady?.invoke(holder.surface, width, height)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                onSurfaceGone?.invoke(holder.surface)
            }
        })
        // One cursor on screen, drawn by the guest.
        pointerIcon = PointerIcon.getSystemIcon(context, PointerIcon.TYPE_NULL)
    }

    // ---- pointer -----------------------------------------------------------

    /**
     * Movement with no button held.
     *
     * This is the event that made the old gesture-shaped input model unusable: hover is most of
     * what a mouse does, and it is what every menu and every tooltip in the guest reacts to.
     */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!interactive) return false
        return when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_ENTER -> {
                sendPointer(event)
                true
            }

            MotionEvent.ACTION_SCROLL -> {
                val guest = toGuest(event.x, event.y) ?: return false
                val vertical = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                if (vertical != 0f) {
                    onInput?.invoke(
                        DesktopInput.Scroll(guest.first, guest.second, Math.round(vertical)),
                    )
                }
                true
            }

            else -> super.onGenericMotionEvent(event)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!interactive) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> {
                buttons = mouseButtons(event)
                sendPointer(event)
                trackLongPress(event)
                if (event.actionMasked == MotionEvent.ACTION_DOWN) requestFocus()
            }

            else -> return super.onTouchEvent(event)
        }
        return true
    }

    /**
     * Long press is right click.
     *
     * Not a nicety: the guest runs Openbox, where the root menu — the only way to launch anything
     * from the desktop itself — opens on button 3. A phone has no button 3, so without this the
     * desktop is a machine you can look at and click on and never start a program from.
     *
     * Fingers only. A real mouse under DeX already has the button, and a held left button there is
     * a drag, which this would break.
     */
    private fun trackLongPress(event: MotionEvent) {
        if (event.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER) return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressOrigin = event.x to event.y
                postDelayed(rightClick, android.view.ViewConfiguration.getLongPressTimeout().toLong())
            }

            MotionEvent.ACTION_MOVE -> {
                val (x, y) = pressOrigin ?: return
                val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
                if (kotlin.math.hypot(event.x - x, event.y - y) > slop) cancelLongPress()
            }

            else -> cancelLongPress()
        }
    }

    override fun cancelLongPress() {
        super.cancelLongPress()
        removeCallbacks(rightClick)
        pressOrigin = null
    }

    private var pressOrigin: Pair<Float, Float>? = null

    private val rightClick = Runnable {
        val (x, y) = pressOrigin ?: return@Runnable
        val guest = toGuest(x, y) ?: return@Runnable
        pressOrigin = null
        // The finger already pressed the left button on the way down. Let it go before the right
        // one, or the guest sees both held and Openbox opens nothing.
        buttons = 0
        onInput?.invoke(DesktopInput.Pointer(guest.first, guest.second, 0))
        onInput?.invoke(DesktopInput.Pointer(guest.first, guest.second, RIGHT))
        onInput?.invoke(DesktopInput.Pointer(guest.first, guest.second, 0))
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    /**
     * A real mouse reports which buttons are held; a finger reports none.
     *
     * `buttonState` is 0 for touch, so a tap would press nothing at all and the guest would see the
     * cursor move without a click. Treating a touch as the left button is what makes the desktop
     * usable on the phone screen as well as under DeX.
     */
    private fun mouseButtons(event: MotionEvent): Int {
        if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            return 0
        }
        val state = event.buttonState
        if (state == 0) return LEFT
        var result = 0
        if (state and MotionEvent.BUTTON_PRIMARY != 0) result = result or LEFT
        if (state and MotionEvent.BUTTON_TERTIARY != 0) result = result or MIDDLE
        if (state and MotionEvent.BUTTON_SECONDARY != 0) result = result or RIGHT
        return result
    }

    private fun sendPointer(event: MotionEvent) {
        val guest = toGuest(event.x, event.y) ?: return
        onInput?.invoke(DesktopInput.Pointer(guest.first, guest.second, buttons))
    }

    /**
     * View pixels to guest pixels, through the same letterbox the renderer draws into.
     *
     * Returns null outside the picture — the black bars are not part of the guest's screen, and
     * clamping there would park the cursor on an edge instead of leaving it where it was.
     */
    private fun toGuest(x: Float, y: Float): Pair<Int, Int>? {
        val (guestWidth, guestHeight) = guestSize ?: return null
        if (width == 0 || height == 0) return null
        val picture = fit(guestWidth, guestHeight, width, height)
        if (!picture.contains(x.toInt(), y.toInt())) return null
        val scale = guestWidth.toFloat() / picture.width()
        return Pair(
            ((x - picture.left) * scale).toInt().coerceIn(0, guestWidth - 1),
            ((y - picture.top) * scale).toInt().coerceIn(0, guestHeight - 1),
        )
    }

    // ---- keyboard ----------------------------------------------------------

    /**
     * There is a keyboard, and it is a picture of one.
     *
     * A `SurfaceView` is not an editor, so Android has no reason to raise the IME over it and a
     * phone would otherwise have no way to type into the guest at all — the desktop would be a
     * machine you can only point at. Claiming to be a text editor and handing back a plain
     * [BaseInputConnection] is what makes soft keyboards deliver key events here; they arrive at
     * [onKeyDown] like any hardware key and take the same path to the guest.
     *
     * `fullscreen = false` matters: a keyboard in extract mode would cover the screen it is typing
     * into with its own text box.
     */
    override fun onCheckIsTextEditor(): Boolean = interactive

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_NULL
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_ACTION_NONE
        return BaseInputConnection(this, false)
    }

    /** Raise the soft keyboard against this view. The button in the computer's menu. */
    fun showKeyboard() {
        interactive = true
        requestFocus()
        val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        manager?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = key(event, down = true)
        ?: super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = key(event, down = false)
        ?: super.onKeyUp(keyCode, event)

    /**
     * Null means "not ours" — let Android have it.
     *
     * Back and the volume keys are deliberately never forwarded. A desktop that swallows Back is a
     * desktop the user cannot leave, and on a foldable that is the only way out of a full-screen
     * view.
     */
    private fun key(event: KeyEvent, down: Boolean): Boolean? {
        if (!interactive) return null
        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_HOME,
            -> return null
        }
        val keysym = Keysyms.of(event.keyCode, event.unicodeChar) ?: return null
        onInput?.invoke(DesktopInput.Key(keysym, down))
        return true
    }

    /** Hardware keyboards in DeX deliver whole strings for some input methods. */
    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean {
        if (!interactive) return super.onKeyMultiple(keyCode, repeatCount, event)
        val characters = event.characters ?: return super.onKeyMultiple(keyCode, repeatCount, event)
        onInput?.invoke(DesktopInput.Text(characters))
        return true
    }

    private companion object {
        const val LEFT = 1
        const val MIDDLE = 1 shl 1
        const val RIGHT = 1 shl 2
    }
}

/** Shared with the renderer so input and pixels agree on where the picture is. */
internal fun fit(sourceWidth: Int, sourceHeight: Int, intoWidth: Int, intoHeight: Int): Rect {
    val scale = minOf(intoWidth.toFloat() / sourceWidth, intoHeight.toFloat() / sourceHeight)
    val width = (sourceWidth * scale).toInt()
    val height = (sourceHeight * scale).toInt()
    val left = (intoWidth - width) / 2
    val top = (intoHeight - height) / 2
    return Rect(left, top, left + width, top + height)
}
