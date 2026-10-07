package io.github.tuthan.paddock

import androidx.test.platform.app.InstrumentationRegistry
import io.github.tuthan.paddock.hostkey.FileHostKeyStore
import io.github.tuthan.paddock.hostkey.PinnedHostKey
import io.github.tuthan.paddock.hostprofile.HostProfile
import io.github.tuthan.paddock.hostprofile.MachineRemoval
import io.github.tuthan.paddock.hostprofile.MachineRoster
import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.ledger.ObservationKind
import io.github.tuthan.paddock.settings.FileAppSettingsStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Remove a machine through the app's real graph and its real files (the process's own, as the other graph tests do): the profile goes last, the
 * pin and the ledger rows go with it, the watched machine moves on, and a second Remove is not an error. Also the chosen machine (D1): what makes a
 * machine the user's choice and what never does. The machines here have ids no real machine has, and the watched and chosen machines are put back
 * afterwards. The start-up migration of the choice (AC-2) is not here: `start()` runs once per process, so it is held at the rule
 * (`MachineRoster.startupChoice`, core `MachinesTest`).
 */
class MachineRemovalGraphTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph get() = (ctx.applicationContext as PaddockApp).graph
    private val pins get() = FileHostKeyStore(File(ctx.filesDir, "host-keys.json"))

    private val a = HostProfile("mtest-a", "A machine under test", "10.255.255.1", 22, "tester")
    private val b = HostProfile("mtest-b", "B machine under test", "10.255.255.2", 22, "tester")
    private val c = HostProfile("mtest-c", "C machine under test", "10.255.255.3", 22, "tester")
    private var before: String? = null
    private var chosenBefore: String? = null

    @Before fun remember() { before = graph.profile.value?.id; chosenBefore = graph.settings.value.chosenProfileId }

    @After fun restore() = runBlocking {
        graph.removeMachine(a.id); graph.removeMachine(b.id); graph.removeMachine(c.id)
        // The choice first (that also watches it), then the watched machine without changing the choice.
        chosenBefore?.let { graph.watchProfile(it, chosen = true) }
        before?.let { graph.watchProfile(it) }
        Unit
    }

    /** What the graph saved, read from its file by a store of its own. */
    private val savedSettings get() = runBlocking { FileAppSettingsStore(File(ctx.filesDir, "settings.json")).load() }

    private suspend fun pin(id: String) = pins.save(PinnedHostKey(id, "10.255.255.1:22", "ssh-ed25519", byteArrayOf(1, 2, 3), "SHA256:test", 1L, 1L))

    @Test fun removingTheWatchedMachineForgetsItAndWatchesAnother() = runBlocking {
        graph.addMachine(a); graph.addMachine(b)
        pin(a.id); pin(b.id)
        val epoch = graph.ledger.allocateEpoch(HostProfileId(b.id), "default")
        graph.ledger.observeHost(ObservationKind.Connected, HostProfileId(b.id), "default", epoch)
        graph.ledger.observeHost(ObservationKind.Connected, HostProfileId(a.id), "default", graph.ledger.allocateEpoch(HostProfileId(a.id), "default"))
        assertEquals(b.id, graph.profile.value?.id)

        assertEquals(MachineRemoval.Result.Removed, graph.removeMachine(b.id))

        assertNull("the profile is gone", graph.profiles.get(b.id))
        assertTrue("and out of the list", graph.machines.value.none { it.id == b.id })
        assertNull("its pin is gone", pins.find(b.id))
        assertNotNull("another machine's pin is not touched", pins.find(a.id))
        assertTrue("its ledger rows are gone", graph.ledger.observations().none { it.host == b.id })
        assertTrue("another machine's rows are not", graph.ledger.observations().any { it.host == a.id })
        assertNotEquals("a removed machine is not the watched one", b.id, graph.profile.value?.id)
        assertNotNull("some saved machine is watched, since others remain", graph.profile.value)
        assertNotEquals(b.id, graph.settings.value.watchedProfileId)
    }

    @Test fun removingAMachineThatIsNotWatchedLeavesTheWatchedOneAlone() = runBlocking {
        graph.addMachine(a); graph.addMachine(b)
        assertEquals(b.id, graph.profile.value?.id)
        assertEquals(MachineRemoval.Result.Removed, graph.removeMachine(a.id))
        assertEquals("b is still watched", b.id, graph.profile.value?.id)
        assertNull(graph.profiles.get(a.id))
        assertNotNull(graph.profiles.get(b.id))
    }

    @Test fun removingTwiceIsNotAnErrorAndAnUnknownIdIsIgnored() = runBlocking {
        graph.addMachine(a)
        assertEquals(MachineRemoval.Result.Removed, graph.removeMachine(a.id))
        assertEquals(MachineRemoval.Result.Removed, graph.removeMachine(a.id))
        assertEquals(MachineRemoval.Result.Removed, graph.removeMachine("never-existed"))
    }

    @Test fun theSameMachineAddedAgainStartsWithoutTheOldPinOrRows() = runBlocking {
        graph.addMachine(a)
        pin(a.id)
        graph.ledger.observeHost(ObservationKind.Connected, HostProfileId(a.id), "default", graph.ledger.allocateEpoch(HostProfileId(a.id), "default"))
        graph.removeMachine(a.id)
        graph.addMachine(a)
        assertNull("trust is asked for again", pins.find(a.id))
        assertTrue(graph.ledger.observations().none { it.host == a.id })
    }

    @Test fun twoRemovalsAtOnceLeaveNeitherMachineSavedOrWatched() = runBlocking {
        graph.addMachine(a); graph.addMachine(b)
        assertEquals(b.id, graph.profile.value?.id)
        val first = async(Dispatchers.Default) { graph.removeMachine(b.id) }
        val second = async(Dispatchers.Default) { graph.removeMachine(a.id) }
        assertEquals(MachineRemoval.Result.Removed, first.await())
        assertEquals(MachineRemoval.Result.Removed, second.await())
        assertNull(graph.profiles.get(a.id)); assertNull(graph.profiles.get(b.id))
        val watched = graph.profile.value?.id
        assertTrue("the watched machine is not one that was just removed: $watched", watched != a.id && watched != b.id)
        assertTrue("and every machine the app lists is really saved", graph.machines.value.all { graph.profiles.get(it.id) != null })
    }

    @Test fun aSwitchAndARemovalAtOnceEndWithTheSwitchedMachineSavedAndWatchedOrGone() = runBlocking {
        graph.addMachine(a); graph.addMachine(b)
        val remove = async(Dispatchers.Default) { graph.removeMachine(b.id) }
        val watch = async(Dispatchers.Default) { graph.watchProfile(a.id) }
        remove.await(); watch.await()
        assertNull(graph.profiles.get(b.id))
        val watched = graph.profile.value
        assertNotNull(watched)
        assertNotEquals(b.id, watched!!.id)
        assertNotNull("the watched machine is saved", graph.profiles.get(watched.id))
    }

    // ---- the chosen machine (D1, 2026-10-06) -------------------------------------------------------------------------------------------

    @Test fun addingAMachineMakesItTheChosenOne() = runBlocking {
        graph.addMachine(a)
        assertEquals(a.id, graph.settings.value.chosenProfileId)
        graph.addMachine(b)
        assertEquals(b.id, graph.settings.value.chosenProfileId)
        assertEquals("and it is saved", b.id, savedSettings.chosenProfileId)
    }

    @Test fun anAlertsSwitchWatchesTheMachineButNeverChangesTheChoice() = runBlocking {
        graph.addMachine(a); graph.addMachine(b)
        // What an alert for a does (freshHerd): the default, not chosen.
        assertEquals(a.id, graph.watchProfile(a.id)?.id)
        assertEquals(a.id, graph.profile.value?.id)
        assertEquals(a.id, graph.settings.value.watchedProfileId)
        assertEquals("the user chose b", b.id, graph.settings.value.chosenProfileId)
        assertEquals("on disk too", b.id, savedSettings.chosenProfileId)
        val rows = MachineRoster.rows(graph.machines.value, graph.profile.value?.id, graph.settings.value.chosenProfileId)
        assertEquals("the list offers b as the free return", listOf(b.id), rows.filter { it.returnFree }.map { it.id })
    }

    @Test fun theUsersOwnSwitchMakesThatMachineTheChoice() = runBlocking {
        graph.addMachine(a); graph.addMachine(b)
        graph.watchProfile(a.id)
        assertEquals(b.id, graph.watchProfile(b.id, chosen = true)?.id)
        assertEquals("back on the choice", b.id, graph.settings.value.chosenProfileId)
        assertEquals(b.id, graph.profile.value?.id)
        graph.watchProfile(a.id, chosen = true)
        assertEquals("a switch the user made moves the choice", a.id, graph.settings.value.chosenProfileId)
        assertEquals(a.id, graph.profile.value?.id)
        assertEquals(a.id, savedSettings.chosenProfileId)
    }

    @Test fun removingTheWatchedMachineMakesTheNextOneWatchedAndChosen() = runBlocking {
        graph.addMachine(a); graph.addMachine(b)
        assertEquals(MachineRemoval.Result.Removed, graph.removeMachine(b.id))
        val next = graph.profile.value?.id
        assertNotNull("a machine is left to watch", next)
        assertEquals(next, graph.settings.value.watchedProfileId)
        assertEquals("the machine the dialog said Paddock then watches is the choice", next, graph.settings.value.chosenProfileId)
    }

    @Test fun removingTheMachineAnAlertMovedThePhoneToGoesBackToTheChosenOne() = runBlocking {
        graph.addMachine(a); graph.addMachine(b); graph.addMachine(c)
        // The user chose c; an alert moved the phone to b. "A machine under test" sorts before c, so the first by name would be a (or a real machine).
        assertEquals(c.id, graph.settings.value.chosenProfileId)
        graph.watchProfile(b.id)
        assertEquals(b.id, graph.profile.value?.id)
        assertEquals(c.id, MachineRoster.after(graph.machines.value, b.id, graph.settings.value.chosenProfileId)?.id)
        assertEquals(MachineRemoval.Result.Removed, graph.removeMachine(b.id))
        assertEquals("the chosen machine is watched again, not the first by name", c.id, graph.profile.value?.id)
        assertEquals(c.id, graph.settings.value.chosenProfileId)
        assertEquals(c.id, savedSettings.chosenProfileId)
    }

    // ---- Wake for a machine that is not watched (D2, 2026-10-06) ---------------------------------------------------------------------------

    @Test fun aWakeForAMachineThatIsNotWatchedIsRecordedUnderItAndLeavesTheWatchedMachinesFactsAlone() = runBlocking {
        // An address no network of the emulator holds and no relay: nothing can leave the phone, so the send says why and nothing is broadcast.
        val target = io.github.tuthan.paddock.wake.WakeTarget(available = true, mac = "02:00:5e:10:00:01", iface = "eth0", capturedAtMillis = 1)
        graph.addMachine(a.copy(wake = target)); graph.addMachine(b)
        assertEquals(b.id, graph.profile.value?.id)
        // Kept subscribed for the whole test, so the flow's value is the live one and not the last one before it stopped.
        val keep = launch(Dispatchers.Default) { graph.wakeFacts(a.id).collect { } }
        try {
            val watchedBefore = graph.wakeFacts.value
            graph.wake(a.id)
            val facts = kotlinx.coroutines.withTimeout(5_000) { graph.wakeFacts(a.id).first { it != null } }!!
            assertTrue("recorded as a send for a machine that is not watched", facts.notWatched)
            assertTrue("and the machine list still has the reading", graph.machines.value.single { it.id == a.id }.wake != null)
            assertEquals("the watched machine's facts are untouched", watchedBefore, graph.wakeFacts.value)
            // Watching a makes its send-only facts go: their "not watching this machine" line is no longer true. While a is watched its facts are the
            // watched tap's, so the drop shows once the phone is back on b: a's old line does not come back. (The switch drops them itself, foreground or not.)
            graph.watchProfile(a.id, chosen = true)
            graph.watchProfile(b.id)
            kotlinx.coroutines.delay(300)
            assertNull("a's send-only facts went when it was watched", graph.wakeFacts(a.id).value)
        } finally { keep.cancel() }
    }

    // ---- review 2026-10-06 ---------------------------------------------------------------------------------------------------------------

    @Test fun settingUpTheWatchedMachinesKeyLeavesTheChoiceWhereItWas() = runBlocking {
        graph.addMachine(a); graph.addMachine(b)
        // An alert moved the phone to a; the user chose b. Home's "Set up key" saves a again (same machine, another user name).
        graph.watchProfile(a.id)
        val fixed = a.copy(user = "tester2")
        graph.addMachine(fixed, chosen = MachineRoster.choosesOnAdd(fixed, fixing = a))
        assertEquals(a.id, graph.profile.value?.id)
        assertEquals("fixing a key is not choosing the machine", b.id, graph.settings.value.chosenProfileId)
        assertEquals(b.id, savedSettings.chosenProfileId)
        // A plain add still is.
        graph.addMachine(c)
        assertEquals(c.id, graph.settings.value.chosenProfileId)
    }

    @Test fun theRemovalMarkIsTheGraphsWhileItRunsAndGoneAfter() = runBlocking {
        graph.addMachine(a); graph.addMachine(b)
        // Started before the removal, on another thread: it sees the mark while the removal does its disk work.
        val seen = async(Dispatchers.Default, start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            kotlinx.coroutines.withTimeout(5_000) { graph.removingMachine.first { it == a.id } }
        }
        assertEquals(MachineRemoval.Result.Removed, graph.removeMachine(a.id))
        assertEquals("Watch and Remove… waited for this machine while it was being removed", a.id, seen.await())
        assertNull("and are free again once it is gone", graph.removingMachine.value)
    }

    @Test fun aRemovalAskedForFromThePageLeavesItsOutcomeUntilTheRootHasShownIt() = runBlocking {
        graph.addMachine(a); graph.addMachine(b)
        // Nothing left from an earlier run in this process.
        graph.removalOutcome.value?.let { graph.consumeRemoval(it) }
        graph.requestRemoval(a.id, a.name)
        val outcome = kotlinx.coroutines.withTimeout(5_000) { graph.removalOutcome.first { it != null } }!!
        assertEquals(a.id, outcome.id)
        assertEquals(a.name, outcome.name)
        assertEquals(MachineRemoval.Result.Removed, outcome.result)
        assertNull(graph.profiles.get(a.id))
        assertNull(graph.removingMachine.value)
        // Kept (a rotation, a trip away) until consumed; consuming an older outcome never clears a newer one.
        assertEquals(outcome, graph.removalOutcome.value)
        graph.requestRemoval(b.id, b.name)
        val newer = kotlinx.coroutines.withTimeout(5_000) { graph.removalOutcome.first { it != null && it !== outcome } }!!
        graph.consumeRemoval(outcome)
        assertEquals(newer, graph.removalOutcome.value)
        graph.consumeRemoval(newer)
        assertNull(graph.removalOutcome.value)
    }

    @Test fun removingTheChosenMachineWhileAnotherIsWatchedMakesTheWatchedOneTheChoice() = runBlocking {
        graph.addMachine(a); graph.addMachine(b)
        graph.watchProfile(a.id)
        assertEquals(b.id, graph.settings.value.chosenProfileId)
        assertEquals(MachineRemoval.Result.Removed, graph.removeMachine(b.id))
        assertEquals("still watching a", a.id, graph.profile.value?.id)
        assertEquals("a removed machine is never the choice", a.id, graph.settings.value.chosenProfileId)
    }
}
