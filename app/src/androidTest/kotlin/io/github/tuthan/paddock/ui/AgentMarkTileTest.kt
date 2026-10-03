package io.github.tuthan.paddock.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tuthan.paddock.attention.AgentMarks
import io.github.tuthan.paddock.attention.AttentionModel
import io.github.tuthan.paddock.attention.ObservedAt
import io.github.tuthan.paddock.herdr.Agent
import io.github.tuthan.paddock.herdr.AgentStatus
import io.github.tuthan.paddock.herdr.Snapshot
import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.ui.components.AGENT_CODE_TAG
import io.github.tuthan.paddock.ui.components.AGENT_GLYPH_TAG
import io.github.tuthan.paddock.ui.components.AgentMarkTile
import io.github.tuthan.paddock.ui.components.AgentRow
import io.github.tuthan.paddock.ui.components.LocalAgentGlyphs
import io.github.tuthan.paddock.ui.screens.HerdHome
import io.github.tuthan.paddock.ui.screens.HomeUiState
import io.github.tuthan.paddock.ui.screens.LocalAccess
import io.github.tuthan.paddock.ui.screens.Settings
import io.github.tuthan.paddock.ui.screens.SettingsState
import io.github.tuthan.paddock.ui.theme.PaddockTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Phase 12 on a device: AC-12.3 (the toggle redraws the herd), AC-12.4 (which kinds draw what), AC-12.5 (the tile says nothing to TalkBack), AC-12.6 (no clipping, no taller rows). */
class AgentMarkTileTest {
    @get:Rule val rule = createComposeRule()

    private val now = 1_000_000L

    private fun agent(pane: String, kind: String?, status: AgentStatus = AgentStatus.Blocked, title: String = "approve edit") =
        Agent(paneId = pane, terminalId = "term_$pane", workspaceId = "w1", tabId = "w1:t1", agent = kind, agentStatus = status, stateChangeSeq = 5,
            terminalTitleStripped = title, foregroundCwd = "/home/u/api")

    private fun home(vararg a: Agent) = AttentionModel.home(Snapshot("0.9.1", 22, agents = a.toList()), now - 40_000, HostProfileId("laptop"), "main", 1, ObservedAt { null })

    private fun glyphIn(kind: String) = hasTestTag(AGENT_GLYPH_TAG) and hasAnyAncestor(hasTestTag("tile:$kind"))
    private fun codeIn(kind: String) = hasTestTag(AGENT_CODE_TAG) and hasAnyAncestor(hasTestTag("tile:$kind"))
    private fun unmerged(m: androidx.compose.ui.test.SemanticsMatcher) = rule.onAllNodes(m, useUnmergedTree = true)
    private fun glyphCount() = unmerged(hasTestTag(AGENT_GLYPH_TAG)).fetchSemanticsNodes().size

