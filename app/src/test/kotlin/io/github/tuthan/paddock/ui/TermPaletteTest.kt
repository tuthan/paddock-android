package io.github.tuthan.paddock.ui

import androidx.compose.ui.graphics.Color
import io.github.tuthan.paddock.output.AnsiColor
import io.github.tuthan.paddock.terminal.TermColor
import io.github.tuthan.paddock.ui.theme.AnsiPalette
import io.github.tuthan.paddock.ui.theme.PaddockDarkColors
import io.github.tuthan.paddock.ui.theme.PaddockLightColors
import io.github.tuthan.paddock.ui.theme.TermPalette
import org.junit.Test

class TermPaletteTest {
    private val dark = PaddockDarkColors

    @Test fun theDefaultIsWhatTheCallerPasses() {
        assertEquals(Color.Red, TermPalette.color(dark, TermColor.Default, Color.Red))
    }

    @Test fun theSixteenNamedColoursAreTheOutputSlabsInBothThemes() {
        for (colors in listOf(PaddockDarkColors, PaddockLightColors)) for (i in 0..15) {
            assertEquals(AnsiPalette.table(colors.isDark).getValue(AnsiColor.entries[i]), TermPalette.color(colors, TermColor.indexed(i), Color.Unspecified), "index $i")
        }
    }

    @Test fun theCubeAndTheGreyRampFollowXterm() {
        assertEquals(Color(0xFF000000), TermPalette.indexed(dark, 16))
        assertEquals(Color(0xFFFFFFFF), TermPalette.indexed(dark, 231))
        assertEquals(Color(0xFF5F87AF), TermPalette.indexed(dark, 67))   // 16 + 36*1 + 6*2 + 3: r=1, g=2, b=3 -> 5f 87 af
        assertEquals(Color(0xFFFF0000), TermPalette.indexed(dark, 196))
        assertEquals(Color(0xFF080808), TermPalette.indexed(dark, 232))
        assertEquals(Color(0xFFEEEEEE), TermPalette.indexed(dark, 255))
    }

    @Test fun aTrueColourIsDrawnAsAsked() {
        assertEquals(Color(0xFF12AB34), TermPalette.color(dark, TermColor.rgb(0x12, 0xAB, 0x34), Color.Red))
    }

    private fun assertEquals(want: Color, got: Color, msg: String = "") = org.junit.Assert.assertEquals(msg, want, got)
}
