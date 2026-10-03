package io.github.tuthan.paddock.ui.components

import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.VectorPath
import io.github.tuthan.paddock.attention.AgentGlyph
import io.github.tuthan.paddock.attention.AgentRowModel
import io.github.tuthan.paddock.attention.Section
import io.github.tuthan.paddock.attention.StateWord
import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.identity.TargetRef
import io.github.tuthan.paddock.identity.TerminalKey
import io.github.tuthan.paddock.ui.theme.PaddockAgentGlyphs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** AC-12.5's text half (the row says which agent it is) and AC-12.7's colour half (a glyph is a one-colour stroke the tile tints). */
class AgentMarkTest {
    private val now = 100_000L

    private fun row(kind: String?, title: String = "approve edit", context: String = "api › tab 2", state: StateWord = StateWord.Blocked) = AgentRowModel(
        key = TerminalKey(TargetRef(HostProfileId("laptop"), "main", "term_w1:p1"), 1), paneId = "w1:p1", title = title, context = context, state = state,
        section = Section.NeedsYou, observedAtMillis = now - 40_000, stateChangeSeq = 3, agentKind = kind,
    )

    @Test fun aRowReadsStateThenAgentThenTitleThenContextThenAge() {
        assertEquals("Blocked, claude, approve edit, api › tab 2, observed 40 s ago", rowDescription(row("claude"), now))
        assertEquals("Last seen blocked, codex, approve edit, api › tab 2, observed 40 s ago", rowDescription(row("codex"), now, stale = true))
    }

    @Test fun theAgentIsNamedAsHerdrSaysItNotAsTheTileDrawsIt() {
        // The tile shows `cn` for cline and `kr` for kiro; the spoken text has the whole name, and never the code.
        assertEquals("Working, cline, refactor, observed 40 s ago", rowDescription(row("cline", "refactor", "", StateWord.Working), now))
        assertTrue("kiro" in rowDescription(row("kiro"), now) && ", kr," !in rowDescription(row("kiro"), now))
    }

    @Test fun aRowWithoutAKindReadsAsBefore() {
        assertEquals("Blocked, approve edit, api › tab 2, observed 40 s ago", rowDescription(row(null), now))
        assertEquals("Blocked, approve edit, api › tab 2, observed 40 s ago", rowDescription(row("   "), now))
    }

    @Test fun everyGlyphIsAOneColourStrokeOnTheSharedGrid() {
        assertEquals(AgentGlyph.entries.size, 10)
        for (glyph in AgentGlyph.entries) {
            val vector = PaddockAgentGlyphs.of(glyph)
            assertEquals("$glyph viewport", 24f, vector.viewportWidth, 0f)
            assertEquals("$glyph viewport", 24f, vector.viewportHeight, 0f)
            val paths = vector.root.map { it as VectorPath }
            assertTrue("$glyph has no path", paths.isNotEmpty())
            for (p in paths) {
                assertNull("$glyph fills a path: colour is for state only and a glyph is a line drawing", p.fill)
                assertEquals("$glyph stroke", SolidColor(Color.Black), p.stroke)
                assertEquals("$glyph stroke width", 1.8f, p.strokeLineWidth, 0f)
                assertTrue("$glyph has an empty path", p.pathData.isNotEmpty())
            }
        }
    }

    @Test fun theTenGlyphsAreAllDifferentDrawings() {
        val drawings = AgentGlyph.entries.map { g -> PaddockAgentGlyphs.of(g).root.joinToString("|") { (it as VectorPath).pathData.joinToString(",") { n -> n.toString() } } }
        assertEquals(10, drawings.toSet().size)
    }
}
