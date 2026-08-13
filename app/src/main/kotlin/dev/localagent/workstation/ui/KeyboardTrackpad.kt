package dev.localagent.workstation.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import dev.localagent.workstation.computer.GuestPointer

/**
 * The trackpad the keyboard turns into.
 *
 * It costs nothing when it isn't there. It has no layout of its own — it is an overlay, drawn on
 * top of the keys — and it only exists from the moment a finger lands in the middle of the keyboard
 * and *sweeps* (see the staging in [GuestKeyboardView]) until [LINGER_MS] after the last finger
 * leaves. So a keystroke is still a keystroke and a gesture is a gesture, without a mode switch
 * anywhere, and no key is ever made smaller to pay for a pointer that is only sometimes wanted.
 *
 * ## Why it stays after you let go
 *
 * A phone-sized pad crossed at 1:1 doesn't cross the guest's screen, so you will run out of glass
 * and have to lift, recentre and go again — and you'll want to click when you get there. Vanishing
 * the instant the finger leaves would make every one of those a fresh arming gesture, which is the
 * same as having no pad at all. Three seconds is long enough for a lift, a re-centre and a click,
 * and short enough that it's gone by the time you've decided to type instead. Anything you do to it
 * starts the clock again; the last half second is spent fading, so it's clear which state you're in
 * before you commit to a key.
 *
 * ## The buttons
 *
 * A tap on the pad clicks and two fingers right-click, exactly as on the desktop above. The two big
 * buttons along the bottom are for the other half of the job: they're **held** for as long as the
 * finger is, so a thumb on the left button and a finger dragging on the pad is a click-and-drag —
 * the thing a tap gesture can't do, on the edge of the surface where a thumb already rests. The
 * right one is what makes Openbox's root menu reachable, which is the only way to launch anything
 * from the guest's desktop.
 *
 * The top row of the keyboard is deliberately left uncovered. It is the way out: touch anything up
 * there and the pad goes, because you have evidently stopped pointing.
 */
internal class KeyboardTrackpad(private val keys: GuestKeyboardView) {

    private val trackpad = Trackpad(keys)
    private val ui = Handler(Looper.getMainLooper())
    private val density = keys.resources.displayMetrics.density

    /** The whole overlay: pad plus buttons. Empty until the keyboard has been laid out. */
    private val panel = RectF()
    private val pad = RectF()
    private val leftButton = RectF()
    private val rightButton = RectF()

    /** Where a sweep has to begin for the keyboard to become a trackpad at all. */
    val armZone = RectF()

    private var showUntil = 0L

    /** Pointer id → the button it is holding down. */
    private val held = HashMap<Int, Int>()

    val showing: Boolean get() = !panel.isEmpty && (!idle || showUntil > SystemClock.uptimeMillis())

    /** True when nothing is touching the pad — the state the fade-out clock runs in. */
    val idle: Boolean get() = trackpad.idle && held.isEmpty()

    fun owns(pointerId: Int) = trackpad.owns(pointerId) || held.containsKey(pointerId)

    fun contains(x: Float, y: Float) = panel.contains(x, y)

    // MARK: - Geometry

    /**
     * Fit the overlay to the keyboard that was just laid out.
     *
     * @param rowTop where the first row of keys starts; rows are flush to the bottom edge.
     * @param unit the width of one alphabetic key, which is what the arming zone is measured in so
     * it stays the same handful of columns on every screen and both layouts.
     * @param gutterUnits the empty column down the middle, zero when the keyboard is whole.
     */
    fun layout(
        width: Int,
        height: Int,
        rowTop: Float,
        rowHeight: Float,
        rows: Int,
        unit: Float,
        gutterUnits: Float,
    ) {
        if (width <= 0 || rowHeight <= 0f || rows < 3) {
            panel.setEmpty()
            armZone.setEmpty()
            return
        }
        val top = rowTop + rowHeight
        panel.set(0f, top, width.toFloat(), height.toFloat())

        val strip = (rowHeight * BUTTON_ROWS).coerceAtMost(panel.height() * 0.4f)
        pad.set(panel.left, panel.top, panel.right, panel.bottom - strip)
        val divide = panel.left + panel.width() * LEFT_SHARE
        leftButton.set(panel.left, pad.bottom, divide, panel.bottom)
        rightButton.set(divide, pad.bottom, panel.right, panel.bottom)

        // The zone excludes the top row — that's the escape hatch — and the bottom row, where
        // space, the modifiers and the existing click keys live and a stray sweep would be
        // expensive.
        //
        // A dragged-apart keyboard can have a gutter wider than the zone, and a zone narrower than
        // the empty space it sits in would be an invisible target inside a visible one: aim for the
        // gap, hit nothing. So it covers the gutter and a column either side, whatever the drag has
        // left it.
        val half = unit * maxOf(ARM_ZONE_UNITS, gutterUnits + 2f) / 2f
        val centre = width / 2f
        armZone.set(centre - half, top, centre + half, rowTop + rowHeight * (rows - 1))
    }

    // MARK: - Touch

    /**
     * Become a trackpad, with [pointerId] already down at the point the sweep started from.
     * Anchoring back there rather than where the finger has got to means the travel that paid for
     * the arming isn't thrown away — the cursor moves by the whole gesture.
     */
    fun arm(pointerId: Int, x: Float, y: Float) {
        if (panel.isEmpty) return
        keepAlive()
        trackpad.down(pointerId, x, y)
        keys.onTrackpadArmed()
        keys.invalidate()
    }

