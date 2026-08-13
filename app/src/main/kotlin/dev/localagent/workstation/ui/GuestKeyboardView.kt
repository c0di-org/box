package dev.localagent.workstation.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import dev.localagent.workstation.computer.GuestPointer
import dev.localagent.workstation.computer.Key
import dev.localagent.workstation.computer.KeyAction
import dev.localagent.workstation.computer.KeyboardLayout
import dev.localagent.workstation.computer.Layout
import dev.localagent.workstation.computer.Mod
import kotlin.math.ceil
import kotlin.math.hypot

/**
 * The on-screen keyboard: a single custom view that draws every key and turns taps into X11
 * keysyms.
 *
 * It exists because no stock IME can drive a Linux desktop. Gboard will happily type prose, but it
 * has no Control, no Alt, no Super, no Escape, no function row and no reliable down/up pairing —
 * and `Ctrl+C` in the guest's terminal matters here far more than autocorrect does. Worse, the
 * route it reaches Box by is a lie told on purpose: [DesktopView] claims to be a text editor so
 * that Android will raise a keyboard over a `SurfaceView` at all. That trick is kept for the case
 * where somebody prefers their own IME; this is what a phone with no keyboard attached gets.
 *
 * Modifiers **latch**: one tap arms a modifier for the next key, a second tap within
 * [DOUBLE_TAP_MS] locks it until tapped again. Without that, no two-key shortcut is reachable with
 * one finger. They also **hold**: a finger resting on Shift while another taps letters behaves
 * exactly like a physical Shift, and lifting it ends the hold. The two gestures need no mode switch
 * — a modifier that was chorded with during its hold was a hold, one that wasn't was a tap and
 * stays armed. Caps Lock is the exception: a lock that un-latches after one letter is just a Shift
 * with the wrong label, so it only ever toggles between locked and off.
 *
 * The two pointer keys do **not** latch — they're held for exactly as long as the finger is, so
 * holding one and dragging on the desktop above is a click-and-drag.
 *
 * ## It is also a trackpad, when you treat it like one
 *
 * Land a finger in the middle of the keys and sweep, and the middle of the keyboard becomes a
 * [KeyboardTrackpad] under your finger and stays for a few seconds after you lift. No button, no
 * mode, no layout given up for it — see that class for why it's an overlay and what happens once
 * it's up.
 *
 * The cost is paid here, and it is worth being honest about it. Telling a keystroke apart from the
 * start of a sweep is not possible at the instant of the touch-down: they are the same event. So a
 * press that lands in the arming zone is **staged** — it flashes, pops its bubble and ticks
 * immediately, exactly as any other key, but the keysym itself waits for the verdict, which arrives
 * at whichever of these comes first: the finger travels far enough to be a sweep (no key was ever
 * typed), the finger lifts (it was a tap, send it now), another finger presses anything (a roll —
 * send it now, the hands have moved on), or [STAGE_HOLD_MS] passes with the finger sitting still
 * (it's being held, send it now and let it repeat). Sitting still, not merely late: a finger that is
 * creeping across the glass is being aimed, and how fast it happens to be going is not the question.
 *
 * For a tap — which is what typing is made of — the delay is the finger's own dwell time and
 * nothing more. Even that would be a real cost on the home row, which is why staging switches
 * itself off during [TYPING_QUIET_MS] after any keystroke: mid-word, a touch in the middle of a
 * keyboard is a letter, and a pointer gesture starts from rest. Typing bursts pay nothing at all,
 * and only the first key after a pause is ever staged.
 *
 * ## Feedback is local, and deliberately so
 *
 * The guest is fully emulated — TCG, two cores — and its answer to a keystroke comes back through
 * an X server, QEMU's VNC encoder and a socket, well behind the finger. A physical keyboard gets
 * away with that because the key mechanism answers the fingertip at zero. This one has to fake the
 * same thing: every press flashes, pops a preview bubble clear of the finger, and ticks, all before
 * the keysym leaves this thread. That is what the keyboard feeling responsive is actually made of,
 * and on this machine the wire was never the slow part.
 */
