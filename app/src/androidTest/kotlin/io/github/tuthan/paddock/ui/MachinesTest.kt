package io.github.tuthan.paddock.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.tuthan.paddock.attention.AttentionModel
import io.github.tuthan.paddock.attention.ObservedAt
import io.github.tuthan.paddock.herdr.Snapshot
import io.github.tuthan.paddock.host.WakeWordsMapper
import io.github.tuthan.paddock.hostprofile.HostProfile
import io.github.tuthan.paddock.hostprofile.MachineCopy
import io.github.tuthan.paddock.hostprofile.MachineRoster
import io.github.tuthan.paddock.hostprofile.MachineRow
import io.github.tuthan.paddock.hostprofile.WakeWord
import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.net.Ipv4Subnet
import io.github.tuthan.paddock.ui.components.HostChip
import io.github.tuthan.paddock.ui.components.HostHealth
import io.github.tuthan.paddock.ui.components.LOCK_TAG
import io.github.tuthan.paddock.probe.ProbeCopy
import io.github.tuthan.paddock.probe.ProbeProblem
import io.github.tuthan.paddock.probe.ProbeReading
import io.github.tuthan.paddock.probe.ProbeState
import io.github.tuthan.paddock.ui.components.GLANCE_TAG
import io.github.tuthan.paddock.ui.components.MachineChip
import io.github.tuthan.paddock.ui.components.MachineChipRow
import io.github.tuthan.paddock.ui.components.NoticeBar
import io.github.tuthan.paddock.ui.components.PRO_LOCK_TAG
import io.github.tuthan.paddock.ui.screens.GateNoticeBar
import io.github.tuthan.paddock.ui.screens.HerdHome
import io.github.tuthan.paddock.ui.screens.HomeUiState
import io.github.tuthan.paddock.ui.screens.MachineCard
import io.github.tuthan.paddock.ui.screens.MachineListState
import io.github.tuthan.paddock.ui.screens.MachinePage
import io.github.tuthan.paddock.ui.screens.MachinePageState
import io.github.tuthan.paddock.ui.screens.MachinesScreen
import io.github.tuthan.paddock.ui.theme.PaddockTheme
import io.github.tuthan.paddock.wake.WakeFacts
import io.github.tuthan.paddock.wake.WakeReadiness
import io.github.tuthan.paddock.wake.WakeRelay
import io.github.tuthan.paddock.wake.WakeSendResult
import io.github.tuthan.paddock.wake.WakeTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The saved machines (decisions D3 and D4, 2026-10-06): the Machines screen (one card per machine, the watched one first, a Watch on the others,
 * Add another machine), a machine's page (Watch, Wake-on-LAN for a machine watched or not, its alerts, Remove), and Home's chip row. Watching another
 * machine is Pro; going back to the machine the user chose after an alert moved the phone is Free; Wake, Remove and Add are Free.
 */
class MachinesTest {
    @get:Rule val rule = createComposeRule()

    private val ready = WakeTarget(
        available = true, mac = "02:00:5e:10:00:01", iface = "eth0", capturedAtMillis = 5, gateway = "10.0.0.1",
        readiness = WakeReadiness(wakeup = "enabled", ethtool = "g"),
    )
    private val machines = listOf(
        HostProfile("alpha", "Alpha laptop", "192.168.42.86", 22, "jdoe"),
        HostProfile("beta", "Beta server", "10.0.0.7", 2222, "ops", session = "work", wake = ready),
        HostProfile("gamma", "Gamma", "gamma.lan", 22, "jdoe"),
    )

    private class Taps {
        val opened = mutableListOf<String>(); val watched = mutableListOf<String>(); var added = 0; var back = 0
        var removed = 0; var relay = 0; var guarded = 0; var woken = 0; val copied = mutableListOf<String>(); val saved = mutableListOf<WakeRelay?>()
    }

