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
 * The rules here are the whole of the policy; [GuestDisplayMode][dev.localagent.runtime.qemu] only
 * carries it out. Three of them, and each exists because of something that went wrong without it.
 *
 * ### The biggest view wins, and thumbnails do not count
 *
 * The same machine is drawn in several places at once — the row in the task list carries a live
 * 230px-wide thumbnail while the computer fills the window behind it. Following the largest
 * surface is right; following *any* surface is not, because leaving the computer for the task list
 * would then resize the guest down to a thumbnail, and coming back would resize it up again. A
 * whole X mode set, twice, for navigation. So a surface below [MIN_VIEWPORT_PIXELS] is understood
 * as a preview of a screen rather than a screen, and if none of the attached surfaces is bigger
 * than that, the answer is null: leave the guest exactly as it is.
 *
 * ### A ceiling on pixels, not on either side
 *
 * The guest is fully emulated — TCG, two cores — and every pixel of a full redraw is walked by the
 * X server, then by QEMU's VNC encoder, then by [VncDesktop]. The fixed screen was 1.02 Mpx. A
 * maximised DeX window on a 3440x1440 monitor asks for 4.95 Mpx, which is five times the work for
 * a desktop nobody is reading at that density.
 *
 * [MAX_PIXELS] is set from measurement rather than taste: 1080x2190 — a phone pane, filled edge to
 * edge — is 2.37 Mpx and was tried on the device before this code was written. It stayed
 * responsive, and the terminal in it became legible for the first time because the guest was
 * finally rendering at 1:1 with the panel instead of being scaled down. So the ceiling is just
 * above that, and anything larger keeps its shape and loses density.
 *
 * ### Stepped down, so that nearly-equal sizes are equal
 *
 * A window drag in DeX reports every intermediate width. Quantising to [STEP] means a few pixels of
 * movement resolve to the size the guest is already at, and [changeIsWorthIt] then discards it
 * without a mode set at all. Always downwards — see [step].
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
     * Below this a surface is a thumbnail. Comfortably above the task list's live row (~230x145)
     * and comfortably below the smallest real pane, which is a phone in the `Single` layout.
     */
    private const val MIN_VIEWPORT_PIXELS = 400L * 300L

    /** See the class comment: measured on the device, not chosen. */
    private const val MAX_PIXELS = 2_600_000L

    private const val MIN_SIDE = 480
    private const val MAX_SIDE = 4096
    private const val STEP = 8
    private const val TOLERANCE = 0.02
}
