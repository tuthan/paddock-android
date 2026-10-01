package io.github.tuthan.paddock.ui

import android.view.KeyEvent as K
import io.github.tuthan.paddock.ui.components.HardwareKeys
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HardwareKeysTest {
    private fun enc(code: Int, uni: Int = 0, shift: Boolean = false, alt: Boolean = false, ctrl: Boolean = false) = HardwareKeys.encode(code, uni, shift, alt, ctrl)
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test fun theNamedKeysAreXtermsBytes() {
        assertArrayEquals(bytes(0x0D), enc(K.KEYCODE_ENTER)); assertArrayEquals(bytes(0x0D), enc(K.KEYCODE_NUMPAD_ENTER))
        assertArrayEquals(bytes(0x1B), enc(K.KEYCODE_ESCAPE)); assertArrayEquals(bytes(0x09), enc(K.KEYCODE_TAB))
        assertArrayEquals(bytes(0x7F), enc(K.KEYCODE_DEL)); assertArrayEquals("\u001B[3~".toByteArray(), enc(K.KEYCODE_FORWARD_DEL))
        assertArrayEquals("\u001B[A".toByteArray(), enc(K.KEYCODE_DPAD_UP)); assertArrayEquals("\u001B[D".toByteArray(), enc(K.KEYCODE_DPAD_LEFT))
        assertArrayEquals("\u001B[5~".toByteArray(), enc(K.KEYCODE_PAGE_UP)); assertArrayEquals("\u001B[H".toByteArray(), enc(K.KEYCODE_MOVE_HOME))
        assertArrayEquals("\u001B[Z".toByteArray(), enc(K.KEYCODE_TAB, shift = true))
        assertArrayEquals("\u001B[1;5C".toByteArray(), enc(K.KEYCODE_DPAD_RIGHT, ctrl = true))
    }

    @Test fun functionKeys() {
        assertArrayEquals("\u001BOP".toByteArray(), enc(K.KEYCODE_F1)); assertArrayEquals("\u001B[15~".toByteArray(), enc(K.KEYCODE_F5))
        assertArrayEquals("\u001B[24~".toByteArray(), enc(K.KEYCODE_F12))
    }

    @Test fun charactersComeFromTheirCodePointAndShiftIsAlreadyInIt() {
        assertArrayEquals("a".toByteArray(), enc(K.KEYCODE_A, 'a'.code)); assertArrayEquals("A".toByteArray(), enc(K.KEYCODE_A, 'A'.code, shift = true))
        assertArrayEquals("é".toByteArray(), enc(K.KEYCODE_E, 'é'.code)); assertArrayEquals("?".toByteArray(), enc(K.KEYCODE_SLASH, '?'.code, shift = true))
        assertArrayEquals(" ".toByteArray(), enc(K.KEYCODE_SPACE, ' '.code))
    }

    @Test fun ctrlChordsComeFromTheKeyCodeWhateverTheLayoutSaysTheUnicodeIs() {
        assertArrayEquals(bytes(3), enc(K.KEYCODE_C, 'c'.code, ctrl = true)); assertArrayEquals(bytes(3), enc(K.KEYCODE_C, 3, ctrl = true)); assertArrayEquals(bytes(3), enc(K.KEYCODE_C, 0, ctrl = true))
        assertArrayEquals(bytes(4), enc(K.KEYCODE_D, 0, ctrl = true)); assertArrayEquals(bytes(26), enc(K.KEYCODE_Z, 0, ctrl = true))
        assertArrayEquals(bytes(0), enc(K.KEYCODE_SPACE, ' '.code, ctrl = true)); assertArrayEquals(bytes(0x1B), enc(K.KEYCODE_LEFT_BRACKET, 0, ctrl = true))
        assertNull(enc(K.KEYCODE_1, '1'.code, ctrl = true))
    }

    @Test fun altPrefixesEscape() {
        assertArrayEquals(bytes(0x1B, 'b'.code), enc(K.KEYCODE_B, 'b'.code, alt = true)); assertArrayEquals(bytes(0x1B, 0x03), enc(K.KEYCODE_C, 0, alt = true, ctrl = true))
    }

    @Test fun keysThatSendNothingSendNothing() {
        for (code in listOf(K.KEYCODE_SHIFT_LEFT, K.KEYCODE_CTRL_LEFT, K.KEYCODE_ALT_LEFT, K.KEYCODE_META_LEFT, K.KEYCODE_CAPS_LOCK, K.KEYCODE_VOLUME_UP, K.KEYCODE_BACK, K.KEYCODE_HOME, K.KEYCODE_UNKNOWN)) {
            assertNull("key $code", enc(code, 0))
        }
        assertNull(enc(K.KEYCODE_A, 0x07)); assertNull(enc(K.KEYCODE_A, 0x7F))
    }
}
