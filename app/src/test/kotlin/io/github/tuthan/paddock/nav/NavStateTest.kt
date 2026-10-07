package io.github.tuthan.paddock.nav

import androidx.compose.runtime.saveable.SaverScope
import io.github.tuthan.paddock.host.ConnectAttempt
import io.github.tuthan.paddock.hostprofile.Boot
import io.github.tuthan.paddock.hostprofile.MachineCopy
import io.github.tuthan.paddock.hostprofile.MachineRemoval
import io.github.tuthan.paddock.hostprofile.PairingLink
import io.github.tuthan.paddock.live.ConnectFix
import io.github.tuthan.paddock.ui.screens.AgentTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where the router is, and every way it moves: the rules the root's route variables used to carry inline. */
class NavStateTest {
    private val link = PairingLink("box.local", 22, "jdoe", listOf("SHA256:" + "A".repeat(43)), session = null)

    private fun state(block: NavState.() -> Unit = {}) = NavState().apply(block)

    // ---- which route is drawn -----------------------------------------------------------------------------------------

    @Test fun withMachinesTheChosenRouteIsDrawn() {
        for (route in Route.entries) assertEquals(route, state { this.route = route }.effective(Boot.Ready))
        assertEquals("while loading too: the root draws its own splash then", Route.Output, state { route = Route.Output }.effective(Boot.Loading))
    }

    @Test fun aPhoneWithNoMachineStartsOnWelcomeAndAnyWayInMovesOnToAddMachine() {
        assertEquals(Route.Welcome, state { route = Route.Settings }.effective(Boot.NoMachines))
        assertEquals(Route.AddMachine, state { add.welcomeChoice = true }.effective(Boot.NoMachines))
        assertEquals("a pairing link is a way in", Route.AddMachine, state { add.pairing = link }.effective(Boot.NoMachines))
    }

    @Test fun aConnectInFlightKeepsTheFormWhateverElseIsTrue() {
        for (boot in Boot.entries) assertEquals(boot.name, Route.AddMachine, state { route = Route.Home; add.attempt = ConnectAttempt("a") }.effective(boot))
    }

    // ---- where Back goes ------------------------------------------------------------------------------------------------

    @Test fun eachScreenGoesBackToWhereItWasOpenedFrom() {
        val s = state { addFrom = Route.Machines; snippetsFrom = Route.Compose; alertRelayFrom = Route.Machine; guardedFrom = Route.Decision; machinesFrom = Route.Settings }
        assertEquals(Route.Machines, s.backTo(Route.AddMachine))
        assertEquals(Route.Compose, s.backTo(Route.Snippets))
        assertEquals(Route.Machine, s.backTo(Route.AlertRelay))
        assertEquals(Route.Decision, s.backTo(Route.GuardedAnswers))
        assertEquals(Route.Settings, s.backTo(Route.Machines))
        assertEquals(Route.Machines, s.backTo(Route.Machine))
        assertEquals(Route.Output, s.backTo(Route.Compose))
        assertEquals(Route.Output, s.backTo(Route.Decision))
        for (r in listOf(Route.Home, Route.Spaces, Route.Activity, Route.Settings, Route.Output, Route.Welcome)) assertEquals(r.name, Route.Home, s.backTo(r))
    }

    // ---- Manual input belongs to the agent screen --------------------------------------------------------------------------

    @Test fun manualInputLivesOnTheAgentsOutputTabTheComposerAndSnippetsOpenedFromIt() {
        assertTrue(state { outputTab = AgentTab.Output }.onAgent(Route.Output))
        assertFalse(state { outputTab = AgentTab.Terminal }.onAgent(Route.Output))
        assertTrue(state().onAgent(Route.Compose))
        assertTrue(state { snippetsFrom = Route.Compose }.onAgent(Route.Snippets))
        assertFalse(state { snippetsFrom = Route.Settings }.onAgent(Route.Snippets))
        for (r in listOf(Route.Home, Route.Settings, Route.Machines, Route.AddMachine, Route.Decision)) assertFalse(r.name, state().onAgent(r))
    }

