package dev.localagent.workstation.ui

import android.content.Context
import android.graphics.Rect
import android.text.InputType
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
import dev.localagent.workstation.computer.GuestPointer
import dev.localagent.workstation.computer.GuestScreen
import dev.localagent.workstation.computer.Keysyms

/**
 * The surface the guest's screen is drawn on, and everything a keyboard and mouse do to it.
 *
 * A plain `SurfaceView` rather than Compose drawing: the frames come from a background thread that
 * owns a `Surface` directly, and handing that thread a Compose canvas would mean marshalling every
 * frame onto the main thread for no benefit.
 *
 * ### Two hands, one pointer
 *
 * A mouse and a finger want opposite things from this surface, and giving them the same treatment
 * gets one of them wrong.
 *
 * A mouse is **absolute**: it reports where it is, RFB pointer events say where the pointer is, and
 * the two agree for free. Hover is most of what a mouse does — it is what every menu and every
 * tooltip in the guest reacts to — and it arrives here with a coordinate already attached.
 *
 * A finger is **relative**, because a finger is not a pointer. Landing the cursor wherever the
 * fingertip touched down means the thing you are aiming at is underneath the thing you are aiming
 * with, there is no hover at all, and precision stops at the width of a fingertip — on a desktop
 * whose window controls and menu items were drawn for a mouse. So touch drives a [Trackpad]
 * instead: drag to move, tap to click, two fingers to right-click, two fingers to scroll,
 * tap-then-drag to drag. The cursor it steers lives in [GuestPointer], which converts the
 * accumulated deltas back into the absolute coordinate the protocol wants — so the guest is still
 * *told* where the pointer is on every event and can never disagree about it.
 *
 * Both hands move the same [GuestPointer], so switching between them mid-session is not a mode
 * change: the cursor is simply somewhere, and either one can pick it up from there.
 */
internal class DesktopView(context: Context) : SurfaceView(context), Trackpad.Host {

    /** Set by the composable. Null while nothing is streaming. */
    var onInput: ((DesktopInput) -> Unit)? = null
    var onSurfaceReady: ((android.view.Surface, Int, Int) -> Unit)? = null
    var onSurfaceGone: ((android.view.Surface) -> Unit)? = null

    /**
     * Where the cursor Box is steering has got to, in this view's pixels, or null when it is off
     * the picture. Only reported while a finger is what's driving — see [showCursor].
     */
    var onCursor: ((Float, Float, Boolean) -> Unit)? = null

    /** The pointer both this surface and the on-screen keyboard move. Assigned by the composable. */
    override var pointer: GuestPointer = GuestPointer {}
        set(value) {
            field = value
            value.screen = guestSize
            value.onMoved = { reportCursor() }
        }

    /** The guest's own resolution, needed to turn view pixels into guest pixels and back. */
    var guestSize: GuestScreen? = null
        set(value) {
            field = value
            pointer.screen = value
            reportCursor()
        }

    /** Whether input is delivered at all. The agent holds the desktop until the user takes it. */
    var interactive: Boolean = false
        set(value) {
            field = value
            isFocusable = value
            isFocusableInTouchMode = value
            if (value) {
                requestFocus()
            } else {
                // The agent is taking the desktop back. A finger mid-drag at that moment would
                // otherwise leave a button down inside the guest that nothing is ever going to
                // lift, and the agent would inherit a machine with the mouse held on something.
                trackpad.cancel()
                pointer.releaseAll()
                touching = false
            }
            reportCursor()
        }

    private val trackpad = Trackpad(this)

    /**
     * True from the first finger to touch this surface until a mouse moves over it.
     *
     * What it decides is whether Box draws a cursor of its own. A mouse already has one — Android's,
     * under the hand, with no latency — and a second arrow an inch away from it is just confusing.
     * A finger has none, and a relative pad you cannot see the pointer of is unusable.
     */
    private var touching = false
        set(value) {
            if (field == value) return
            field = value
            reportCursor()
        }

    override val pointerScale: Float
        get() {
            val guest = guestSize ?: return 1f
            val picture = picture() ?: return 1f
            return guest.width.toFloat() / picture.width()
        }

    /** Whether Box is responsible for drawing the pointer. See [touching]. */
    val showCursor: Boolean get() = interactive && touching

    /**
     * Say again how big this surface is, without waiting for it to change.
     *
     * There is a second thing that decides how much of the window the desktop really has — the
     * on-screen keyboard, and the soft keyboard behind it — and neither arrives through
     * `surfaceChanged`. The caller that knows about them needs a way to re-state the size when only
     * its half moved.
     */
    fun reportSize() {
        val surface = holder.surface
        if (!surface.isValid || width <= 0 || height <= 0) return
        onSurfaceReady?.invoke(surface, width, height)
    }

