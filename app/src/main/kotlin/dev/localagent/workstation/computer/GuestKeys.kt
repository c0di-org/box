package dev.localagent.workstation.computer

/**
 * The modifier bits Box tracks for itself, so a latch has something to be a latch *of*.
 *
 * They are not sent anywhere. X11 has no modifier bitfield on the wire — a held Shift is a
 * `Shift_L` that went down and has not come up — so these exist only to answer two local
 * questions: which keysym a key should send right now, and how the key should be drawn.
 */
internal object Mod {
    const val SHIFT = 1
    const val CONTROL = 1 shl 1
    const val ALT = 1 shl 2
    const val SUPER = 1 shl 3
    const val CAPS_LOCK = 1 shl 4
}

/** What pressing a key does. The two pointer buttons carry no keysym and go out as clicks. */
internal enum class KeyAction { TYPE, LEFT_CLICK, RIGHT_CLICK }

/**
 * One key on the on-screen keyboard, described in X11 keysyms.
 *
 * Keysyms rather than Android key codes, the opposite of every other input path in Box:
 * [DesktopView][dev.localagent.workstation.ui.DesktopView] translates a `KeyEvent` late because
 * only it knows which character a physical key produced under the installed layout. A drawn key
 * has no layout to consult — the glyph printed on it *is* the promise — so it carries the keysym
 * it means and nothing has to be recovered later.
 *
 * @param width in units of one alphabetic key.
 * @param modifier non-zero for a latching modifier key: the [Mod] bit it contributes.
 * @param shifted the glyph and keysym this key means while Shift is on. Null for keys that mean
 * the same either way, and derived for letters — see [KeyboardLayout].
 */
internal data class Key(
    val label: String,
    val keysym: Int,
    val width: Float = 1f,
    val modifier: Int = 0,
    val shiftedLabel: String? = null,
    val shiftedKeysym: Int = keysym,
    val repeats: Boolean = false,
    val action: KeyAction = KeyAction.TYPE,
) {
    /** Keys that aren't letters: modifiers, pointer buttons, and anything that repeats. */
    val isSpecial: Boolean get() = modifier != 0 || repeats || action != KeyAction.TYPE

    /**
     * Whether a press pops a preview bubble above the key.
     *
     * Only single-glyph keys get one, which is exactly the set a fingertip hides and the set you
     * can mistype: letters, digits, punctuation, arrows. A bubble reading "control" would be both
     * ugly and pointless — those keys are wide enough to hit and their state shows in their own
     * colour.
     */
    val previews: Boolean get() = action == KeyAction.TYPE && modifier == 0 && label.length == 1

    /**
     * The keysym to send under the modifiers currently on.
     *
     * Shift is applied here rather than left to the guest, because the wire carries a keysym and a
     * keysym is a *character*: `2` and `@` are different ones, and sending `2` with `Shift_L` held
     * asks QEMU's keymap to undo a shift it never applied. Caps Lock only reaches letters, which is
     * what makes it a Caps Lock and not a second Shift.
     */
    fun keysymFor(modifiers: Int): Int {
        val shift = modifiers and Mod.SHIFT != 0
        val caps = modifiers and Mod.CAPS_LOCK != 0
        if (isLetter) return if (shift != caps) shiftedKeysym else keysym
        return if (shift) shiftedKeysym else keysym
    }

    /** The glyph to draw under the modifiers currently on. Letters case themselves; see above. */
    fun labelFor(modifiers: Int): String {
        val shift = modifiers and Mod.SHIFT != 0
        val caps = modifiers and Mod.CAPS_LOCK != 0
        if (isLetter) return if (shift != caps) label.uppercase() else label
        return if (shift) shiftedLabel ?: label else label
    }

    private val isLetter: Boolean get() = label.length == 1 && label[0].isLetter()
}

/** A key placed on the grid. [x] and [width] are in key-widths from the left edge. */
internal class Slot(val key: Key, val x: Float, val width: Float)