    // ---- moving ---------------------------------------------------------------------------------------------------------

    @Test fun openingAnAgentSetsItsTabAndLeavesEveryOtherChoiceAlone() {
        val s = state { addFrom = Route.Machines }
        s.openAgent("term_1")
        assertEquals(Route.Output, s.route); assertEquals("term_1", s.terminalId); assertEquals(AgentTab.Output, s.outputTab)
        s.openAgent("term_2", AgentTab.Terminal)
        assertEquals("term_2", s.terminalId); assertEquals(AgentTab.Terminal, s.outputTab)
        assertEquals(Route.Machines, s.addFrom)
    }

    @Test fun eachScreenRemembersWhereItWasOpenedFrom() {
        val s = state()
        s.openMachines(Route.Settings); assertEquals(Route.Machines, s.route); assertEquals(Route.Settings, s.machinesFrom)
        s.openMachine("m1"); assertEquals(Route.Machine, s.route); assertEquals("m1", s.machineId)
        s.openSnippets(Route.Compose); assertEquals(Route.Snippets, s.route); assertEquals(Route.Compose, s.snippetsFrom)
        s.openAlertRelay(Route.Machine); assertEquals(Route.AlertRelay, s.route); assertEquals(Route.Machine, s.alertRelayFrom)
        s.openGuardedAnswers(Route.Decision); assertEquals(Route.GuardedAnswers, s.route); assertEquals(Route.Decision, s.guardedFrom)
    }

    @Test fun addMachineOpenedToFixTheWatchedMachinesKeyIsEditingAndNotAnotherMachine() {
        val s = state { add.pairing = link; route = Route.Settings }
        s.openAddMachineForKey(showCommand = false)
        assertTrue(s.add.editing); assertFalse(s.add.authorizeIntro); assertNull(s.add.pairing)
        assertEquals(Route.AddMachine, s.route); assertEquals(Route.Home, s.addFrom)
        s.openAddMachineForKey(showCommand = true)
        assertTrue(s.add.authorizeIntro)
    }

    @Test fun anotherMachineFromTheListIsNotEditingAndReturnsToTheList() {
        val s = state { add.editing = true; add.pairing = link }
        s.openAddMachineFromMachines()
        assertFalse(s.add.editing); assertNull(s.add.pairing)
        assertEquals(Route.AddMachine, s.route); assertEquals(Route.Machines, s.addFrom)
    }

    // ---- a pairing link -----------------------------------------------------------------------------------------------------

    @Test fun aPairingLinkOpensAddMachineAndReturnsToWhereTheUserWas() {
        val s = state { route = Route.Settings; alertNotice = "old"; add.editing = true }
        s.openPairing(link, Boot.Ready)
        assertEquals(Route.AddMachine, s.route); assertEquals(Route.Settings, s.addFrom)
        assertEquals(link, s.add.pairing); assertFalse(s.add.editing); assertNull(s.alertNotice)
    }

    @Test fun aPairingLinkFromTheAgentScreenOrAPhoneWithNoMachineReturnsHome() {
        assertEquals(Route.Home, state { route = Route.Output }.also { it.openPairing(link, Boot.Ready) }.addFrom)
        assertEquals(Route.Home, state { route = Route.Settings }.also { it.openPairing(link, Boot.NoMachines) }.addFrom)
    }

    @Test fun aPairingLinkWhileAddMachineIsOpenFillsItInPlace() {
        val s = state { route = Route.AddMachine; addFrom = Route.Machines }
        s.openPairing(link, Boot.Ready)
        assertEquals(Route.Machines, s.addFrom)
        assertEquals(link, s.add.pairing)
    }

    // ---- a removal, a connect, back ------------------------------------------------------------------------------------------

