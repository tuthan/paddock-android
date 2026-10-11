package io.github.tuthan.paddock.hostprofile

import io.github.tuthan.paddock.applock.AppLock
import io.github.tuthan.paddock.ops.InMemorySnippetStore
import io.github.tuthan.paddock.settings.AppSettings
import io.github.tuthan.paddock.settings.InMemoryAppSettingsStore
import io.github.tuthan.paddock.settings.SettingsController
import io.github.tuthan.paddock.wake.WakeReading
import io.github.tuthan.paddock.wake.WakeRelay
import io.github.tuthan.paddock.wake.WakeTarget
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After

/**
 * Every change to the saved machines and the watched one, against a profile store and settings in memory and a recording [MachineBook.Effects]: the
 * same book the app graph builds. The graph's own test (androidTest `MachineRemovalGraphTest`) runs these rules over the real files; the rules are here
 * so they run without a phone.
 */
class MachineBookTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun stop() { scope.cancel() }

    private val a = HostProfile("mtest-a", "A machine", "10.255.255.1", 22, "tester")
    private val b = HostProfile("mtest-b", "B machine", "10.255.255.2", 22, "tester")
    private val c = HostProfile("mtest-c", "C machine", "10.255.255.3", 22, "tester")

    private val profiles = InMemoryHostProfileStore()
    private val settingsStore = InMemoryAppSettingsStore()
    private val prefs = SettingsController(scope, settingsStore, InMemorySnippetStore(), AppLock { 0L })
    private val events = CopyOnWriteArrayList<String>()
    @Volatile private var foreground = true
    /** What the phone holds about a machine besides its profile: a pin and some ledger rows, by id. */
    private val pins = ConcurrentHashMap.newKeySet<String>()
    private val rows = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var failForget: String? = null
    @Volatile private var holdForget: CompletableDeferred<Unit>? = null
    @Volatile private var inForgetFor: String? = null

    private val effects = object : MachineBook.Effects {
        override val inForeground: Boolean get() = foreground
        override fun watch(profile: HostProfile) { events += "watch:${profile.id}" }
        override fun stopWatching() { events += "stop" }
        override suspend fun dropConnection(id: String) { events += "drop:$id" }
        override fun becameWatched(id: String) { events += "watched:$id" }
        override fun forgotten(id: String) { events += "forgotten:$id" }
        override fun watchedRemoved() { events += "reset" }
        override fun addressChanged(id: String) { events += "moved:$id" }
    }

    private val removal = MachineRemoval(
        listOf(
            MachineRemoval.Forget("the host key") { id -> if (failForget == "the host key") error("cannot"); pins -= id },
            MachineRemoval.Forget("what the phone saw there") { id -> inForgetFor = id; holdForget?.await(); rows -= id },
        ),
    ) { id -> profiles.remove(id) }

    private val book = MachineBook(profiles, prefs, scope, { 1_000L }, removal, effects)

    private val saved get() = runBlocking { settingsStore.load() }

    // ---- start-up -----------------------------------------------------------------------------------------------------------

    @Test fun nothingSavedIsNoMachinesAndNothingWatched() = runBlocking<Unit> {
        assertEquals(Boot.Loading, book.boot.value)
        book.load()
        assertEquals(Boot.NoMachines, book.boot.value)
        assertNull(book.profile.value)
        assertTrue(book.machines.value.isEmpty())
    }

    @Test fun theMachineWatchedLastTimeIsWatchedNotTheFirstByName() = runBlocking<Unit> {
        profiles.put(a); profiles.put(b)
        settingsStore.save(AppSettings(watchedProfileId = "mtest-b", chosenProfileId = "mtest-b"))
        prefs.load()
        book.load()
        assertEquals(Boot.Ready, book.boot.value)
        assertEquals(b.id, book.profile.value?.id)
        assertEquals(listOf(a.id, b.id), book.machines.value.map { it.id })
    }

    @Test fun aSavedChoiceThatNamesNothingFallsBackToTheFirstAndTheMissingChoiceIsFilledInAndSavedOnce() = runBlocking<Unit> {
        profiles.put(a); profiles.put(b)
        settingsStore.save(AppSettings(watchedProfileId = "gone"))
        prefs.load()
        book.load()
        assertEquals(a.id, book.profile.value?.id)
        assertEquals(a.id, prefs.value.chosenProfileId, "settings from before there was a chosen machine: the watched machine is the choice")
        assertEquals(a.id, saved.chosenProfileId)
    }

    @Test fun aListThatCannotBeReadNeverMigratesTheChoice() = runBlocking<Unit> {
        val broken = object : HostProfileStore by profiles {
            override suspend fun list(): List<HostProfile> = throw HostProfilesCorrupt(null)
        }
        settingsStore.save(AppSettings(watchedProfileId = "mtest-a", chosenProfileId = "mtest-b"))
        prefs.load()
        val b0 = MachineBook(broken, prefs, scope, { 1_000L }, removal, effects)
        b0.load()
        assertEquals(Boot.NoMachines, b0.boot.value)
        assertEquals("mtest-b", saved.chosenProfileId, "an empty list that was really an unreadable one must not rewrite the choice")
    }

    // ---- add, watch, choose -----------------------------------------------------------------------------------------------------

    @Test fun addingAMachineSavesItWatchesItAndMakesItTheChosenOne() = runBlocking<Unit> {
        book.add(a)
        assertEquals(a, profiles.get(a.id))
        assertEquals(a.id, book.profile.value?.id)
        assertEquals(Boot.Ready, book.boot.value)
        assertEquals(a.id, prefs.value.chosenProfileId)
        book.add(b)
        assertEquals(b.id, prefs.value.chosenProfileId)
        assertEquals(b.id, saved.chosenProfileId, "and it is saved")
        assertEquals(b.id, saved.watchedProfileId)
        assertEquals(listOf("watch:mtest-a", "watch:mtest-b"), events.toList())
    }

    @Test fun inTheBackgroundAMachineIsSavedAndWatchedButNotConnected() = runBlocking<Unit> {
        foreground = false
        book.add(a)
        assertEquals(a.id, book.profile.value?.id)
        assertTrue(events.isEmpty())
    }

    @Test fun aSettingsFileThatCannotBeWrittenFailsTheAddAfterTheProfileWasSaved() = runBlocking<Unit> {
        val failing = object : io.github.tuthan.paddock.settings.AppSettingsStore {
            override suspend fun load() = AppSettings()
            override suspend fun save(settings: AppSettings) = throw java.io.IOException("disk full")
        }
        val p = SettingsController(scope, failing, InMemorySnippetStore(), AppLock { 0L })
        val bk = MachineBook(profiles, p, scope, { 1_000L }, removal, effects)
        val failure = runCatching { bk.add(a) }.exceptionOrNull()
        assertTrue(failure is java.io.IOException, failure.toString())
        assertNotNull(profiles.get(a.id), "what add did before the write stays")
        assertNull(bk.profile.value, "but nothing is watched that was not saved as the watched machine")
    }

    @Test fun anAlertsSwitchWatchesTheMachineButNeverChangesTheChoice() = runBlocking<Unit> {
        book.add(a); book.add(b)
        events.clear()
        assertEquals(a.id, book.watch(a.id)?.id)
        assertEquals(a.id, book.profile.value?.id)
        assertEquals(a.id, prefs.value.watchedProfileId)
        assertEquals(b.id, prefs.value.chosenProfileId, "the user chose b")
        assertEquals(b.id, saved.chosenProfileId, "on disk too")
        assertEquals(listOf("watched:mtest-a", "watch:mtest-a"), events.toList())
        val list = MachineRoster.rows(book.machines.value, book.profile.value?.id, prefs.value.chosenProfileId)
        assertEquals(listOf(b.id), list.filter { it.returnFree }.map { it.id }, "the list offers b as the free return")
    }

    @Test fun theUsersOwnSwitchMakesThatMachineTheChoice() = runBlocking<Unit> {
        book.add(a); book.add(b)
        book.watch(a.id)
        assertEquals(b.id, book.watch(b.id, chosen = true)?.id)
        assertEquals(b.id, prefs.value.chosenProfileId)
        book.watch(a.id, chosen = true)
        assertEquals(a.id, prefs.value.chosenProfileId)
        assertEquals(a.id, saved.chosenProfileId)
    }

    @Test fun watchingTheMachineAlreadyWatchedChangesNothingUnlessItIsNowTheChoice() = runBlocking<Unit> {
        book.add(a); book.add(b)
        book.watch(a.id)
        events.clear()
        book.watch(a.id)
        assertTrue(events.isEmpty(), "no switch, no reconnect")
        book.watch(a.id, chosen = true)
        assertTrue(events.isEmpty())
        assertEquals(a.id, prefs.value.chosenProfileId, "but the user's own pick of the watched machine is the choice")
    }

    @Test fun anUnknownMachineIsNotWatched() = runBlocking<Unit> {
        book.add(a)
        assertNull(book.watch("nope"))
        assertEquals(a.id, book.profile.value?.id)
    }

    @Test fun settingUpTheWatchedMachinesKeyLeavesTheChoiceWhereItWas() = runBlocking<Unit> {
        book.add(a); book.add(b)
        book.watch(a.id)
        val fixed = a.copy(user = "tester2")
        book.add(fixed, chosen = MachineRoster.choosesOnAdd(fixed, fixing = a))
        assertEquals(a.id, book.profile.value?.id)
        assertEquals(b.id, prefs.value.chosenProfileId, "fixing a key is not choosing the machine")
        assertEquals(b.id, saved.chosenProfileId)
        book.add(c)
        assertEquals(c.id, prefs.value.chosenProfileId)
    }

    // ---- remove -----------------------------------------------------------------------------------------------------------------

    private fun hold(vararg ids: String) { pins += ids; rows += ids }

    @Test fun removingTheWatchedMachineForgetsItStopsItsConnectionAndWatchesAnother() = runBlocking<Unit> {
        book.add(a); book.add(b)
        hold(a.id, b.id)
        events.clear()
        assertEquals(MachineRemoval.Result.Removed, book.remove(b.id))
        assertNull(profiles.get(b.id)); assertTrue(book.machines.value.none { it.id == b.id })
        assertFalse(b.id in pins); assertFalse(b.id in rows)
        assertTrue(a.id in pins && a.id in rows, "another machine's facts are not touched")
        assertEquals(a.id, book.profile.value?.id)
        assertEquals(a.id, prefs.value.watchedProfileId)
        assertEquals(a.id, prefs.value.chosenProfileId, "the machine the dialog said Paddock then watches is the choice")
        assertEquals(listOf("stop", "drop:mtest-b", "forgotten:mtest-b", "reset", "watch:mtest-a"), events.toList())
        assertFalse(book.isRemoving(b.id))
    }

    @Test fun removingTheOnlyMachineLeavesNothingWatchedAndNoMachines() = runBlocking<Unit> {
        book.add(a)
        events.clear()
        assertEquals(MachineRemoval.Result.Removed, book.remove(a.id))
        assertNull(book.profile.value)
        assertEquals(Boot.NoMachines, book.boot.value)
        assertNull(prefs.value.watchedProfileId); assertNull(prefs.value.chosenProfileId)
        assertEquals(listOf("stop", "drop:mtest-a", "forgotten:mtest-a", "reset"), events.toList())
    }

    @Test fun removingAMachineThatIsNotWatchedLeavesTheWatchedOneAlone() = runBlocking<Unit> {
        book.add(a); book.add(b)
        events.clear()
        assertEquals(MachineRemoval.Result.Removed, book.remove(a.id))
        assertEquals(b.id, book.profile.value?.id)
        assertNull(profiles.get(a.id)); assertNotNull(profiles.get(b.id))
        assertEquals(listOf("drop:mtest-a", "forgotten:mtest-a"), events.toList(), "its connection goes; the watched one is not stopped")
    }

    @Test fun removingTwiceIsNotAnErrorAndAnUnknownIdIsIgnored() = runBlocking<Unit> {
        book.add(a)
        assertEquals(MachineRemoval.Result.Removed, book.remove(a.id))
        events.clear()
        assertEquals(MachineRemoval.Result.Removed, book.remove(a.id))
        assertEquals(MachineRemoval.Result.Removed, book.remove("never-existed"))
        assertTrue(events.isEmpty())
    }

    @Test fun theSameMachineAddedAgainStartsWithoutTheOldPinOrRows() = runBlocking<Unit> {
        book.add(a)
        hold(a.id)
        book.remove(a.id)
        book.add(a)
        assertFalse(a.id in pins); assertFalse(a.id in rows)
    }

    @Test fun removingTheMachineAnAlertMovedThePhoneToGoesBackToTheChosenOne() = runBlocking<Unit> {
        book.add(a); book.add(b); book.add(c)
        assertEquals(c.id, prefs.value.chosenProfileId)
        book.watch(b.id)
        assertEquals(c.id, MachineRoster.after(book.machines.value, b.id, prefs.value.chosenProfileId)?.id)
        assertEquals(MachineRemoval.Result.Removed, book.remove(b.id))
        assertEquals(c.id, book.profile.value?.id, "the chosen machine is watched again, not the first by name")
        assertEquals(c.id, saved.chosenProfileId)
    }

    @Test fun removingTheChosenMachineWhileAnotherIsWatchedMakesTheWatchedOneTheChoice() = runBlocking<Unit> {
        book.add(a); book.add(b)
        book.watch(a.id)
        assertEquals(b.id, prefs.value.chosenProfileId)
        assertEquals(MachineRemoval.Result.Removed, book.remove(b.id))
        assertEquals(a.id, book.profile.value?.id, "still watching a")
        assertEquals(a.id, prefs.value.chosenProfileId, "a removed machine is never the choice")
        assertEquals(a.id, saved.chosenProfileId)
    }

    @Test fun whenSomethingCannotBeForgottenTheMachineStaysListedAndWatchedAgain() = runBlocking<Unit> {
        book.add(a); book.add(b)
        hold(b.id)
        failForget = "the host key"
        events.clear()
        val r = book.remove(b.id)
        assertEquals(MachineRemoval.Result.Incomplete(listOf("the host key")), r)
        assertNotNull(profiles.get(b.id), "the profile is removed last, and not at all when a step failed")
        assertEquals(b.id, book.profile.value?.id)
        assertEquals(listOf("stop", "drop:mtest-b", "watch:mtest-b"), events.toList(), "watched again for another try")
        assertFalse(book.isRemoving(b.id))
        failForget = null
        assertEquals(MachineRemoval.Result.Removed, book.remove(b.id))
    }

    @Test fun inTheBackgroundAFailedRemovalDoesNotReconnect() = runBlocking<Unit> {
        book.add(a)
        foreground = false
        failForget = "the host key"
        events.clear()
        book.remove(a.id)
        assertEquals(listOf("stop", "drop:mtest-a"), events.toList())
    }

    @Test fun twoRemovalsAtOnceLeaveNeitherMachineSavedOrWatched() = runBlocking<Unit> {
        book.add(a); book.add(b)
        val first = async(Dispatchers.Default) { book.remove(b.id) }
        val second = async(Dispatchers.Default) { book.remove(a.id) }
        assertEquals(MachineRemoval.Result.Removed, first.await())
        assertEquals(MachineRemoval.Result.Removed, second.await())
        assertNull(profiles.get(a.id)); assertNull(profiles.get(b.id))
        assertNull(book.profile.value)
        assertTrue(book.machines.value.isEmpty())
    }

    @Test fun aSwitchAndARemovalAtOnceEndWithTheSwitchedMachineSavedAndWatchedOrGone() = runBlocking<Unit> {
        book.add(a); book.add(b)
        val remove = async(Dispatchers.Default) { book.remove(b.id) }
        val watch = async(Dispatchers.Default) { book.watch(a.id) }
        remove.await(); watch.await()
        assertNull(profiles.get(b.id))
        val watched = book.profile.value
        assertNotNull(watched)
        assertNotNull(profiles.get(watched!!.id), "the watched machine is saved")
    }

    @Test fun theRemovalMarkIsHeldWhileItRunsAndNothingMayWatchTheMachineMeanwhile() = runBlocking<Unit> {
        book.add(a); book.add(b)
        val gate = CompletableDeferred<Unit>().also { holdForget = it }
        val removing = async(Dispatchers.Default) { book.remove(b.id) }
        withTimeout(5_000) { book.removingMachine.first { it == b.id } }
        withTimeout(5_000) { while (inForgetFor != b.id) kotlinx.coroutines.delay(5) }
        assertTrue(book.isRemoving(b.id), "a foreground flip must not start watching it now")
        gate.complete(Unit)
        assertEquals(MachineRemoval.Result.Removed, removing.await())
        assertNull(book.removingMachine.value, "free again once it is gone")
        assertFalse(book.isRemoving(b.id))
    }

    @Test fun aRemovalAskedForFromThePageLeavesItsOutcomeUntilTheRootHasShownIt() = runBlocking<Unit> {
        book.add(a); book.add(b)
        val gate = CompletableDeferred<Unit>().also { holdForget = it }
        book.requestRemoval(a.id, a.name)
        assertEquals(a.id, book.removingMachine.value, "marked at the tap, before the removal has run")
        assertNull(book.removalOutcome.value)
        gate.complete(Unit)
        val outcome = withTimeout(5_000) { book.removalOutcome.first { it != null } }!!
        assertEquals(a.id, outcome.id); assertEquals(a.name, outcome.name)
        assertEquals(MachineRemoval.Result.Removed, outcome.result)
        assertNull(profiles.get(a.id))
        withTimeout(5_000) { book.removingMachine.first { it == null } }
        assertEquals(outcome, book.removalOutcome.value)
        book.requestRemoval(b.id, b.name)
        val newer = withTimeout(5_000) { book.removalOutcome.first { it != null && it !== outcome } }!!
        book.consumeRemoval(outcome)
        assertEquals(newer, book.removalOutcome.value, "consuming an older outcome never clears a newer one")
        book.consumeRemoval(newer)
        assertNull(book.removalOutcome.value)
    }

    // ---- what a bring-up and the user write to one machine -------------------------------------------------------------------

    private val target = WakeTarget(available = true, mac = "02:00:5e:10:00:01", iface = "wlp0s20f3", capturedAtMillis = 5)

    @Test fun aWakeReadingIsKeptForTheWatchedMachineAndShownInTheList() = runBlocking<Unit> {
        book.add(a); book.add(b)
        book.saveWake(b, WakeReading(target, answered = true))
        assertEquals(target, profiles.get(b.id)?.wake)
        assertEquals(target, book.profile.value?.wake)
        assertEquals(target, book.machines.value.single { it.id == b.id }.wake)
        assertNull(book.machines.value.single { it.id == a.id }.wake)
    }

    @Test fun aWakeReadingForAMachineWhoseAddressWasEditedMeanwhileIsDropped() = runBlocking<Unit> {
        book.add(a)
        profiles.update(a.id) { it.copy(host = "10.255.255.99") }
        book.saveWake(a, WakeReading(target, answered = true))
        assertNull(profiles.get(a.id)?.wake)
    }

    @Test fun theDetectedOsIsKeptOnceAndAnEqualReadingIsNotAWrite() = runBlocking<Unit> {
        book.add(a)
        book.saveDetectedOs(a, HostOs.Mac)
        assertEquals(HostOs.Mac, book.profile.value?.detectedOs)
        assertEquals(HostOs.Mac, book.machines.value.single().detectedOs)
        profiles.update(a.id) { it.copy(os = HostOs.Linux) }
        book.saveDetectedOs(book.profile.value!!, HostOs.Mac)
        assertEquals(HostOs.Linux, profiles.get(a.id)?.os, "the user's own pick is never touched")
        book.saveDetectedOs(a.copy(host = "elsewhere"), HostOs.Windows)
        assertEquals(HostOs.Mac, profiles.get(a.id)?.detectedOs, "a reading for a machine that is no longer this one is dropped")
    }

    @Test fun renamingTheWatchedMachineReconnectsOnceAndAnotherDoesNot() = runBlocking<Unit> {
        book.add(a); book.add(b)
        events.clear()
        assertTrue(book.rename(b.id, "B renamed"))
        assertEquals("B renamed", book.profile.value?.name)
        assertEquals(listOf("watch:mtest-b"), events.toList())
        assertTrue(book.rename(a.id, "A renamed"))
        assertEquals(listOf("watch:mtest-b"), events.toList(), "another machine's name is not part of the live connection")
        assertEquals(listOf("A renamed", "B renamed"), book.machines.value.map { it.name })
        assertTrue(book.rename(a.id, "A renamed"), "saying the name it already has is not a failure")
        assertFalse(book.rename("nope", "X"))
    }

    @Test fun movingTheWatchedMachineKeepsItsIdAndFactsAndWatchesItAgainAtTheNewAddress() = runBlocking<Unit> {
        book.add(a); book.add(b)
        book.saveWake(b, WakeReading(target, answered = true))
        events.clear()
        assertTrue(book.setAddress(b.id, "devbox.local", 2222))
        val moved = profiles.get(b.id)!!
        assertEquals("devbox.local" to 2222, moved.host to moved.port)
        assertEquals(b.copy(host = "devbox.local", port = 2222, wake = target), moved, "the id, the name, the user and the wake facts stay")
        assertEquals(moved, book.profile.value)
        assertEquals(moved, book.machines.value.single { it.id == b.id })
        // The watch stops before the connection to the old address is closed, as a removal does, so the next connection is a new one to the new address.
        assertEquals(listOf("moved:mtest-b", "stop", "drop:mtest-b", "watch:mtest-b"), events.toList())
        assertEquals("mtest-b", saved.watchedProfileId, "it is still the watched machine")
    }

    @Test fun movingAnotherMachineClosesWhatWasOpenToItAndLeavesTheWatchedOneAlone() = runBlocking<Unit> {
        book.add(a); book.add(b)
        events.clear()
        assertTrue(book.setAddress(a.id, "10.255.255.50", 22))
        assertEquals("10.255.255.50", profiles.get(a.id)?.host)
        assertEquals(listOf("moved:mtest-a", "drop:mtest-a"), events.toList())
        assertEquals(b, book.profile.value)
    }

    @Test fun inTheBackgroundTheWatchedMachineIsMovedButNotConnected() = runBlocking<Unit> {
        book.add(a)
        foreground = false
        events.clear()
        assertTrue(book.setAddress(a.id, "10.255.255.60", 22))
        assertEquals(listOf("moved:mtest-a", "stop", "drop:mtest-a"), events.toList())
        assertEquals("10.255.255.60", book.profile.value?.host)
    }

    @Test fun theSameAddressIsNotAWriteAndAnUnknownMachineIsNotMoved() = runBlocking<Unit> {
        book.add(a)
        events.clear()
        assertTrue(book.setAddress(a.id, a.host, a.port), "saying the address it already has is not a failure")
        assertFalse(book.setAddress("nope", "10.255.255.70", 22))
        assertTrue(events.isEmpty())
    }

    @Test fun aWakeReadingFromTheOldAddressIsDroppedAfterAMove() = runBlocking<Unit> {
        book.add(a)
        book.setAddress(a.id, "10.255.255.80", 22)
        // The controller that read it was started for the old address.
        book.saveWake(a, WakeReading(target, answered = true))
        assertNull(profiles.get(a.id)?.wake)
    }

    @Test fun theHostnameIsKeptWithoutAReconnectAndOnlyForTheAddressThatAsked() = runBlocking<Unit> {
        book.add(a)
        events.clear()
        book.saveDetectedName(a, "alpha-box")
        assertEquals("alpha-box", book.profile.value?.detectedName)
        assertEquals("alpha-box", book.machines.value.single().detectedName)
        book.saveDetectedName(a.copy(host = "10.255.255.99"), "someone-else")
        assertEquals("alpha-box", profiles.get(a.id)?.detectedName, "a reading for a machine that is no longer at that address is dropped")
        assertTrue(events.isEmpty())
    }

    // ---- the hostname as the default name ---------------------------------------------------------------------------------------

    /** A machine added by its address, as the Add screen does when the user types no name: the address is its name. */
    private val d = HostProfile("mtest-d", "10.255.255.4", "10.255.255.4", 22, "tester")

    @Test fun aMachineNamedByItsAddressTakesItsHostnameWithoutAReconnectAndFollowsIt() = runBlocking<Unit> {
        book.add(d)
        events.clear()
        book.saveDetectedName(d, "devbox")
        assertEquals("devbox", profiles.get(d.id)?.name)
        assertEquals("devbox", book.profile.value?.name, "the watched machine's profile is the new one")
        assertEquals("devbox", book.machines.value.single().name)
        assertEquals("devbox", book.machines.value.single().detectedName)
        book.saveDetectedName(d, "devbox-2")
        assertEquals("devbox-2", profiles.get(d.id)?.name, "the machine was renamed on its own side: the default follows")
        assertTrue(events.isEmpty(), "a default name is not a reason to reconnect: $events")
    }

    @Test fun aNameTheUserTypedIsNeverReplacedByTheHostname() = runBlocking<Unit> {
        book.add(a)
        book.saveDetectedName(a, "alpha-box")
        assertEquals("A machine", profiles.get(a.id)?.name)
        assertEquals("alpha-box", profiles.get(a.id)?.detectedName)
        book.add(d)
        assertTrue(book.rename(d.id, "Build server"))
        book.saveDetectedName(d, "devbox")
        assertEquals("Build server", profiles.get(d.id)?.name, "a rename on the phone ends the following, whatever the hostname is")
    }

    @Test fun aNameTypedAsTheHostnameIsTheDefaultAgainAndFollowsIt() = runBlocking<Unit> {
        book.add(d)
        book.saveDetectedName(d, "devbox")
        assertTrue(book.rename(d.id, "Build server"))
        assertTrue(book.rename(d.id, "DEVBOX"))
        book.saveDetectedName(d, "devbox-new")
        assertEquals("devbox-new", profiles.get(d.id)?.name)
    }

    @Test fun aHostnameAnotherMachineAlreadyUsesAsItsNameIsNotTakenAndIsAskedAgainWhenItIsFree() = runBlocking<Unit> {
        val other = HostProfile("mtest-e", "devbox", "10.255.255.5", 22, "tester")
        book.add(other); book.add(d)
        events.clear()
        book.saveDetectedName(d, "DevBox")
        assertEquals("10.255.255.4", profiles.get(d.id)?.name, "two cards are never told apart only by a capital")
        assertEquals("DevBox", profiles.get(d.id)?.detectedName, "the reading itself is kept")
        assertTrue(book.rename(other.id, "Other box"))
        book.saveDetectedName(d, "DevBox")
        assertEquals("DevBox", profiles.get(d.id)?.name, "the same reading at the next bring-up finds the name free")
        assertTrue(events.isEmpty(), "neither the skipped adoption, the other machine's rename nor the later adoption reconnects the watched machine: $events")
    }

    @Test fun aHostnameTooLongForANameIsCutAndStillTheDefault() = runBlocking<Unit> {
        val long = "h".repeat(63)
        book.add(d)
        book.saveDetectedName(d, long)
        assertEquals(long.take(60), profiles.get(d.id)?.name)
        assertTrue(profiles.get(d.id)!!.nameIsDefault)
    }

    @Test fun aMachineNamedByItsAddressIsNamedByTheNewOneWhenMovedAndACustomNameStays() = runBlocking<Unit> {
        book.add(d)
        assertTrue(book.setAddress(d.id, "10.255.255.40", 22))
        assertEquals("10.255.255.40", profiles.get(d.id)?.name)
        book.add(a)
        assertTrue(book.setAddress(a.id, "10.255.255.41", 22))
        assertEquals("A machine", profiles.get(a.id)?.name)
        book.add(d.copy(id = "mtest-f", name = "10.255.255.6", host = "10.255.255.6"))
        assertTrue(book.rename(a.id, "10.255.255.60"))
        assertTrue(book.setAddress("mtest-f", "10.255.255.60", 22))
        assertEquals("10.255.255.6", profiles.get("mtest-f")?.name, "another machine already has that name: it keeps the old one")
    }

    @Test fun aMoveFoundByPaddockIsRefusedWhenTheUserMovedTheMachineMeanwhile() = runBlocking<Unit> {
        book.add(a)
        val lookedFor = book.profile.value!!
        assertTrue(book.setAddress(a.id, "10.255.255.40", 22))
        events.clear()
        assertFalse(book.setAddress(a.id, "10.255.255.41", 22, onlyFrom = lookedFor), "the user's address stands")
        assertEquals("10.255.255.40", profiles.get(a.id)?.host)
        assertTrue(events.isEmpty())
        assertTrue(book.setAddress(a.id, "10.255.255.41", 22, onlyFrom = book.profile.value), "still at the address looked for: moved")
        assertEquals("10.255.255.41", profiles.get(a.id)?.host)
    }

    @Test fun theNetworkSearchSwitchIsSavedKeptThroughAMoveAndNeverReconnects() = runBlocking<Unit> {
        book.add(a); book.add(b)
        events.clear()
        assertFalse(profiles.get(b.id)!!.searchNetwork, "off until the user turns it on")
        book.setSearchNetwork(b.id, true)
        assertTrue(profiles.get(b.id)!!.searchNetwork)
        assertTrue(book.profile.value!!.searchNetwork)
        assertTrue(book.machines.value.single { it.id == b.id }.searchNetwork)
        book.setSearchNetwork(a.id, true)
        assertTrue(book.machines.value.single { it.id == a.id }.searchNetwork)
        book.setSearchNetwork("nope", true)
        assertTrue(events.isEmpty(), "a setting: nothing is closed or watched again")
        assertTrue(book.setAddress(b.id, "10.255.255.90", 22))
        assertTrue(profiles.get(b.id)!!.searchNetwork, "a moved machine keeps the user's switch")
        book.setSearchNetwork(b.id, false)
        assertFalse(profiles.get(b.id)!!.searchNetwork)
    }

    @Test fun aDecisionAboutTheOldAddressRunsOnlyWhileTheMachineIsStillThere() = runBlocking<Unit> {
        book.add(a)
        assertEquals("ran", book.whileAt(a.id, "10.255.255.1:22") { "ran" })
        assertNull(book.whileAt(a.id, "10.255.255.9:22") { "ran" }, "another address")
        assertNull(book.whileAt("nope", "10.255.255.1:22") { "ran" }, "no such machine")
        assertTrue(book.setAddress(a.id, "10.255.255.50", 22))
        assertNull(book.whileAt(a.id, "10.255.255.1:22") { "ran" }, "it moved since the warning was about the old address")
        assertEquals("ran", book.whileAt(a.id, "10.255.255.50:22") { "ran" })
    }

    @Test fun aMoveWaitsForADecisionRunningAtTheOldAddressSoNoPinIsMadeAfterIt() = runBlocking<Unit> {
        book.add(a)
        val inside = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val deciding = async(Dispatchers.Default) { book.whileAt(a.id, "10.255.255.1:22") { inside.complete(Unit); release.await(); "pinned" } }
        inside.await()
        val moving = async(Dispatchers.Default) { book.setAddress(a.id, "10.255.255.51", 22) }
        kotlinx.coroutines.delay(150)
        assertFalse(moving.isCompleted, "the move waits for the decision that was made at the old address")
        assertEquals("10.255.255.1", profiles.get(a.id)?.host)
        release.complete(Unit)
        assertEquals("pinned", deciding.await())
        assertTrue(moving.await())
        assertEquals("10.255.255.51", profiles.get(a.id)?.host)
    }

    @Test fun theUsersIconNeverReconnects() = runBlocking<Unit> {
        book.add(a)
        events.clear()
        book.setOs(a.id, HostOs.Windows)
        assertEquals(HostOs.Windows, book.profile.value?.os)
        assertEquals(HostOs.Windows, book.machines.value.single().os)
        book.setOs(a.id, null)
        assertNull(book.profile.value?.os)
        book.setOs("nope", HostOs.Mac)
        assertTrue(events.isEmpty())
    }

    @Test fun aWakeRelayBeforeAnyReadingMakesANotReadYetTargetThatCarriesOnlyIt() = runBlocking<Unit> {
        book.add(a)
        val relay = WakeRelay("10.8.0.1", 9009)
        book.setWakeRelay(a.id, relay)
        val wake = profiles.get(a.id)!!.wake!!
        assertFalse(wake.available)
        assertEquals(relay, wake.relay)
        assertEquals(1_000L, wake.capturedAtMillis)
        assertEquals(wake, book.profile.value?.wake)
        book.saveWake(a, WakeReading(target, answered = true))
        assertEquals(relay, profiles.get(a.id)!!.wake!!.relay, "the next bring-up fills in the rest and keeps the relay")
        book.setWakeRelay(a.id, null)
        assertNull(profiles.get(a.id)!!.wake!!.relay)
        book.setWakeRelay("nope", relay)
    }
}
