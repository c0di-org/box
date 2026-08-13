package dev.localagent.workstation.computer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The keyboard is a grid measured in key-widths, and every row has to come out to the same total.
 *
 * This is not a style rule. A row that adds up to less than the rest leaves a strip down the edge
 * of the screen that belongs to no key, and one that adds up to more pushes its last key off the
 * side — and neither is visible in a screenshot of the keyboard, only in the tap that misses. The
 * split layout is worse: its two halves have to be *equal*, or the columns stop lining up down the
 * rows and every key below the mistake is a millimetre further from where the thumb learned it.
 */
class KeyboardLayoutTest {

    @Test
    fun `every whole row spans the keyboard`() {
        forEachRow(KeyboardLayout.whole(functionRow = true)) { index, row ->
            assertEquals("row $index", KeyboardLayout.WHOLE_UNITS, row.last().let { it.x + it.width }, 0.001f)
        }
    }

    @Test
    fun `every split row fills both halves and neither the gutter`() {
        val gutter = KeyboardLayout.GUTTER_UNITS
        val layout = KeyboardLayout.apart(functionRow = true, gutterUnits = gutter)
        forEachRow(layout) { index, row ->
            val left = row.filter { it.x < KeyboardLayout.HALF_UNITS }
            val right = row - left.toSet()
            assertEquals("left half of row $index", KeyboardLayout.HALF_UNITS, left.last().let { it.x + it.width }, 0.001f)
            assertEquals("right half of row $index starts", KeyboardLayout.HALF_UNITS + gutter, right.first().x, 0.001f)
            assertEquals("right half of row $index", layout.units, right.last().let { it.x + it.width }, 0.001f)
        }
    }

    /** Keys butt up against each other. A gap in the middle of a row is a strip that types nothing. */
    @Test
    fun `keys leave no holes between them`() {
        forEachRow(KeyboardLayout.whole(functionRow = true)) { index, row ->
            row.zipWithNext { left, right ->
                assertEquals("hole in row $index", left.x + left.width, right.x, 0.001f)
            }
        }
    }

    /** Shift is applied here, not by the guest: a keysym is a character, and `2` is not `@`. */
    @Test
    fun `shift picks the other glyph and caps only reaches letters`() {
        val layout = KeyboardLayout.whole(functionRow = false)
        val two = layout.key("2")
        assertEquals('2'.code, two.keysymFor(0))
        assertEquals('@'.code, two.keysymFor(Mod.SHIFT))
        assertEquals("caps must leave punctuation alone", '2'.code, two.keysymFor(Mod.CAPS_LOCK))

        val a = layout.key("a")
        assertEquals('a'.code, a.keysymFor(0))
        assertEquals('A'.code, a.keysymFor(Mod.SHIFT))
        assertEquals('A'.code, a.keysymFor(Mod.CAPS_LOCK))
        assertEquals("shift on top of caps is lower case", 'a'.code, a.keysymFor(Mod.SHIFT or Mod.CAPS_LOCK))
    }

    /** The label follows the keysym, because the glyph printed on the key is the promise. */
    @Test
    fun `labels case themselves the same way the keysyms do`() {
        val layout = KeyboardLayout.whole(functionRow = false)
        assertEquals("a", layout.key("a").labelFor(0))
        assertEquals("A", layout.key("a").labelFor(Mod.SHIFT))
        assertEquals("@", layout.key("2").labelFor(Mod.SHIFT))
        assertEquals("shift", layout.key("shift").labelFor(Mod.SHIFT))
    }

    /** Only the keys a fingertip hides and you can mistype. A bubble reading "control" helps nobody. */
    @Test
    fun `only single glyph keys pop a preview`() {
        val layout = KeyboardLayout.whole(functionRow = true)
        assertTrue(layout.key("a").previews)
        assertTrue(layout.key("←").previews)
        assertTrue(!layout.key("shift").previews)
        assertTrue(!layout.key("F1").previews)
        assertTrue("the click keys are a pointer, not a character", !layout.key("click").previews)
    }

    /** The duplicated inner columns are what let the two halves be the same width. */
    @Test
    fun `the split keyboard reaches its inner columns from either thumb`() {
        val layout = KeyboardLayout.apart(functionRow = false, gutterUnits = KeyboardLayout.GUTTER_UNITS)
        listOf("6", "y", "h", "n").forEach { label ->
            val copies = layout.rows.flatten().count { it.key.label == label }
            assertEquals("$label should be on both halves", 2, copies)
        }
    }

    private fun forEachRow(layout: Layout, check: (Int, List<Slot>) -> Unit) {
        assertTrue(layout.rows.isNotEmpty())
        layout.rows.forEachIndexed { index, row -> check(index, row) }
    }

    private fun Layout.key(label: String): Key =
        rows.flatten().first { it.key.label == label }.key
}