    @Test fun aRemovedMachineIsSaidOnceAndTheListReplacesItsPageOnlyWhileTheUserIsStillThere() {
        val s = state { route = Route.Machine; machineId = "m1" }
        s.removalShown("m1", "Beta", MachineRemoval.Result.Removed)
        assertEquals(MachineCopy.removed("Beta"), s.alertNotice); assertEquals(Route.Machines, s.route)
        val elsewhere = state { route = Route.Settings; machineId = "m1" }
        elsewhere.removalShown("m1", "Beta", MachineRemoval.Result.Removed)
        assertEquals(Route.Settings, elsewhere.route)
        val another = state { route = Route.Machine; machineId = "m2" }
        another.removalShown("m1", "Beta", MachineRemoval.Result.Removed)
        assertEquals("the page of another machine stays", Route.Machine, another.route)
    }

    @Test fun aRemovalThatCouldNotForgetEverythingStaysOnThePageAndSaysWhat() {
        val s = state { route = Route.Machine; machineId = "m1" }
        s.removalShown("m1", "Beta", MachineRemoval.Result.Incomplete(listOf("the host key")))
        assertEquals(MachineCopy.removeFailed(listOf("the host key")), s.alertNotice)
        assertEquals(Route.Machine, s.route)
    }

    @Test fun aConnectThatCameUpEndsTheAttemptAndTheFormsLeftoversAndGoesHome() {
        val s = state { route = Route.AddMachine; relayDismissed = true; add.attempt = ConnectAttempt("a"); add.editing = true; add.authorizeIntro = true; add.pairing = link }
        s.connected()
        assertNull(s.add.attempt); assertFalse(s.add.editing); assertFalse(s.add.authorizeIntro); assertNull(s.add.pairing)
        assertEquals(Route.Home, s.route); assertFalse(s.relayDismissed)
    }

    @Test fun backEndsAnAttemptAndGoesWhereItIsToldWhileKeepingWhatTheFormHolds() {
        val s = state { route = Route.AddMachine; add.attempt = ConnectAttempt("a"); add.authorizeIntro = true; add.editing = true; add.pairing = link }
        s.back(Route.Machines)
        assertEquals(Route.Machines, s.route); assertNull(s.add.attempt); assertFalse(s.add.authorizeIntro)
        assertTrue("the system Back does not clear what the form was given", s.add.editing && s.add.pairing == link)
    }

    @Test fun theFormsOwnBackGoesToWhereItWasOpenedFromWithAMachineAndToWelcomeWithout() {
        val ready = state { route = Route.AddMachine; add.welcomeChoice = true; add.editing = true; add.pairing = link; add.attempt = ConnectAttempt("a") }
        ready.leaveAddMachine(Boot.Ready, Route.Machines)
        assertEquals(Route.Machines, ready.route); assertTrue("Welcome's choice is not touched with a machine saved", ready.add.welcomeChoice)
        assertNull(ready.add.attempt); assertFalse(ready.add.editing); assertNull(ready.add.pairing)
        val empty = state { route = Route.AddMachine; add.welcomeChoice = true }
        empty.leaveAddMachine(Boot.NoMachines, Route.Home)
        assertFalse(empty.add.welcomeChoice); assertEquals(Route.AddMachine, empty.route)
        assertEquals(Route.Welcome, empty.effective(Boot.NoMachines))
    }

    @Test fun backFromTheFormWithNoMachineReturnsToWelcomeAndDropsAPairingLink() {
        val s = state { add.welcomeChoice = true; add.pairing = link }
        s.backToWelcome()
        assertFalse(s.add.welcomeChoice); assertNull(s.add.pairing)
        assertEquals(Route.Welcome, s.effective(Boot.NoMachines))
    }

    // ---- "Not now" on the relay prompt ----------------------------------------------------------------------------------------

