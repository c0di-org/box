package dev.localagent.runtime.qemu

/**
 * Teaching the guest's X server a screen size it was never told about at boot.
 *
 * The machine is built with `virtio-gpu-pci,xres=1280,yres=800` and that is the only mode offered,
 * so the desktop is one fixed landscape rectangle letterboxed into whatever window it lands in.
 *
 * **Not over RFB, because the server end is not there.** The documented route is `SetDesktopSize`
 * answered by `ExtendedDesktopSize`; this build links QEMU 5.1.0, whose VNC server has
 * `vnc_desktop_resize` (server telling client) but no `_ext` and no handler for client message 251
 * at all. A `SetDesktopSize` would be read as an unknown message type and desynchronise the
 * stream. The negotiation runs one way only, and nothing on the phone can ask.
 *
 * What does exist is the guest. `virtio-gpu` reports `maximum 8192 x 8192` and the modesetting
 * driver accepts a mode it has never seen, so the guest is *told* its size from inside over the
 * channel already there: agentd, running as `agent` with `DISPLAY=:0` (set in
 * `guest/systemd/local-agentd.service` so `scrot` can see the session). No image rebuild, and the
 * result returns on its own — QEMU notices the console changed shape and sends the `DesktopSize`
 * rectangle the RFB client has always decoded.
 *
 * The timings below are invented, which is correct rather than lazy: no cable, no panel, nothing
 * clocking these pixels. They exist because `xrandr --newmode` parses a modeline and X validates
 * the ordering of its fields, so they must be *consistent*, not real.
 */
internal object GuestDisplayMode {

    /** A mode name Box can recognise later, so the ones it made can be cleaned up. */
    const val PREFIX = "box_"

    /**
     * The script that puts the guest's screen at [width] x [height].
     *
     * Safe to run repeatedly with the same size, because it will be: the view is re-measured on
     * every rotation, fold and window drag, and several settle on a size the guest already has.
     * `--newmode` and `--addmode` fail loudly on a mode that exists, so both may fail; only the
     * `--output` must succeed.
     *
     * The output is discovered, not assumed — it is `Virtual-1` on this image today, but that name
     * comes from the kernel's virtio-gpu driver. Old `box_` modes are removed *afterwards*: a mode
     * cannot be deleted while it is the one being displayed, so the switch happens first.
     *
     * The screensaver is turned off here because X blanks an idle screen and drops DRM scanout with
     * it, and QEMU then reports the console as *disabled* rather than black — so opening the
     * computer after a while showed black with QEMU's own `Guest disabled display.` in 8px type.
     * Blanking saves a backlight this machine does not have, and all it achieves is a desktop
     * missing exactly when somebody asked to see it.
     *
     * In this script rather than the image because this runs when the desktop is opened, and takes
     * effect on a box that is already running — a change to `local-agent-desktop.service` would
     * need a rebuilt image and a reboot to reach the machine open right now.
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
