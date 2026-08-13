package dev.localagent.runtime.qemu

/**
 * Teaching the guest's X server a screen size it was never told about at boot.
 *
 * The machine is built with `virtio-gpu-pci,xres=1280,yres=800` and that is the only mode the
 * guest is offered, so the desktop is one fixed landscape rectangle letterboxed into whatever
 * window it lands in — bars in DeX, most of a nearly-square Fold panel wasted, and about 70% of a
 * phone screen black with the rest too small to read.
 *
 * ### Why this is done with xrandr and not over RFB
 *
 * The obvious route is the one RFB already documents: the client asks for a size with
 * `SetDesktopSize`, the server resizes and answers with an `ExtendedDesktopSize` rectangle. Box's
 * client end could offer it in an afternoon. The server end is not there — this build links
 * QEMU 5.1.0, whose VNC server has `vnc_desktop_resize` (server telling client the size changed)
 * and no `vnc_desktop_resize_ext` and no handler for client message 251 at all. A `SetDesktopSize`
 * would be read as an unknown message type and desynchronise the stream. So the negotiation only
 * runs one way, and nothing on the phone can ask.
 *
 * What does exist is the guest itself. `virtio-gpu` reports `maximum 8192 x 8192` and the
 * modesetting driver accepts a mode it has never seen, so the guest can be *told* its screen size
 * from inside, by the one channel that was already there: agentd, which runs as `agent` with
 * `DISPLAY=:0` set (see `guest/systemd/local-agentd.service`, which sets it so `scrot` can see the
 * session). No image rebuild, no new socket, and the result comes back on its own — QEMU notices
 * the console changed shape and sends the `DesktopSize` rectangle the RFB client has always
 * decoded.
 *
 * The timings below are invented, and that is correct rather than lazy: there is no cable and no
 * panel, so nothing is clocking these pixels. They exist because `xrandr --newmode` parses a
 * modeline and X validates the ordering of its fields, so they have to be *consistent*, not real.
 */
internal object GuestDisplayMode {

    /** A mode name Box can recognise later, so the ones it made can be cleaned up. */
    const val PREFIX = "box_"

    /**
     * The script that puts the guest's screen at [width] x [height].
     *
     * Written to be safe to run repeatedly with the same size, because it will be: the view is
     * re-measured on every rotation, fold and window drag, and several of those settle on a size
     * the guest is already at. `--newmode` and `--addmode` fail loudly on a mode that exists, so
     * both are allowed to fail; only the `--output` is required to succeed.
     *
     * The output is discovered rather than assumed. It is `Virtual-1` on this image today, but
     * that name comes from the kernel's virtio-gpu driver and is not something Box should have an
     * opinion about.
     *
     * Old `box_` modes are removed afterwards rather than before: a mode cannot be deleted while
     * it is the one being displayed, so the switch has to happen first.
     *
     * ### Why the screensaver is turned off here
     *
     * X blanks an idle screen and drops DRM scanout with it, and QEMU then reports the console as
     * *disabled* rather than black — so opening the computer after a while showed a phone-sized
     * expanse of black with `Guest disabled display.` in 8px type in the middle of it, which is
     * QEMU's text and not something Box ever chose to say. Blanking exists to save a backlight,
     * and this machine does not have one: nothing is being saved, and the only thing it achieves
     * is that the desktop is missing exactly when somebody has just asked to see it.
     *
     * It sits in this script rather than in the guest image because this is the code that runs
     * when the desktop is opened, and because it takes effect on a box that is already running —
     * a change to `local-agent-desktop.service` would need a rebuilt image and a reboot to reach
     * the machine the user has open right now.
     */
    fun script(width: Int, height: Int): String {
        val mode = Modeline(width, height)
        return """
            set -e
            xset s off -dpms 2>/dev/null || true
            xset dpms force on 2>/dev/null || true
            out=$(xrandr | awk '/ connected/ { print ${'$'}1; exit }')
            [ -n "${'$'}out" ] || { echo 'no connected output' >&2; exit 1; }
            xrandr --newmode ${mode.name} ${mode.timings} 2>/dev/null || true
            xrandr --addmode "${'$'}out" ${mode.name} 2>/dev/null || true
            xrandr --output "${'$'}out" --mode ${mode.name}
            for old in $(xrandr | awk '/^ *$PREFIX/ { print ${'$'}1 }'); do
                [ "${'$'}old" = ${mode.name} ] && continue
                xrandr --delmode "${'$'}out" "${'$'}old" 2>/dev/null || true
                xrandr --rmmode "${'$'}old" 2>/dev/null || true
            done
        """.trimIndent()
    }

    /** The command agentd is asked to run. `sh`, not `sh -l`: no profile is wanted here. */
    fun command(width: Int, height: Int): List<String> = listOf("/bin/sh", "-c", script(width, height))

    /**
     * A modeline for a screen nobody is going to display.
     *
     * The blanking intervals are the smallest ones that keep X's own validation happy — each
     * boundary has to be strictly greater than the one before it — and the pixel clock is then
     * whatever those totals imply at 60 Hz, so the numbers are at least self-consistent if a human
     * ever reads them out of `xrandr`.
     */
    internal class Modeline(width: Int, height: Int) {
        val name = "$PREFIX${width}x$height"

        private val hSyncStart = width + 8
        private val hSyncEnd = width + 24
        private val hTotal = width + 40
        private val vSyncStart = height + 3
        private val vSyncEnd = height + 8
        private val vTotal = height + 14

        /** Megahertz, to two places, as `xrandr --newmode` expects it first. */
        private val clock = hTotal.toLong() * vTotal.toLong() * REFRESH_HZ / 1_000_000.0

        val timings: String = buildString {
            append(String.format(java.util.Locale.US, "%.2f", clock))
            append(" $width $hSyncStart $hSyncEnd $hTotal")
            append(" $height $vSyncStart $vSyncEnd $vTotal")
            append(" -hsync +vsync")
        }
    }

    private const val REFRESH_HZ = 60
}