    @Composable
    private fun Themed(fontScale: Float?, dark: Boolean, content: @Composable () -> Unit) {
        val base = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides if (fontScale != null) Density(base.density, fontScale) else base) { PaddockTheme(darkTheme = dark) { content() } }
    }

    /** The cards as the route builds them: the state line from the same `MachineCopy` words. */
    private fun cards(watched: String?, chosen: String?) = MachineRoster.rows(machines, watched, chosen).map { row ->
        MachineCard(row, if (row.watched) MachineCopy.watchingLine(MachineCopy.LIVE) else MachineCopy.otherLine(if (row.id == "beta") WakeWord.Ready else WakeWord.NotRead, alerts = row.id == "beta"))
    }

    private fun list(locked: Boolean, watched: String? = "alpha", chosen: String? = watched, busy: Boolean = false, fontScale: Float? = null, dark: Boolean = true): Taps {
        val taps = Taps()
        rule.setContent {
            Themed(fontScale, dark) {
                MachinesScreen(
                    MachineListState(cards(watched, chosen), switchLocked = locked, busy = busy),
                    onBack = { taps.back++ }, onOpen = { taps.opened += it.id }, onWatch = { taps.watched += it.id }, onAdd = { taps.added++ },
                )
            }
        }
        return taps
    }

    private fun card(name: String): SemanticsNodeInteraction = rule.onNode(hasText(name) and hasClickAction())

    // ---- the Machines screen ------------------------------------------------------------------------------------------------------------

    @Test fun everySavedMachineIsListedWithItsAddressSessionAndState() {
        list(locked = false)
        rule.onNodeWithText(MachineCopy.TITLE).assertIsDisplayed()
        for (name in listOf("Alpha laptop", "Beta server", "Gamma")) card(name).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("jdoe@192.168.42.86:22 · default session", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("ops@10.0.0.7:2222 · session work", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Watching · live", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Not watched · wake ready · alerts set up", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Not watched · wake not read", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText(MachineCopy.INTRO).assertIsDisplayed()
        rule.onNodeWithText("An alert moved", substring = true).assertDoesNotExist()
    }

    @Test fun theWatchedMachineComesFirstAndHasNoWatchButton() {
        list(locked = false, watched = "beta")
        val top = { name: String -> card(name).getUnclippedBoundsInRoot().top }
        assertTrue("the watched machine is the first card", top("Beta server") < top("Alpha laptop") && top("Alpha laptop") < top("Gamma"))
        rule.onNodeWithContentDescription("Watch Beta server").assertDoesNotExist()
        rule.onNodeWithContentDescription("Watch Alpha laptop").assertHasClickAction()
        rule.onNodeWithContentDescription("Watch Gamma").assertHasClickAction()
    }

    @Test fun aCardOpensItsPageAndWatchIsItsOwnTap() {
        val taps = list(locked = false)
        card("Gamma").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Watch Beta server").performScrollTo().performClick()
        assertEquals(listOf("gamma"), taps.opened)
        assertEquals(listOf("beta"), taps.watched)
    }

    @Test fun removeIsOnlyOnThePage() {
        list(locked = false)
        rule.onAllNodesWithText("Remove…").assertCountEquals(0)
        rule.onAllNodesWithContentDescription("Remove", substring = true).assertCountEquals(0)
    }

    @Test fun withoutProALockedWatchSaysProInItsNameAndShowsTheTagAndStillReachesTheCaller() {
        val taps = list(locked = true)
        rule.onNodeWithContentDescription("Watch Beta server · Pro").assertHasClickAction()
        rule.onNodeWithContentDescription("Watch Gamma · Pro").performScrollTo().performClick()
        rule.onAllNodesWithText("Pro", useUnmergedTree = true).assertCountEquals(2)
        rule.onAllNodesWithTag(PRO_LOCK_TAG, useUnmergedTree = true).assertCountEquals(2)
        rule.onAllNodesWithText("Watch · Pro").assertCountEquals(0)
        assertEquals("a locked Watch hands the row to the caller, which asks the gate", listOf("gamma"), taps.watched)
    }

    @Test fun withoutProGoingBackToTheChosenMachineIsPlainAndTheListSaysWhy() {
        // The user chose Alpha; an alert moved the phone to Beta.
        val taps = list(locked = true, watched = "beta", chosen = "alpha")
        rule.onNodeWithText(MachineCopy.displaced("Alpha laptop", "Beta server")).assertIsDisplayed()
        rule.onNodeWithContentDescription("Watch Alpha laptop").assertHasClickAction()
        rule.onNodeWithContentDescription("Watch Alpha laptop · Pro").assertDoesNotExist()
        rule.onNodeWithContentDescription("Watch Gamma · Pro").assertHasClickAction()
        rule.onAllNodesWithText("Pro", useUnmergedTree = true).assertCountEquals(1)
        rule.onAllNodesWithTag(PRO_LOCK_TAG, useUnmergedTree = true).assertCountEquals(1)
        rule.onNode(hasTestTag(PRO_LOCK_TAG) and hasAnyAncestor(hasContentDescription("Watch Gamma · Pro")), useUnmergedTree = true).assertExists()
        rule.onNodeWithContentDescription("Watch Alpha laptop").performScrollTo().performClick()
        assertEquals(listOf("alpha"), taps.watched)
    }

    @Test fun withProThereIsNoDisplacedLineAndNoTag() {
        list(locked = false, watched = "beta", chosen = "alpha")
        rule.onNodeWithText(MachineCopy.displaced("Alpha laptop", "Beta server")).assertDoesNotExist()
        rule.onAllNodesWithText("Pro", useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodesWithTag(PRO_LOCK_TAG, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun whileARemovalRunsWatchWaitsButOpenAndAddDoNot() {
        val taps = list(locked = false, busy = true)
        rule.onNodeWithContentDescription("Watch Gamma").assertIsNotEnabled()
        card("Gamma").performScrollTo().performClick()
        rule.onNodeWithText("Add another machine").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf("gamma"), taps.opened)
        assertEquals(1, taps.added)
    }

    @Test fun addAnotherMachineAndBackAreThere() {
        val taps = list(locked = true)
        rule.onNodeWithText("Add another machine").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, taps.added)
        assertEquals(1, taps.back)
    }

    @Test fun theListIsReachableAtTwiceTheFontSize() {
        val taps = list(locked = true, fontScale = 2f)
        rule.onNodeWithContentDescription("Watch Gamma · Pro").performScrollTo().assertIsDisplayed().performClick()
        card("Gamma").performScrollTo().performClick()
        rule.onNodeWithText("Add another machine").performScrollTo().assertIsDisplayed()
        assertEquals(listOf("gamma"), taps.watched)
        assertEquals(listOf("gamma"), taps.opened)
    }

    @Test fun auditListDark() { list(locked = false); SemanticsAudit.expectClean(rule, "Machines, dark") }
    @Test fun auditListLockedLight() { list(locked = true, dark = false); SemanticsAudit.expectClean(rule, "Machines, locked, light") }
    @Test fun auditListDisplacedLockedLight() { list(locked = true, watched = "beta", chosen = "alpha", dark = false); SemanticsAudit.expectClean(rule, "Machines, displaced, locked, light") }

    // ---- a machine's page ---------------------------------------------------------------------------------------------------------------

    private val now = 100_000L

    private fun row(id: String, watched: String = "alpha", chosen: String = watched): MachineRow = MachineRoster.rows(machines, watched, chosen).single { it.id == id }

    /** The page as the route builds it, with the real wake words; [facts] is what the last Wake tap came to. */
    private fun page(
        id: String, locked: Boolean = true, watched: String = "alpha", chosen: String = watched, facts: WakeFacts? = null, wakeReady: Boolean = true,
        busy: Boolean = false, fontScale: Float? = null, dark: Boolean = true, alerts: String = "No UnifiedPush registration for this machine on this phone.",
        guardedLocked: Boolean = false,
    ): Taps {
        val taps = Taps()
        val r = row(id, watched, chosen)
        val profile = machines.single { it.id == id }
        val words = WakeWordsMapper.words(profile, facts, now, wakeReady = wakeReady && profile.wake != null, phoneSuggestion = null, clockLabel = { "18:04" })
        val state = if (r.watched) MachineCopy.watchingLine(MachineCopy.LIVE) else MachineCopy.otherLine(MachineRoster.wakeWord(profile, wakeReady), alerts = false)
        rule.setContent {
            Themed(fontScale, dark) {
                MachinePage(
                    MachinePageState(r, state, switchLocked = locked, wake = words, alerts = alerts, next = MachineRoster.after(machines, id, chosen)?.name, busy = busy, guardedLocked = guardedLocked),
                    onBack = { taps.back++ }, onWatch = { taps.watched += id }, onRemove = { taps.removed++ }, onAlertRelay = { taps.relay++ }, onGuardedAnswers = { taps.guarded++ },
                    onCopyWakeCommand = { taps.copied += it }, onSaveWakeRelay = { taps.saved += it }, onWake = { taps.woken++ },
                )
            }
        }
        return taps
    }

    private val sentNotWatched = WakeFacts(now - 1_000, WakeSendResult.Sent(listOf(Ipv4Subnet.literal("10.0.0.255")!!)), notWatched = true)

    @Test fun thePageOfAMachineThatIsNotWatchedSaysWhatItIsAndOffersALockedWatch() {
        val taps = page("beta", locked = true)
        // The header; the name field below it holds the same words, so it is told apart by its text action.
        rule.onNode(hasText("Beta server") and !hasSetTextAction()).assertIsDisplayed()
        rule.onNode(hasContentDescription("Address: ops@10.0.0.7:2222")).assertIsDisplayed()
        rule.onNode(hasContentDescription("Session: work")).assertIsDisplayed()
        rule.onNodeWithText("Not watched · wake ready").assertIsDisplayed()
        rule.onNodeWithContentDescription("Watch Beta server · Pro").performScrollTo().assertHasClickAction().performClick()
        rule.onAllNodesWithText("Pro", useUnmergedTree = true).assertCountEquals(1)
        rule.onNode(hasTestTag(PRO_LOCK_TAG) and hasAnyAncestor(hasContentDescription("Watch Beta server · Pro")), useUnmergedTree = true).assertExists()
        assertEquals(listOf("beta"), taps.watched)
    }

    @Test fun theFreeReturnsWatchIsPlainOnItsPage() {
        page("alpha", locked = true, watched = "beta", chosen = "alpha")
        rule.onNodeWithContentDescription("Watch Alpha laptop").performScrollTo().assertHasClickAction()
        rule.onAllNodesWithText("Pro", useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodesWithTag(PRO_LOCK_TAG, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun theWatchedMachinesPageIsMarkedAndOpensTheAlertRelay() {
        val taps = page("alpha", watched = "alpha")
        rule.onNodeWithText("Watching · live").assertIsDisplayed()
        rule.onNodeWithText("Watch").assertDoesNotExist()
        rule.onNodeWithText(MachineCopy.ALERTS_WATCH_FIRST).assertDoesNotExist()
        rule.onNodeWithText("Set up or check…").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, taps.relay)
    }

    @Test fun anUnwatchedMachinesAlertsSayToWatchItFirstAndOfferNoRelayScreen() {
        page("beta", alerts = "The address is on Beta server.")
        rule.onNodeWithText("The address is on Beta server.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(MachineCopy.ALERTS_WATCH_FIRST).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Set up or check…").assertDoesNotExist()
    }

    // ---- guarded answers are per machine (2026-10-07): the setup moved here from Settings --------------------------------------------

    @Test fun theWatchedMachinesPageOffersGuardedAnswersAsOneRowThatOnlyReportsTheTap() {
        val taps = page("alpha", watched = "alpha")
        rule.onNodeWithText("GUARDED ANSWERS").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(MachineCopy.GUARDED_WATCH_FIRST).assertDoesNotExist()
        rule.onNodeWithText("Set up guarded answers…").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, taps.guarded)
        assertEquals("the alert relay row is another row", 0, taps.relay)
        rule.onAllNodesWithText("Pro", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun guardedAnswersAreMarkedProInWordsWhenLockedAndTheTapStillOnlyReportsIt() {
        val taps = page("alpha", watched = "alpha", guardedLocked = true)
        rule.onNodeWithText("Set up guarded answers… · Pro").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, taps.guarded)
    }

    @Test fun anUnwatchedMachineSaysToWatchItBeforeGuardedAnswersAndOffersNoSetup() {
        page("beta")
        rule.onNodeWithText("GUARDED ANSWERS").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(MachineCopy.GUARDED_WATCH_FIRST).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Set up guarded answers…").assertDoesNotExist()
    }

    @Test fun wakeOnTheUnwatchedMachinesPageSendsAndSaysItIsNotWatched() {
        val taps = page("beta")
        rule.onNodeWithText("WAKE-ON-LAN").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Wake the machine").performScrollTo().assertHasClickAction().performClick()
        assertEquals(1, taps.woken)
    }

    @Test fun afterAnUnwatchedWakeThePacketLineAndTheNotWatchedLineAreShownAndTheGuardHidesWake() {
        page("beta", facts = sentNotWatched)
        rule.onNodeWithText("Wake packet sent to 10.0.0.255.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(WakeFacts.NOT_WATCHED).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("The machine answered", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Wake the machine").assertDoesNotExist()
    }

    @Test fun aMachineNeverReadSaysSoAndOffersNoWake() {
        page("gamma", wakeReady = false)
        rule.onNodeWithText(WakeWordsMapper.NOT_READ).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Wake the machine").assertDoesNotExist()
    }

    @Test fun theRelayAndTheCommandsAreThisMachines() {
        val taps = page("beta")
        rule.onNode(hasSetTextAction() and hasText("Relay for waking from away")).performScrollTo().performTextInput("10.0.0.1")
        rule.onNodeWithText("Save relay").performScrollTo().performClick()
        assertEquals(listOf<WakeRelay?>(WakeRelay("10.0.0.1")), taps.saved)
        rule.onAllNodesWithText("Copy")[0].performScrollTo().performClick()
        assertEquals(1, taps.copied.size)
    }

    @Test fun removeAsksFirstAndCancelChangesNothing() {
        val taps = page("gamma")
        rule.onNodeWithContentDescription("Remove Gamma…").performScrollTo().performClick()
        rule.onNodeWithText(MachineCopy.removeTitle("Gamma")).assertIsDisplayed()
        rule.onNodeWithText(MachineCopy.removeBody("Gamma", watched = false, next = null)).assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(0, taps.removed)
        rule.onNodeWithContentDescription("Remove Gamma…").performScrollTo().performClick()
        rule.onNodeWithText(MachineCopy.REMOVE).performClick()
        assertEquals(1, taps.removed)
    }

    @Test fun removingTheWatchedMachineNamesTheChosenOneAsNext() {
        // An alert moved the phone to Gamma; the user chose Beta. Removing Gamma goes back to Beta, and the dialog says so.
        page("gamma", watched = "gamma", chosen = "beta")
        rule.onNodeWithContentDescription("Remove Gamma…").performScrollTo().performClick()
        rule.onNodeWithText("Paddock then watches Beta server.", substring = true).assertIsDisplayed()
    }

    @Test fun whileARemovalRunsWatchAndRemoveWait() {
        page("gamma", busy = true)
        rule.onNodeWithContentDescription("Watch Gamma · Pro").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Remove Gamma…").assertIsNotEnabled()
    }

    @Test fun thePageIsReachableAtTwiceTheFontSize() {
        val taps = page("beta", fontScale = 2f)
        rule.onNodeWithContentDescription("Watch Beta server · Pro").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Wake the machine").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Remove Beta server…").performScrollTo().assertIsDisplayed()
        assertEquals(1, taps.woken)
    }

    @Test fun auditPageUnwatchedDark() { page("beta", facts = sentNotWatched); SemanticsAudit.expectClean(rule, "Machine page, not watched, dark") }
    @Test fun auditPageUnwatchedLight() { page("beta", dark = false); SemanticsAudit.expectClean(rule, "Machine page, not watched, light") }
    @Test fun auditPageWatchedLight() { page("alpha", dark = false); SemanticsAudit.expectClean(rule, "Machine page, watched, light") }
    @Test fun auditPageWatchedDark() { page("alpha"); SemanticsAudit.expectClean(rule, "Machine page, watched, dark") }

    // ---- Home's chips (D4) --------------------------------------------------------------------------------------------------------------

    private val chips = listOf(
        MachineChip("beta", "Beta server", watched = false, locked = true),
        MachineChip("alpha", "Alpha laptop", watched = true),
        MachineChip("gamma", "Gamma", watched = false, locked = false),
    )

    private fun chipRow(chips: List<MachineChip> = this.chips, fontScale: Float? = null): Taps {
        val taps = Taps()
        rule.setContent {
            Themed(fontScale, dark = true) {
                MachineChipRow(chips, "live · 3 s", HostHealth.Live, onMachines = { taps.opened += "list" }, onWatch = { taps.watched += it.id })
            }
        }
        return taps
    }

    @Test fun theWatchedChipComesFirstAndOpensTheList() {
        val taps = chipRow()
        val watched = rule.onNode(hasContentDescription("Alpha laptop, watching, live · 3 s"))
        val left = { d: String -> rule.onNode(hasContentDescription(d)).getUnclippedBoundsInRoot().left }
        assertTrue("watched first, then the store's order", left("Alpha laptop, watching, live · 3 s") < left("Beta server, not watched, Pro") && left("Beta server, not watched, Pro") < left("Gamma, not watched"))
        watched.assertHasClickAction().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf("list"), taps.opened)
        assertEquals(emptyList<String>(), taps.watched)
    }

    @Test fun anotherChipAsksToWatchItAndALockedOneCarriesTheLock() {
        val taps = chipRow()
        rule.onNode(hasContentDescription("Gamma, not watched")).assertHeightIsAtLeast(48.dp).performClick()
        rule.onNode(hasContentDescription("Beta server, not watched, Pro")).performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf("gamma", "beta"), taps.watched)
        rule.onAllNodesWithTag(LOCK_TAG, useUnmergedTree = true).assertCountEquals(1)
    }

    @Test fun theStatusIsSaidOnceWithTheWatchedChip() {
        chipRow()
        // The dashed chip's words are in the watched chip's name, so TalkBack does not read them a second time on their own: drawn (the unmerged
        // tree, which the audit's contrast check reads), but not a node of the tree TalkBack walks.
        rule.onNodeWithText("live · 3 s", useUnmergedTree = true).assertIsDisplayed()
        rule.onAllNodesWithText("live · 3 s").assertCountEquals(0)
        rule.onAllNodesWithContentDescription("live · 3 s", substring = true).assertCountEquals(1)
    }

    @Test fun theStatusChipStaysInViewWhileTheMachineChipsScrollAndIsStillSaidOnce() {
        // Three machines on a narrow phone: the chips are wider than the row, so they scroll; the status chip sits outside that scroll.
        rule.setContent {
            Themed(null, dark = true) {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.width(300.dp)) {
                    MachineChipRow(chips, "live · 3 s", HostHealth.Live, onMachines = {}, onWatch = {})
                }
            }
        }
        val status = { rule.onNodeWithText("live · 3 s", useUnmergedTree = true).getUnclippedBoundsInRoot() }
        rule.onNodeWithText("live · 3 s", useUnmergedTree = true).assertIsDisplayed()
        val before = status()
        assertTrue("the status chip ends inside the 300 dp row: $before", before.right <= 300.dp)
        // The last machine chip has to be scrolled to; the status chip does not move with it.
        rule.onNode(hasContentDescription("Gamma, not watched")).performScrollTo().assertIsDisplayed()
        assertEquals(before, status())
        rule.onNodeWithText("live · 3 s", useUnmergedTree = true).assertIsDisplayed()
        // Still read once, inside the watched chip's name, and never as a node of its own.
        rule.onAllNodesWithText("live · 3 s").assertCountEquals(0)
        rule.onAllNodesWithContentDescription("live · 3 s", substring = true).assertCountEquals(1)
    }

    @Test fun theChipRowIsReachableAtTwiceTheFontSize() {
        val taps = chipRow(fontScale = 2f)
        rule.onNode(hasContentDescription("Gamma, not watched")).performScrollTo().assertIsDisplayed().performClick()
        assertEquals(listOf("gamma"), taps.watched)
    }

    private fun home(chips: List<MachineChip>, dark: Boolean = true, fontScale: Float? = null): Taps {
        val taps = Taps()
        val model = AttentionModel.home(Snapshot("0.9.1", 22, agents = emptyList()), now - 3_000, HostProfileId("alpha"), "main", 1, ObservedAt { null })
        rule.setContent {
            Themed(fontScale, dark) {
                HerdHome(
                    HomeUiState.Live("Alpha laptop", model, 40_000), now, onMachines = { taps.opened += "list" },
                    machineChips = chips, onWatchMachine = { taps.watched += it.id },
                )
            }
        }
        return taps
    }

    @Test fun homeDrawsTheRowWithTwoMachinesAndTheSingleChipWithOne() {
        home(chips)
        rule.onNode(hasContentDescription("Alpha laptop, watching, live · 40 s")).assertIsDisplayed()
        rule.onNode(hasContentDescription("Gamma, not watched")).assertIsDisplayed()
    }

    @Test fun homeWithOneMachineKeepsTheSingleChip() {
        val taps = home(listOf(MachineChip("alpha", "Alpha laptop", watched = true)))
        // Read as the chip row reads its watched chip (homeDrawsTheRowWithTwoMachinesAndTheSingleChipWithOne): a second machine does not change how it sounds.
        rule.onNode(hasContentDescription("Alpha laptop, watching, live · 40 s")).assertHasClickAction().performClick()
        rule.onNode(hasContentDescription("not watched", substring = true)).assertDoesNotExist()
        assertEquals(listOf("list"), taps.opened)
    }

    // ---- what the probe found on a machine that is not watched (Pro, hosts.merged) -----------------------------------------------------

    private val seen = listOf(
        MachineChip("beta", "Beta server", watched = false, glance = ProbeCopy.glance(ProbeState(ProbeReading(2, 100_000L - 20_000L)), 100_000L)),
        MachineChip("alpha", "Alpha laptop", watched = true),
        MachineChip("gamma", "Gamma", watched = false, glance = ProbeCopy.glance(ProbeState(ProbeReading(0, 100_000L - 8_000L)), 100_000L)),
        MachineChip("delta", "Delta", watched = false, glance = ProbeCopy.glance(ProbeState(ProbeReading(3, 100_000L - 200_000L), ProbeProblem.Unreachable, 1), 100_000L)),
    )

    @Test fun aChipSaysHowManyAgentsNeedYouAndHowOldTheLookIs() {
        chipRow(seen)
        rule.onNodeWithText("2 need you · 20 s ago", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("all clear · 8 s ago", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        // TalkBack reads one name per chip: watched state, then what was found, and Pro last when it applies.
        rule.onNode(hasContentDescription("Beta server, not watched, 2 agents need you, read 20 s ago")).assertHasClickAction().assertHeightIsAtLeast(48.dp)
        rule.onNode(hasContentDescription("Gamma, not watched, no agent needs you, read 8 s ago")).performScrollTo().assertHasClickAction()
    }

    @Test fun aMachineThatCouldNotBeReadSaysSoNotItsOldCount() {
        chipRow(seen)
        rule.onNodeWithText("not reachable", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        rule.onAllNodesWithText("3 need you", substring = true, useUnmergedTree = true).assertCountEquals(0)
        // The old count is still spoken, with its age, so nobody is told nothing is wrong when it may be.
        rule.onNode(hasContentDescription("Delta, not watched, not reachable; last read 3 min ago, 3 agents need you")).performScrollTo().assertHasClickAction()
    }

    @Test fun theWatchedChipNeverCarriesACountAndAChipWithoutOneIsJustItsName() {
        chipRow(seen + MachineChip("eps", "Eps", watched = false))
        rule.onNode(hasContentDescription("Eps, not watched")).performScrollTo().assertHasClickAction()
        rule.onAllNodesWithTag(GLANCE_TAG, useUnmergedTree = true).assertCountEquals(3)
        rule.onNode(hasContentDescription("Alpha laptop, watching, live · 3 s")).assertHasClickAction()
    }

    @Test fun aChipWithACountStillAsksToWatchItWhenTapped() {
        val taps = chipRow(seen)
        rule.onNode(hasContentDescription("Beta server, not watched, 2 agents need you, read 20 s ago")).performClick()
        assertEquals(listOf("beta"), taps.watched)
    }

    @Test fun auditHomeWithCountsOnTheChipsDark() { home(seen); SemanticsAudit.expectClean(rule, "Home, chip counts, dark") }
    @Test fun auditHomeWithCountsOnTheChipsLight() { home(seen, dark = false); SemanticsAudit.expectClean(rule, "Home, chip counts, light") }
    @Test fun auditHomeWithCountsOnTheChipsAtTwiceTheFontSize() { home(seen, fontScale = 2f); SemanticsAudit.expectClean(rule, "Home, chip counts, 200%") }

    @Test fun auditHomeWithTheChipRowDark() { home(chips); SemanticsAudit.expectClean(rule, "Home, chip row, dark") }
    @Test fun auditHomeWithTheChipRowLight() { home(chips, dark = false); SemanticsAudit.expectClean(rule, "Home, chip row, light") }
    @Test fun auditHomeWithTheChipRowAtTwiceTheFontSize() { home(chips, fontScale = 2f); SemanticsAudit.expectClean(rule, "Home, chip row, 200%") }

    // ---- what a tap came to is read out (review 2026-10-06) ------------------------------------------------------------------------------

    private val live = SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)

    @Test fun aSwitchOrARemovalSaysSoInALiveRegion() {
        var text by mutableStateOf(MachineCopy.nowWatching("Beta server"))
        rule.setContent { PaddockTheme(darkTheme = true) { NoticeBar(text, onDismiss = {}) } }
        rule.onNode(hasText(MachineCopy.nowWatching("Beta server")) and live, useUnmergedTree = true).assertExists()
        for (next in listOf(MachineCopy.removed("Beta server"), MachineCopy.SWITCH_BUSY)) {
            rule.runOnIdle { text = next }
            rule.onNode(hasText(next) and live, useUnmergedTree = true).assertExists()
        }
        // The Dismiss button is not part of what is announced.
        rule.onNode(hasText("Dismiss") and live, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun theGatesNoticeIsOneLiveRegionNotTwo() {
        rule.setContent { PaddockTheme(darkTheme = true) { GateNoticeBar(io.github.tuthan.paddock.billing.ProGate.deferNotice(io.github.tuthan.paddock.billing.GateContext.MANUAL_INPUT)!!, onDismiss = {}) } }
        rule.onAllNodes(live, useUnmergedTree = true).assertCountEquals(1)
    }

    @Test fun theWakeLinesOnAMachinesPageAreALiveRegion() {
        page("beta", facts = sentNotWatched)
        rule.onNode(live and hasAnyDescendant(hasText(WakeFacts.NOT_WATCHED)) and hasAnyDescendant(hasText("Wake packet sent to 10.0.0.255.")), useUnmergedTree = true).assertExists()
    }

    @Test fun theWakeLinesOnHomeAreALiveRegion() {
        rule.setContent {
            PaddockTheme(darkTheme = true) {
                HerdHome(
                    HomeUiState.Degraded("Alpha laptop", null, "Paddock cannot reach the machine.", null), now,
                    wakeLines = listOf("Wake packet sent to 10.0.0.255.", "The machine answered: not yet."),
                )
            }
        }
        rule.onNode(hasContentDescription("Wake packet sent to 10.0.0.255.", substring = true) and live).assertExists()
    }

    @Test fun theSingleChipOpensTheListOnlyWhenItIsGivenSomewhereToGo() {
        var opened = 0
        rule.setContent {
            PaddockTheme(darkTheme = true) {
                androidx.compose.foundation.layout.Column {
                    HostChip("Plain", "live · 3 s", health = HostHealth.Live)
                    HostChip("Openable", "live · 3 s", health = HostHealth.Live, onClick = { opened++ })
                }
            }
        }
        rule.onNode(hasContentDescription("Plain, watching, live · 3 s")).assertIsDisplayed().assertHasNoClickAction()
        rule.onNode(hasContentDescription("Openable, watching, live · 3 s")).assertHasClickAction().performClick()
        assertEquals(1, opened)
    }
}
