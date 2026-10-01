package io.github.tuthan.paddock.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import io.github.tuthan.paddock.output.AnsiColor
import io.github.tuthan.paddock.ui.theme.AnsiPalette
import io.github.tuthan.paddock.ui.theme.PaddockDarkColors
import io.github.tuthan.paddock.ui.theme.PaddockLightColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnsiPaletteTest {
    /** WCAG contrast ratio. */
    private fun contrast(a: Color, b: Color): Double {
        val l1 = a.luminance().toDouble(); val l2 = b.luminance().toDouble()
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    @Test fun everyColourClearsFourPointFiveOnTheSlabInBothThemes() {
        for ((name, colors) in listOf("dark" to PaddockDarkColors, "light" to PaddockLightColors)) {
            for (c in AnsiColor.entries) {
                val fg = AnsiPalette.color(colors, c)
                val ratio = contrast(fg, colors.slab)
                assertTrue("$name $c is ${"%.2f".format(ratio)}:1 on the slab", ratio >= 4.5)
            }
        }
    }

    @Test fun blackAndBrightBlackAreDimNeverFaint() {
        for (colors in listOf(PaddockDarkColors, PaddockLightColors)) {
            assertEquals(colors.dim, AnsiPalette.color(colors, AnsiColor.Black))
            assertEquals(colors.dim, AnsiPalette.color(colors, AnsiColor.BrightBlack))
            assertNotEquals(colors.faint, AnsiPalette.color(colors, AnsiColor.BrightBlack))
        }
    }

    @Test fun noForegroundMeansTheThemeText() {
        assertEquals(PaddockDarkColors.text, AnsiPalette.color(PaddockDarkColors, null))
    }

    @Test fun theSixteenAreDistinguishableApartFromTheTwoDimAliases() {
        val distinct = AnsiColor.entries.map { AnsiPalette.color(PaddockDarkColors, it) }.toSet()
        assertEquals(15, distinct.size)
    }
}
