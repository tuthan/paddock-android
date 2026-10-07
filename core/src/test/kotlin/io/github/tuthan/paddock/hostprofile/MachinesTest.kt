package io.github.tuthan.paddock.hostprofile

import io.github.tuthan.paddock.billing.GateContext
import io.github.tuthan.paddock.settings.AppSettings
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MachinesTest {
    private fun profile(id: String, name: String, host: String = "10.0.0.2", session: String? = null) = HostProfile(id, name, host, 22, "jdoe", session = session)

    // ---- the list ----------------------------------------------------------------------------------------------------------------

    @Test fun theWatchedMachineComesFirstAndTheRestKeepTheStoresOrder() {
        val all = listOf(profile("a", "Alpha"), profile("b", "Beta"), profile("c", "Gamma"))
        assertEquals(listOf("b", "a", "c"), MachineRoster.rows(all, "b", "b").map { it.id })
        assertEquals(listOf(true, false, false), MachineRoster.rows(all, "b", "b").map { it.watched })
        assertEquals(listOf("b", "a", "c"), MachineRoster.rows(all, "b", "c").map { it.id }, "the chosen machine does not move up")
    }

    @Test fun noWatchedMachineMarksNone() {
        assertTrue(MachineRoster.rows(listOf(profile("a", "Alpha")), null, null).none { it.watched })
        assertTrue(MachineRoster.rows(listOf(profile("a", "Alpha")), "gone", null).none { it.watched })
    }

    @Test fun onlyTheChosenMachineAnAlertMovedThePhoneOffIsAFreeReturn() {
        val all = listOf(profile("a", "Alpha"), profile("b", "Beta"), profile("c", "Gamma"))
        val moved = MachineRoster.rows(all, watchedId = "b", chosenId = "a")
        assertEquals(listOf("a"), moved.filter { it.returnFree }.map { it.id })
        assertFalse(moved.single { it.watched }.returnFree, "the watched machine is never a return")
        assertTrue(MachineRoster.rows(all, "a", "a").none { it.returnFree }, "watching the chosen machine leaves nothing to return to")
        assertTrue(MachineRoster.rows(all, "a", null).none { it.returnFree }, "no choice yet, no free return")
        assertTrue(MachineRoster.rows(all, "a", "gone").none { it.returnFree }, "a removed choice marks nothing")
    }

    @Test fun theChosenMachineIsTheSavedChoiceWhileItExistsAndOtherwiseTheWatchedOne() {
        val all = listOf(profile("a", "Alpha"), profile("b", "Beta"))
        assertEquals("a", MachineRoster.chosen(all, chosenId = "a", watchedId = "b"), "an alert's machine does not replace a choice that still exists")
        assertEquals("b", MachineRoster.chosen(all, chosenId = null, watchedId = "b"), "settings from before the choice: the watched machine")
        assertEquals("b", MachineRoster.chosen(all, chosenId = "gone", watchedId = "b"), "a removed choice: the watched machine")
        assertNull(MachineRoster.chosen(emptyList(), chosenId = "gone", watchedId = null), "no machine, no choice")
    }

    @Test fun startUpSavesTheChoiceOnlyWhenTheListWasReadAndTheChoiceIsMissingOrGone() {
        val all = listOf(profile("a", "Alpha"), profile("b", "Beta"))
        val old = AppSettings(watchedProfileId = "b", chosenProfileId = "a", keepPromptText = true)
        assertNull(MachineRoster.startupChoice(listRead = false, all = emptyList(), settings = old, watchedId = null), "a failed read never saves a choice of null")
        assertNull(MachineRoster.startupChoice(listRead = false, all = all, settings = old.copy(chosenProfileId = null), watchedId = "b"), "nor anything else from a read that failed")
        assertNull(MachineRoster.startupChoice(listRead = true, all = emptyList(), settings = old, watchedId = null), "a read that found no machine says nothing about the choice")
        assertNull(MachineRoster.startupChoice(listRead = true, all = all, settings = old, watchedId = "b"), "a choice that is still saved needs no write")
        // Settings written before there was a choice (AC-2), and a choice whose machine was removed: the watched machine, everything else kept.
        assertEquals(old.copy(chosenProfileId = "b"), MachineRoster.startupChoice(listRead = true, all = all, settings = old.copy(chosenProfileId = null), watchedId = "b"))
        assertEquals(old.copy(chosenProfileId = "b"), MachineRoster.startupChoice(listRead = true, all = all, settings = old.copy(chosenProfileId = "gone"), watchedId = "b"))
    }

    @Test fun addingAMachineChoosesItButSettingUpTheWatchedMachinesKeyDoesNot() {
        val b = profile("b", "Beta")
        assertTrue(MachineRoster.choosesOnAdd(b, fixing = null), "a plain add is the user's choice")
        assertFalse(MachineRoster.choosesOnAdd(b.copy(user = "ops"), fixing = b), "fixing the key of the machine an alert moved the phone to keeps the free return")
        assertTrue(MachineRoster.choosesOnAdd(profile("c", "Gamma", host = "10.0.0.9"), fixing = b), "a fix that became another machine is an add")
    }

    @Test fun aChipIsLockedOnlyWhenTheSwitchIsProAndItIsNeitherWatchedNorTheFreeReturn() {
        val all = listOf(profile("a", "Alpha"), profile("b", "Beta"), profile("c", "Gamma"))
        val rows = MachineRoster.rows(all, watchedId = "b", chosenId = "a")
        assertEquals(
            listOf(MachineChip("b", "Beta", watched = true, locked = false), MachineChip("a", "Alpha", watched = false, locked = false), MachineChip("c", "Gamma", watched = false, locked = true)),
            MachineRoster.chips(rows, switchLocked = true),
            "the watched chip first and never locked, the free return open, every other machine locked",
        )
        assertTrue(MachineRoster.chips(rows, switchLocked = false).none { it.locked }, "with Pro nothing is locked")
        assertFalse(MachineRoster.watchLocked(rows.single { it.id == "a" }, switchLocked = true))
        assertTrue(MachineRoster.watchLocked(rows.single { it.id == "c" }, switchLocked = true))
    }

    @Test fun aFreeReturnNeverAsksTheGateAndAnythingAllowedWaitsForAnOperationInFlight() {
        // Without Pro: a row that is not the free return asks the gate, idle or busy (the gate's own sentence says when Pro is offered).
        for (context in GateContext.entries) assertEquals(MachineSwitch.Decision.AskGate, MachineSwitch.decide(returnFree = false, switchLocked = true, context = context), "$context")
        // The free return to the chosen machine: never the gate, and still not while an operation runs on the machine being left.
        assertEquals(MachineSwitch.Decision.Switch, MachineSwitch.decide(returnFree = true, switchLocked = true, context = GateContext.IDLE))
        assertEquals(MachineSwitch.Decision.Switch, MachineSwitch.decide(returnFree = true, switchLocked = true, context = GateContext.MANUAL_INPUT))
        assertEquals(MachineSwitch.Decision.Busy, MachineSwitch.decide(returnFree = true, switchLocked = true, context = GateContext.OPERATION_IN_FLIGHT))
        // With Pro: no gate for any row, and the same wait.
        assertEquals(MachineSwitch.Decision.Switch, MachineSwitch.decide(returnFree = false, switchLocked = false, context = GateContext.IDLE))
        assertEquals(MachineSwitch.Decision.Busy, MachineSwitch.decide(returnFree = false, switchLocked = false, context = GateContext.OPERATION_IN_FLIGHT))
        assertEquals(MachineSwitch.Decision.Busy, MachineSwitch.decide(returnFree = true, switchLocked = false, context = GateContext.OPERATION_IN_FLIGHT))
    }

    @Test fun aRowCarriesTheEndpointAndTheSession() {
        val row = MachineRoster.rows(listOf(profile("a", "Alpha", "box.lan", session = "work")), "a", "a").single()
        assertEquals("jdoe@box.lan:22", row.endpoint)
        assertEquals("work", row.session)
        assertEquals("Alpha", row.name)
    }

    @Test fun afterARemovalTheFirstOtherMachineIsWatchedAsAtStartUp() {
        val all = listOf(profile("a", "Alpha"), profile("b", "Beta"), profile("c", "Gamma"))
        assertEquals("b", MachineRoster.after(all, "a", chosenId = null)?.id)
        assertEquals("a", MachineRoster.after(all, "c", chosenId = null)?.id)
        assertNull(MachineRoster.after(listOf(profile("a", "Alpha")), "a", chosenId = "a"))
        assertNull(MachineRoster.after(emptyList(), "a", chosenId = null))
    }

    @Test fun afterARemovalTheChosenMachineIsPreferredWhenItIsAnotherSavedOne() {
        val all = listOf(profile("a", "Alpha"), profile("b", "Beta"), profile("c", "Gamma"))
        // An alert moved the phone to b; the user chose c. Removing b goes back to c, not to the first by name.
        assertEquals("c", MachineRoster.after(all, "b", chosenId = "c")?.id)
        assertEquals("b", MachineRoster.after(all, "a", chosenId = "a")?.id, "removing the chosen machine itself: the first other by name")
        assertEquals("a", MachineRoster.after(all, "b", chosenId = "gone")?.id, "a choice that no longer exists: the first other by name")
    }

    @Test fun theWakeWordFollowsTheReadingAndWhetherThePhoneCanSendFromHere() {
        val read = io.github.tuthan.paddock.wake.WakeTarget(available = true, mac = "02:00:5e:10:00:01", iface = "eth0")
        assertEquals(WakeWord.NotRead, MachineRoster.wakeWord(profile("a", "Alpha"), readyHere = false))
        assertEquals(WakeWord.NotRead, MachineRoster.wakeWord(profile("a", "Alpha"), readyHere = true), "no reading is never ready")
        assertEquals(WakeWord.Ready, MachineRoster.wakeWord(profile("a", "Alpha").copy(wake = read), readyHere = true))
        assertEquals(WakeWord.NotAvailable, MachineRoster.wakeWord(profile("a", "Alpha").copy(wake = read), readyHere = false))
        val relayOnly = io.github.tuthan.paddock.wake.WakeTarget.unavailable("Not read yet", 1L, relay = io.github.tuthan.paddock.wake.WakeRelay("192.168.1.1"))
        assertEquals(WakeWord.NotAvailable, MachineRoster.wakeWord(profile("a", "Alpha").copy(wake = relayOnly), readyHere = false))
    }

    // ---- what the screens say ----------------------------------------------------------------------------------------------------

    @Test fun theIntroSaysACardOpensItsMachine() {
        assertEquals("Paddock watches one machine at a time. Open a machine to watch it, wake it or remove it.", MachineCopy.INTRO)
        assertFalse("Tap" in MachineCopy.INTRO)
    }

    @Test fun theStateLineSaysWatchingWithTheHealthOrNotWatchedWithWhatIsKnown() {
        assertEquals("Watching · live", MachineCopy.watchingLine(MachineCopy.LIVE))
        assertEquals("Watching · not live", MachineCopy.watchingLine(MachineCopy.NOT_LIVE))
        assertEquals("Watching · connecting", MachineCopy.watchingLine(MachineCopy.CONNECTING))
        assertEquals("Not watched", MachineCopy.otherLine(null, alerts = false))
        assertEquals("Not watched · wake ready · alerts set up", MachineCopy.otherLine(WakeWord.Ready, alerts = true))
        assertEquals("Not watched · wake not read", MachineCopy.otherLine(WakeWord.NotRead, alerts = false))
        assertEquals("Not watched · wake not available", MachineCopy.otherLine(WakeWord.NotAvailable, alerts = false))
    }

    @Test fun theChipsSayWhichIsWatchedAndWhichArePro() {
        assertEquals("Alpha, watching, live · 3 s", MachineCopy.chipWatched("Alpha", MachineCopy.LIVE, "live · 3 s"))
        assertEquals("Alpha, watching, not live, as of 14:02", MachineCopy.chipWatched("Alpha", MachineCopy.NOT_LIVE, "as of 14:02"), "the health is said when the status does not")
        assertEquals("Alpha, watching, connecting", MachineCopy.chipWatched("Alpha", MachineCopy.CONNECTING, "connecting"))
        assertEquals("Beta, not watched", MachineCopy.chipOther("Beta", locked = false))
        assertEquals("Beta, not watched, Pro", MachineCopy.chipOther("Beta", locked = true))
    }

    @Test fun settingsRowCountsTheMachinesAndNamesTheWatchedOne() {
        assertEquals("1 saved · watching Laptop", MachineCopy.settingsRow(1, "Laptop"))
        assertEquals("3 saved · watching Laptop", MachineCopy.settingsRow(3, "Laptop"))
        assertEquals("None saved", MachineCopy.settingsRow(0, null))
    }

    @Test fun theSmallerSentences() {
        assertEquals("Watching Beta.", MachineCopy.nowWatching("Beta"))
        assertEquals("Removed Beta.", MachineCopy.removed("Beta"))
        assertEquals("Watch Beta", MachineCopy.watchLabel("Beta"))
        assertEquals("Open Beta", MachineCopy.openLabel("Beta"))
        assertEquals("Remove Beta…", MachineCopy.removeLabel("Beta"))
        assertEquals("session work", MachineCopy.session("work"))
        assertEquals("default session", MachineCopy.session(null))
        assertEquals("Watch this machine to set up or check its alerts.", MachineCopy.ALERTS_WATCH_FIRST)
        assertEquals("Watch this machine to set up or check its guarded answers.", MachineCopy.GUARDED_WATCH_FIRST)
    }

    @Test fun theDisplacedLineNamesTheAlertsMachineAndTheFreeReturn() {
        assertEquals("An alert moved this phone to Beta. Returning to Alpha is free.", MachineCopy.displaced(chosen = "Alpha", watched = "Beta"))
        assertFalse("Pro" in MachineCopy.displaced("Alpha", "Beta"), "the free return is not a sales line")
    }

    @Test fun theDialogNamesTheMachineAndWhatHappensToTheWatchedOne() {
        assertEquals("Remove Alpha?", MachineCopy.removeTitle("Alpha"))
        assertFalse("then" in MachineCopy.removeBody("Alpha", watched = false, next = "Beta"), "a machine that is not watched changes nothing else")
        assertTrue("Paddock then watches Beta." in MachineCopy.removeBody("Alpha", watched = true, next = "Beta"))
        assertTrue("asks you to add one" in MachineCopy.removeBody("Alpha", watched = true, next = null))
    }

    @Test fun theFactsSayWhatIsForgottenKeptAndLeftAloneOnTheMachine() {
        val facts = MachineCopy.REMOVE_FACTS.toMap()
        assertEquals(setOf("Forgotten", "Kept", "On the machine"), facts.keys)
        assertTrue("host key" in facts.getValue("Forgotten") && "alert registration" in facts.getValue("Forgotten"))
        assertTrue("sent to it" in facts.getValue("Kept") && "imported key" in facts.getValue("Kept"))
        assertTrue("paddock@phone" in facts.getValue("On the machine") && "authorized_keys" in facts.getValue("On the machine"))
    }

    @Test fun aFailureNamesWhatDidNotFinishAndSaysTheMachineIsStillListed() {
        val text = MachineCopy.removeFailed(listOf("the host key", "the record"))
        assertTrue("the host key, the record" in text && "stays listed" in text)
        assertTrue("already forgotten" in text && "trust it again" in text && "alerts are off" in text, "a machine that stays listed after a failure is partly gone, and says so")
    }

    // ---- the removal -------------------------------------------------------------------------------------------------------------

    private class Log { val calls = mutableListOf<String>() }

    private fun removal(log: Log, failing: Set<String> = emptySet(), profileFails: Boolean = false, cancelAt: String? = null) = MachineRemoval(
        listOf("the host key", "the record", "alerts").map { what ->
            MachineRemoval.Forget(what) { id ->
                log.calls += "$what:$id"
                if (what == cancelAt) throw CancellationException("cancelled")
                if (what in failing) throw IOException("disk")
            }
        },
    ) { id -> log.calls += "profile:$id"; if (profileFails) throw IOException("disk") }

    @Test fun everythingHangingOffTheProfileGoesFirstAndTheProfileLast() = runBlocking<Unit> {
        val log = Log()
        assertEquals(MachineRemoval.Result.Removed, removal(log).remove("alpha"))
        assertEquals(listOf("the host key:alpha", "the record:alpha", "alerts:alpha", "profile:alpha"), log.calls)
    }

    @Test fun aStepThatFailsKeepsTheProfileButTheOtherStepsStillRun() = runBlocking<Unit> {
        val log = Log()
        val result = removal(log, failing = setOf("the record")).remove("alpha")
        assertEquals(MachineRemoval.Result.Incomplete(listOf("the record")), result)
        assertEquals(listOf("the host key:alpha", "the record:alpha", "alerts:alpha"), log.calls, "nothing left behind that could still be forgotten, and no profile removed")
    }

    @Test fun severalFailuresAreAllNamed() = runBlocking<Unit> {
        val result = removal(Log(), failing = setOf("the host key", "alerts")).remove("alpha")
        assertEquals(MachineRemoval.Result.Incomplete(listOf("the host key", "alerts")), result)
    }

    @Test fun aProfileThatCannotBeRemovedIsReportedAndEverythingElseIsAlreadyGone() = runBlocking<Unit> {
        val log = Log()
        assertEquals(MachineRemoval.Result.Incomplete(listOf("the saved machine")), removal(log, profileFails = true).remove("alpha"))
        assertEquals(4, log.calls.size)
    }

    @Test fun aSecondTryAfterAFailureFinishesTheJob() = runBlocking<Unit> {
        val log = Log()
        removal(log, failing = setOf("alerts")).remove("alpha")
        assertEquals(MachineRemoval.Result.Removed, removal(log).remove("alpha"))
        assertEquals("profile:alpha", log.calls.last())
    }

    @Test fun cancellationIsNeverSwallowedAndRemovesNoProfile() = runBlocking<Unit> {
        val log = Log()
        assertFailsWith<CancellationException> { removal(log, cancelAt = "the record").remove("alpha") }
        assertFalse("profile:alpha" in log.calls)
    }

    @Test fun theRealStoreLosesOnlyTheRemovedProfile() = runBlocking<Unit> {
        val store = InMemoryHostProfileStore()
        store.put(profile("a", "Alpha")); store.put(profile("b", "Beta"))
        MachineRemoval(emptyList()) { store.remove(it) }.remove("a")
        assertEquals(listOf("b"), store.list().map { it.id })
        assertEquals(MachineRemoval.Result.Removed, MachineRemoval(emptyList()) { store.remove(it) }.remove("a"), "removing it again is not an error")
    }
}
