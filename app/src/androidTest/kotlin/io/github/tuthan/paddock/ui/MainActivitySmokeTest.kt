package io.github.tuthan.paddock.ui

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import io.github.tuthan.paddock.MainActivity
import org.junit.Rule
import org.junit.Test

/**
 * The real activity starts and draws its first screen: Home when a machine is saved, Add a machine on a fresh install.
 * Lives in the `ui` package so the UI test run includes it.
 */
class MainActivitySmokeTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun startsOnHomeOrOnAddAMachine() {
        rule.waitUntil(10_000) {
            rule.onAllNodes(hasText("Paddock")).fetchSemanticsNodes().isNotEmpty() || rule.onAllNodes(hasText("Add a machine")).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
