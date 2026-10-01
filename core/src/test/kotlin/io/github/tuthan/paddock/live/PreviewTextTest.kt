package io.github.tuthan.paddock.live

import io.github.tuthan.paddock.output.Ansi
import kotlin.test.Test
import kotlin.test.assertEquals

class PreviewTextTest {
    private fun lines(s: String) = Ansi.parse(s)

    @Test fun blankEdgesAreDroppedAndTheLastLinesAreKept() {
        val text = "\n\n" + (1..20).joinToString("\n") { "line $it" } + "\n\n\n"
        val t = PreviewText.trim(lines(text))
        assertEquals(PreviewText.MAX_LINES, t.size)
        assertEquals("line 20", t.last().text)
        assertEquals("line 13", t.first().text)
    }

    @Test fun blankLinesInTheMiddleOfAPromptSurvive() {
        val t = PreviewText.trim(lines("\nQuestion\n\n  1. Yes\n  2. No\n"))
        assertEquals(listOf("Question", "", "  1. Yes", "  2. No"), t.map { it.text })
    }

    @Test fun allBlankIsEmpty() = assertEquals(emptyList(), PreviewText.trim(lines("\n  \n\n")))

    @Test fun terminalControlsNeverSurviveIntoThePreview() {
        val esc = "\u001B"
        val t = PreviewText.trim(lines("ok$esc]52;c;aGk=\u0007 $esc[2Jdone"))
        assertEquals("ok done", t.single().text)
    }
}
