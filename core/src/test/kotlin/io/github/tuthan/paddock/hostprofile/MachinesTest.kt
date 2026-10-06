package io.github.tuthan.paddock.hostprofile

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
        assertEquals(listOf("b", "a", "c"), MachineRoster.rows(all, "b").map { it.id })
        assertEquals(listOf(true, false, false), MachineRoster.rows(all, "b").map { it.watched })
    }

    @Test fun noWatchedMachineMarksNone() {
        assertTrue(MachineRoster.rows(listOf(profile("a", "Alpha")), null).none { it.watched })
        assertTrue(MachineRoster.rows(listOf(profile("a", "Alpha")), "gone").none { it.watched })
    }

    @Test fun aRowCarriesTheEndpointAndTheSession() {
        val row = MachineRoster.rows(listOf(profile("a", "Alpha", "box.lan", session = "work")), "a").single()
        assertEquals("jdoe@box.lan:22", row.endpoint)
        assertEquals("work", row.session)
        assertEquals("Alpha", row.name)
    }

    @Test fun afterARemovalTheFirstOtherMachineIsWatchedAsAtStartUp() {
        val all = listOf(profile("a", "Alpha"), profile("b", "Beta"), profile("c", "Gamma"))
        assertEquals("b", MachineRoster.after(all, "a")?.id)
        assertEquals("a", MachineRoster.after(all, "c")?.id)
        assertNull(MachineRoster.after(listOf(profile("a", "Alpha")), "a"))
        assertNull(MachineRoster.after(emptyList(), "a"))
    }

    // ---- what the dialog says ----------------------------------------------------------------------------------------------------

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
