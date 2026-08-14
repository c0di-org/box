package dev.localagent.workstation.computer

import kotlin.math.floor
import kotlin.math.sqrt

/** A guest screen size, in guest pixels. */
data class GuestScreen(val width: Int, val height: Int) {
    val pixels: Long get() = width.toLong() * height.toLong()
}

/**
 * What size the guest's screen should be, given the views currently showing it.
 *
 * The rules here are the whole policy; `GuestDisplayMode` only carries it out. Each exists because
 * of something that went wrong without it.
 *
 * **The biggest view wins, and views only looked at never get here.** The same machine is drawn in
 * several places at once — the box's header carries a live minimap while the computer fills the
 * window. Following the largest surface is right; following *any* surface is not, or leaving the
 * computer for the task list would resize the guest down to the minimap and back again: a whole X
 * mode set, twice, for navigation. Which view is a picture rather than a screen cannot be decided
 * by size — the minimap is larger than a desktop pane on a small window — so the caller says so
 * and [VncDesktop] filters those out. [MIN_VIEWPORT_PIXELS] is the floor under what is left; below
 * it the answer is null and the guest is left alone.
 *
 * **A ceiling on pixels, not on either side.** Every pixel of a full redraw is walked by the X
 * server, then QEMU's VNC encoder, then [VncDesktop], on two emulated cores. The old fixed screen
 * was 1.02 Mpx; a maximised DeX window on a 3440x1440 monitor asks for 4.95 Mpx. [MAX_PIXELS] is
 * measured rather than chosen: 1080x2190, a phone pane filled edge to edge, is 2.37 Mpx, was tried
 * on the device, stayed responsive, and made the terminal legible for the first time by rendering
 * at 1:1 with the panel. The ceiling sits just above that; larger keeps its shape and loses density.
 *
 * **Stepped down, so nearly-equal sizes are equal.** A DeX window drag reports every intermediate
 * width. Quantising to [STEP] resolves a few pixels of movement to the size the guest already has,
 * and [changeIsWorthIt] discards it with no mode set. Always downwards — see [step].
 */
object GuestScreenFit {

    /**
     * The size to ask for, or null if nothing on screen is big enough to be worth resizing for.
     */
    fun of(viewports: Collection<GuestScreen>): GuestScreen? {
        val largest = viewports
            .filter { it.pixels >= MIN_VIEWPORT_PIXELS && it.width > 0 && it.height > 0 }
            .maxByOrNull { it.pixels }
            ?: return null

        // Shrink along the diagonal so the aspect ratio survives: the point of the whole exercise
        // is that the guest stops being letterboxed, and a ceiling applied to one side would put
        // the bars back.
        val scale = if (largest.pixels <= MAX_PIXELS) 1.0 else sqrt(MAX_PIXELS.toDouble() / largest.pixels)
        return GuestScreen(
            width = step(largest.width * scale).coerceIn(MIN_SIDE, MAX_SIDE),
            height = step(largest.height * scale).coerceIn(MIN_SIDE, MAX_SIDE),
        )
    }

    /**
     * Whether moving from [current] to [target] earns the mode set.
     *
     * A resize is not free on the guest's side — X reallocates the screen, every client redraws,
     * and the whole framebuffer crosses the emulated link once. Ignoring changes under [TOLERANCE]
     * keeps a settling layout from spending that repeatedly to arrive somewhere it already was.
     */
    fun changeIsWorthIt(current: GuestScreen?, target: GuestScreen): Boolean {
        if (current == null) return true
        val widthMoved = kotlin.math.abs(current.width - target.width) > current.width * TOLERANCE
        val heightMoved = kotlin.math.abs(current.height - target.height) > current.height * TOLERANCE
        return widthMoved || heightMoved
    }

    /**
     * Down to the step, never up. A guest wider than the view showing it would be scaled *up* to
     * fit, and an upscaled desktop is blurry in exactly the place this change is trying to make
     * legible — small text. Losing up to seven pixels a side costs nothing by comparison.
     */
    private fun step(value: Double): Int = floor(value / STEP).toInt() * STEP

    /**
     * Below this a surface is too small to resize a whole desktop for, whatever it claims to be.
     * Comfortably below the smallest real pane, which is a phone in the `Single` layout.
     */
    private const val MIN_VIEWPORT_PIXELS = 400L * 300L

    /** See the class comment: measured on the device, not chosen. */
    private const val MAX_PIXELS = 2_600_000L

    private const val MIN_SIDE = 480
    private const val MAX_SIDE = 4096
    private const val STEP = 8
    private const val TOLERANCE = 0.02
}
