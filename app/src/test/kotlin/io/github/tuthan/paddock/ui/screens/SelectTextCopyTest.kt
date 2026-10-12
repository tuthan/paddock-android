package io.github.tuthan.paddock.ui.screens

import io.github.tuthan.paddock.output.Ansi
import io.github.tuthan.paddock.output.OutputCopy
import org.junit.Assert.assertEquals
import org.junit.Test

/** The sentences the Select text view shows under its title (AC-15.1), in the order they are shown. */
class SelectTextCopyTest {
    private fun fit(text: String, budget: Int = OutputCopy.BUDGET) = OutputCopy.fit(Ansi.parse(text))
    private val plain = fit("a\nb\nc")

    @Test fun aFreshCopySaysWhenItWasReadAndHowMany() {
        assertEquals(listOf("Text as read at 14:03:12 · 3 lines"), SelectTextCopy.facts("14:03:12", plain, 3, stale = false, since = null))
        assertEquals(listOf("Text as read at 14:03:12 · 1 line"), SelectTextCopy.facts("14:03:12", fit("a"), 1, stale = false, since = null))
    }

    @Test fun aFailedReadIsSaid() {
        assertEquals("The last read failed.", SelectTextCopy.facts("14:03:12", plain, 3, stale = true, since = null)[1])
    }

    @Test fun aCutLineIsSaidWithTheLimit() {
        val cut = fit("y".repeat(Ansi.MAX_LINE_CHARS + 1) + "\nz")
        assertEquals("1 line is cut at 2,000 characters.", SelectTextCopy.facts("t", cut, 2, stale = false, since = null)[1])
        val two = fit("y".repeat(Ansi.MAX_LINE_CHARS + 1) + "\n" + "y".repeat(Ansi.MAX_LINE_CHARS + 1))
        assertEquals("2 lines are cut at 2,000 characters.", SelectTextCopy.facts("t", two, 2, stale = false, since = null)[1])
    }

    @Test fun linesLeftOutForTheBudgetAreSaidWithTheCounts() {
        val worst = OutputCopy.fit(Ansi.parse((1..200).joinToString("\n") { "x".repeat(Ansi.MAX_LINE_CHARS + 1) }, maxLines = 200))
        val facts = SelectTextCopy.facts("t", worst, 200, stale = false, since = null)
        assertEquals("The newest ${worst.lines.size} of 200 lines; the rest are too long to copy at once.", facts.single { it.startsWith("The newest") })
    }

    @Test fun anExitedAgentAndAClosedTerminalAreSaidLast() {
        assertEquals("This agent has exited since.", SelectTextCopy.facts("t", plain, 3, stale = true, since = Since.AgentExited).last())
        assertEquals("This terminal has closed since.", SelectTextCopy.facts("t", plain, 3, stale = false, since = Since.TerminalClosed).last())
    }
}
