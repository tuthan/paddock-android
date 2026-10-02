package io.github.tuthan.paddock.terminal

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a soft keyboard's edits to the hidden field become as terminal keys. */
class SoftInputTest {
    private val s = SoftInput.SENTINEL
    private fun chars(vararg cps: Int) = cps.map { SoftKey.Char(it) }

    @Test fun typedCharactersAreSentInOrder() {
        assertEquals(chars('a'.code), SoftInput.keys(s + "a"))
        assertEquals(chars('l'.code, 's'.code, ' '.code), SoftInput.keys(s + "ls "), "a swipe-typed word and its space arrive as one edit")
    }

    @Test fun anEditThatChangesNothingSendsNothing() {
        assertEquals(emptyList(), SoftInput.keys(s))
    }

    @Test fun deletingSentinelCharactersIsBackspaces() {
        assertEquals(listOf(SoftKey.Backspace), SoftInput.keys(s.dropLast(1)))
        assertEquals(listOf(SoftKey.Backspace, SoftKey.Backspace), SoftInput.keys(""))
    }

    @Test fun aLineBreakIsEnterWhateverItsForm() {
        assertEquals(listOf(SoftKey.Enter), SoftInput.keys(s + "\n"))
        assertEquals(listOf(SoftKey.Enter), SoftInput.keys(s + "\r"))
        assertEquals(listOf(SoftKey.Enter), SoftInput.keys(s + "\r\n"), "CRLF is one Enter, not two")
        assertEquals(chars('a'.code) + SoftKey.Enter + chars('b'.code), SoftInput.keys(s + "a\nb"))
    }

    @Test fun anInsertAfterTheCaretMovedInsideTheSentinelIsJustTheInsert() {
        assertEquals(chars('x'.code), SoftInput.keys(s.take(1) + "x" + s.drop(1)))
        assertEquals(chars('x'.code), SoftInput.keys("x" + s))
    }

    @Test fun aReplacementIsBackspacesThenTheNewText() {
        assertEquals(listOf(SoftKey.Backspace, SoftKey.Backspace) + chars('o'.code, 'k'.code), SoftInput.keys("ok"))
    }

    @Test fun anEmojiIsOneCodePointNotTwoSurrogates() {
        val smile = "😀"
        assertEquals(chars(smile.codePointAt(0)), SoftInput.keys(s + smile))
    }

    @Test fun theSentinelItselfIsNeverSent() {
        assertTrue(SoftInput.keys(s + "​").isEmpty())
    }

    @Test fun enterAndBackspaceHaveTheirTerminalBytes() {
        assertContentEquals(byteArrayOf(0x0D), SoftInput.encode(SoftKey.Enter, ctrl = false).bytes)
        assertContentEquals(byteArrayOf(0x7F), SoftInput.encode(SoftKey.Backspace, ctrl = false).bytes)
    }

    @Test fun aCharacterIsItsUtf8() {
        assertContentEquals("é".toByteArray(), SoftInput.encode(SoftKey.Char('é'.code), ctrl = false).bytes)
        assertFalse(SoftInput.encode(SoftKey.Char('a'.code), ctrl = false).usedCtrl)
    }

    @Test fun stickyCtrlTurnsALetterIntoItsControlCodeAndIsSpent() {
        val c = SoftInput.encode(SoftKey.Char('c'.code), ctrl = true)
        assertContentEquals(byteArrayOf(0x03), c.bytes)
        assertTrue(c.usedCtrl)
        assertContentEquals(byteArrayOf(0x03), SoftInput.encode(SoftKey.Char('C'.code), ctrl = true).bytes, "the shifted letter is the same chord")
        assertContentEquals(byteArrayOf(0x04), SoftInput.encode(SoftKey.Char('d'.code), ctrl = true).bytes)
    }

    @Test fun stickyCtrlOnACharacterItHasNoMeaningForSendsTheCharacterAndIsStillSpent() {
        val one = SoftInput.encode(SoftKey.Char('1'.code), ctrl = true)
        assertContentEquals("1".toByteArray(), one.bytes)
        assertTrue(one.usedCtrl, "one tap, one use, whatever the key")
    }

    @Test fun aCodePointATerminalCannotTakeEncodesToNothing() {
        assertNull(SoftInput.encode(SoftKey.Char(0x7F), ctrl = false).bytes)
    }
}
