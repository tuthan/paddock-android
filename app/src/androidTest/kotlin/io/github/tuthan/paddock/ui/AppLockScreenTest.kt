package io.github.tuthan.paddock.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.tuthan.paddock.ui.screens.AppLockScreen
import io.github.tuthan.paddock.ui.theme.PaddockTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** What covers the app while it is locked: one word and one button, and nothing of the app. */
class AppLockScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private class Taps { var unlock = 0; var leave = 0 }

    private fun show(message: String? = null, busy: Boolean = false, dark: Boolean = true): Taps {
        val taps = Taps()
        rule.setContent { PaddockTheme(darkTheme = dark) { AppLockScreen(message, busy, onUnlock = { taps.unlock++ }, onLeave = { taps.leave++ }) } }
        return taps
    }

    @Test fun itSaysPaddockIsLockedAndHowToOpenItAndShowsNothingElse() {
        show()
        rule.onNodeWithText("Paddock is locked").assertIsDisplayed()
        rule.onNodeWithText("Unlock with the way you unlock this phone.").assertIsDisplayed()
        rule.onNodeWithText("Unlock").assertIsDisplayed().assertIsEnabled()
    }

    @Test fun theButtonAsksAndWhileThePromptIsUpItCannotBeTappedAgain() {
        val taps = show()
        rule.onNodeWithText("Unlock").performClick()
        assertEquals(1, taps.unlock)
    }

    @Test fun whileThePhonePromptIsShowingTheButtonIsOff() {
        show(busy = true)
        rule.onNodeWithText("Unlock").assertIsNotEnabled()
    }

    @Test fun aFailureIsSaidInWordsAndTheButtonStaysSoItCanBeTriedAgain() {
        show(message = "That did not work. Try again. (Too many attempts)")
        rule.onNodeWithText("That did not work. Try again. (Too many attempts)").assertIsDisplayed()
        rule.onNodeWithText("Unlock").assertIsEnabled()
    }

    @Test fun backLeavesTheAppInsteadOfReachingTheScreensUnderneath() {
        val taps = show()
        // The lock is a dialog window, so Back is delivered to it, not to the activity underneath.
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        rule.waitForIdle()
        assertEquals(1, taps.leave)
    }

    @Test fun auditLockScreenDark() { show(); SemanticsAudit.expectClean(rule, "Lock screen, dark") }
    @Test fun auditLockScreenLight() { show(message = "That did not work. Try again.", dark = false); SemanticsAudit.expectClean(rule, "Lock screen, light") }
}