    fun down(pointerId: Int, x: Float, y: Float) {
        keepAlive()
        val button = when {
            leftButton.contains(x, y) -> GuestPointer.LEFT
            rightButton.contains(x, y) -> GuestPointer.RIGHT
            else -> {
                trackpad.down(pointerId, x, y)
                keys.invalidate()
                return
            }
        }
        held[pointerId] = button
        keys.pointer.press(button)
        keys.onTrackpadClick(button, true)
        keys.invalidate()
    }

    fun move(event: MotionEvent) {
        if (idle) return
        keepAlive()
        trackpad.move(event)
    }

    fun up(pointerId: Int) {
        val button = held.remove(pointerId)
        if (button != null) {
            keys.pointer.release(button)
            keys.onTrackpadClick(button, false)
        } else {
            // A finger lifting off the pad while a button is held is part of a drag, not a tap:
            // clicking there too would fire a second, unasked-for click into whatever the drag had
            // hold of.
            trackpad.up(pointerId, cancelled = held.isNotEmpty())
        }
        keepAlive()
        keys.invalidate()
    }

    /** Let go of everything, but stay on screen — the gesture was interrupted, not finished. */
    fun cancel() {
        releaseHeld()
        trackpad.cancel()
        keepAlive()
        keys.invalidate()
    }

    /** Let go of everything and leave. */
    fun releaseAll() {
        releaseHeld()
        trackpad.cancel()
        dismiss()
    }

    /**
     * Take the pad away. Only ever called with nothing touching it — a finger elsewhere on the
     * keyboard means the user is doing two things at once, not that they're done.
     */
    fun dismiss() {
        showUntil = 0
        ui.removeCallbacks(startFade)
        keys.invalidate()
    }

    fun detach() = ui.removeCallbacks(startFade)

    private fun releaseHeld() {
        held.values.forEach { keys.pointer.release(it) }
        held.clear()
    }

    private fun keepAlive() {
        showUntil = SystemClock.uptimeMillis() + LINGER_MS
        ui.removeCallbacks(startFade)
        ui.postDelayed(startFade, LINGER_MS - FADE_MS)
    }

    /** One wake-up at the moment the fade begins; [draw] drives the rest of it frame by frame. */
    private val startFade = Runnable { keys.invalidate() }

    // MARK: - Drawing

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    fun draw(canvas: Canvas, radius: Float) {
        if (!showing) return
        val now = SystemClock.uptimeMillis()
        val alpha = alphaAt(now)
        if (alpha <= 0) return

        fill.color = SURFACE
        fill.alpha = alpha
        canvas.drawRect(panel, fill)

        // An inner outline around the pad proper, so the surface reads as a surface and the seam
        // where the buttons start is visible without looking for it.
        stroke.color = EDGE
        stroke.alpha = alpha
        val inset = 3f * density
        canvas.drawRoundRect(
            pad.left + inset, pad.top + inset, pad.right - inset, pad.bottom - inset,
            radius, radius, stroke,
        )

        button(canvas, leftButton, "click", radius, alpha)
        button(canvas, rightButton, "right-click", radius, alpha)

        // While it's on its way out, keep asking for frames; the alpha is a function of the clock
        // and nothing else is going to invalidate for us.
        if (alpha < 255) keys.postInvalidateOnAnimation()
    }

    private fun button(canvas: Canvas, rect: RectF, text: String, radius: Float, alpha: Int) {
        val down = held.containsValue(if (rect === leftButton) GuestPointer.LEFT else GuestPointer.RIGHT)
        val gap = 3f * density
        fill.color = if (down) BUTTON_PRESSED else BUTTON
        fill.alpha = alpha
        canvas.drawRoundRect(
            rect.left + gap, rect.top + gap, rect.right - gap, rect.bottom - gap,
            radius, radius, fill,
        )
        label.color = TEXT
        label.alpha = alpha
        label.textSize = rect.height() * 0.26f
        canvas.drawText(
            text,
            rect.centerX(),
            rect.centerY() - (label.descent() + label.ascent()) / 2,
            label,
        )
    }

    private fun alphaAt(now: Long): Int {
        if (!idle) return 255
        val left = showUntil - now
        if (left >= FADE_MS) return 255
        return (255L * left / FADE_MS).coerceIn(0L, 255L).toInt()
    }

    private companion object {
        /** How long the pad outlives the finger that was using it. */
        const val LINGER_MS = 3_000L

        /** The tail end of that, spent fading, so the mode you're in is never a guess. */
        const val FADE_MS = 500L

        /** Height of the click buttons, in keyboard rows. */
        const val BUTTON_ROWS = 1.25f

        /** The left button's share of the width. It's the one you actually use. */
        const val LEFT_SHARE = 0.6f

        /** Width of the strip down the middle where a sweep arms the pad, in key-widths. */
        const val ARM_ZONE_UNITS = 4f

        val SURFACE = Color.parseColor("#0B120D")
        val EDGE = Color.parseColor("#2A3A2E")
        val BUTTON = Color.parseColor("#161F19")
        val BUTTON_PRESSED = Color.parseColor("#33463A")
        val TEXT = Color.parseColor("#8FA394")
    }
}