    init {
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = Unit

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                onSurfaceReady?.invoke(holder.surface, width, height)
                reportCursor()
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                onSurfaceGone?.invoke(holder.surface)
            }
        })
        // Keep Android's own pointer. This used to be TYPE_NULL, on the reasoning that the guest
        // draws the only cursor worth showing -- but the guest's cursor never arrived, so hiding
        // this one left the user with none at all, and no way to tell that the machine was even
        // receiving them. See guest/xorg.conf.d/10-virtio-cursor.conf for that half.
        //
        // Two pointers once both halves work, and that is the right answer rather than a
        // compromise. This one tracks the hand with no latency; the guest's is however far behind
        // the VM currently is. The gap between them is the only honest feedback available about
        // how far behind the machine is running, and a single remote cursor hides exactly that --
        // input that lands late reads as input that was dropped.
        pointerIcon = PointerIcon.getSystemIcon(context, PointerIcon.TYPE_ARROW)
    }

    // ---- pointer -----------------------------------------------------------

    /**
     * Movement with no button held, and the wheel. Both are a mouse's alone: a finger cannot hover,
     * and this is where the absolute path lives.
     */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!interactive) return false
        return when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_ENTER -> {
                // A mouse has arrived, so Box's cursor stands down in favour of Android's.
                touching = false
                moveToTouch(event)
                true
            }

            MotionEvent.ACTION_SCROLL -> {
                moveToTouch(event)
                val vertical = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                if (vertical != 0f) pointer.scroll(Math.round(vertical))
                true
            }

            else -> super.onGenericMotionEvent(event)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!interactive) return false
        // A mouse's button press arrives through here too, and it already knows where it is. Only
        // fingers go to the pad.
        if (event.getToolType(event.actionIndex) != MotionEvent.TOOL_TYPE_FINGER) {
            return mouseTouch(event)
        }
        touching = true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestFocus()
                trackpad.down(event.getPointerId(0), event.x, event.y)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                trackpad.down(event.getPointerId(index), event.getX(index), event.getY(index))
            }

            MotionEvent.ACTION_MOVE -> trackpad.move(event)

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                trackpad.up(event.getPointerId(event.actionIndex))

            MotionEvent.ACTION_CANCEL -> trackpad.cancel()

            else -> return super.onTouchEvent(event)
        }
        return true
    }

    /** A real mouse, pressing its own buttons at its own coordinate. */
    private fun mouseTouch(event: MotionEvent): Boolean {
        touching = false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> {
                moveToTouch(event)
                setMouseButtons(event)
                if (event.actionMasked == MotionEvent.ACTION_DOWN) requestFocus()
            }

            else -> return super.onTouchEvent(event)
        }
        return true
    }

    /**
     * A real mouse reports which buttons are held. On the way up it reports none, which is what
     * releases whatever was down.
     */
    private fun setMouseButtons(event: MotionEvent) {
        val state = if (
            event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            0
        } else {
            event.buttonState
        }
        var wanted = 0
        if (state and MotionEvent.BUTTON_PRIMARY != 0) wanted = wanted or GuestPointer.LEFT
        if (state and MotionEvent.BUTTON_TERTIARY != 0) wanted = wanted or GuestPointer.MIDDLE
        if (state and MotionEvent.BUTTON_SECONDARY != 0) wanted = wanted or GuestPointer.RIGHT
        val held = pointer.buttons
        if (wanted == held) return
        pointer.release(held and wanted.inv())
        pointer.press(wanted and held.inv())
    }

    private fun moveToTouch(event: MotionEvent) {
        val guest = toGuest(event.x, event.y) ?: return
        pointer.moveTo(guest.first, guest.second)
    }

    /**
     * View pixels to guest pixels, through the same letterbox the renderer draws into.
     *
     * Returns null outside the picture — the black bars are not part of the guest's screen, and
     * clamping there would park the cursor on an edge instead of leaving it where it was.
     */
    private fun toGuest(x: Float, y: Float): Pair<Float, Float>? {
        val guest = guestSize ?: return null
        val picture = picture() ?: return null
        if (!picture.contains(x.toInt(), y.toInt())) return null
        val scale = guest.width.toFloat() / picture.width()
        return Pair((x - picture.left) * scale, (y - picture.top) * scale)
    }

    private fun picture(): Rect? {
        val guest = guestSize ?: return null
        if (width == 0 || height == 0) return null
        return fit(guest.width, guest.height, width, height)
    }

    /**
     * The other direction, so the cursor Box steers can be drawn where the guest will draw it.
     *
     * Reported with its own visibility rather than being withheld, so the composable above always
     * has a last known position to fade out from instead of a null that snaps the arrow to a corner.
     */
    private fun reportCursor() {
        val listener = onCursor ?: return
        val guest = guestSize
        val picture = picture()
        if (guest == null || picture == null) {
            listener(0f, 0f, false)
            return
        }
        val scale = picture.width().toFloat() / guest.width
        listener(
            picture.left + pointer.guestX * scale,
            picture.top + pointer.guestY * scale,
            showCursor,
        )
    }

    // ---- keyboard ----------------------------------------------------------

    /**
     * There is a keyboard, and it is a picture of one.
     *
     * A `SurfaceView` is not an editor, so Android has no reason to raise the IME over it. Claiming
     * to be a text editor and handing back a plain [BaseInputConnection] is what makes soft
     * keyboards deliver key events here; they arrive at [onKeyDown] like any hardware key and take
     * the same path to the guest.
     *
     * Box's own [GuestKeyboardView] is what a phone gets by default, and it does not come through
     * here at all — it sends keysyms straight out, because it knows what it drew. This route is kept
     * for the person who would rather use their own IME, and for anything that commits whole strings.
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

    /** Raise the system soft keyboard against this view, for the user who asked for theirs. */
    fun showSystemKeyboard() {
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
