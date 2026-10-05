package io.github.tuthan.paddock.e2e

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performClick

/** A phone with no saved machine opens on Welcome: take its "Enter the address" way in. Nothing to do once Add a machine (or Home) is showing. */
internal fun ComposeTestRule.passWelcome() {
    if (onAllNodes(hasText("Enter the address")).fetchSemanticsNodes().isNotEmpty()) onNode(hasText("Enter the address")).performClick()
}
