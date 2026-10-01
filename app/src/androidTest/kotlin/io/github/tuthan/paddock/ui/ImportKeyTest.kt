package io.github.tuthan.paddock.ui

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tuthan.paddock.ssh.ImportCheck
import io.github.tuthan.paddock.ui.screens.ImportKey
import io.github.tuthan.paddock.ui.screens.PickedKeyFile
import io.github.tuthan.paddock.ui.screens.importMessage
import io.github.tuthan.paddock.ui.theme.PaddockTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Compose UI tests for the private-key import screen. The key text used here is a placeholder, not a key. */
class ImportKeyTest {
    @get:Rule val rule = createComposeRule()

    private class Calls { var chose = 0; var cleared = 0; var back = 0; var imported: Pair<String, String>? = null }

    private fun shoot(name: String) {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.getExternalFilesDir(null), "screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @androidx.compose.runtime.Composable
    private fun screen(calls: Calls, picked: PickedKeyFile?, pickError: String?, busy: Boolean, result: ImportCheck?) = ImportKey(
        picked, pickError, busy, result,
        onChooseFile = { calls.chose++ }, onClearFile = { calls.cleared++ }, onImport = { pem, pass -> calls.imported = pem to pass }, onBack = { calls.back++ },
    )

    private fun show(picked: PickedKeyFile? = null, pickError: String? = null, busy: Boolean = false, result: ImportCheck? = null, fontScale: Float? = null): Calls {
        val calls = Calls()
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides if (fontScale != null) Density(base.density, fontScale) else base) {
                PaddockTheme(darkTheme = true) { screen(calls, picked, pickError, busy, result) }
            }
        }
        return calls
    }

    @Test fun importIsOffMeansNothingIsSentUntilThereIsKeyText() {
        val calls = show()
        rule.onNodeWithText("Import key").assertIsNotEnabled().performClick()
        assertNull(calls.imported)
    }

    @Test fun pastedTextAndThePassphraseAreHandedOverAsTyped() {
        val calls = show()
        rule.onNodeWithText("Or paste the key").performTextInput("KEY-TEXT-PLACEHOLDER")
        rule.onNodeWithText("Passphrase, if the key has one").performTextInput("hunter2")
        rule.onNodeWithText("Import key").assertIsEnabled().performClick()
        assertEquals("KEY-TEXT-PLACEHOLDER" to "hunter2", calls.imported)
    }

    @Test fun bothTextFieldsAreMaskedAsSecrets() {
        show()
        val secret = SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)
        assertEquals(2, rule.onAllNodes(secret).fetchSemanticsNodes().size)
    }

    @Test fun aChosenFileReplacesThePasteFieldAndSendsItsText() {
        val calls = show(picked = PickedKeyFile("id_ed25519", "FILE-TEXT-PLACEHOLDER"))
        rule.onNode(hasContentDescription("Chosen file: id_ed25519")).assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("Or paste the key").fetchSemanticsNodes().size)
        rule.onNodeWithText("Import key").assertIsEnabled().performClick()
        assertEquals("FILE-TEXT-PLACEHOLDER" to "", calls.imported)
        rule.onNodeWithText("Choose a different file").performClick()
        assertEquals(1, calls.cleared)
    }

    @Test fun chooseAKeyFileAsksTheCallerToOpenThePicker() {
        val calls = show()
        rule.onNodeWithText("Choose a key file").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, calls.chose)
    }

    private fun failureIsShown(check: ImportCheck) {
        show(result = check)
        rule.onNodeWithText(importMessage(check)!!, substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun anEncryptedKeyWithoutAPassphraseAsksForIt() = failureIsShown(ImportCheck.NeedsPassphrase)
    @Test fun aWrongPassphraseIsSaidPlainly() = failureIsShown(ImportCheck.WrongPassphrase)
    @Test fun somethingThatIsNotAKeyPointsAwayFromThePublicKeyFile() = failureIsShown(ImportCheck.NotAKey)
    @Test fun anUnsupportedKeyNamesTheReason() = failureIsShown(ImportCheck.Unsupported("DSA keys are not supported"))

    @Test fun aFileThatCouldNotBeReadIsSaidPlainly() {
        show(pickError = "That file could not be read, or it is too large to be a private key.")
        rule.onNodeWithText("That file could not be read", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun whileCheckingTheButtonSaysSoAndCannotBePressedTwice() {
        val calls = show(picked = PickedKeyFile("k", "T"), busy = true)
        rule.onNodeWithText("Checking…").assertIsNotEnabled().performClick()
        assertNull(calls.imported)
    }

    @Test fun keyTextAndPassphraseAreNotKeptInSavedState() {
        val tester = StateRestorationTester(rule)
        val calls = Calls()
        tester.setContent { PaddockTheme(darkTheme = true) { screen(calls, null, null, false, null) } }
        rule.onNodeWithText("Or paste the key").performTextInput("KEY-TEXT-PLACEHOLDER")
        rule.onNodeWithText("Import key").assertIsEnabled()
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("Import key").assertIsNotEnabled()
        assertTrue(rule.onAllNodesWithText("KEY-TEXT-PLACEHOLDER", substring = true).fetchSemanticsNodes().isEmpty())
    }

    @Test fun importStaysReachableAtTwoHundredPercentFont() {
        show(fontScale = 2f)
        rule.onNodeWithText("Import key").assertIsDisplayed()
        shoot("import-key-200")
    }

    @Test fun backIsAFortyEightDpTarget() {
        val calls = show()
        rule.onNode(hasContentDescription("Back")).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, calls.back)
    }
}
