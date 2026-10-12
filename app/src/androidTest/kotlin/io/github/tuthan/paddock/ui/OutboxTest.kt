package io.github.tuthan.paddock.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.unit.dp
import io.github.tuthan.paddock.ui.screens.GetCopy
import io.github.tuthan.paddock.ui.screens.OutboxRowUi
import io.github.tuthan.paddock.ui.screens.OutboxSheet
import io.github.tuthan.paddock.ui.screens.OutboxUi
import io.github.tuthan.paddock.ui.theme.PaddockTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The agent's outbox list (Phase 15, slice 12): newest first, "List read at 14:02", Get on a row, and nothing that deletes. */
class OutboxTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val rows = listOf(
        OutboxRowUi("report.pdf", "48 KB · changed 3 min ago", "report.pdf"),
        OutboxRowUi("run.log", "2 KB · changed 1 h 4 min ago", "run.log"),
    )

    private class Calls { val got = mutableListOf<String>(); var reread = 0; var closed = 0 }

    private fun show(ui: OutboxUi, calls: Calls = Calls(), dark: Boolean = true): Calls {
        rule.setContent { PaddockTheme(darkTheme = dark) { OutboxSheet(ui, onGet = { calls.got += it.key }, onReRead = { calls.reread++ }, onClose = { calls.closed++ }) } }
        return calls
    }

    @Test fun theListShowsTheTimeItWasReadAndEveryRow() {
        show(OutboxUi.Listed(rows, "14:02:07"))
        rule.onNodeWithText("List read at 14:02:07").assertIsDisplayed()
        rule.onAllNodesWithTag("outbox-row").assertCountEquals(2)
        rule.onNodeWithText("report.pdf").assertIsDisplayed()
        rule.onNodeWithText("2 KB · changed 1 h 4 min ago").assertIsDisplayed()
    }

    @Test fun aRowIsOneClickTargetThatSaysGetAndAtLeast48Tall() {
        val calls = show(OutboxUi.Listed(rows, "14:02:07"))
        val row = rule.onNode(hasContentDescription("run.log, 2 KB · changed 1 h 4 min ago. Get"))
        row.assertHasClickAction().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf("run.log"), calls.got)
    }

    @Test fun anEmptyOutboxSaysSoAndOffersNoRows() {
        show(OutboxUi.Listed(emptyList(), "14:02:07"))
        rule.onNodeWithText(GetCopy.OUTBOX_EMPTY).assertIsDisplayed()
        rule.onAllNodesWithTag("outbox-row").assertCountEquals(0)
    }

    @Test fun aFailedReadShowsItsSentenceAndCanBeReReadOrClosed() {
        val calls = show(OutboxUi.Failed("Could not read the folder: the link was lost."))
        rule.onNodeWithText("Could not read the folder: the link was lost.").assertIsDisplayed()
        rule.onNodeWithText("Re-read").performClick()
        rule.onNodeWithText("Close").performClick()
        assertEquals(1, calls.reread); assertEquals(1, calls.closed)
    }

    @Test fun whileReadingItSaysSo() {
        show(OutboxUi.Reading)
        rule.onNodeWithText("Reading the folder…").assertIsDisplayed()
    }

    @Test fun thereIsNoDeleteAnywhere() {
        show(OutboxUi.Listed(rows, "14:02:07"))
        for (word in listOf("Delete", "Remove", "Clear")) rule.onAllNodesWithText(word, substring = true).assertCountEquals(0)
        rule.onNodeWithText(GetCopy.OUTBOX_NOTE).assertExists()
    }

    @Test fun auditTheListDark() { show(OutboxUi.Listed(rows, "14:02:07")); SemanticsAudit.expectClean(rule, "Outbox, dark") }
    @Test fun auditTheListLight() { show(OutboxUi.Listed(rows, "14:02:07"), dark = false); SemanticsAudit.expectClean(rule, "Outbox, light") }
    @Test fun auditAnEmptyListAndAFailure() { show(OutboxUi.Failed("Could not read the folder: x.")); SemanticsAudit.expectClean(rule, "Outbox failure") }
}
