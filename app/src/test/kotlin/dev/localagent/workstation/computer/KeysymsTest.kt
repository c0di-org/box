package dev.localagent.workstation.computer

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeysymsTest {

    @Test
    fun `a character key uses the character, not the key code`() {
        // Shift-2 on a UK keyboard is a quotation mark. Deriving the character from the key code
        // would type 2, and the whole layout would be wrong for anyone not on US-ASCII.
        assertEquals('"'.code, Keysyms.of(KeyEvent.KEYCODE_2, '"'.code))
        assertEquals('a'.code, Keysyms.of(KeyEvent.KEYCODE_A, 'a'.code))
        assertEquals('A'.code, Keysyms.of(KeyEvent.KEYCODE_A, 'A'.code))
    }

    @Test
    fun `latin-1 is the identity mapping and everything else moves to the unicode plane`() {
        assertEquals(0x20, Keysyms.ofCharacter(0x20)) // space
        assertEquals(0xFF, Keysyms.ofCharacter(0xFF)) // y with diaeresis, the last identity
        assertEquals(0x01000100, Keysyms.ofCharacter(0x100)) // the first one that has to move
        assertEquals(0x010003BB, Keysyms.ofCharacter(0x3BB)) // lambda
    }

    @Test
    fun `keys that produce no character still work`() {
        assertEquals(0xFF52, Keysyms.of(KeyEvent.KEYCODE_DPAD_UP, 0))
        assertEquals(0xFF0D, Keysyms.of(KeyEvent.KEYCODE_ENTER, 0))
        assertEquals(0xFF08, Keysyms.of(KeyEvent.KEYCODE_DEL, 0))
        assertEquals(0xFF1B, Keysyms.of(KeyEvent.KEYCODE_ESCAPE, 0))
    }

    @Test
    fun `a special key wins even when a character is also reported`() {
        // Enter arrives with a carriage return attached on some keyboards. Taken as a character it
        // becomes keysym 13, which is not Return, and the guest sees nothing useful.
        assertEquals(0xFF0D, Keysyms.of(KeyEvent.KEYCODE_ENTER, '\r'.code))
        assertEquals(0xFF09, Keysyms.of(KeyEvent.KEYCODE_TAB, '\t'.code))
    }

    @Test
    fun `left and right modifiers stay distinct`() {
        // A guest that treats right-Alt as AltGr behaves wrongly if both sides collapse into one.
        assertEquals(0xFFE9, Keysyms.of(KeyEvent.KEYCODE_ALT_LEFT, 0))
        assertEquals(0xFFEA, Keysyms.of(KeyEvent.KEYCODE_ALT_RIGHT, 0))
        assertEquals(0xFFE3, Keysyms.of(KeyEvent.KEYCODE_CTRL_LEFT, 0))
        assertEquals(0xFFE4, Keysyms.of(KeyEvent.KEYCODE_CTRL_RIGHT, 0))
    }

    @Test
    fun `function keys run in order`() {
        assertEquals(0xFFBE, Keysyms.of(KeyEvent.KEYCODE_F1, 0))
        assertEquals(0xFFC9, Keysyms.of(KeyEvent.KEYCODE_F12, 0))
    }

    @Test
    fun `a key Box cannot name is dropped rather than guessed`() {
        // Volume keys, the fold's own hardware buttons. Sending a plausible-looking keysym would
        // type something into whatever the guest has focused.
        assertNull(Keysyms.of(KeyEvent.KEYCODE_VOLUME_UP, 0))
    }
}
