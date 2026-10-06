package io.github.tuthan.paddock

import androidx.test.platform.app.InstrumentationRegistry
import io.github.tuthan.paddock.hostkey.FileHostKeyStore
import io.github.tuthan.paddock.hostkey.PinnedHostKey
import io.github.tuthan.paddock.hostprofile.HostProfile
import io.github.tuthan.paddock.hostprofile.MachineRemoval
import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.ledger.ObservationKind
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
 * pin and the ledger rows go with it, the watched machine moves on, and a second Remove is not an error. The machines here have ids no real
 * machine has, and the watched machine is put back afterwards.
 */
class MachineRemovalGraphTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph get() = (ctx.applicationContext as PaddockApp).graph
    private val pins get() = FileHostKeyStore(File(ctx.filesDir, "host-keys.json"))

    private val a = HostProfile("mtest-a", "A machine under test", "10.255.255.1", 22, "tester")
    private val b = HostProfile("mtest-b", "B machine under test", "10.255.255.2", 22, "tester")
    private var before: String? = null

    @Before fun remember() { before = graph.profile.value?.id }

    @After fun restore() = runBlocking {
        graph.removeMachine(a.id); graph.removeMachine(b.id)
        before?.let { graph.watchProfile(it) }
        Unit
    }

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
}
