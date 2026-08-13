package dev.localagent.runtime.qemu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestDisplayModeTest {

    private fun script(width: Int, height: Int) = GuestDisplayMode.script(width, height)

    @Test
    fun `the command is a plain shell, with no login profile`() {
        val command = GuestDisplayMode.command(1080, 2192)
        assertEquals(listOf("/bin/sh", "-c"), command.take(2))
        assertEquals(3, command.size)
    }

    @Test
    fun `the output is discovered rather than named`() {
        // It is Virtual-1 on this image, but that comes from the kernel's virtio-gpu driver and is
        // not Box's to assume.
        val script = script(1080, 2192)
        assertTrue(script.contains("xrandr | awk"))
        assertTrue("must not hard-code an output", !script.contains("Virtual-1"))
    }

    @Test
    fun `the mode is named after its size, so it can be found again`() {
        assertTrue(script(1080, 2192).contains("box_1080x2192"))
    }

    @Test
    fun `running it twice with the same size is not an error`() {
        val script = script(1280, 800)
        // --newmode and --addmode both fail loudly on a mode that already exists, and both will
        // meet one: the view is re-measured on every fold and several settle where the guest is.
        val newmode = script.lines().single { it.contains("--newmode") }
        val addmode = script.lines().single { it.contains("--addmode") }
        assertTrue("newmode must tolerate an existing mode", newmode.endsWith("|| true"))
        assertTrue("addmode must tolerate an existing mode", addmode.endsWith("|| true"))
        // The switch itself is the one thing that has to work.
        assertTrue(script.lines().any { it.trimStart().startsWith("xrandr --output") && !it.contains("||") })
    }

    @Test
    fun `the screen is woken and kept awake`() {
        val script = script(1080, 2184)
        // A blanked X drops scanout, and QEMU reports the console disabled -- which reaches the
        // user as "Guest disabled display." in QEMU's own 8px type, at the moment they asked to
        // see the desktop.
        assertTrue(script.contains("xset s off -dpms"))
        assertTrue(script.contains("xset dpms force on"))
        // Never fatal: a guest without xset should still get its resize.
        script.lines().filter { it.contains("xset") }.forEach {
            assertTrue("$it must not be able to fail the script", it.endsWith("|| true"))
        }
    }

    @Test
    fun `old modes are cleaned up only after the switch`() {
        val script = script(1080, 2192)
        val switched = script.indexOf("--output")
        val removed = script.indexOf("--rmmode")
        assertTrue("a mode cannot be removed while it is on screen", switched < removed)
    }

    @Test
    fun `the modeline fields increase, which is all X actually checks`() {
        val line = script(1080, 2192).lines().single { it.contains("--newmode") }
        val fields = line.trim().removeSuffix("|| true").trim().split(" ")
        // xrandr --newmode <name> <clock> <hdisp hss hse htotal> <vdisp vss vse vtotal> <flags>
        val numbers = fields.drop(4).take(8).map { it.toInt() }
        val horizontal = numbers.take(4)
        val vertical = numbers.drop(4)
        assertEquals(1080, horizontal.first())
        assertEquals(2192, vertical.first())
        assertEquals(horizontal.sorted(), horizontal)
        assertEquals(vertical.sorted(), vertical)
        assertTrue(horizontal.distinct().size == 4)
        assertTrue(vertical.distinct().size == 4)
    }

    @Test
    fun `the pixel clock is consistent with the totals it implies`() {
        val line = script(1280, 800).lines().single { it.contains("--newmode") }
        val fields = line.trim().split(" ")
        val clock = fields[3].toDouble()
        val hTotal = fields[7].toInt()
        val vTotal = fields[11].toInt()
        assertEquals(hTotal.toDouble() * vTotal * 60 / 1_000_000.0, clock, 0.01)
    }
}