/**
 * A whole keyboard, measured in key-widths rather than pixels so it survives any screen.
 *
 * @param units total width of the keyboard, gutter included.
 * @param keyAspect cap on key height as a fraction of key width.
 * @param gutterUnits width of the empty column between the halves, or null when the keyboard is
 * whole.
 */
internal class Layout(
    val rows: List<List<Slot>>,
    val units: Float,
    val keyAspect: Float,
    val gutterUnits: Float? = null,
) {
    val split: Boolean get() = gutterUnits != null
}

/**
 * A US QWERTY layout built for driving a Debian desktop rather than for typing prose.
 *
 * Escape, Tab, Control, Alt, Super, the arrows, the function row and both mouse buttons are
 * discrete keys always on screen. Each is load-bearing: Control for the terminal the guest is
 * mostly for, Escape and Tab for the editors in it, Super and the pointer buttons for Openbox,
 * whose root menu is the only way to launch anything and opens on button 3.
 *
 * Two shapes, neither a compromise of the other:
 *
 * - **Whole** — every row [WHOLE_UNITS] wide, edge to edge. The biggest keys the screen allows.
 * - **Split** — each half [HALF_UNITS] wide, pinned to its edge, so the inner columns come out
 *   from under the middle of the screen and land under the thumbs. The halves must be equal width
 *   or the columns will not line up down the rows, which is what the duplicated 6, Y, H, N and
 *   space pay for — and those duplicates double as the keys neither thumb should cross for.
 *
 * **The gutter is a variable, not a constant, and that is what lets the keyboard be resized.** A
 * keyboard given less height than it wants must give something up, and the usual answer — keep the
 * width, squash the rows — is the worst: keys twice as wide as tall are harder to hit, not easier.
 * So keys keep their shape and lose width, and the lost width becomes gutter. [GUTTER_UNITS] is
 * only where a keyboard split by choice rather than by squeeze starts.
 *
 * The shape kept under the drag is [KEY_ASPECT], roughly square — not the taller [SPLIT_ASPECT].
 * At the small end *height* is what is scarce and width is going spare, so a key is never made
 * narrower than it is tall to feed a gutter already wider than either half needs. [SPLIT_ASPECT]
 * caps the other end, where width is short.
 *
 * The arrows sit inline at the end of the bottom row, as a 60% keyboard does it — an inverted T
 * would cost a whole row of height the guest's screen is paying for.
 */
internal object KeyboardLayout {

    /** Width of every row when the keyboard is whole, in units of one alphabetic key. */
    const val WHOLE_UNITS = 15f

    /** Width of each half when it's split. */
    const val HALF_UNITS = 8f

    /**
     * The gutter a keyboard split by choice gets. It is what pushes the inner keys outward, so it
     * has to be wide enough to be worth the key width it costs — a token gap would take the price
     * and deliver none of the reach.
     */
    const val GUTTER_UNITS = 2.5f

    /**
     * The narrowest gutter a keyboard split by a drag may show. The first pixel of squeeze asks for
     * a gutter of almost nothing, and a hairline crack down the middle of a keyboard reads as a
     * rendering fault rather than as a split — so the halves come apart by a visible amount or not
     * at all.
     */
    const val MIN_GUTTER_UNITS = 1.5f

    /**
     * The widest gutter there's any point in. Past this the halves have stopped being one keyboard,
     * and the keys are small enough that the height being saved isn't buying anything.
     */
    const val MAX_GUTTER_UNITS = 12f

    /**
     * Key height as a fraction of key width — a hair wider than square, which is the shape a key is
     * when nothing is in short supply. Keys read wrong when they're tall and are hard to aim at
     * when they're narrow, so this is also the shape a squeezed keyboard shrinks *along*: both
     * dimensions together, rather than one at the other's expense.
     */
    const val KEY_ASPECT = 0.95f

    /**
     * How much taller than wide a split key may go when it's the width that has run out — at the
     * moment of the split, and whenever the band is taller than the keys can use.
     */
    const val SPLIT_ASPECT = 1.1f