@SuppressLint("ViewConstructor")
internal class GuestKeyboardView(
    context: Context,
    private val prefs: KeyboardPrefs,
) : View(context), Trackpad.Host {

    /** Where the pointer is. Shared with the desktop above, so the two agree about the cursor. */
    override var pointer: GuestPointer = GuestPointer {}

    /** One key transition, as a keysym. Null while nothing is streaming. */
    var onKey: ((Int, Boolean) -> Unit)? = null

    /**
     * View pixels → guest pixels, borrowed from the desktop pane rather than derived here, so that
     * a swipe of a given length moves the cursor by the same amount on both surfaces. The two are
     * different widths, and a pointer that changes gear depending on which half of the screen you
     * touched is one you can't aim.
     */
    var desktopPointerScale: () -> Float = { 1f }

    override val pointerScale: Float get() = desktopPointerScale()

    private enum class Latch { ARMED, LOCKED }

    /**
     * One key, described twice. [bounds] is the rounded rectangle we draw; [cell] is the whole
     * tile, gaps included, and is what we hit-test.
     *
     * The difference is the entire point. Hit-testing the drawn rectangle leaves a strip of dead
     * mortar between every pair of keys where a tap matches nothing at all — no key, no tick, no
     * pixel moving — which is indistinguishable, from the outside, from a keyboard that has stopped
     * working.
     */
    private class Placed(val key: Key, val bounds: RectF, val cell: RectF)

    private var functionRow = prefs.functionRow
    private var split = prefs.split

    /** The shape the settings ask for. The band above is sized from this one. */
    private var natural = KeyboardLayout.natural(functionRow, split)

    /** The shape that fits the band we were actually given — see [reshape]. */
    private var layout = natural
    private var placed: List<Placed> = emptyList()

    /** False until the first layout, so arriving already-split isn't announced as a change. */
    private var shaped = false

    /** Modifier bit → latch state. Absent means off. */
    private val latch = HashMap<Int, Latch>()

    /** Modifier bit → the keysym we sent the key-down for, so the up matches. */
    private val heldModifierKey = HashMap<Int, Int>()

    private var lastModifierTapBit = 0
    private var lastModifierTapAt = 0L

    /**
     * One non-modifier key, held by one finger.
     *
     * [keysym] is resolved at the moment of the down, and the up replays it. Resolving again at the
     * up instead, the pair can disagree — a two-finger roll releases one key, that spends the armed
     * Shift, and the still-held key's eventual up then releases `a` where its down pressed `A`,
     * which leaves the guest holding a key nothing will ever let go of.
     *
     * [finished] marks a press whose finger slid off the key: its up has already been sent and
     * nothing further may come of it but the lift of the finger.
     */
    private class Press(val placed: Placed, val keysym: Int) {
        var finished = false
    }

    /** Pointer id → the non-modifier key that pointer is holding down. */
    private val pressed = HashMap<Int, Press>()

    /**
     * A press in the trackpad zone that hasn't been sent yet, and everything the eventual verdict
     * needs: where the finger landed, so travel can be measured from it and the pad can be anchored
     * back there, and the keysym it would have gone out with, so committing it late is
     * indistinguishable from having sent it early.
     *
     * [placed] is null in the gutter of a split keyboard, where the touch hit no key at all.
     * Nothing to commit, so nothing to time out either: a thumb can rest there and start a sweep
     * whenever it likes.
     */
    private class Staged(
        val pointerId: Int,
        val placed: Placed?,
        val keysym: Int,
        val x: Float,
        val y: Float,
    ) {
        /** Where the finger is now, and how far it had come at the last verdict. */
        var lastX = x
        var lastY = y
        var checked = 0f

        val travel: Float get() = hypot(lastX - x, lastY - y)
    }

    private var staged: Staged? = null

    /** When the last keysym went out, so a burst of typing can turn staging off. */
    private var lastKeyAt = 0L

    private val pad = KeyboardTrackpad(this)

    /** Pointer id → the modifier bit that finger is resting on. */
    private val modifierHolds = HashMap<Int, Int>()

    /** Modifier bits that were chorded with while a finger held them — holds, not taps. */
    private val holdChorded = HashSet<Int>()

    /**
     * Key → the moment it may stop looking pressed.
     *
     * A tap at typing speed holds the glass for 30–50 ms. Painting the pressed state on touch-down
     * and clearing it on touch-up means, at 60 Hz, that it is shown for one frame or for none —
     * which is why fast taps look dead while slow ones look fine. Every press is held lit for
     * [MIN_LIT_MS] whatever the finger does.
     */
    private val litUntil = HashMap<Placed, Long>()

    private val ui = Handler(Looper.getMainLooper())

    /** Pointer id → its running repeat loop. Repeat belongs to a finger, not to the view. */
    private val repeaters = HashMap<Int, Runnable>()

    private val feedback = Feedback(this)

    /** Half the gap between neighbouring keys. */
    private val gap = 2.5f * resources.displayMetrics.density

    private var unit = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * resources.displayMetrics.density
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT
    }

    init {
        // Must never take focus: [DesktopView] needs it for physical key events from a keyboard
        // attached under DeX. A focusable key here silently kills them.
        isFocusable = false
        isFocusableInTouchMode = false
        // So one hand's key doesn't block the other hand's.
        isHapticFeedbackEnabled = true
        setBackgroundColor(BACKDROP)
    }

    /** Re-read settings that change the shape of the keyboard. */
    fun reloadLayout() {
        if (prefs.functionRow == functionRow && prefs.split == split) return
        pad.releaseAll()
        functionRow = prefs.functionRow
        split = prefs.split
        natural = KeyboardLayout.natural(functionRow, split)
        layout = natural
        layoutKeys()
        invalidate()
    }

    // MARK: - Geometry

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutKeys()
    }

    /** Unit key width for a given view width. Every key is a multiple of it. */
    private fun unitFor(width: Int) = width / layout.units

    /**
     * The strip this keyboard needs above its top row for preview bubbles, in key-widths — and zero
     * for most of them.
     *
     * A bubble is drawn directly over whatever is above the key, and every row but the first has a
     * row of keys up there to cover. So the strip is only owed to the *top* row, and only when the
     * top row pops bubbles at all: [Key.previews] is single-glyph keys, which the function row's
     * F1–F12 are not. Reserved unconditionally it is a band of black above the keys that nothing
     * can ever draw in, in the configuration that ships — and the guest's screen is what pays.
     */
    private val Layout.headroom: Float
        get() = if (rows.firstOrNull()?.any { it.key.previews } == true) PREVIEW_HEADROOM else 0f

    /**
     * The height a keyboard of this key size needs, preview headroom included. Rounded up, because
     * a band one pixel short of what the keys asked for is a band they don't fit in — and being
     * handed back our own answer is the commonest case there is.
     */
    private fun heightFor(unit: Float, aspect: Float) =
        ceil(natural.rows.size * unit * aspect + unit * natural.headroom).toInt()

    /** The largest key a band of this height can carry without the rows going squat. */
    private fun unitForHeight(height: Int, aspect: Float) =
        height / (natural.rows.size * aspect + natural.headroom)

    /**
     * The height this keyboard actually wants, keys and preview headroom included. Rows are capped
     * so a key is never much taller than it is wide — stretched keys read as a toy, and the height
     * they'd steal is better spent on the guest's screen.
     *
     * It is what the band is given by default, and the most the *keys* will ever use: they are
     * already as wide as the screen allows, so a taller band only adds space above them. The band
     * itself may still be dragged past it — see [OnScreenKeyboard] — but nothing here grows to fill
     * that.
     */
    fun preferredHeight(forWidth: Int): Int =
        heightFor(forWidth / natural.units, natural.keyAspect)

    /**
     * The bottom of the drag: the shortest band still worth showing keys in.
     *
     * Shrinking narrows the keys and widens the gutter, and both run out — the keys at [MIN_KEY_DP],
     * below which a thumb can't aim at them however much screen it buys, and the gutter at
     * [KeyboardLayout.MAX_GUTTER_UNITS], where the halves have stopped being one keyboard. Whichever
     * comes first is the floor. On a wide screen that's the key; on a narrow one it's the gutter.
     */
    fun minimumHeight(forWidth: Int): Int {
        val widest = KeyboardLayout.HALF_UNITS * 2 + KeyboardLayout.MAX_GUTTER_UNITS
        val floor = maxOf(forWidth / widest, MIN_KEY_DP * resources.displayMetrics.density)
        return heightFor(floor, KeyboardLayout.KEY_ASPECT).coerceAtMost(preferredHeight(forWidth))
    }

    /**
     * Choose the shape that fits the band, and split the keyboard when whole no longer does.
     *
     * A keyboard shorter than [preferredHeight] can't keep both its width and its key shape. Key
     * shape wins — see [KeyboardLayout] — so the unit comes from the height we've got and the width
     * that leaves over becomes the gutter. Squeeze it and the halves walk apart; let it back out and
     * they close up and the keyboard is whole again.
     *
     * **The key a given height justifies is the one [KeyboardLayout.KEY_ASPECT] describes, not
     * [KeyboardLayout.SPLIT_ASPECT], and the difference is the whole of the reason a shrunk keyboard
     * is still usable.** Sizing the key off the taller split shape makes it *narrower* than the rows
     * are high, and it narrows further with every pixel of drag — so the small end of the range is a
     * keyboard of vertical slivers standing next to a gutter with room to spare. Width is the
     * dimension there is spare of down here, and spending it on the keys costs nothing but gutter
     * nobody asked for.
     *
     * @return the gutter to use, or null to stay whole.
     */
    private fun gutterFor(width: Int, height: Int): Float? {
        if (width <= 0 || height <= 0) return layout.gutterUnits
        val wide = unitForHeight(height, KeyboardLayout.KEY_ASPECT)
        if (!split && wide >= width / KeyboardLayout.WHOLE_UNITS * WHOLE_FIT) return null
        // Split by choice keeps its full gutter however much height there is; split by squeeze gets
        // whatever the keys left over.
        val least = KeyboardLayout.HALF_UNITS * 2 +
            if (split) KeyboardLayout.GUTTER_UNITS else KeyboardLayout.MIN_GUTTER_UNITS
        val u = minOf(wide, width / least)
        return (width / u - KeyboardLayout.HALF_UNITS * 2)
            .coerceAtMost(KeyboardLayout.MAX_GUTTER_UNITS)
    }

    private fun reshape() {
        val gutter = gutterFor(width, height)
        if (shaped && gutter == layout.gutterUnits) return
        val next = gutter?.let { KeyboardLayout.apart(functionRow, it) }
            ?: KeyboardLayout.whole(functionRow)
        // The halves coming apart under a finger is the one change here you're meant to feel: it
        // happens mid-drag, when you're looking at the screen edge rather than at the keys, and
        // it's the moment the layout under your thumbs stopped being the one you learned.
        if (shaped && next.split != layout.split) feedback.reshaped()
        layout = next
        shaped = true
    }

    private fun layoutKeys() {
        if (width <= 0 || height <= 0) return
        reshape()
        if (layout.rows.isEmpty()) return
        unit = unitFor(width)
        // A strip along the top belongs to the preview bubbles, so the top row's bubble has
        // somewhere to go that isn't underneath the finger that summoned it.
        val headroom = (unit * layout.headroom).coerceAtMost(height * 0.2f)
        val rowHeight = minOf((height - headroom) / layout.rows.size, unit * layout.keyAspect)
        if (rowHeight <= 0f) return
        // Keys sit against the bottom edge, where your hands are. Slack collects above them, next
        // to the guest's screen, rather than being poured into taller keys.
        val offset = height - rowHeight * layout.rows.size
        val out = ArrayList<Placed>(80)

        layout.rows.forEachIndexed { rowIndex, row ->
            val top = offset + rowIndex * rowHeight
            val bottom = if (rowIndex == layout.rows.lastIndex) height.toFloat() else top + rowHeight
            row.forEach { slot ->
                // Keys that reach the right edge are snapped to it so rounding can't leave an
                // unhittable sliver down the side of the screen.
                val left = slot.x * unit
                val right = if (slot.x + slot.width >= layout.units - 0.01f) {
                    width.toFloat()
                } else {
                    (slot.x + slot.width) * unit
                }
                out += Placed(
                    slot.key,
                    RectF(left + gap, top + gap, right - gap, bottom - gap),
                    RectF(left, top, right, bottom),
                )
            }
        }
        placed = out
        litUntil.clear()
        pad.layout(width, height, offset, rowHeight, layout.rows.size, unit, layout.gutterUnits ?: 0f)
    }

    // MARK: - Drawing

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val mods = activeModifiers()
        val radius = gap * 2.4f

        placed.forEach { p ->
            val state = latch[p.key.modifier]
            fill.color = when {
                isLit(p, now) -> PRESSED
                state == Latch.LOCKED -> ACCENT
                p.key.isSpecial -> KEY_SPECIAL
                else -> KEY
            }
            canvas.drawRoundRect(p.bounds, radius, radius, fill)

            // Armed differs from locked in *kind*, not just brightness: an underline bar against a
            // normal key face, where locked is a filled cap. A dim fill for armed reads as "off" in
            // sunlight and at fold-crease angles.
            if (state == Latch.ARMED && !isLit(p, now)) {
                fill.color = ACCENT
                val inset = p.bounds.width() * 0.24f
                canvas.drawRoundRect(
                    p.bounds.left + inset, p.bounds.bottom - gap * 2.6f,
                    p.bounds.right - inset, p.bounds.bottom - gap * 1.4f,
                    gap, gap, fill,
                )
            }

            val text = p.key.labelFor(mods)
            if (text.isEmpty()) return@forEach
            label.color = when (state) {
                Latch.LOCKED -> ON_ACCENT
                Latch.ARMED -> ACCENT
                else -> TEXT
            }
            // Long words ride smaller so "right-click" fits the key it belongs on.
            label.textSize = p.bounds.height() * if (text.length > 2) 0.26f else 0.40f
            canvas.drawText(text, p.bounds.centerX(), baselineIn(p.bounds), label)
        }

        // Previews last, so a bubble is never painted under the key it belongs to.
        placed.forEach { p ->
            if (p.key.previews && isLit(p, now)) drawPreview(canvas, p, p.key.labelFor(mods), radius)
        }

        // And the trackpad over the lot of it, which is what it is: an overlay that owns the keys
        // underneath for as long as it's there.
        pad.draw(canvas, radius)
    }

    private fun baselineIn(rect: RectF) = rect.centerY() - (label.descent() + label.ascent()) / 2

    /**
     * The bubble above a pressed key.
     *
     * Without it the only thing that changes when you tap is the colour of the rectangle directly
     * underneath your fingertip, which you cannot see. Every mobile keyboard since 2008 pops one of
     * these, and it is not decoration — it is the only feedback channel a hand doesn't block.
     *
     * It goes **above**, always, as long as there is any usable room: shrinking the bubble to fit
     * the space above the key beats a full-size one below it, because below is where the rest of
     * the hand is. Only a keyboard squeezed so short that there's no headroom left falls back to
     * dropping it underneath.
     */
    private fun drawPreview(canvas: Canvas, p: Placed, text: String, radius: Float) {
        if (text.isEmpty()) return
        val w = maxOf(p.cell.width(), p.cell.height()) * 1.15f
        val cx = p.cell.centerX().coerceIn(w / 2f + gap, width - w / 2f - gap)
        val room = p.cell.top - gap * 2f
        val h = minOf(p.cell.height() * 1.2f, room)

        val bubble = RectF(cx - w / 2f, 0f, cx + w / 2f, 0f)
        if (h >= p.cell.height() * MIN_PREVIEW) {
            bubble.bottom = room
            bubble.top = room - h
        } else {
            bubble.top = p.cell.bottom + gap * 2f
            bubble.bottom = bubble.top + p.cell.height() * 1.2f
            if (bubble.bottom > height) bubble.offset(0f, height - bubble.bottom)
        }

        fill.color = PREVIEW
        canvas.drawRoundRect(bubble, radius * 1.3f, radius * 1.3f, fill)
        outline.color = PREVIEW_EDGE
        canvas.drawRoundRect(bubble, radius * 1.3f, radius * 1.3f, outline)

        label.color = TEXT
        label.textSize = bubble.height() * 0.5f
        canvas.drawText(text, bubble.centerX(), baselineIn(bubble), label)
    }

    // MARK: - Touch

    /**
     * Every finger is routed once, at its own touch-down, and keeps that role until it lifts.
     * Deciding per-event instead would mean a finger resting on a click button changed what the
     * other hand's drag did, which is the opposite of what a trackpad with buttons is for.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val x = event.getX(index)
                val y = event.getY(index)
                val pointerId = event.getPointerId(index)
                if (pad.showing) {
                    if (pad.contains(x, y)) {
                        pad.down(pointerId, x, y)
                        return true
                    }
                    // A key, while the pad is up. That's a change of mind — unless another finger is
                    // still using the pad, in which case it's two hands doing two things and neither
                    // of them is wrong.
                    if (pad.idle) pad.dismiss()
                }
                press(pointerId, x, y)
            }

            MotionEvent.ACTION_MOVE -> {
                // The staged finger first: it may become the pad's, in which case the same event
                // then moves the cursor by the whole distance it has travelled.
                stagedMoved(event)
                pad.move(event)
                for (i in 0 until event.pointerCount) {
                    val pointerId = event.getPointerId(i)
                    if (pad.owns(pointerId) || staged?.pointerId == pointerId) continue
                    slid(pointerId, event.getX(i), event.getY(i))
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                val pointerId = event.getPointerId(index)
                if (pad.owns(pointerId)) pad.up(pointerId) else release(pointerId)
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelStaged()
                pad.cancel()
                (pressed.keys + modifierHolds.keys).toList().forEach { release(it) }
            }
        }
        return true
    }

    /**
     * A finger that slides off its key must not keep it down. The keysym itself went out on the
     * touch-down — that is the price of sending before anything else, and it is the right price —
     * but everything that outlives the down is cancelled here: the key-up goes out now, repeat
     * stops, and the press goes inert. The one exception is the pointer buttons, which are defined
     * as held for exactly as long as the finger is — a drag shouldn't drop mid-gesture because a
     * thumb drifted.
     */
    private fun slid(pointerId: Int, x: Float, y: Float) {
        val press = pressed[pointerId] ?: return
        if (press.finished || press.placed.key.action != KeyAction.TYPE) return
        if (distanceTo(press.placed.cell, x, y) <= unit * SLOP_UNITS) return
        press.finished = true
        stopRepeat(pointerId)
        onKey?.invoke(press.keysym, false)
        invalidate()
    }

    /**
     * The key under a touch.
     *
     * Cells tile each row exactly, so a touch on the keys always lands on one. Anything that misses
     * — the slack above the top row, the gutter of a split keyboard — snaps to the nearest key
     * within [SLOP_UNITS], and is ignored beyond that. The slop is what makes the edges forgiving;
     * the limit is what stops a tap in the middle of a split keyboard from typing a letter from one
     * of the halves.
     */
    private fun keyAt(x: Float, y: Float): Placed? {
        placed.firstOrNull { it.cell.contains(x, y) }?.let { return it }
        val nearest = placed.minByOrNull { distanceTo(it.cell, x, y) } ?: return null
        return nearest.takeIf { distanceTo(it.cell, x, y) <= unit * SLOP_UNITS }
    }

    private fun distanceTo(r: RectF, x: Float, y: Float) =
        hypot(maxOf(r.left - x, 0f, x - r.right), maxOf(r.top - y, 0f, y - r.bottom))

    private fun press(pointerId: Int, x: Float, y: Float) {
        // Any new finger settles the pending question: whatever the last one was doing, it wasn't
        // the start of a sweep, because sweeps are made of one finger.
        commitStaged()

        val target = keyAt(x, y)
        if (stageable(target, x, y)) {
            stage(pointerId, target, x, y)
            return
        }
        if (target == null) return

        // The send goes first, before the paint and before the haptic. `performHapticFeedback` is a
        // binder hop into system_server and it lands on this thread; there is no reason for the
        // guest to wait behind it.
        when {
            target.key.action != KeyAction.TYPE -> {
                val button = buttonFor(target.key.action)
                pressed[pointerId] = Press(target, 0)
                chordHeldModifiers()
                pointer.press(button)
            }

            target.key.modifier != 0 -> modifierDown(pointerId, target.key)

            else -> {
                val keysym = target.key.keysymFor(activeModifiers())
                pressed[pointerId] = Press(target, keysym)
                chordHeldModifiers()
                sendKey(keysym, down = true)
                if (target.key.repeats) startRepeat(pointerId, keysym)
            }
        }

        light(target)
        invalidate()
        feedback.press(target.key)
    }

    // MARK: - Keystroke, or the start of a sweep

    /**
     * Whether a press has to wait for a verdict.
     *
     * Only in the middle of the keyboard, only for ordinary keys — a modifier or a click key sends
     * nothing you'd want to take back — and only when the last keystroke is far enough behind us
     * that this touch could plausibly be the start of something else. A touch that hit no key at
     * all is always stageable: in the gutter of a split keyboard there is nothing to lose by
     * waiting.
     */
    private fun stageable(target: Placed?, x: Float, y: Float): Boolean {
        if (pad.showing || !pad.armZone.contains(x, y)) return false
        if (SystemClock.uptimeMillis() - lastKeyAt < TYPING_QUIET_MS) return false
        val key = target?.key ?: return true
        return key.action == KeyAction.TYPE && key.modifier == 0
    }

    private fun stage(pointerId: Int, target: Placed?, x: Float, y: Float) {
        staged = Staged(pointerId, target, target?.key?.keysymFor(activeModifiers()) ?: 0, x, y)
        if (target == null) return
        // Everything the finger can see happens now regardless. Only the keysym waits.
        light(target)
        invalidate()
        feedback.press(target.key)
        ui.postDelayed(commit, STAGE_HOLD_MS)
    }

    private val commit = Runnable { verdict() }

    /**
     * [STAGE_HOLD_MS] is up. Send the key — unless the finger is still travelling, in which case it
     * is not a keystroke, however slowly it happens to be going.
     *
     * The deadline alone is wrong, and a device caught it: a sweep of an inch over a quarter second
     * is an ordinary careful drag, and it reaches the arming distance at 180 ms. Judged on the
     * deadline it typed a letter and no trackpad ever appeared, which made the feature look like it
     * worked only if you were brisk about it. Speed was never the question — the deadline exists to
     * catch a finger that has settled, so a finger that hasn't settled just gets asked again.
     */
    private fun verdict() {
        val pending = staged ?: return
        val travel = pending.travel
        if (travel - pending.checked > unit * CREEP_UNITS) {
            pending.checked = travel
            ui.postDelayed(commit, STAGE_HOLD_MS)
            return
        }
        commitStaged()
    }

    /** Send a staged key for real, as if it had gone out at the touch-down. */
    private fun commitStaged() {
        val pending = staged ?: return
        staged = null
        ui.removeCallbacks(commit)
        val target = pending.placed ?: return
        pressed[pending.pointerId] = Press(target, pending.keysym)
        chordHeldModifiers()
        sendKey(pending.keysym, down = true)
        if (target.key.repeats) startRepeat(pending.pointerId, pending.keysym)
    }

    /** Throw a staged key away unsent, along with the flash that promised it. */
    private fun cancelStaged() {
        val pending = staged ?: return
        staged = null
        ui.removeCallbacks(commit)
        pending.placed?.let { litUntil.remove(it) }
        invalidate()
    }

    /** A staged finger that has travelled far enough is a sweep, and the keyboard becomes a pad. */
    private fun stagedMoved(event: MotionEvent) {
        val pending = staged ?: return
        val index = event.findPointerIndex(pending.pointerId)
        if (index < 0) return
        pending.lastX = event.getX(index)
        pending.lastY = event.getY(index)
        if (pending.travel < unit * ARM_UNITS) return
        cancelStaged()
        pad.arm(pending.pointerId, pending.x, pending.y)
    }

    private fun release(pointerId: Int) {
        // Lifted before the verdict: it was a tap after all. Commit it, and let the rest of this
        // method send the up that goes with it.
        if (staged?.pointerId == pointerId) commitStaged()
        stopRepeat(pointerId)
        if (modifierUp(pointerId)) {
            feedback.release()
            invalidate()
            return
        }
        val press = pressed.remove(pointerId) ?: return
        if (!press.finished) {
            if (press.placed.key.action != KeyAction.TYPE) {
                pointer.release(buttonFor(press.placed.key.action))
            } else {
                onKey?.invoke(press.keysym, false)
            }
        }
        // A one-shot modifier is spent by the key it modified — but only once every finger is up.
        // Spending it on the first release of a two-finger roll re-writes the state under the key
        // still being held.
        if (pressed.isEmpty()) clearArmed()
        feedback.release()
        invalidate()
    }

    private fun sendKey(keysym: Int, down: Boolean) {
        if (keysym == 0) return
        if (down) lastKeyAt = SystemClock.uptimeMillis()
        onKey?.invoke(keysym, down)
    }

    // MARK: - Pointer buttons

    private fun buttonFor(action: KeyAction) =
        if (action == KeyAction.RIGHT_CLICK) GuestPointer.RIGHT else GuestPointer.LEFT

    // MARK: - The pressed flash

    private fun isLit(p: Placed, now: Long) =
        pressed.values.any { it.placed === p && !it.finished } || (litUntil[p] ?: 0L) > now

    private fun light(p: Placed) {
        litUntil[p] = SystemClock.uptimeMillis() + MIN_LIT_MS
        ui.removeCallbacks(unlight)
        ui.postDelayed(unlight, MIN_LIT_MS + 1)
    }

    private val unlight = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            var latest = 0L
            val entries = litUntil.entries.iterator()
            while (entries.hasNext()) {
                val due = entries.next().value
                if (due <= now) entries.remove() else latest = maxOf(latest, due)
            }
            invalidate()
            if (latest > 0L) ui.postDelayed(this, latest - now + 1)
        }
    }

    // MARK: - Repeat

    private fun startRepeat(pointerId: Int, keysym: Int) {
        val loop = object : Runnable {
            override fun run() {
                // A repeat is a fresh press, not a held one: X11 autorepeat is a stream of
                // press/release pairs, and a client that only ever saw the one down would take the
                // whole run as a single keystroke.
                sendKey(keysym, down = false)
                sendKey(keysym, down = true)
                feedback.repeatTick()
                ui.postDelayed(this, REPEAT_INTERVAL_MS)
            }
        }
        repeaters[pointerId] = loop
        ui.postDelayed(loop, REPEAT_DELAY_MS)
    }

    private fun stopRepeat(pointerId: Int) {
        repeaters.remove(pointerId)?.let(ui::removeCallbacks)
    }

    // MARK: - Latching modifiers

    private fun activeModifiers(): Int = latch.keys.fold(0) { acc, bit -> acc or bit }

    /** A non-modifier key just went down: every modifier under a finger became a hold. */
    private fun chordHeldModifiers() {
        holdChorded.addAll(modifierHolds.values)
    }

    private fun modifierDown(pointerId: Int, key: Key) {
        val bit = key.modifier
        val now = SystemClock.uptimeMillis()
        val current = latch[bit]
        val next = when {
            // Caps Lock only locks: an armed caps that un-latches after one letter would just be a
            // second Shift wearing the wrong label.
            bit == Mod.CAPS_LOCK -> if (current == null) Latch.LOCKED else null
            current == null -> Latch.ARMED
            // A quick second tap on a freshly armed modifier locks it down.
            current == Latch.ARMED && bit == lastModifierTapBit &&
                now - lastModifierTapAt < DOUBLE_TAP_MS -> Latch.LOCKED
            else -> null
        }
        lastModifierTapBit = bit
        lastModifierTapAt = now

        // While the finger stays down this is possibly a hold; the release decides, by whether
        // anything was chorded with it in between. Caps Lock has no hold reading.
        if (next != null && bit != Mod.CAPS_LOCK) modifierHolds[pointerId] = bit

        val wasActive = current != null
        if (next == null) latch.remove(bit) else latch[bit] = next
        val nowActive = next != null
        if (wasActive == nowActive) return

        // The modifier's own key event, so a locked Control really is held down inside the guest.
        // There is no bitfield to send alongside it: X11 keeps the state itself, from exactly these
        // two messages, which is why they have to be balanced everywhere below.
        if (nowActive) {
            heldModifierKey[bit] = key.keysym
            onKey?.invoke(key.keysym, true)
        } else {
            heldModifierKey.remove(bit)?.let { onKey?.invoke(it, false) }
        }
    }

    /**
     * @return true when this pointer was resting on a modifier. If keys were typed during the hold
     * it behaved as a physical modifier and the lift ends it; untouched, it was a tap and the latch
     * stands.
     */
    private fun modifierUp(pointerId: Int): Boolean {
        val bit = modifierHolds.remove(pointerId) ?: return false
        val chorded = holdChorded.remove(bit)
        // The same bit can be under another finger (two Shifts); the last one out decides.
        if (chorded && !modifierHolds.containsValue(bit) && latch.remove(bit) != null) {
            heldModifierKey.remove(bit)?.let { onKey?.invoke(it, false) }
        }
        return true
    }

    private fun clearArmed() {
        // A bit still under a finger isn't spent by the key it modified — the finger is the source
        // of truth, and its lift will decide.
        val spent = latch.filterValues { it == Latch.ARMED }.keys
            .filterNot { modifierHolds.containsValue(it) }
        if (spent.isEmpty()) return
        spent.forEach { latch.remove(it) }
        spent.forEach { bit -> heldModifierKey.remove(bit)?.let { onKey?.invoke(it, false) } }
    }

    /**
     * Drop everything we're holding. Called whenever the keyboard goes away, so hiding it
     * mid-Control or mid-drag doesn't leave the guest wedged with a key nothing will release.
     */
    fun releaseAll() {
        cancelStaged()
        pad.releaseAll()
        repeaters.keys.toList().forEach(::stopRepeat)
        pressed.values.toList().forEach { press ->
            if (press.finished) return@forEach
            if (press.placed.key.action != KeyAction.TYPE) {
                pointer.release(buttonFor(press.placed.key.action))
            } else {
                onKey?.invoke(press.keysym, false)
            }
        }
        pressed.clear()
        modifierHolds.clear()
        holdChorded.clear()
        val held = latch.keys.toList()
        latch.clear()
        held.forEach { bit -> heldModifierKey.remove(bit)?.let { onKey?.invoke(it, false) } }
        litUntil.clear()
        ui.removeCallbacks(unlight)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        repeaters.keys.toList().forEach(::stopRepeat)
        ui.removeCallbacks(unlight)
        ui.removeCallbacks(commit)
        pad.detach()
    }

    // MARK: - Trackpad.Host

    /** A click the pad decided on — a tap, a two-finger tap, one of its own buttons. */
    override fun onTrackpadClick(button: Int, down: Boolean) = feedback.click(down)

    /** The instant the keyboard becomes a trackpad, which is worth feeling. */
    fun onTrackpadArmed() = feedback.mode()

    /**
     * The local half of every keystroke: the tick and the click.
     *
     * The vibrator is driven directly, through primitives where the hardware has them, because that
     * is the only route that owns its intensity — the [HapticFeedbackConstants] path is deliberately
     * faint and is silenced entirely by a system toggle users don't know they've set. A press ticks
     * hard, a release ticks light — one event per edge, like the break and return of a real switch —
     * and key repeat ticks lighter still so a held arrow key feels like it's doing something.
     *
     * Sound rides the system keypress effects, which follow the global touch-sounds setting for free
     * and audibly distinguish space, delete and return the way every stock keyboard does. On glass,
     * audio is the one channel a hand can't block.
     */
    private class Feedback(private val view: View) {
        private val audio = view.context.getSystemService(AudioManager::class.java)
        private val vibrator: Vibrator? =
            view.context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        private val primitives = vibrator?.areAllPrimitivesSupported(
            VibrationEffect.Composition.PRIMITIVE_CLICK,
            VibrationEffect.Composition.PRIMITIVE_TICK,
        ) == true

        fun press(key: Key) {
            if (!tick(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.65f)) {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_PRESS)
            }
            audio?.playSoundEffect(soundFor(key))
        }

        fun release() {
            if (!tick(VibrationEffect.Composition.PRIMITIVE_TICK, 0.45f)) {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_RELEASE)
            }
        }

        fun repeatTick() {
            if (!tick(VibrationEffect.Composition.PRIMITIVE_TICK, 0.25f)) {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        }

        /**
         * A pointer click. Silent, unlike a keystroke: the sounds are keypress effects, and a
         * keyboard that clacks when you click reads as a mis-hit rather than a click.
         */
        fun click(down: Boolean) {
            if (!down) return release()
            if (!tick(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.7f)) {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_PRESS)
            }
        }

        /**
         * The keyboard becoming a trackpad — the hardest thing this surface does, because it's the
         * only one that changes what every other touch means. It gets the firmest buzz there is, so
         * the mode is felt rather than noticed a beat later.
         */
        fun mode() {
            if (!tick(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f)) {
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
        }

        /**
         * The keyboard splitting or closing back up mid-drag. Firm enough to be felt through the
         * finger that caused it, softer than [mode] because nothing about what the surface *does*
         * has changed — only where the keys are.
         */
        fun reshaped() {
            if (!tick(VibrationEffect.Composition.PRIMITIVE_TICK, 0.9f)) {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        }

        private fun tick(primitive: Int, intensity: Float): Boolean {
            if (!primitives) return false
            vibrator?.vibrate(
                VibrationEffect.startComposition().addPrimitive(primitive, intensity).compose(),
            )
            return true
        }

        private fun soundFor(key: Key) = when (key.keysym) {
            0x20 -> AudioManager.FX_KEYPRESS_SPACEBAR
            KeyboardLayout.BACKSPACE -> AudioManager.FX_KEYPRESS_DELETE
            KeyboardLayout.RETURN -> AudioManager.FX_KEYPRESS_RETURN
            else -> AudioManager.FX_KEYPRESS_STANDARD
        }
    }

    private companion object {
        const val DOUBLE_TAP_MS = 340L
        const val REPEAT_DELAY_MS = 380L
        const val REPEAT_INTERVAL_MS = 45L

        /** Shortest a key may look pressed. Below ~70 ms a fast tap can miss every frame. */
        const val MIN_LIT_MS = 90L

        /** Strip reserved at the top for preview bubbles, in key-widths. See [Layout.headroom]. */
        const val PREVIEW_HEADROOM = 0.6f

        /**
         * How much shorter than it asked for the whole keyboard puts up with before it splits. A
         * cushion against rounding — a band a pixel or two under, from a layout pass or a dp
         * conversion, is still the height we asked for and not a squeeze.
         */
        const val WHOLE_FIT = 0.995f

        /**
         * The narrowest a key may be dragged, in dp. Below this you are aiming at something smaller
         * than the phone's own portrait keyboard gives you, and the screen the shrinking bought
         * stops being worth what it cost.
         */
        const val MIN_KEY_DP = 30f

        /** Smallest a bubble may shrink, relative to its key, before it gives up and goes below. */
        const val MIN_PREVIEW = 0.5f

        /** How far outside a key a touch may land and still count, in key-widths. */
        const val SLOP_UNITS = 0.35f

        /**
         * How far a finger has to sweep before the keyboard becomes a trackpad, in key-widths.
         * Comfortably past [SLOP_UNITS], so a press that merely drifts off its key is still a
         * keystroke that slid rather than a pointer gesture.
         */
        const val ARM_UNITS = 0.5f

        /**
         * How long a *still* finger may stay staged before its key is sent anyway. A tap is settled
         * by the lift long before this, so it only ever delays a deliberate hold — and only the
         * first one after a pause.
         */
        const val STAGE_HOLD_MS = 120L

        /**
         * How far a staged finger has to creep between one verdict and the next to count as still
         * moving, in key-widths. About a millimetre per deadline: below that a finger is resting on
         * a key, above it something is being aimed.
         */
        const val CREEP_UNITS = 0.06f

        /**
         * How long after a keystroke the middle of the keyboard is just keys again. Mid-word a touch
         * there is a letter; pointer gestures start from rest, and staging every press in a burst of
         * typing would tax the home row for a gesture nobody was making.
         */
        const val TYPING_QUIET_MS = 400L

        // The keyboard is the guest's, so it wears the guest's colours: Box's terminal ground, and
        // the one green the app owns for anything that is live.
        val BACKDROP = Color.parseColor("#080A09")
        val KEY = Color.parseColor("#1A211C")
        val KEY_SPECIAL = Color.parseColor("#121814")
        val PRESSED = Color.parseColor("#33463A")
        val PREVIEW = Color.parseColor("#2C3B31")
        val PREVIEW_EDGE = Color.parseColor("#55705E")
        val ACCENT = Color.parseColor("#7FE868")
        val TEXT = Color.parseColor("#E2E5DE")
        val ON_ACCENT = Color.parseColor("#0A2600")
    }
}
