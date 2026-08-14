package dev.localagent.workstation.computer

import android.view.KeyEvent

/**
 * Android key codes to X11 keysyms.
 *
 * The guest is a Linux desktop, so keysyms are what it wants to hear; there is no more neutral
 * currency, and inventing one would mean translating twice.
 *
 * The rule that matters: **a character, when there is one; a key, when there is not.** Android
 * hands over the character a key produced under the modifiers actually held, which already
 * accounts for layout, Shift, and anything a physical keyboard in DeX does. Deriving a character
 * from the key code instead would type `2` when someone presses shift-2 on a UK keyboard
 * expecting `"`. Only when there is no character — arrows, function keys, modifiers — does the key
 * code decide, and then it maps to a fixed keysym.
 *
 * Plain integers rather than a `KeyEvent`, so a lookup table and a range check can be tested
 * without a device. `KEYCODE_*` are compile-time constants and inline.
 */
internal object Keysyms {

    /** Null when Box has no idea what this key is; the caller drops it rather than guessing. */
    fun of(keyCode: Int, unicodeChar: Int): Int? {
        // A modifier's own character is meaningless, and some devices report one anyway.
        special(keyCode)?.let { return it }
        if (unicodeChar != 0) return ofCharacter(unicodeChar)
        return null
    }

    /**
     * A character to a keysym.
     *
     * Latin-1 is the identity mapping — an accident of history that X11's keysym space was built
     * around ASCII — and everything above it lives in the Unicode plane at 0x01000000.
     */
    fun ofCharacter(codePoint: Int): Int =
        if (codePoint in 0x20..0xFF) codePoint else UNICODE_BASE + codePoint

    private fun special(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> 0xFF0D // Return
        KeyEvent.KEYCODE_DEL -> 0xFF08 // BackSpace
        KeyEvent.KEYCODE_FORWARD_DEL -> 0xFFFF // Delete
        KeyEvent.KEYCODE_TAB -> 0xFF09
        KeyEvent.KEYCODE_ESCAPE -> 0xFF1B
        KeyEvent.KEYCODE_MOVE_HOME -> 0xFF50
        KeyEvent.KEYCODE_DPAD_LEFT -> 0xFF51
        KeyEvent.KEYCODE_DPAD_UP -> 0xFF52
        KeyEvent.KEYCODE_DPAD_RIGHT -> 0xFF53
        KeyEvent.KEYCODE_DPAD_DOWN -> 0xFF54
        KeyEvent.KEYCODE_PAGE_UP -> 0xFF55
        KeyEvent.KEYCODE_PAGE_DOWN -> 0xFF56
        KeyEvent.KEYCODE_MOVE_END -> 0xFF57
        KeyEvent.KEYCODE_INSERT -> 0xFF63
        KeyEvent.KEYCODE_MENU -> 0xFF67
        KeyEvent.KEYCODE_SYSRQ -> 0xFF61 // Print
        KeyEvent.KEYCODE_SCROLL_LOCK -> 0xFF14
        KeyEvent.KEYCODE_BREAK -> 0xFF13 // Pause
        KeyEvent.KEYCODE_NUM_LOCK -> 0xFF7F
        KeyEvent.KEYCODE_CAPS_LOCK -> 0xFFE5

        // Left and right are separate keysyms and worth keeping apart: a guest that remaps only one
        // Control, or treats right-Alt as AltGr, is common and behaves wrongly if both collapse.
        KeyEvent.KEYCODE_SHIFT_LEFT -> 0xFFE1
        KeyEvent.KEYCODE_SHIFT_RIGHT -> 0xFFE2
        KeyEvent.KEYCODE_CTRL_LEFT -> 0xFFE3
        KeyEvent.KEYCODE_CTRL_RIGHT -> 0xFFE4
        KeyEvent.KEYCODE_ALT_LEFT -> 0xFFE9
        KeyEvent.KEYCODE_ALT_RIGHT -> 0xFFEA
        KeyEvent.KEYCODE_META_LEFT -> 0xFFEB // Super_L
        KeyEvent.KEYCODE_META_RIGHT -> 0xFFEC

        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 ->
            0xFFBE + (keyCode - KeyEvent.KEYCODE_F1)

        else -> null
    }

    private const val UNICODE_BASE = 0x01000000
}
