package io.github.tuthan.paddock.terminal

import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyEncoderTest {
    private fun s(b: ByteArray?) = b!!.toString(Charsets.ISO_8859_1).replace("\u001B", "\\e").replace("\r", "\\r").replace("\n", "\\n")
    private fun named(k: NamedKey, shift: Boolean = false, alt: Boolean = false, ctrl: Boolean = false) = s(KeyEncoder.named(k, Mods(shift, alt, ctrl)))

    @Test fun theKeysOfTheStripAreTheBytesAnXtermSends() {
        assertEquals("\\e", named(NamedKey.Escape)); assertEquals("\\r", named(NamedKey.Enter)); assertEquals("\t", named(NamedKey.Tab))
        assertEquals("\\e[A", named(NamedKey.Up)); assertEquals("\\e[B", named(NamedKey.Down)); assertEquals("\\e[C", named(NamedKey.Right)); assertEquals("\\e[D", named(NamedKey.Left))
        assertContentEquals(byteArrayOf(3), KeyEncoder.interrupt)
    }

    @Test fun editingKeys() {
        assertContentEquals(byteArrayOf(0x7F), KeyEncoder.named(NamedKey.Backspace))
        assertContentEquals(byteArrayOf(0x08), KeyEncoder.named(NamedKey.Backspace, Mods(ctrl = true)))
        assertEquals("\\e[3~", named(NamedKey.Delete)); assertEquals("\\e[2~", named(NamedKey.Insert))
        assertEquals("\\e[H", named(NamedKey.Home)); assertEquals("\\e[F", named(NamedKey.End))
        assertEquals("\\e[5~", named(NamedKey.PageUp)); assertEquals("\\e[6~", named(NamedKey.PageDown))
        assertEquals("\\e[Z", named(NamedKey.Tab, shift = true))
    }

    @Test fun functionKeys() {
        val want = listOf("\\eOP", "\\eOQ", "\\eOR", "\\eOS", "\\e[15~", "\\e[17~", "\\e[18~", "\\e[19~", "\\e[20~", "\\e[21~", "\\e[23~", "\\e[24~")
        assertEquals(want, NamedKey.entries.filter { it.name.matches(Regex("F\\d+")) }.map { named(it) })
    }

    @Test fun modifiersGoIntoTheParameterOfTheKeysThatHaveOne() {
        assertEquals("\\e[1;2A", named(NamedKey.Up, shift = true)); assertEquals("\\e[1;3A", named(NamedKey.Up, alt = true))
        assertEquals("\\e[1;5C", named(NamedKey.Right, ctrl = true)); assertEquals("\\e[1;8D", named(NamedKey.Left, true, true, true))
        assertEquals("\\e[3;5~", named(NamedKey.Delete, ctrl = true)); assertEquals("\\e[1;2P", named(NamedKey.F1, shift = true)); assertEquals("\\e[15;3~", named(NamedKey.F5, alt = true))
        assertEquals("\\e[1;5H", named(NamedKey.Home, ctrl = true))
    }

    @Test fun altOnAKeyWithoutAParameterIsAnEscapePrefix() {
        assertEquals("\\e\\r", named(NamedKey.Enter, alt = true)); assertEquals("\\e\\e", named(NamedKey.Escape, alt = true))
        assertContentEquals(byteArrayOf(0x1B, 0x7F), KeyEncoder.named(NamedKey.Backspace, Mods(alt = true)))
        assertEquals("\\n", named(NamedKey.Enter, ctrl = true))
    }

    @Test fun textIsUtf8() {
        assertContentEquals("a".toByteArray(), KeyEncoder.text('a'.code)); assertContentEquals("é".toByteArray(), KeyEncoder.text('é'.code))
        assertContentEquals("日".toByteArray(), KeyEncoder.text('日'.code)); assertContentEquals("😀".toByteArray(), KeyEncoder.text(0x1F600))
        assertContentEquals(" ".toByteArray(), KeyEncoder.text(' '.code))
    }

    @Test fun ctrlLettersAndTheControlPunctuation() {
        for (c in 'a'..'z') {
            assertContentEquals(byteArrayOf((c - 'a' + 1).toByte()), KeyEncoder.text(c.code, Mods(ctrl = true)))
            assertContentEquals(byteArrayOf((c - 'a' + 1).toByte()), KeyEncoder.text(c.uppercaseChar().code, Mods(ctrl = true)))
        }
        assertContentEquals(byteArrayOf(0), KeyEncoder.text(' '.code, Mods(ctrl = true))); assertContentEquals(byteArrayOf(0), KeyEncoder.text('@'.code, Mods(ctrl = true)))
        assertContentEquals(byteArrayOf(0x1B), KeyEncoder.text('['.code, Mods(ctrl = true))); assertContentEquals(byteArrayOf(0x1C), KeyEncoder.text('\\'.code, Mods(ctrl = true)))
        assertContentEquals(byteArrayOf(0x1D), KeyEncoder.text(']'.code, Mods(ctrl = true))); assertContentEquals(byteArrayOf(0x1E), KeyEncoder.text('^'.code, Mods(ctrl = true)))
        assertContentEquals(byteArrayOf(0x1F), KeyEncoder.text('_'.code, Mods(ctrl = true))); assertContentEquals(byteArrayOf(0x7F), KeyEncoder.text('?'.code, Mods(ctrl = true)))
    }

    @Test fun altPrefixesEscape() {
        assertContentEquals(byteArrayOf(0x1B, 'b'.code.toByte()), KeyEncoder.text('b'.code, Mods(alt = true)))
        assertContentEquals(byteArrayOf(0x1B, 0x03), KeyEncoder.text('c'.code, Mods(alt = true, ctrl = true)))
    }

    @Test fun whatIsNotAKeyIsNothing() {
        for (cp in listOf(-1, 0, 0x08, 0x0A, 0x1F, 0x7F, 0xD800, 0xDFFF, 0x110000)) assertNull(KeyEncoder.text(cp), "U+${cp.toString(16)}")
        assertNull(KeyEncoder.text('1'.code, Mods(ctrl = true))); assertNull(KeyEncoder.text('é'.code, Mods(ctrl = true)))
    }

    @Test fun everyNamedKeyInEveryModifierComboIsOneBoundedSequence() {
        for (k in NamedKey.entries) for (mask in 0..7) {
            val b = KeyEncoder.named(k, Mods(mask and 1 != 0, mask and 2 != 0, mask and 4 != 0))
            assertTrue(b.size in 1..8, "$k $mask -> ${s(b)}")
        }
    }

    @Test fun everyBytePathFitsOneInputRecord() {
        assertTrue(KeyEncoder.named(NamedKey.F12, Mods(true, true, true)).size <= TerminalLimits.INPUT_BYTES)
        assertEquals(8, Mods(true, true, true).param)
    }
}
