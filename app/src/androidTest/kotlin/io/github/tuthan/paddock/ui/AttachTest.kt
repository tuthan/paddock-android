package io.github.tuthan.paddock.ui

import android.view.View
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tuthan.paddock.attention.StateWord
import io.github.tuthan.paddock.ops.SendGate
import io.github.tuthan.paddock.ui.screens.ATTACH_NOTE_OFF_NO_FOLDER
import io.github.tuthan.paddock.ui.screens.AgentHeader
import io.github.tuthan.paddock.ui.screens.AttachMenuDialog
import io.github.tuthan.paddock.ui.screens.AttachSource
import io.github.tuthan.paddock.ui.screens.AttachUi
import io.github.tuthan.paddock.ui.screens.AttachmentUi
import io.github.tuthan.paddock.ui.screens.Composer
import io.github.tuthan.paddock.ui.screens.ComposerUi
import io.github.tuthan.paddock.ui.screens.KEEP_OPEN_NOTE
import io.github.tuthan.paddock.ui.screens.Sizes
import io.github.tuthan.paddock.ui.screens.TransferUi
import io.github.tuthan.paddock.ui.theme.PaddockTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The composer's Attach pieces (Phase 15, slice 10): the button and its two states, the chip, the menu and the transfer sheet. */
class AttachTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val now = 1_000_000L
    private val header = AgentHeader("Assess Rust migration", "claude · blindpass", StateWord.Ready, now - 120_000, agentKind = "claude")
    private val file = AttachmentUi("2026-10-11-143205-photo.jpg", "1.2 MB", "Image")

    private class Calls { var attach = 0; var remove = 0; var cancel = 0; var dismiss = 0 }

    private var ui by mutableStateOf(ComposerUi(header, SendGate.Open(false)))

    private fun show(state: ComposerUi, calls: Calls = Calls(), dark: Boolean = true, fontScale: Float? = null, probe: View? = null): Calls {
        ui = state
        rule.setContent {
            val base = LocalDensity.current
            val locals = buildList {
                add(LocalDensity provides if (fontScale != null) Density(base.density, fontScale) else base)
                if (probe != null) add(LocalView provides probe)
            }
            CompositionLocalProvider(*locals.toTypedArray()) {
                PaddockTheme(darkTheme = dark) {
                    var text by remember { mutableStateOf("") }
                    Composer(
                        ui, now, text, { text = it }, onSnippet = {}, onSend = {}, onBack = {}, onEditSnippets = {}, onOpenTerminal = {}, onDismissOutcome = {},
                        onAttach = { calls.attach++ }, onRemoveAttachment = { calls.remove++ }, onCancelTransfer = { calls.cancel++ }, onDismissAttachNotice = { calls.dismiss++ },
                    )
                }
            }
        }
        return calls
    }

    // ---- the button ----

    @Test fun attachIsOnWhenTheAgentHasAFolderAndATapOpensTheMenuRequest() {
        val calls = show(ComposerUi(header, SendGate.Open(false), attach = AttachUi.On))
        rule.onNodeWithText("Attach").assertIsEnabled().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, calls.attach)
    }

    @Test fun attachIsOffWithItsReasonRightBesideIt() {
        val calls = show(ComposerUi(header, SendGate.Open(false), attach = AttachUi.Off(ATTACH_NOTE_OFF_NO_FOLDER)))
        rule.onNodeWithText("Attach").assertIsNotEnabled()
        rule.onNodeWithText(ATTACH_NOTE_OFF_NO_FOLDER).assertIsDisplayed()
        rule.onNodeWithContentDescriptionStartingWith("Attach is off").assertExists()
        assertEquals(0, calls.attach)
    }

    @Test fun aComposerThatCannotAttachShowsNoButton() {
        show(ComposerUi(header, SendGate.Open(false)))
        rule.onAllNodesWithText("Attach").assertCountEquals(0)
    }

    // ---- the chip ----

    @Test fun theChipShowsTheNameTheKindAndTheSizeAndRemoveTakesItOut() {
        val calls = show(ComposerUi(header, SendGate.Open(false), attach = AttachUi.On, attachment = file))
        rule.onNodeWithText(file.name).assertIsDisplayed()
        rule.onNodeWithText("Image · 1.2 MB").assertIsDisplayed()
        rule.onAllNodesWithText("Attach").assertCountEquals(0) // one file per draft: the button gives way to the chip
        rule.onNode(hasContentDescription("Remove attachment")).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, calls.remove)
    }

    @Test fun aSweptFileSaysSoAndCanBeRemoved() {
        val calls = show(ComposerUi(header, SendGate.Open(false), attach = AttachUi.On, attachment = file.copy(gone = true)))
        rule.onNodeWithText("No longer on this phone. Remove it and attach it again.").assertIsDisplayed()
        rule.onNode(hasContentDescription("Remove attachment")).performClick()
        assertEquals(1, calls.remove)
    }

    @Test fun anIntakeRefusalIsSaidOnceAndCanBeDismissed() {
        val calls = show(ComposerUi(header, SendGate.Open(false), attach = AttachUi.On, attachNotice = "That file is over 25 MB."))
        rule.onNodeWithText("That file is over 25 MB.").assertIsDisplayed()
        rule.onNodeWithText("Dismiss").performClick()
        assertEquals(1, calls.dismiss)
    }

    // ---- the menu ----

    @Test fun theMenuOffersThreeSourcesAndCancel() {
        val chosen = mutableListOf<AttachSource>(); var dismissed = 0
        rule.setContent { PaddockTheme { AttachMenuDialog({ chosen += it }, { dismissed++ }) } }
        rule.onNodeWithText("Photo").performClick()
        rule.onNodeWithText("Document").performClick()
        rule.onNodeWithText("Take a photo").performClick()
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(listOf(AttachSource.Photo, AttachSource.Document, AttachSource.Camera), chosen)
        assertEquals(1, dismissed)
    }

    @Test fun theMenuLeavesTheCameraOutOnAPhoneWithNone() {
        rule.setContent { PaddockTheme { AttachMenuDialog({}, {}, cameraAvailable = false) } }
        rule.onAllNodesWithText("Take a photo").assertCountEquals(0)
        rule.onNodeWithText("Photo").assertIsDisplayed()
    }

    @Test fun theMenuSaysWhatHappensToTheFile() {
        rule.setContent { PaddockTheme { AttachMenuDialog({}, {}) } }
        rule.onNode(androidx.compose.ui.test.hasText("location and camera details", substring = true)).assertIsDisplayed()
    }

    // ---- the transfer sheet ----

    @Test fun theSheetShowsProgressAndTheKeepOpenNoteAndCancelStopsIt() {
        val calls = show(ComposerUi(header, SendGate.Open(false), attachment = file, transfer = TransferUi(file.name, 600_000, 1_200_000)))
        rule.onNodeWithText("Sending ${file.name}").assertIsDisplayed()
        rule.onNodeWithText("${Sizes.text(600_000)} of ${Sizes.text(1_200_000)}").assertIsDisplayed()
        rule.onNodeWithText(KEEP_OPEN_NOTE).assertIsDisplayed()
        val range = rule.onNodeWithTag("transfer-progress").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)
        assertEquals(0.5f, range!!.current, 0.01f)
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(1, calls.cancel)
    }

    @Test fun theSheetKeepsTheScreenOnWhileItShowsAndPutsTheSettingBackWhenItGoes() {
        show(ComposerUi(header, SendGate.Open(false), attachment = file, transfer = TransferUi(file.name, 1, 10)))
        rule.waitForIdle()
        assertTrue("the screen stays on while the sheet shows", hostKeepsScreenOn())
        rule.runOnIdle { ui = ui.copy(transfer = null) }
        rule.waitForIdle()
        assertFalse("and the setting is put back when it goes", hostKeepsScreenOn())
    }

    /** In a Dialog the composition's view is the host window's (the sheet sits over it), so that is where the flag goes. */
    private fun hostKeepsScreenOn(): Boolean {
        var on = false
        rule.runOnUiThread { on = anyKeepsScreenOn(rule.activity.window.decorView) }
        return on
    }

    private fun anyKeepsScreenOn(v: View): Boolean = v.keepScreenOn || (v is android.view.ViewGroup && (0 until v.childCount).any { anyKeepsScreenOn(v.getChildAt(it)) })

    @Test fun theSheetCannotBeDismissedByTheBackButtonOrOutsideTaps() {
        show(ComposerUi(header, SendGate.Open(false), attachment = file, transfer = TransferUi(file.name, 1, 10)))
        androidx.test.espresso.Espresso.pressBackUnconditionally()
        rule.waitForIdle()
        rule.onNodeWithTag("transfer-sheet").assertExists()
    }

    @Test fun anUnknownTotalDoesNotDivideByZero() {
        show(ComposerUi(header, SendGate.Open(false), attachment = file, transfer = TransferUi(file.name, 0, 0)))
        val range = rule.onNodeWithTag("transfer-progress").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)
        assertEquals(0f, range!!.current, 0f)
    }

    // ---- audits ----

    @Test fun auditComposerWithAChipDark() { show(ComposerUi(header, SendGate.Open(false), attach = AttachUi.On, attachment = file)); SemanticsAudit.expectClean(rule, "Composer with an attachment, dark") }
    @Test fun auditComposerWithAChipLight() { show(ComposerUi(header, SendGate.Open(false), attach = AttachUi.On, attachment = file), dark = false); SemanticsAudit.expectClean(rule, "Composer with an attachment, light") }
    @Test fun auditComposerWithAChipAt200Percent() { show(ComposerUi(header, SendGate.Open(false), attach = AttachUi.On, attachment = file), fontScale = 2f); SemanticsAudit.expectClean(rule, "Composer with an attachment, 200%") }
    @Test fun auditAttachOffWithItsReason() { show(ComposerUi(header, SendGate.Open(false), attach = AttachUi.Off(ATTACH_NOTE_OFF_NO_FOLDER))); SemanticsAudit.expectClean(rule, "Composer, Attach off") }
    @Test fun auditTheTransferSheetDark() { sheetAlone(dark = true); SemanticsAudit.expectClean(rule, "Transfer sheet, dark") }
    @Test fun auditTheTransferSheetAt200Percent() { sheetAlone(dark = false, fontScale = 2f); SemanticsAudit.expectClean(rule, "Transfer sheet, light 200%") }
    @Test fun auditTheMenu() { rule.setContent { PaddockTheme { AttachMenuDialog({}, {}) } }; SemanticsAudit.expectClean(rule, "Attach menu") }

    // The dialog alone, as the other dialogs' audits draw theirs: with the screen's window behind it the audit measures its text against pixels that are not its own.
    private fun sheetAlone(dark: Boolean, fontScale: Float = 1f) = rule.setContent {
        val base = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
            PaddockTheme(darkTheme = dark) { io.github.tuthan.paddock.ui.screens.TransferSheet(TransferUi(file.name, 5, 10)) {} }
        }
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onNodeWithContentDescriptionStartingWith(prefix: String) =
        onNode(androidx.compose.ui.test.SemanticsMatcher("content description starts with $prefix") { n ->
            n.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith(prefix) } == true
        })
}
