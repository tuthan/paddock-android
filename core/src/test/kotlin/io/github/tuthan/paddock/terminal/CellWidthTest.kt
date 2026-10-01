package io.github.tuthan.paddock.terminal

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class CellWidthTest {
    @Test fun asciiAndLatinAreOneCell() { for (c in listOf('a', 'Z', '~', ' ', 'é', '❯', '─', '█')) assertEquals(1, CellWidth.of(c.code), c.toString()) }

    @Test fun cjkAndFullwidthAreTwoCells() { for (c in listOf('日', '本', '語', 'の', '字', '全', '角', 'Ａ', 'Ｂ', '한', '가', '，')) assertEquals(2, CellWidth.of(c.code), c.toString()) }

    @Test fun combiningMarksAndJoinersAreZeroCells() {
        for (cp in listOf(0x0301, 0x200D, 0x200B, 0xFE0F, 0x20D0, 0x0E31)) assertEquals(0, CellWidth.of(cp), cp.toString(16))
        assertEquals(1, CellWidth.of(0x00AD), "a soft hyphen is a visible hyphen")
    }

    @Test fun emojiThatArePicturesAreTwoCells() { for (cp in listOf(0x1F600, 0x1F680, 0x2705, 0x1F9E0, 0x1F44D)) assertEquals(2, CellWidth.of(cp), cp.toString(16)) }

    @Test fun planeTwoIdeographsAreTwoCells() { assertEquals(2, CellWidth.of(0x20000)); assertEquals(2, CellWidth.of(0x2A6DF)) }

    @Test fun theWideTableIsSortedAndDoesNotOverlap() {
        val table = CellWidth::class.java.getDeclaredField("WIDE").also { it.isAccessible = true }.get(CellWidth) as IntArray
        assertEquals(0, table.size % 2)
        for (i in 0 until table.size / 2) {
            assertTrue(table[i * 2] <= table[i * 2 + 1], "range $i is inverted")
            if (i > 0) assertTrue(table[i * 2] > table[i * 2 - 1], "range $i overlaps or is out of order")
        }
    }
}