    private fun shoot(name: String) {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.getExternalFilesDir(null), "screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun showTiles(kinds: List<String?>, glyphs: Boolean) = rule.setContent {
        PaddockTheme(darkTheme = true) {
            CompositionLocalProvider(LocalAgentGlyphs provides glyphs) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(8.dp)) {
                    kinds.forEach { k -> Box(Modifier.testTag("tile:$k").padding(2.dp)) { AgentMarkTile(k) } }
                }
            }
        }
    }

    @Test fun withIconsOnTheTenCommonKindsDrawAGlyphAndEveryOtherKindItsCode() {
        val kinds = AgentMarks.listed.keys.toList()
        showTiles(kinds, glyphs = true)
        for ((kind, mark) in AgentMarks.listed) {
            if (mark.glyph != null) {
                unmerged(glyphIn(kind)).assertCountEquals(1)
                unmerged(codeIn(kind)).assertCountEquals(0)
            } else {
                unmerged(glyphIn(kind)).assertCountEquals(0)
                unmerged(codeIn(kind)).assertCountEquals(1)
                rule.onNode(codeIn(kind), useUnmergedTree = true).assertTextEquals(mark.code)
            }
        }
        assertEquals("exactly the ten common kinds draw a glyph", 10, glyphCount())
    }

    @Test fun withIconsOffEveryKindDrawsItsTwoLetters() {
        val kinds = AgentMarks.listed.keys.toList()
        showTiles(kinds, glyphs = false)
        assertEquals(0, glyphCount())
        for ((kind, mark) in AgentMarks.listed) rule.onNode(codeIn(kind), useUnmergedTree = true).assertTextEquals(mark.code)
    }

    @Test fun aKindWithNoRowInTheTableShowsItsFirstLettersAndNoKindShowsTwoDots() {
        showTiles(listOf("fake", null, "Antigravity-CLI"), glyphs = true)
        rule.onNode(codeIn("fake"), useUnmergedTree = true).assertTextEquals("fa")
        rule.onNode(codeIn("null"), useUnmergedTree = true).assertTextEquals("··")
        rule.onNode(codeIn("Antigravity-CLI"), useUnmergedTree = true).assertTextEquals("ag")
    }

    @Test fun theTileAddsNothingToWhatTalkBackReads() {
        showTiles(listOf("claude", "cline"), glyphs = true)
        // The merged tree is what accessibility services see: no letters, no glyph, no description from either tile.
        rule.onAllNodes(hasTestTag(AGENT_GLYPH_TAG)).assertCountEquals(0)
        rule.onAllNodes(hasTestTag(AGENT_CODE_TAG)).assertCountEquals(0)
        rule.onAllNodes(hasText("cn")).assertCountEquals(0)
        rule.onAllNodes(hasContentDescription("claude", substring = true)).assertCountEquals(0)
    }

    @Test fun aRowSaysWhichAgentItIsInWordsWhateverItsTileDraws() {
        rule.setContent {
            PaddockTheme(darkTheme = true) {
                Column {
                    val h = home(agent("w1:p1", "claude"), agent("w1:p2", "cline", AgentStatus.Working, "refactor"))
                    h.rows.forEach { AgentRow(it, now, enabled = true) }
                }
            }
        }
        rule.onNode(hasContentDescription("Blocked, claude, approve edit, api, observed 40 s ago")).assertIsDisplayed()
        rule.onNode(hasContentDescription("Working, cline, refactor, api, observed 40 s ago")).assertIsDisplayed()
        assertEquals("claude draws a glyph and cline its letters", 1, glyphCount())
        rule.onNode(hasTestTag(AGENT_CODE_TAG), useUnmergedTree = true).assertTextEquals("cn")
    }

    @Test fun flippingAgentIconsInSettingsChangesTheHerdAtOnceWithoutARestart() {
        var glyphs by mutableStateOf(true)
        var onSettings by mutableStateOf(false)
        val h = home(agent("w1:p1", "claude"), agent("w1:p2", "cline", AgentStatus.Working, "refactor"))
        rule.setContent {
            PaddockTheme(darkTheme = true) {
                CompositionLocalProvider(LocalAgentGlyphs provides glyphs) {
                    if (onSettings) Settings(SettingsState(true, LocalAccess.NotRequired, "0.1.0", agentGlyphs = glyphs), {}, {}, { onSettings = false }, onAgentGlyphs = { glyphs = it })
                    else HerdHome(HomeUiState.Live("laptop", h, 40_000), now, onSettings = { onSettings = true })
                }
            }
        }
        assertEquals("on: claude has its glyph", 1, glyphCount())
        rule.onNode(hasContentDescription("Settings")).performClick()
        rule.onNodeWithText("Agent icons").performScrollTo().performClick()
        rule.onNodeWithText("Two letters for every agent.").assertIsDisplayed()
        assertTrue(!glyphs)
        rule.onNode(hasContentDescription("Back")).performClick()
        assertEquals("off: no glyph on the herd", 0, glyphCount())
        rule.onAllNodes(hasTestTag(AGENT_CODE_TAG), useUnmergedTree = true).assertCountEquals(2)
        unmerged(hasTestTag(AGENT_CODE_TAG) and hasText("cl")).assertCountEquals(1)
        rule.onNode(hasContentDescription("Settings")).performClick()
        rule.onNodeWithText("Agent icons").performScrollTo().performClick()
        rule.onNodeWithText("Pictures for the common agents, letters for the rest.").assertIsDisplayed()
        rule.onNode(hasContentDescription("Back")).performClick()
        assertEquals("on again", 1, glyphCount())
    }

    /** One composition per theme and scale; the setting flips inside it so the same row is measured both ways. */
    private fun measureRows(dark: Boolean, scale: Float) {
        var glyphs by mutableStateOf(true)
        val h = home(agent("w1:p1", "claude"), agent("w1:p2", "shell", AgentStatus.Idle, "a shell"), agent("w1:p3", "hermes", AgentStatus.Working, "deliver"), agent("w1:p4", "kiro", AgentStatus.Done, "docs"))
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, scale), LocalAgentGlyphs provides glyphs) {
                PaddockTheme(darkTheme = dark) {
                    Column(Modifier.fillMaxWidth().padding(8.dp)) { h.rows.forEach { AgentRow(it, now, enabled = true, modifier = Modifier.padding(bottom = 6.dp)) } }
                }
            }
        }
        val tag = "${if (dark) "dark" else "light"}-${(scale * 100).toInt()}"
        val rows = h.rows.map { r -> r.paneId to { rule.onNode(hasContentDescription(r.title, substring = true)) } }
        fun heights() = rows.associate { (id, node) -> id to node().getUnclippedBoundsInRoot().let { it.bottom - it.top } }
        val withGlyphs = heights()
        shoot("marks-$tag-glyphs")
        // AC-12.6: a glyph sits wholly inside a tile that stops growing at 1.3 x, and nothing is clipped.
        val cap = 32.dp * minOf(scale, 1.3f)
        unmerged(hasTestTag(AGENT_GLYPH_TAG)).fetchSemanticsNodes().forEach { n ->
            val r = n.boundsInRoot
            assertTrue("glyph $r inside the ${cap} tile", r.width <= cap.value * rule.density.density + 1f && r.height <= cap.value * rule.density.density + 1f)
        }
        glyphs = false
        rule.waitForIdle()
        val withLetters = heights()
        shoot("marks-$tag-letters")
        assertEquals("a glyph never makes a row taller than letters (dark=$dark, scale=$scale)", withLetters, withGlyphs)
        assertEquals(0, glyphCount())
    }

    @Test fun darkAtNormalFontRowsDoNotGrowBecauseOfAGlyph() = measureRows(dark = true, scale = 1.0f)
    @Test fun darkAtOneAndAThirdRowsDoNotGrowBecauseOfAGlyph() = measureRows(dark = true, scale = 1.3f)
    @Test fun darkAtDoubleFontRowsDoNotGrowBecauseOfAGlyph() = measureRows(dark = true, scale = 2.0f)
    @Test fun lightAtNormalFontRowsDoNotGrowBecauseOfAGlyph() = measureRows(dark = false, scale = 1.0f)
    @Test fun lightAtOneAndAThirdRowsDoNotGrowBecauseOfAGlyph() = measureRows(dark = false, scale = 1.3f)
    @Test fun lightAtDoubleFontRowsDoNotGrowBecauseOfAGlyph() = measureRows(dark = false, scale = 2.0f)
}
