package io.github.tuthan.paddock.ui

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import io.github.tuthan.paddock.output.Ansi
import io.github.tuthan.paddock.ui.components.ansiLineToText
import io.github.tuthan.paddock.ui.theme.AnsiPalette
import io.github.tuthan.paddock.output.AnsiColor
import io.github.tuthan.paddock.ui.theme.PaddockDarkColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OutputComponentsTest {
    private val esc = "\u001B"
    private fun styled(s: String) = ansiLineToText(Ansi.parse(s).single(), PaddockDarkColors)

    @Test fun colourWeightAndUnderlineBecomeSpanStyles() {
        val t = styled("a $esc[31;1mred$esc[0m $esc[4mu$esc[0m")
        assertEquals("a red u", t.text)
        val red = t.spanStyles.first { it.start == 2 }
        assertEquals(AnsiPalette.color(PaddockDarkColors, AnsiColor.Red), red.item.color)
        assertEquals(FontWeight.Bold, red.item.fontWeight)
        assertEquals(TextDecoration.Underline, t.spanStyles.first { it.start == 6 }.item.textDecoration)
    }

    @Test fun dimWithNoColourReadsAsTheDimToken() {
        val t = styled("$esc[2mquiet$esc[0m")
        assertEquals(PaddockDarkColors.dim, t.spanStyles.single().item.color)
    }

    @Test fun aRequestedColourKeepsItsContrastEvenWhenDim() {
        val t = styled("$esc[2;32mgreen$esc[0m")
        assertEquals(AnsiPalette.color(PaddockDarkColors, AnsiColor.Green), t.spanStyles.single().item.color)
    }

    @Test fun brightBlackIsDimNotFaint() {
        val t = styled("$esc[90mgrey$esc[0m")
        assertEquals(PaddockDarkColors.dim, t.spanStyles.single().item.color)
        assertNull(t.spanStyles.single().item.fontWeight)
    }

    @Test fun anEmptyLineKeepsItsHeight() {
        assertEquals(" ", ansiLineToText(Ansi.parse("\n").single(), PaddockDarkColors).text)
    }
}