    @Test fun notNowIsAboutTheMachineItWasShownForAndAnotherMachineGetsItsOwnPrompt() {
        val s = state()
        s.watchedChanged("a")
        s.relayDismissed = true
        s.watchedChanged("a")
        assertTrue(s.relayDismissed)
        s.watchedChanged("b")
        assertFalse(s.relayDismissed); assertEquals("b", s.dismissedFor)
        val fresh = state { relayDismissed = true }
        fresh.watchedChanged("a")
        assertTrue("the first watched machine is not a change", fresh.relayDismissed)
    }

    // ---- saved state ------------------------------------------------------------------------------------------------------------

    private fun roundTrip(s: NavState): NavState? {
        val saved = with(NavState.Saver) { SaverScope { true }.save(s) }
        return saved?.let { NavState.Saver.restore(it) }
    }

    @Test fun everythingTheRouterKeepsSurvivesRotationAndProcessDeath() {
        val s = state {
            route = Route.Machine; addFrom = Route.Machines; terminalId = "term_1"; outputTab = AgentTab.Terminal; snippetsFrom = Route.Compose; guardedFrom = Route.Decision
            machinesFrom = Route.Settings; machineId = "m1"; alertRelayFrom = Route.Machine; relayDismissed = true; dismissedFor = "m1"; reviewKey = true; alertNotice = "Removed Beta."
            add.editing = true; add.authorizeIntro = true; add.welcomeChoice = true; add.welcomeFinder = true; add.welcomeScan = true
            add.pairing = link; add.attempt = ConnectAttempt("a", "No answer.", ConnectFix.Retry)
        }
        val back = roundTrip(s)!!
        assertEquals(Route.Machine, back.route); assertEquals(Route.Machines, back.addFrom); assertEquals("term_1", back.terminalId); assertEquals(AgentTab.Terminal, back.outputTab)
        assertEquals(Route.Compose, back.snippetsFrom); assertEquals(Route.Decision, back.guardedFrom); assertEquals(Route.Settings, back.machinesFrom); assertEquals("m1", back.machineId)
        assertEquals(Route.Machine, back.alertRelayFrom); assertTrue(back.relayDismissed); assertEquals("m1", back.dismissedFor); assertTrue(back.reviewKey); assertEquals("Removed Beta.", back.alertNotice)
        assertTrue(back.add.editing && back.add.authorizeIntro && back.add.welcomeChoice && back.add.welcomeFinder && back.add.welcomeScan)
        assertEquals(link, back.add.pairing); assertEquals(ConnectAttempt("a", "No answer.", ConnectFix.Retry), back.add.attempt)
    }

    @Test fun aFreshStateRoundTripsToTheDefaultsAndTheFindersPickIsNotSaved() {
        val back = roundTrip(state { add.found = io.github.tuthan.paddock.hostprofile.AddMachineInput(host = "h") })!!
        assertEquals(Route.Home, back.route); assertEquals(Route.Settings, back.snippetsFrom); assertEquals(Route.Machine, back.guardedFrom)
        assertNull(back.terminalId); assertNull(back.add.pairing); assertNull(back.add.attempt); assertNull("a hand-off, never restored", back.add.found)
    }

    @Test fun aSavedStateThatIsNotOursOrNamesAScreenThisVersionLacksFallsBackInsteadOfCrashing() {
        assertNull(NavState.Saver.restore(listOf("Home")))
        assertNull(NavState.Saver.restore("nope"))
        val saved = with(NavState.Saver) { SaverScope { true }.save(state()) } as List<*>
        val odd = saved.toMutableList().also { it[0] = "Settings2"; it[3] = "Pager"; it[4] = 7 }
        val back = NavState.Saver.restore(odd)
        assertNotNull(back)
        assertEquals(Route.Home, back!!.route); assertEquals(AgentTab.Output, back.outputTab); assertEquals(Route.Settings, back.snippetsFrom)
    }
}