    // Keysyms that aren't a character. Latin-1 is the identity mapping, so everything else below is
    // written as a literal glyph and converted by `sym`.
    const val ESCAPE = 0xFF1B
    const val TAB = 0xFF09
    const val BACKSPACE = 0xFF08
    const val RETURN = 0xFF0D
    const val CAPS_LOCK = 0xFFE5
    const val SHIFT_LEFT = 0xFFE1
    const val SHIFT_RIGHT = 0xFFE2
    const val CONTROL_LEFT = 0xFFE3
    const val ALT_LEFT = 0xFFE9
    const val SUPER_LEFT = 0xFFEB
    const val LEFT = 0xFF51
    const val UP = 0xFF52
    const val RIGHT = 0xFF53
    const val DOWN = 0xFF54
    const val F1 = 0xFFBE

    private fun sym(glyph: String) = glyph[0].code

    /** A letter. Its shifted self is the same key in upper case, which is a different keysym. */
    private fun letter(label: String) =
        Key(label, sym(label), shiftedLabel = label.uppercase(), shiftedKeysym = sym(label.uppercase()))

    /** A key with two glyphs printed on it, and a different keysym for each. */
    private fun pair(label: String, shifted: String, width: Float = 1f) =
        Key(label, sym(label), width, shiftedLabel = shifted, shiftedKeysym = sym(shifted))

    private fun k(label: String, keysym: Int, width: Float = 1f) = Key(label, keysym, width)

    private fun mod(label: String, keysym: Int, bit: Int, width: Float) =
        Key(label, keysym, width, modifier = bit)

    private fun rep(label: String, keysym: Int, width: Float = 1f) =
        Key(label, keysym, width, repeats = true)

    private fun click(label: String, action: KeyAction, width: Float) =
        Key(label, 0, width, action = action)

    // MARK: - Whole

    // The number row is a plain 15-column grid rather than the usual wide backspace. At this key
    // size a uniform grid is easier to hit than a varied one: every column lines up with the one
    // above it, so your thumb learns one spacing instead of two.
    private val wholeRows: List<List<Key>> = listOf(
        listOf(
            k("esc", ESCAPE),
            pair("`", "~"),
            pair("1", "!"), pair("2", "@"), pair("3", "#"), pair("4", "$"), pair("5", "%"),
            pair("6", "^"), pair("7", "&"), pair("8", "*"), pair("9", "("), pair("0", ")"),
            pair("-", "_"), pair("=", "+"),
            rep("delete", BACKSPACE),
        ),
        listOf(
            k("tab", TAB, 1.5f),
            letter("q"), letter("w"), letter("e"), letter("r"), letter("t"), letter("y"),
            letter("u"), letter("i"), letter("o"), letter("p"),
            pair("[", "{"), pair("]", "}"), pair("\\", "|", 1.5f),
        ),
        listOf(
            mod("caps", CAPS_LOCK, Mod.CAPS_LOCK, 1.75f),
            letter("a"), letter("s"), letter("d"), letter("f"), letter("g"), letter("h"),
            letter("j"), letter("k"), letter("l"),
            pair(";", ":"), pair("'", "\""),
            k("return", RETURN, 2.25f),
        ),
        listOf(
            mod("shift", SHIFT_LEFT, Mod.SHIFT, 2.25f),
            letter("z"), letter("x"), letter("c"), letter("v"), letter("b"), letter("n"),
            letter("m"),
            pair(",", "<"), pair(".", ">"), pair("/", "?"),
            mod("shift", SHIFT_RIGHT, Mod.SHIFT, 2.75f),
        ),
        listOf(
            mod("ctrl", CONTROL_LEFT, Mod.CONTROL, 1.5f),
            mod("alt", ALT_LEFT, Mod.ALT, 1.5f),
            mod("super", SUPER_LEFT, Mod.SUPER, 1.75f),
            k("", sym(" "), 3.5f),
            click("click", KeyAction.LEFT_CLICK, 1.375f),
            click("right", KeyAction.RIGHT_CLICK, 1.375f),
            rep("←", LEFT), rep("↑", UP), rep("↓", DOWN), rep("→", RIGHT),
        ),
    )

    private val wholeFunctionRow = (1..12).map { k("F$it", F1 + it - 1, 1.25f) }

