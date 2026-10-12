package io.github.tuthan.paddock.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import io.github.tuthan.paddock.files.ApkNotes
import io.github.tuthan.paddock.files.FileNames
import io.github.tuthan.paddock.files.GetLook
import io.github.tuthan.paddock.files.GetState
import io.github.tuthan.paddock.files.OpenableTypes
import io.github.tuthan.paddock.files.RemoteStat
import io.github.tuthan.paddock.files.TransferRefusal
import io.github.tuthan.paddock.routes.toUi
import io.github.tuthan.paddock.ui.screens.GetActions
import io.github.tuthan.paddock.ui.screens.GetCopy
import io.github.tuthan.paddock.ui.screens.GetSheet
import io.github.tuthan.paddock.ui.screens.GetUi
import io.github.tuthan.paddock.ui.theme.PaddockTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The Get sheet (Phase 15, slice 12): the offer, the read, the three ways to hand the file on, the APK sheet and every refusal's sentence. */
class GetSheetTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private class Calls { val seen = mutableListOf<String>() }

    private fun show(ui: GetUi, calls: Calls = Calls(), dark: Boolean = true, fontScale: Float = 1f): Calls {
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                PaddockTheme(darkTheme = dark) {
                    GetSheet(
                        ui,
                        GetActions(
                            onGet = { calls.seen += "get" }, onCancel = { calls.seen += "cancel" }, onOpen = { calls.seen += "open" },
                            onShare = { calls.seen += "share" }, onSave = { calls.seen += "save" }, onDone = { calls.seen += "done" },
                        ),
                    )
                }
            }
        }
        return calls
    }

    private val offer = GetUi.Offer("report.pdf", listOf("48 KB · changed 3 min ago", "In /work/proj/.paddock/outbox"), nameNote = null)
    private fun ready(canOpen: Boolean = true, apk: List<String> = emptyList(), note: String? = null) = GetUi.Ready("report.pdf", listOf("48 KB"), canOpen, apk, note)

    @Test fun theOfferShowsTheNameSizeAgeAndFolderAndGetAndCancelWork() {
        val calls = show(offer)
        rule.onNodeWithText(GetCopy.TITLE_OFFER).assertIsDisplayed()
        rule.onNodeWithText("report.pdf").assertIsDisplayed()
        rule.onNodeWithText("48 KB · changed 3 min ago").assertIsDisplayed()
        rule.onNodeWithText("In /work/proj/.paddock/outbox").assertIsDisplayed()
        rule.onNodeWithText("Get").performClick()
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(listOf("get", "cancel"), calls.seen)
    }

    @Test fun lookingShowsWhatWasTappedAndCancelWorks() {
        val calls = show(GetUi.Looking("out/report.pdf"))
        rule.onNodeWithText("Looking at out/report.pdf").assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(listOf("cancel"), calls.seen)
    }

    @Test fun theReadShowsProgressAndKeepOpenAndCancels() {
        val calls = show(GetUi.Reading("report.pdf", 12_000, 48_000))
        rule.onNodeWithTag("transfer-progress").assertIsDisplayed()
        rule.onNodeWithText("Getting report.pdf").assertIsDisplayed()
        rule.onNodeWithText("12 KB of 47 KB").assertIsDisplayed()
        rule.onNodeWithText("Keep Paddock open", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(listOf("cancel"), calls.seen)
    }

    @Test fun aReadyOpenableFileOffersOpenShareAndSaveEachToItsOwnCallback() {
        val calls = show(ready())
        for (label in listOf("Open", "Share", "Save", "Done")) rule.onNodeWithText(label).performClick()
        assertEquals(listOf("open", "share", "save", "done"), calls.seen)
    }

    @Test fun aTypeOutsideTheAllowlistOffersShareAndSaveOnly() {
        show(ready(canOpen = false))
        rule.onAllNodesWithText("Open").assertCountEquals(0)
        rule.onNodeWithText("Share").assertIsDisplayed()
        rule.onNodeWithText("Save").assertIsDisplayed()
    }

    @Test fun anApkNeverOffersOpenAndShowsItsFactsAndWhereToInstallFrom() {
        val facts = ApkNotes.lines(io.github.tuthan.paddock.files.ApkInfo("com.example.tool", "1.2", 34, listOf("a".repeat(64))), "io.github.tuthan.paddock", listOf("b".repeat(64)))
        show(ready(canOpen = false, apk = facts))
        rule.onAllNodesWithText("Open").assertCountEquals(0)
        rule.onNodeWithText("Package com.example.tool").assertIsDisplayed()
        rule.onNodeWithText("Version 1.2 (34)").assertIsDisplayed()
        rule.onNodeWithText("Signer SHA-256 ${"a".repeat(64)}").assertExists()
        rule.onNodeWithText(ApkNotes.WHERE).assertExists()
        rule.onNodeWithText("Share").assertExists()
        rule.onNodeWithText("Save").assertExists()
    }

    @Test fun anUnsignedApkIsNamedUnsigned() {
        show(ready(canOpen = false, apk = ApkNotes.lines(io.github.tuthan.paddock.files.ApkInfo("com.example.tool", null, 1, emptyList()), "io.github.tuthan.paddock", emptyList())))
        rule.onNodeWithText(ApkNotes.UNSIGNED).assertExists()
    }

    @Test fun paddocksOwnPackageUnderAnotherKeySaysUninstallFirst() {
        show(ready(canOpen = false, apk = ApkNotes.lines(io.github.tuthan.paddock.files.ApkInfo("io.github.tuthan.paddock", "0.0.1", 1, listOf("a".repeat(64))), "io.github.tuthan.paddock", listOf("b".repeat(64)))))
        rule.onNodeWithText(ApkNotes.OTHER_SIGNER).assertExists()
    }

    @Test fun whatSaveSaidIsShown() {
        show(ready(note = "Saved."))
        rule.onNodeWithTag("get-note").assertIsDisplayed()
        rule.onNodeWithText("Saved.").assertIsDisplayed()
    }

    @Test fun everyRefusalOfTheStatShowsItsSentenceAndDone() {
        for (r in listOf(TransferRefusal.NotFound, TransferRefusal.OutsideFolder, TransferRefusal.Symlink, TransferRefusal.NotRegular, TransferRefusal.OverCap, TransferRefusal.NotYours, TransferRefusal.BadPath)) {
            val calls = Calls()
            val ui = androidx.compose.runtime.mutableStateOf<GetUi>(GetUi.Failed(r.sentence))
            rule.setContent {
                PaddockTheme { GetSheet(ui.value, GetActions({}, {}, {}, {}, {}, { calls.seen += "done" })) }
            }
            rule.onNodeWithText(r.sentence).assertIsDisplayed()
            rule.onNodeWithText("Done").performClick()
            assertEquals(listOf("done"), calls.seen)
            rule.activityRule.scenario.recreate()
        }
    }

    // ---- what the controller's states become ----

    private fun look(machineName: String, size: Long = 48_000) = GetLook(
        RemoteStat(size, 1_791_700_000, "/work/proj/.paddock/outbox/$machineName"), "/work/proj",
        shownName = io.github.tuthan.paddock.output.SafeText.clean(machineName), nameChanged = io.github.tuthan.paddock.output.SafeText.clean(machineName) != machineName,
        cachedName = FileNames.cachedName(machineName), kind = OpenableTypes.kindOf(machineName),
    )

    @Test fun aNameWithABidirectionalOverrideIsShownCleanedAndTheChangeIsSaid() {
        val raw = "gpj.‮txt"
        val ui = GetState.Offer(look(raw)).toUi(1_791_700_000_000L + 180_000)
        show(ui)
        rule.onNodeWithText("gpj.txt").assertIsDisplayed()
        rule.onNodeWithText(GetCopy.savedAs("gpj._txt")).assertIsDisplayed()
        assertTrue("the override reaches no text on the sheet", rule.onAllNodesWithText("‮", substring = true).fetchSemanticsNodes().isEmpty())
    }

    @Test fun theOfferStatesSizeAgeAndFolderFromTheStat() {
        val ui = GetState.Offer(look("report.pdf")).toUi(1_791_700_000_000L + 180_000) as GetUi.Offer
        assertEquals(listOf("47 KB · changed 3 min ago", "In /work/proj/.paddock/outbox"), ui.facts)
        assertEquals(null, ui.nameNote)
    }

    @Test fun readyIsOpenableOnlyForTheAllowlist() {
        val file = File("/dev/null")
        fun canOpen(name: String) = (GetState.Ready(look(name), file).toUi(0) as GetUi.Ready).canOpen
        for (ok in listOf("a.png", "a.jpg", "a.pdf", "a.txt", "a.md", "a.json", "a.log")) assertTrue(ok, canOpen(ok))
        for (no in listOf("a.apk", "a.svg", "a.html", "a.htm", "a.zip", "a.sh", "a.exe", "a", "a.PDF.exe")) assertEquals(no, false, canOpen(no))
    }

    // ---- audits ----

    @Test fun auditTheOfferDark() { show(offer); SemanticsAudit.expectClean(rule, "Get offer, dark") }
    @Test fun auditTheOfferLight() { show(offer, dark = false); SemanticsAudit.expectClean(rule, "Get offer, light") }
    @Test fun auditTheOfferAt200Percent() { show(offer, fontScale = 2f); SemanticsAudit.expectClean(rule, "Get offer, 200%") }
    @Test fun auditReadyDark() { show(ready(note = "Saved.")); SemanticsAudit.expectClean(rule, "Get ready, dark") }
    @Test fun auditReadyAt200Percent() { show(ready(), dark = false, fontScale = 2f); SemanticsAudit.expectClean(rule, "Get ready, light 200%") }
    @Test fun auditTheApkSheet() {
        show(ready(canOpen = false, apk = ApkNotes.lines(io.github.tuthan.paddock.files.ApkInfo("com.example.tool", "1.2", 34, listOf("a".repeat(64))), "io.github.tuthan.paddock", listOf("b".repeat(64)))))
        SemanticsAudit.expectClean(rule, "Get APK sheet")
    }
    @Test fun auditAFailureAndLooking() { show(GetUi.Failed(TransferRefusal.OutsideFolder.sentence)); SemanticsAudit.expectClean(rule, "Get failure") }
}
