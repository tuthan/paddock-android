package io.github.tuthan.paddock.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import io.github.tuthan.paddock.ops.Snippets
import io.github.tuthan.paddock.ui.screens.SnippetEditor
import io.github.tuthan.paddock.ui.theme.PaddockTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Compose UI tests for the snippet editor (Phase 06 slice 4): every change is checked and every refusal says why. */
class SnippetEditorTest {
    @get:Rule val rule = createComposeRule()

    private var list by mutableStateOf(emptyList<String>())
    private var changes = 0
    private var backs = 0

    private fun show(initial: List<String> = emptyList()) {
        list = initial; changes = 0; backs = 0
        rule.setContent { PaddockTheme(darkTheme = true) { SnippetEditor(list, { list = it; changes++ }, { backs++ }) } }
    }

    private fun type(text: String) = rule.onNode(hasSetTextAction()).performTextInput(text)

    @Test fun anEmptyListSaysSoAndAddIsOffUntilThereIsText() {
        show()
        rule.onNodeWithText("No snippets yet.").assertIsDisplayed()
        rule.onNodeWithText("Add snippet").assertIsNotEnabled()
        rule.onNodeWithText("0 of 50 saved.").performScrollTo().assertIsDisplayed()
    }

    @Test fun addingASnippetSavesItAndClearsTheField() {
        show()
        type("  Run the tests  ")
        rule.onNodeWithText("Add snippet").assertIsEnabled().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf("Run the tests"), list)
        assertEquals(1, changes)
        rule.onNodeWithText("1 of 50 saved.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Add snippet").assertIsNotEnabled()
    }

    @Test fun aRepeatIsRefusedInWordsAndNothingIsSaved() {
        show(listOf("Continue"))
        type("Continue")
        rule.onNodeWithText("Add snippet").performClick()
        rule.onNodeWithText("That snippet is already saved.").assertIsDisplayed()
        assertEquals(0, changes)
    }

    @Test fun anOverlongSnippetIsRefusedWithItsLength() {
        show()
        type("x".repeat(Snippets.MAX_LENGTH + 1))
        rule.onNodeWithText("Add snippet").performClick()
        rule.onNodeWithText("A snippet is at most 500 characters; this one is 501.").assertIsDisplayed()
        assertEquals(0, changes)
    }

    @Test fun theFiftyFirstIsRefusedWithTheWayOut() {
        show((1..50).map { "snippet $it" })
        type("one more")
        rule.onNodeWithText("Add snippet").performClick()
        rule.onNodeWithText("You can keep 50 snippets. Remove one to add another.").assertIsDisplayed()
        assertEquals(50, list.size)
    }

    @Test fun editingFillsTheFieldAndSavesInPlace() {
        show(listOf("one", "two", "three"))
        rule.onAllNodesWithText("Edit")[1].performScrollTo().performClick()
        rule.onNodeWithText("Edit snippet").assertIsDisplayed()
        rule.onNodeWithText("Save snippet").assertIsEnabled()
        rule.onNode(hasSetTextAction()).performTextReplacement("two and more")
        rule.onNodeWithText("Save snippet").performClick()
        assertEquals(listOf("one", "two and more", "three"), list)
        rule.onNodeWithText("New snippet").assertIsDisplayed()
    }

    @Test fun cancellingAnEditLeavesTheListAlone() {
        show(listOf("one", "two"))
        rule.onAllNodesWithText("Edit")[0].performScrollTo().performClick()
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(listOf("one", "two"), list)
        assertEquals(0, changes)
        rule.onNodeWithText("New snippet").assertIsDisplayed()
    }

    @Test fun removeAndMoveUpChangeTheListAndTheFirstCannotMoveUp() {
        show(listOf("one", "two", "three"))
        assertEquals(2, rule.onAllNodesWithText("Move up").fetchSemanticsNodes().size)
        rule.onAllNodesWithText("Move up")[1].performScrollTo().performClick()
        assertEquals(listOf("one", "three", "two"), list)
        rule.onAllNodesWithText("Remove")[0].performScrollTo().performClick()
        assertEquals(listOf("three", "two"), list)
    }

    @Test fun removingTheSnippetBeingEditedLeavesEditMode() {
        show(listOf("one", "two"))
        rule.onAllNodesWithText("Edit")[0].performScrollTo().performClick()
        rule.onAllNodesWithText("Remove")[0].performScrollTo().performClick()
        assertEquals(listOf("two"), list)
        rule.onNodeWithText("New snippet").assertIsDisplayed()
        rule.onNodeWithText("Save snippet").assertDoesNotExist()
    }

    @Test fun theScreenSaysTheyStayOnThePhone() {
        show()
        rule.onNodeWithText("never synced", substring = true).assertIsDisplayed()
    }
}