    // MARK: - Split

    /** A row of a split keyboard. Each half totals [HALF_UNITS]. */
    private class Half(val left: List<Key>, val right: List<Key>)

    private val splitRows: List<Half> = listOf(
        Half(
            left = listOf(
                k("esc", ESCAPE), pair("`", "~"),
                pair("1", "!"), pair("2", "@"), pair("3", "#"), pair("4", "$"), pair("5", "%"),
                pair("6", "^"),
            ),
            right = listOf(
                pair("6", "^"), pair("7", "&"), pair("8", "*"), pair("9", "("), pair("0", ")"),
                pair("-", "_"), pair("=", "+"),
                rep("delete", BACKSPACE),
            ),
        ),
        Half(
            left = listOf(
                k("tab", TAB, 2f),
                letter("q"), letter("w"), letter("e"), letter("r"), letter("t"), letter("y"),
            ),
            right = listOf(
                letter("y"), letter("u"), letter("i"), letter("o"), letter("p"),
                pair("[", "{"), pair("]", "}"), pair("\\", "|"),
            ),
        ),
        Half(
            left = listOf(
                mod("caps", CAPS_LOCK, Mod.CAPS_LOCK, 2f),
                letter("a"), letter("s"), letter("d"), letter("f"), letter("g"), letter("h"),
            ),
            right = listOf(
                letter("h"), letter("j"), letter("k"), letter("l"),
                pair(";", ":"), pair("'", "\""),
                k("return", RETURN, 2f),
            ),
        ),
        Half(
            left = listOf(
                mod("shift", SHIFT_LEFT, Mod.SHIFT, 2f),
                letter("z"), letter("x"), letter("c"), letter("v"), letter("b"), letter("n"),
            ),
            right = listOf(
                letter("n"), letter("m"),
                pair(",", "<"), pair(".", ">"), pair("/", "?"),
                mod("shift", SHIFT_RIGHT, Mod.SHIFT, 3f),
            ),
        ),
        Half(
            left = listOf(
                mod("ctrl", CONTROL_LEFT, Mod.CONTROL, 1.5f),
                mod("alt", ALT_LEFT, Mod.ALT, 1.5f),
                mod("super", SUPER_LEFT, Mod.SUPER, 2f),
                k("", sym(" "), 3f),
            ),
            right = listOf(
                k("", sym(" "), 1.5f),
                rep("←", LEFT), rep("↑", UP), rep("↓", DOWN), rep("→", RIGHT),
                click("click", KeyAction.LEFT_CLICK, 1.25f),
                click("right", KeyAction.RIGHT_CLICK, 1.25f),
            ),
        ),
    )

    private val splitFunctionRow = Half(
        left = (1..6).map { k("F$it", F1 + it - 1, 1.3333f) },
        right = (7..12).map { k("F$it", F1 + it - 1, 1.3333f) },
    )

    // MARK: - Placement

    /** The keyboard the settings ask for, at the size it would like to be. */
    fun natural(functionRow: Boolean, split: Boolean): Layout =
        if (split) apart(functionRow, GUTTER_UNITS) else whole(functionRow)

    fun whole(functionRow: Boolean): Layout {
        val rows = if (functionRow) listOf(wholeFunctionRow) + wholeRows else wholeRows
        return Layout(rows.map { place(it, 0f) }, WHOLE_UNITS, KEY_ASPECT)
    }

    /** Split, with [gutterUnits] of nothing between the halves. */
    fun apart(functionRow: Boolean, gutterUnits: Float): Layout {
        val rows = if (functionRow) listOf(splitFunctionRow) + splitRows else splitRows
        val units = HALF_UNITS * 2 + gutterUnits
        return Layout(
            rows.map { place(it.left, 0f) + place(it.right, units - HALF_UNITS) },
            units,
            SPLIT_ASPECT,
            gutterUnits,
        )
    }

    private fun place(keys: List<Key>, from: Float): List<Slot> {
        var x = from
        return keys.map { key -> Slot(key, x, key.width).also { x += key.width } }
    }
}
