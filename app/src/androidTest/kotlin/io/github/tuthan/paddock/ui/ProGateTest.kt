package io.github.tuthan.paddock.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.tuthan.paddock.billing.GateContext
import io.github.tuthan.paddock.billing.ProCapabilities
import io.github.tuthan.paddock.billing.ProCopy
import io.github.tuthan.paddock.billing.ProGate
import io.github.tuthan.paddock.herdr.SessionEntry
import io.github.tuthan.paddock.live.SessionRow
import io.github.tuthan.paddock.live.SpacesState
import io.github.tuthan.paddock.ui.components.ButtonKind
import io.github.tuthan.paddock.ui.components.HostHealth
import io.github.tuthan.paddock.ui.components.PRO_LOCK_TAG
import io.github.tuthan.paddock.ui.components.PaddockButton
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

    // ---- D6: the sheet lists what Pro covers with the chosen capability first, then the free path, then what stays free ----------------

    private val chosen = ProCapabilities.MANAGE_SESSIONS.id
    private val lines = ProCopy.coverageLines(ProGate.GATED, first = chosen)

    @Test
    fun theGateListsWhatProCoversWithTheChosenOneFirstThenTheFreePathAndWhatStaysFree() {
        var bought = 0; var declined = 0
        rule.setContent {
            PaddockTheme(darkTheme = true) {
                ProGateSheet(
                    lines, ProCapabilities.freePath(chosen), "US$19.99", busy = false, message = null, onBuy = { bought++ }, onNotNow = { declined++ },
                    title = ProCopy.gateTitle(chosen),
                )
            }
        }
        rule.onNodeWithText("Stopping and deleting sessions is a Pro capability").assertIsDisplayed()
        assertEquals("Stopping and deleting sessions", lines.first())
        // The list is one block for TalkBack: "Pro covers: a, b, c", the chosen capability first.
        rule.onNode(hasContentDescription("Pro covers: " + lines.joinToString(", "))).assertIsDisplayed()
        for (line in lines) rule.onNodeWithText(line, useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText(ProCapabilities.freePath(chosen)!!).assertIsDisplayed()
        rule.onNodeWithText(ProCopy.STAYS_FREE).performScrollTo().assertIsDisplayed()
        // The free path is said above what stays free, and both above the buy button.
        val top = { t: String -> rule.onNodeWithText(t).fetchSemanticsNode().boundsInRoot.top }
        assertTrue(top(ProCapabilities.freePath(chosen)!!) < top(ProCopy.STAYS_FREE))
        assertTrue(top(ProCopy.STAYS_FREE) < top("Buy Pro · US$19.99"))
        assertEquals(0, rule.onAllNodesWithTextCount("This is the free version", substring = true))
        rule.onNodeWithText("Not now").performScrollTo().performClick()
        rule.onNodeWithText("Buy Pro · US$19.99").performScrollTo().performClick()
        assertEquals(1, declined); assertEquals(1, bought)
    }

    @Test
    fun theFreeVersionsGateIsTitleFreePathWhereProComesFromAndNotNow() {
        rule.setContent {
            PaddockTheme(darkTheme = true) {
                ProGateSheet(lines, ProCapabilities.freePath(chosen), null, busy = false, message = null, onBuy = {}, onNotNow = {}, canBuy = false, title = ProCopy.gateTitle(chosen))
            }
        }
        rule.onNodeWithText("Stopping and deleting sessions is a Pro capability").assertIsDisplayed()
        rule.onNodeWithText(ProCapabilities.freePath(chosen)!!).assertIsDisplayed()
        rule.onNodeWithText("This is the free version", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Not now").assertIsDisplayed()
        // Nothing can be bought here, so there is no list of what a purchase brings and no offer beside it.
        assertEquals(0, rule.onAllNodesWithTextCount("Buy Pro", substring = true))
        assertEquals(0, rule.onAllNodesWithTextCount(ProCopy.COVERS))
        assertEquals(0, rule.onAllNodesWithTextCount(ProCopy.STAYS_FREE))
        assertEquals(0, rule.onAllNodes(hasContentDescription(ProCopy.COVERS, substring = true)).fetchSemanticsNodes().size)
    }

    // ---- D5: the overview Settings opens ("What Pro covers") ------------------------------------------------------------------------

    private val all = ProCopy.coverageLines(ProGate.GATED)

    private fun overview(canBuy: Boolean, onBuy: () -> Unit = {}, onNotNow: () -> Unit = {}) {
        rule.setContent {
            PaddockTheme(darkTheme = true) {
                ProGateSheet(
                    all, null, "US$19.99".takeIf { canBuy }, busy = false, message = null, onBuy = onBuy, onNotNow = onNotNow, canBuy = canBuy,
                    title = ProCopy.gateTitle(ProCopy.OVERVIEW_ID), overview = true,
                )
            }
        }
    }

    @Test
    fun theFreeVersionsOverviewListsEveryLineSaysWhatStaysFreeAndWhereProComesFromAndOffersNoBuy() {
        var declined = 0
        overview(canBuy = false, onNotNow = { declined++ })
        rule.onNodeWithText("What Pro covers").assertIsDisplayed()
        // The overview's point is the list, so unlike a capability's sheet in the free version it is shown, in the one list's order.
        assertEquals(6, all.size)
        rule.onNode(hasContentDescription("Pro covers: " + all.joinToString(", "))).assertIsDisplayed()
        for (line in all) rule.onNodeWithText(line, useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText(ProCopy.STAYS_FREE).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(ProCopy.FREE_VERSION_ROUTE).performScrollTo().assertIsDisplayed()
        val top = { t: String -> rule.onNodeWithText(t).fetchSemanticsNode().boundsInRoot.top }
        assertTrue("what stays free, then where Pro comes from", top(ProCopy.STAYS_FREE) < top(ProCopy.FREE_VERSION_ROUTE))
        assertEquals(0, rule.onAllNodesWithTextCount("Buy Pro", substring = true))
        assertEquals("no capability was chosen, so no free path for one", 0, rule.onAllNodesWithTextCount("Free: ", substring = true))
        rule.onNodeWithText("Not now").performScrollTo().performClick()
        assertEquals(1, declined)
    }

    @Test
    fun whereProIsSoldTheOverviewOffersBuyAndNoFreePath() {
        // The play build's overview (its instrumentation is compile-only here; this is the same composable with canBuy on).
        var bought = 0
        overview(canBuy = true, onBuy = { bought++ })
        rule.onNodeWithText("What Pro covers").assertIsDisplayed()
        rule.onNode(hasContentDescription("Pro covers: " + all.joinToString(", "))).assertIsDisplayed()
        rule.onNodeWithText(ProCopy.STAYS_FREE).performScrollTo().assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTextCount("Free: ", substring = true))
        assertEquals(0, rule.onAllNodesWithTextCount("This is the free version", substring = true))
        rule.onNodeWithText("Buy Pro · US$19.99").performScrollTo().performClick()
        assertEquals(1, bought)
    }

    @Test
    fun aGateWithNoFreePathLeavesTheLineOut() {
        rule.setContent { PaddockTheme(darkTheme = true) { ProGateSheet(lines, null, null, busy = false, message = null, onBuy = {}, onNotNow = {}) } }
        assertEquals(0, rule.onAllNodesWithTextCount("Free: ", substring = true))
        rule.onNodeWithText(ProCopy.STAYS_FREE).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aBusyGateCannotBuyTwice() {
        var bought = 0
        rule.setContent { PaddockTheme(darkTheme = true) { ProGateSheet(listOf("x"), null, null, busy = true, message = "Working", onBuy = { bought++ }, onNotNow = {}) } }
        rule.onNodeWithText("Buy Pro").performScrollTo().performClick()
        assertEquals(0, bought)
    }

    @Test
    fun at200PercentFontBothButtonsOfTheGateStayReachable() {
        var declined = 0
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, 2f)) {
                PaddockTheme(darkTheme = true) { ProGateSheet(lines, ProCapabilities.freePath(chosen), "US$19.99", busy = false, message = "Google Play did not answer.", onBuy = {}, onNotNow = { declined++ }) }
            }
        }
        rule.onNodeWithText("Buy Pro · US$19.99").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Not now").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, declined)
    }

    // ---- D6: a locked button draws the lock and the tag, and is read as "<label> · Pro" -----------------------------------------------

    @Test
    fun aProButtonIsReadAsItsLabelThenProAndShowsTheTag() {
        var taps = 0
        rule.setContent { PaddockTheme(darkTheme = true) { PaddockButton("Stop…", { taps++ }, kind = ButtonKind.Danger, small = true, pro = true) } }
        rule.onNode(hasContentDescription("Stop… · Pro") and hasClickAction()).assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals("a locked button still reaches its caller, which asks the gate", 1, taps)
        // The visible label is the plain one, and the tag says "Pro" in words, so nothing rests on the glyph or a colour.
        rule.onNode(hasText("Stop…") and hasText("Pro") and hasClickAction()).assertExists()
        // And the lock glyph is drawn (AC-4: glyph and tag), inside this button.
        rule.onNode(hasTestTag(PRO_LOCK_TAG) and hasAnyAncestor(hasContentDescription("Stop… · Pro")), useUnmergedTree = true).assertExists()
        assertEquals(0, rule.onAllNodesWithTextCount("Stop… · Pro"))
    }

    @Test
    fun aPlainButtonSaysNothingAboutPro() {
        rule.setContent { PaddockTheme(darkTheme = true) { PaddockButton("Stop…", {}, kind = ButtonKind.Danger, small = true) } }
        rule.onNode(hasText("Stop…") and hasClickAction()).assertIsDisplayed()
        assertEquals(0, rule.onAllNodes(hasContentDescription("Pro", substring = true)).fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithTextCount("Pro"))
        assertEquals(0, rule.onAllNodes(hasTestTag(PRO_LOCK_TAG), useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun aCallersOwnDescriptionWinsOverTheProOne() {
        rule.setContent {
            PaddockTheme(darkTheme = true) {
                PaddockButton("Watch", {}, Modifier.semantics { contentDescription = "Watch Beta server · Pro" }, kind = ButtonKind.Secondary, small = true, fillWidth = false, pro = true)
            }
        }
        rule.onNode(hasContentDescription("Watch Beta server · Pro") and hasClickAction()).assertIsDisplayed()
        assertEquals(0, rule.onAllNodes(hasContentDescription("Watch · Pro")).fetchSemanticsNodes().size)
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

    /** A locked button as TalkBack reaches it: its spoken name is "<label> · Pro" (D6: the visible label is plain, the tag says Pro). */
    private fun locked(label: String) = rule.onNode(hasContentDescription("$label · Pro") and hasClickAction())

    @Test
    fun aLockedStartAgentSaysProAndTheFormStaysClosedWhileTheGateRefuses() {
        val calls = spaces(setOf(ProCapabilities.START_AGENT.id), allow = false)
        locked("Start an agent…").performScrollTo().assertIsDisplayed()
        rule.onNode(hasText("Start an agent…") and hasText("Pro") and hasClickAction()).assertExists()
        rule.onNode(hasTestTag(PRO_LOCK_TAG) and hasAnyAncestor(hasContentDescription("Start an agent… · Pro")), useUnmergedTree = true).assertExists()
        locked("Start an agent…").performClick()
        assertEquals(listOf("operations.start"), calls.chosen)
        assertEquals(0, rule.onAllNodesWithTextCount("Start agent"))
    }

    @Test
    fun theFormOpensWhenTheGateAllowsIt() {
        val calls = spaces(setOf(ProCapabilities.START_AGENT.id), allow = true)
        locked("Start an agent…").performScrollTo().performClick()
        assertEquals(listOf("operations.start"), calls.chosen)
        rule.onNodeWithText("Start agent").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aLockedStopAndDeleteSayProAndAskNoQuestionWhileTheGateRefuses() {
        val calls = spaces(setOf(ProCapabilities.MANAGE_SESSIONS.id), allow = false)
        // Locked, they stay Danger buttons with their plain labels and the tag beside them.
        rule.onNode(hasText("Stop…") and hasText("Pro") and hasClickAction()).assertExists()
        rule.onNode(hasText("Delete…") and hasText("Pro") and hasClickAction()).assertExists()
        for (label in listOf("Stop…", "Delete…")) rule.onNode(hasTestTag(PRO_LOCK_TAG) and hasAnyAncestor(hasContentDescription("$label · Pro")), useUnmergedTree = true).assertExists()
        locked("Stop…").performScrollTo().performClick()
        locked("Delete…").performScrollTo().performClick()
        assertEquals(listOf("operations.manage", "operations.manage"), calls.chosen)
        assertTrue(calls.stopped.isEmpty() && calls.deleted.isEmpty())
        assertEquals(0, rule.onAllNodesWithTextCount("Stop session", substring = true))
        assertEquals(0, rule.onAllNodesWithTextCount("Delete session", substring = true))
        // Start an agent was not locked: it has no tag and no Pro in its name.
        assertEquals(0, rule.onAllNodes(hasContentDescription("Start an agent… · Pro")).fetchSemanticsNodes().size)
    }

    @Test
    fun anUnlockedSpacesScreenKeepsItsPlainLabels() {
        spaces(emptySet(), allow = true)
        rule.onNodeWithText("Start an agent…").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Stop…").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Delete…").performScrollTo().assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTextCount(" · Pro", substring = true))
        assertEquals(0, rule.onAllNodesWithTextCount("Pro"))
        assertEquals(0, rule.onAllNodes(hasContentDescription("· Pro", substring = true)).fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodes(hasTestTag(PRO_LOCK_TAG), useUnmergedTree = true).fetchSemanticsNodes().size)
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
