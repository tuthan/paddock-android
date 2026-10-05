package io.github.tuthan.paddock.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.tuthan.paddock.billing.GateContext
import io.github.tuthan.paddock.billing.ProCapabilities
import io.github.tuthan.paddock.billing.ProGate
import io.github.tuthan.paddock.herdr.SessionEntry
import io.github.tuthan.paddock.live.SessionRow
import io.github.tuthan.paddock.live.SpacesState
import io.github.tuthan.paddock.ui.components.HostHealth
import io.github.tuthan.paddock.ui.screens.GateNoticeBar
import io.github.tuthan.paddock.ui.screens.LocalAccess
import io.github.tuthan.paddock.ui.screens.ProCardState
import io.github.tuthan.paddock.ui.screens.ProGateSheet
import io.github.tuthan.paddock.ui.screens.Settings
import io.github.tuthan.paddock.ui.screens.SpacesActions
import io.github.tuthan.paddock.ui.screens.SpacesScreen
import io.github.tuthan.paddock.ui.screens.SpacesScreenState
import io.github.tuthan.paddock.ui.screens.StartAvailability
import io.github.tuthan.paddock.ui.screens.WorkspaceChoice
import io.github.tuthan.paddock.ui.screens.SettingsState
import io.github.tuthan.paddock.ui.screens.TipOption
import io.github.tuthan.paddock.ui.theme.PaddockTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The Pro gate sheet and Settings' Pro card (Phase 13). Placement rules are JVM tests (`ProGateTest` in :core); these are the screens. */
class ProGateTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun settings(pro: ProCardState, onRestore: () -> Unit = {}, onTip: (String) -> Unit = {}) {
        rule.setContent {
            PaddockTheme(darkTheme = true) {
                Settings(SettingsState(true, LocalAccess.NotRequired, "0.1.0", pro = pro), {}, {}, {}, onRestorePurchase = onRestore, onBuyTip = onTip)
            }
        }
    }

    @Test
    fun theGateSaysWhatStaysFreeBesideTheBuyButtonAndNotNowIsOffered() {
        var bought = 0; var declined = 0
        rule.setContent {
            PaddockTheme(darkTheme = true) {
                ProGateSheet("Everything else, including every view of your agents, stays free.", "US$19.99", busy = false, message = null, onBuy = { bought++ }, onNotNow = { declined++ })
            }
        }
        rule.onNodeWithText("This is a Pro capability").assertIsDisplayed()
        rule.onNodeWithText("Everything else, including every view of your agents, stays free.").assertIsDisplayed()
        rule.onNodeWithText("Not now").performClick()
        rule.onNodeWithText("Buy Pro · US$19.99").performClick()
        assertEquals(1, declined); assertEquals(1, bought)
    }

    @Test
    fun theFreeVersionsGateOffersNoBuyButtonAndSaysWhereProComesFrom() {
        rule.setContent {
            PaddockTheme(darkTheme = true) { ProGateSheet("x", null, busy = false, message = null, onBuy = {}, onNotNow = {}, canBuy = false) }
        }
        rule.onNodeWithText("This is the free version", substring = true).assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTextCount("Buy Pro"))
        rule.onNodeWithText("Not now").assertIsDisplayed()
    }

    @Test
    fun aBusyGateCannotBuyTwice() {
        var bought = 0
        rule.setContent { PaddockTheme(darkTheme = true) { ProGateSheet("x", null, busy = true, message = "Working", onBuy = { bought++ }, onNotNow = {}) } }
        rule.onNodeWithText("Buy Pro").performClick()
        assertEquals(0, bought)
    }

    @Test
    fun theUnlockedBuildSaysSoAndOffersNothingToBuy() {
        settings(ProCardState("Everything is unlocked", "This build carries every feature. Nothing is sold in it."))
        rule.onNodeWithText("Everything is unlocked").performScrollTo().assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTextCount("Restore purchase"))
        assertEquals(0, rule.onAllNodesWithTextCount("Support development"))
    }

    @Test
    fun theFreeVersionCardNamesTheRouteToProAndHasNoRestore() {
        settings(ProCardState("Free version", io.github.tuthan.paddock.billing.ProCopy.FREE_VERSION_ROUTE))
        rule.onNodeWithText("Free version").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("source code", substring = true).assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTextCount("Restore purchase"))
    }

    @Test
    fun thePlayBuildShowsRestoreAndTipsOnlyWhenTheStoreListedPrices() {
        var restored = 0; var tipped: String? = null
        settings(
            ProCardState("Free", "Pro is not on this Google account.", sellsPro = true, refunds = "Refunds are Google Play's.", tips = listOf(TipOption("tip_3", "US$2.99"))),
            onRestore = { restored++ }, onTip = { tipped = it },
        )
        rule.onNodeWithText("Restore purchase").performScrollTo().performClick()
        rule.onNodeWithText("Tip US$2.99").performScrollTo().performClick()
        assertEquals(1, restored); assertEquals("tip_3", tipped)
    }

    @Test
    fun withoutPricesThereIsNoTipSectionAndABusyCardDisablesRestore() {
        var restored = 0
        settings(ProCardState("Free", "d", sellsPro = true, busy = true), onRestore = { restored++ })
        rule.onNodeWithText("Restore purchase").performScrollTo().performClick()
        assertEquals(0, restored)
        assertEquals(0, rule.onAllNodesWithTextCount("Support development"))
    }

    // ---- M8: what the lock looks like where a Pro capability is chosen ------------------------------------------------------------

    private fun entry(name: String, running: Boolean) =
        SessionEntry(name, name == "main", running, "/home/u/.config/herdr/sessions/$name", "/home/u/.config/herdr/sessions/$name/herdr.sock")

    private class Spaced { val chosen = mutableListOf<String>(); val stopped = mutableListOf<String>(); val deleted = mutableListOf<String>(); var allow = false }

    private fun spaces(locked: Set<String>, allow: Boolean): Spaced {
        val calls = Spaced().also { it.allow = allow }
        val rows = listOf(SessionRow(entry("main", true), null), SessionRow(entry("scratch", false), null))
        rule.setContent {
            PaddockTheme(darkTheme = true) {
                SpacesScreen(
                    SpacesScreenState(
                        hostName = "Laptop", hostStatus = "live", health = HostHealth.Live, list = SpacesState.Ready(rows, 1_790_000_000_000), notice = null, watchedSession = "main",
                        cards = emptyList(), progress = null, start = StartAvailability.Available(listOf(WorkspaceChoice("w_1", "paddock"))), locked = locked,
                    ),
                    SpacesActions(
                        onChoose = { id -> calls.chosen += id; calls.allow }, onStop = { calls.stopped += it.name }, onDelete = { calls.deleted += it.name },
                    ),
                    clock = { "12:34" },
                )
            }
        }
        return calls
    }

    @Test
    fun aLockedStartAgentSaysProAndTheFormStaysClosedWhileTheGateRefuses() {
        val calls = spaces(setOf(ProCapabilities.START_AGENT.id), allow = false)
        rule.onNodeWithText("Start an agent… · Pro").performScrollTo().performClick()
        assertEquals(listOf("operations.start"), calls.chosen)
        assertEquals(0, rule.onAllNodesWithTextCount("Start agent"))
    }

    @Test
    fun theFormOpensWhenTheGateAllowsIt() {
        val calls = spaces(setOf(ProCapabilities.START_AGENT.id), allow = true)
        rule.onNodeWithText("Start an agent… · Pro").performScrollTo().performClick()
        assertEquals(listOf("operations.start"), calls.chosen)
        rule.onNodeWithText("Start agent").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aLockedStopAndDeleteSayProAndAskNoQuestionWhileTheGateRefuses() {
        val calls = spaces(setOf(ProCapabilities.MANAGE_SESSIONS.id), allow = false)
        rule.onNodeWithText("Stop… · Pro").performScrollTo().performClick()
        rule.onNodeWithText("Delete… · Pro").performScrollTo().performClick()
        assertEquals(listOf("operations.manage", "operations.manage"), calls.chosen)
        assertTrue(calls.stopped.isEmpty() && calls.deleted.isEmpty())
        assertEquals(0, rule.onAllNodesWithTextCount("Stop session", substring = true))
        assertEquals(0, rule.onAllNodesWithTextCount("Delete session", substring = true))
    }

    @Test
    fun anUnlockedSpacesScreenKeepsItsPlainLabels() {
        spaces(emptySet(), allow = true)
        rule.onNodeWithText("Start an agent…").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Stop…").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Delete…").performScrollTo().assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTextCount(" · Pro", substring = true))
    }

    @Test
    fun settingsMarksGuardedAnswersProWhenLockedAndOpensNothingItself() {
        var opened = 0
        rule.setContent {
            PaddockTheme(darkTheme = true) {
                Settings(SettingsState(true, LocalAccess.NotRequired, "0.1.0", guardedAnswersLocked = true), {}, {}, {}, onGuardedAnswers = { opened++ })
            }
        }
        rule.onNodeWithText("Guarded answers · Pro").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, opened) // the screen only reports the tap; the route asks the gate before it navigates
    }

    @Test
    fun settingsKeepsThePlainGuardedAnswersLabelWhenNothingIsLocked() {
        rule.setContent { PaddockTheme(darkTheme = true) { Settings(SettingsState(true, LocalAccess.NotRequired, "0.1.0"), {}, {}, {}) } }
        rule.onNodeWithText("Guarded answers").performScrollTo().assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTextCount("Guarded answers · Pro"))
    }

    // ---- a deferred tap says why, without the gate ------------------------------------------------------------------------------------

    @Test
    fun aDeferredTapShowsItsSentenceAsANoticeThatCanBeDismissedAndNeverAsTheGate() {
        // The notice is what PaddockRoot shows for AppGraph.gateNotice (ProGraphTest holds which sentence each context gets); it is the notice bar, not the sheet.
        val contexts = listOf(GateContext.PENDING_ANSWER, GateContext.MANUAL_INPUT, GateContext.OPERATION_IN_FLIGHT)
        var sentence by mutableStateOf<String?>(ProGate.deferNotice(contexts.first()))
        var dismissed = 0
        rule.setContent { PaddockTheme(darkTheme = true) { sentence?.let { GateNoticeBar(it, onDismiss = { dismissed++ }) } } }
        for (context in contexts) {
            val text = ProGate.deferNotice(context)!!
            rule.runOnIdle { sentence = text }
            rule.onNodeWithText(text).assertIsDisplayed()
            // The notice carries no offer: nothing to buy, no gate title, no "Not now" to decide.
            assertEquals(0, rule.onAllNodesWithTextCount("Buy Pro", substring = true))
            assertEquals(0, rule.onAllNodesWithTextCount("a Pro capability", substring = true))
            assertEquals(0, rule.onAllNodesWithTextCount("Not now"))
        }
        rule.onNodeWithText("Dismiss").performClick()
        assertEquals(1, dismissed)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String, substring: Boolean = false) =
        onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().size
}
