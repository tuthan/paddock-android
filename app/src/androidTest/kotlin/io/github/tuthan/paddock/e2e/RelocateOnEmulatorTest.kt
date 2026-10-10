package io.github.tuthan.paddock.e2e

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tuthan.paddock.discovery.MachineRelocator
import io.github.tuthan.paddock.discovery.RelocateState
import io.github.tuthan.paddock.hostkey.FileHostKeyStore
import io.github.tuthan.paddock.hostkey.HostKeyPolicy
import io.github.tuthan.paddock.hostkey.PinnedHostKey
import io.github.tuthan.paddock.hostprofile.HostProfile
import io.github.tuthan.paddock.net.AndroidLanPaths
import io.github.tuthan.paddock.net.AndroidRelocatorPorts
import io.github.tuthan.paddock.net.Ipv4Subnet
import io.github.tuthan.paddock.ports.Clock
import io.github.tuthan.paddock.ports.DownReason
import io.github.tuthan.paddock.ssh.ConnectFailure
import io.github.tuthan.paddock.ssh.LocalNetworkGate
import io.github.tuthan.paddock.ssh.PhoneKey
import io.github.tuthan.paddock.ssh.SshAuth
import io.github.tuthan.paddock.ssh.SshTarget
import io.github.tuthan.paddock.ssh.SshlibConnector
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A machine whose address changed, on the emulator's own network, through the parts the app uses ([AndroidRelocatorPorts]: the phone's networks,
 * the grant, mDNS, the scan bound to the network, the key-only check). The throwaway sshd from tools/test-sshd.sh is the machine, reached at
 * 10.0.2.2; the machine is saved at an address on the same subnet where nothing answers. Run by paddock-harness/run-transport-tests.sh with this class
 * as the filter; the run's sshd log should hold no accepted sign-in.
 */
class RelocateOnEmulatorTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val host = args.getString("host", "10.0.2.2")
    private val port = args.getString("port", "2222").toInt()
    private val user = args.getString("user", "jdoe")

    private val clock = Clock { System.currentTimeMillis() }
    private val storeFile = File(ctx.cacheDir, "pins-${UUID.randomUUID()}.json")
    private val store = FileHostKeyStore(storeFile)
    private val gate = LocalNetworkGate(ctx)
    private val connector = SshlibConnector(HostKeyPolicy(store, clock::nowMillis), clock, gate)
    private val lanPaths = AndroidLanPaths(ctx)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val moves = CopyOnWriteArrayList<String>()
    private val relocator = MachineRelocator(
        scope, clock, AndroidRelocatorPorts(ctx, lanPaths, gate, store, connector, inFront = { true }, othersOf = { emptySet() }, moveTo = { _, a -> moves += a; true }),
    )

    @After fun cleanUp() { scope.cancel(); storeFile.delete() }

    /** An address on the phone's LAN subnet that nothing answers on: where the machine "was". */
    private fun deadAddress(): String {
        val subnet = lanPaths.paths().filter { !it.tunnel }.first { it.hasIpv4 }.subnets.first()
        Log.i("E2E", "RELOCATE subnet $subnet, paths ${lanPaths.paths().map { "${it.interfaceName} ${it.subnets}" }}")
        assertTrue("the test sshd's address $host must be on the phone's subnet $subnet", subnet.contains(Ipv4Subnet.literal(host)!!))
        return Ipv4Subnet.fromValue(Ipv4Subnet.value(subnet.network) + 99).hostAddress!!
    }

    private suspend fun pinTheServersKey(profile: String) {
        val phone = PhoneKey("paddock-phone-key-transport-test")
        val auth = phone.getOrCreate().let { SshAuth.Phone(phone.privateKey(), it.publicKey) }
        val r = runCatching { connector.connect(SshTarget(profile, host, port, "paddock-no-such-user"), auth) { true } }
        assertTrue("first contact should pin the key and be refused: ${r.exceptionOrNull()}", r.exceptionOrNull() is ConnectFailure.AuthFailed)
    }

    private suspend fun ended(id: String): RelocateState =
        withTimeout(60_000) { relocator.states.first { val s = it[id]; s is RelocateState.NotFound || s is RelocateState.Moved }[id]!! }

    @Test fun aMachineThatMovedIsFoundOnThisNetworkByItsKey() = runBlocking<Unit> {
        pinTheServersKey("rel-1")
        // The scan is the user's to allow, machine by machine: this machine has it switched on.
        val saved = HostProfile("rel-1", "Moved machine", deadAddress(), port, user, searchNetwork = true)
        val t0 = System.nanoTime()
        relocator.lookFor(saved, DownReason.Timeout)
        val state = ended("rel-1")
        Log.i("E2E", "RELOCATE found=$state in ${(System.nanoTime() - t0) / 1_000_000} ms, saved at ${saved.host}")
        assertEquals(RelocateState.Moved(host, (state as RelocateState.Moved).atMillis), state)
        assertEquals(listOf(host), moves.toList())
    }

    @Test fun withTheSearchSwitchOffTheNetworkIsNotScannedAndTheBannerSaysSo() = runBlocking<Unit> {
        pinTheServersKey("rel-3")
        val saved = HostProfile("rel-3", "Switch off", deadAddress(), port, user)
        relocator.lookFor(saved, DownReason.Timeout)
        val state = ended("rel-3")
        Log.i("E2E", "RELOCATE switch off: $state")
        assertEquals(RelocateState.NotFound((state as RelocateState.NotFound).atMillis, searchOff = true), state)
        assertTrue("nothing was found, so nothing moved even though the machine is on this network", moves.isEmpty())
    }

    @Test fun aMachineWhoseKeyNobodyHereHoldsIsNotMoved() = runBlocking<Unit> {
        store.save(PinnedHostKey("rel-2", "x", "ssh-ed25519", ByteArray(51).also { java.util.Random(3).nextBytes(it) }, "SHA256:other", 1L, 1L))
        val saved = HostProfile("rel-2", "Elsewhere", deadAddress(), port, user, searchNetwork = true)
        relocator.lookFor(saved, DownReason.Timeout)
        assertTrue(ended("rel-2") is RelocateState.NotFound)
        assertTrue(moves.isEmpty())
    }
}
