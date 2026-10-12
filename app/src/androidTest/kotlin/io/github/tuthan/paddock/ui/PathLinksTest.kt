package io.github.tuthan.paddock.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import io.github.tuthan.paddock.output.Ansi
import io.github.tuthan.paddock.output.AnsiLine
import io.github.tuthan.paddock.ui.components.OutputSlab
import io.github.tuthan.paddock.ui.theme.PaddockTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Phase 15, slice 12: paths the agent printed are links in the live list: a tap, TalkBack's actions, and a long press that still opens the line's menu (S1). */
class PathLinksTest {
    @get:Rule val rule = createComposeRule()

    private val taps = mutableListOf<String>()
    private var menuAction: String? = null

    private fun show(text: String, withPaths: Boolean = true, cut: Boolean = false) {
        val lines = Ansi.parse(text).map { if (cut) AnsiLine(it.spans, cut = true) else it }
        rule.setContent {
            PaddockTheme(darkTheme = true) {
                OutputSlab(
                    lines, true, {}, onLongPress = {}, onLineAction = { _, _, a -> menuAction = a.name },
                    onPath = if (withPaths) { p -> taps += p } else null,
                )
            }
        }
    }

    private fun layoutOf(line: String): TextLayoutResult {
        val node = rule.onNode(hasText(line, substring = true), useUnmergedTree = true).fetchSemanticsNode()
        val out = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!(out)
        return out.single()
    }

    private fun onWord(line: String, word: String, gesture: androidx.compose.ui.test.TouchInjectionScope.(androidx.compose.ui.geometry.Offset) -> Unit) {
        val at = line.indexOf(word) + word.length / 2
        val center = layoutOf(line).getBoundingBox(at).center
        rule.onNode(hasText(line, substring = true)).performTouchInput { gesture(center) }
        rule.waitForIdle()
    }

    private val actionsOf get() = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)).fetchSemanticsNodes()
        .map { n -> n.config[SemanticsActions.CustomActions].map { it.label } }

    @Test fun aTapOnAPathCallsBackWithThePathAsPrinted() {
        val line = "wrote out/report.pdf (3 KB) for you"
        show(line)
        onWord(line, "report", { click(it) })
        assertEquals(listOf("out/report.pdf"), taps)
    }

    @Test fun aLinkIsUnderlinedAndNothingElseOnTheLineIs() {
        val line = "wrote out/report.pdf (3 KB) for you"
        show(line)
        val underlined = layoutOf(line).layoutInput.text.spanStyles.filter { it.item.textDecoration == androidx.compose.ui.text.style.TextDecoration.Underline }
        val start = line.indexOf("out/report.pdf")
        assertEquals(listOf(start to start + "out/report.pdf".length), underlined.map { it.start to it.end })
    }

    @Test fun withoutACallbackNothingIsUnderlined() {
        val line = "wrote out/report.pdf (3 KB) for you"
        show(line, withPaths = false)
        assertTrue(layoutOf(line).layoutInput.text.spanStyles.none { it.item.textDecoration == androidx.compose.ui.text.style.TextDecoration.Underline })
    }

    @Test fun aTapOnTheWordsAroundItDoesNothing() {
        val line = "wrote out/report.pdf (3 KB) for you"
        show(line)
        onWord(line, "wrote", { click(it) })
        onWord(line, "for you", { click(it) })
        assertTrue(taps.isEmpty())
    }

    @Test fun anAbsolutePathAndAPathWithALineNumberAreBothLinksAndTapsGoToTheRightOne() {
        val line = "see /work/proj/src/a.kt:12 and docs/b.md"
        show(line)
        onWord(line, "docs/b.md", { click(it) })
        onWord(line, "a.kt", { click(it) })
        assertEquals(listOf("docs/b.md", "/work/proj/src/a.kt"), taps)
    }

    @Test fun aLongPressOverAPathOpensTheLineMenuAndFollowsNoLink() {
        val line = "wrote out/report.pdf (3 KB) for you"
        show(line)
        onWord(line, "report", { longClick(it) })
        rule.onNodeWithText("Copy line").assertExists()
        assertTrue("a long press is not a tap on the link", taps.isEmpty())
    }

    @Test fun talkBackGetsTheThreeLineActionsThenOneGetPerPath() {
        show("wrote out/report.pdf and logs/run.log today")
        assertEquals(
            listOf("Copy line", "Copy from here to newest", "Select text from this line", "Get file out/report.pdf", "Get file logs/run.log"),
            actionsOf.single(),
        )
    }

    @Test fun aGetActionDoesWhatATapDoes() {
        show("wrote out/report.pdf today")
        val node = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))[0].fetchSemanticsNode()
        val action = node.config[SemanticsActions.CustomActions].single { it.label == "Get file out/report.pdf" }
        rule.runOnUiThread { action.action() }
        rule.waitForIdle()
        assertEquals(listOf("out/report.pdf"), taps)
    }

    @Test fun aBusyLineIsLimitedToEightPaths() {
        show((1..12).joinToString(" ") { "dir/f$it.txt" })
        assertEquals(3 + 8, actionsOf.single().size)
    }

    @Test fun aCutLinesLastTokenIsNoLink() {
        show("see a/first.txt and a/second.tx", cut = true)
        assertEquals(listOf("Get file a/first.txt"), actionsOf.single().drop(3))
    }

    @Test fun withoutACallbackPathsAreOrdinaryText() {
        val line = "wrote out/report.pdf today"
        show(line, withPaths = false)
        assertEquals(3, actionsOf.single().size)
        onWord(line, "report", { click(it) })
        assertTrue(taps.isEmpty())
        assertFalse(rule.onAllNodes(hasText("Get file", substring = true)).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun aLineWithoutAPathHasOnlyTheThreeActions() {
        show("nothing to fetch here, just words")
        assertEquals(3, actionsOf.single().size)
    }
}
