package io.github.tuthan.paddock.discovery

import io.github.tuthan.paddock.hostprofile.HostProfile
import io.github.tuthan.paddock.net.Ipv4Subnet
import io.github.tuthan.paddock.ports.DownReason
import io.github.tuthan.paddock.ports.LanPath
import io.github.tuthan.paddock.wake.WakeTarget
import java.net.Inet4Address
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After

/** Where a machine whose LAN address stopped answering is looked for, in which order, what decides it is the machine, and when nothing runs. */
class MachineRelocatorTest {
    private fun ip(s: String) = Ipv4Subnet.literal(s)!!

    // ---- the rules ------------------------------------------------------------------------------------------------------------------------------

    private val alpha = HostProfile("alpha", "Alpha", "192.168.42.86", 22, "jdoe", detectedName = "labbox")
    private val home = Ipv4Subnet(ip("192.168.42.2"), 24)

    @Test fun onlyFailuresAMoveCausesAreWorthALook() {
        for (r in listOf(DownReason.Timeout, DownReason.Network("No route to host"), DownReason.HostKeyChanged)) assertTrue(RelocateRules.worthLooking(r), "$r")
        for (r in listOf(DownReason.AuthFailed, DownReason.Refused, DownReason.PermissionDenied, DownReason.LocalNetworkTimeout, DownReason.Closed,
            DownReason.KeyUnavailable, DownReason.HostKeysUnreadable)) assertFalse(RelocateRules.worthLooking(r), "$r")
    }

    @Test fun onlyAMachineSavedAtALanAddressIsMovedNeverANameOrATailnetAddress() {
        for (h in listOf("192.168.42.86", "10.0.0.5", "172.16.3.4", "172.31.0.9", "169.254.10.2")) assertTrue(RelocateRules.movable(alpha.copy(host = h)), h)
        for (h in listOf("100.101.102.103", "172.32.0.1", "8.8.8.8", "labbox.local", "box.example.ts.net", "fe80::1")) assertFalse(RelocateRules.movable(alpha.copy(host = h)), h)
    }

    @Test fun theNamesAskedAreTheHostnameDotLocalAndBareAndNoneBeforeItWasRead() {
        assertEquals(listOf("labbox.local", "labbox"), RelocateRules.names(alpha))
        assertEquals(emptyList(), RelocateRules.names(alpha.copy(detectedName = null)))
    }

    @Test fun anAnnouncementNamesTheMachineByItsHostnameOrItsHardwareAddress() {
        val at = ip("192.168.42.120")
        assertTrue(RelocateRules.announcedFor(NsdService("labbox", at, 22), "labbox", null))
        assertTrue(RelocateRules.announcedFor(NsdService("LABBOX", at, 22), "labbox", null), "case does not matter")
        assertTrue(RelocateRules.announcedFor(NsdService("labbox [02:00:5e:10:00:01]", at, 9, nameOnly = true), "labbox", null))
        assertTrue(RelocateRules.announcedFor(NsdService("renamed [02:00:5e:10:00:01]", at, 9, nameOnly = true), "labbox", "02:00:5e:10:00:01"), "by the hardware address")
        assertFalse(RelocateRules.announcedFor(NsdService("renamed [02:00:5e:10:00:01]", at, 22), "labbox", "02:00:5e:10:00:01"), "an SSH service's name is not a workstation's")
        assertFalse(RelocateRules.announcedFor(NsdService("labbox-2", at, 22), "labbox", null))
        assertFalse(RelocateRules.announcedFor(NsdService("labbox", at, 22), null, null), "nothing to compare before the hostname was read")
        assertTrue(RelocateRules.announcedFor(NsdService("lab​box", at, 22), "labbox", null), "the name is compared as it would be shown")
    }

    @Test fun candidatesAreOnThisSubnetEachOnceNeverThePhoneTheSavedAddressOrOneTried() {
        val found = listOf("192.168.42.120", "192.168.42.2", "192.168.42.0", "192.168.42.255", "192.168.42.86", "10.0.0.5", "192.168.42.120", "192.168.42.7", "192.168.42.9").map(::ip)
        assertEquals(listOf("192.168.42.120", "192.168.42.9"), RelocateRules.candidates(found, home, saved = "192.168.42.86", tried = setOf("192.168.42.7")).map { it.hostAddress })
    }

    @Test fun theSavedNetworkIsTheSubnetHoldingTheSavedAddress() {
        assertTrue(RelocateRules.onSavedNetwork(alpha, home))
        assertFalse(RelocateRules.onSavedNetwork(alpha, Ipv4Subnet(ip("10.20.0.14"), 24)))
    }

    @Test fun theBannerSaysWhatIsHappeningAndTheNoticeWhereItWent() {
        assertEquals("Paddock is looking for Alpha on this network, in case its address changed.", RelocateCopy.line(RelocateState.Looking, "Alpha"))
        assertTrue(RelocateCopy.line(RelocateState.NotFound(1), "Alpha")!!.endsWith("If its address changed, change it on its page in Machines."))
        assertEquals(
            "Paddock looked for Alpha by its name only. To search this network for it, turn on \"Search the network if it moves\" on its page in Machines, or change its address there.",
            RelocateCopy.line(RelocateState.NotFound(1, searchOff = true), "Alpha"),
        )
        assertNull(RelocateCopy.line(RelocateState.Moved("192.168.42.120", 1), "Alpha"))
        assertNull(RelocateCopy.line(null, "Alpha"))
        assertEquals("Found Alpha at 192.168.42.120 (it was at 192.168.42.86). Paddock connects there now.", RelocateCopy.moved(Relocation("alpha", "Alpha", "192.168.42.86", "192.168.42.120")))
    }

    // ---- the relocator --------------------------------------------------------------------------------------------------------------------------

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun stop() { scope.cancel() }

    private val now = AtomicLong(1_000)

    private inner class FakePorts : MachineRelocator.Ports {
        @Volatile var front = true
        @Volatile var grant = true
        @Volatile var pinned = true
        @Volatile var paths = listOf(LanPath("wifi", "wlan0", tunnel = false, subnets = listOf(home)))
        /** Where the machine really is now: the one address that presents its key. */
        @Volatile var keyAt: String? = "192.168.42.120"
        /** Addresses where some other machine answers with its own key. */
        val otherKey = HashSet<String>()
        /** The addresses of the other saved machines. */
        @Volatile var others: Set<String> = emptySet()
        val names = HashMap<String, List<String>>()
        @Volatile var announced: List<NsdService> = emptyList()
        @Volatile var scanHits: List<String> = emptyList()
        @Volatile var moveAllowed = true
        @Volatile var holdCheck: CompletableDeferred<Unit>? = null
        val asked = CopyOnWriteArrayList<String>()
        val checked = CopyOnWriteArrayList<String>()
        val moves = CopyOnWriteArrayList<String>()
        val scans = AtomicInteger()
        val listens = AtomicInteger()
        @Volatile var scannedPort = 0
        @Volatile var scannedCount = 0

        override val mayLook get() = front
        override fun lanPaths() = paths
        override fun grantMissing() = !grant
        override suspend fun hasPin(profile: HostProfile) = pinned
        override suspend fun resolve(name: String): List<Inet4Address> { asked += name; return names[name].orEmpty().map(::ip) }
        override fun announcements(): Flow<NsdService> { listens.incrementAndGet(); return flowOf(*announced.toTypedArray()) }
        override fun scan(path: LanPath, addresses: List<Inet4Address>, port: Int): Flow<ProbeEvent> {
            scans.incrementAndGet(); scannedPort = port; scannedCount = addresses.size
            return flow { for (h in scanHits) emit(ProbeEvent.Hit(ProbeHit(ip(h), port, "SSH-2.0-OpenSSH_9.9"))) }
        }
        override fun otherAddresses(id: String): Set<String> = others
        override suspend fun checkKey(profile: HostProfile, address: String): KeyCheck {
            checked += address; holdCheck?.await()
            return when { address == keyAt -> KeyCheck.Matches; address in otherKey -> KeyCheck.OtherKey; else -> KeyCheck.NoAnswer }
        }
        override suspend fun move(profile: HostProfile, address: String): Boolean { moves += address; return moveAllowed }
    }

    private val ports = FakePorts()
    private fun relocator(maxChecks: Int = 24) = MachineRelocator(scope, { now.get() }, ports, listenMillis = 200, resolveMillis = 200, maxChecks = maxChecks)

    private suspend fun MachineRelocator.ended(id: String = "alpha"): RelocateState =
        withTimeout(5_000) { states.first { val s = it[id]; s is RelocateState.NotFound || s is RelocateState.Moved }[id]!! }

    @Test fun foundByItsHostnameItIsMovedWithoutListeningOrScanning() = runBlocking<Unit> {
        ports.names["labbox.local"] = listOf("192.168.42.120")
        val r = relocator()
        r.lookFor(alpha, DownReason.Timeout)
        assertEquals(RelocateState.Moved("192.168.42.120", 1_000), r.ended())
        assertEquals(listOf("192.168.42.120"), ports.moves.toList())
        assertEquals(listOf("192.168.42.120"), ports.checked.toList())
        assertEquals(0, ports.listens.get()); assertEquals(0, ports.scans.get())
        val moved = r.moved.value!!
        assertEquals(listOf("alpha", "192.168.42.86", "192.168.42.120"), listOf(moved.id, moved.from, moved.to))
        r.consumeMoved(moved); assertNull(r.moved.value)
    }

    @Test fun aNameThatPointsAtAnotherDeviceIsNotTheMachineTheAnnouncementThatNamesItIs() = runBlocking<Unit> {
        // Anyone can answer for a name: only the key decides.
        ports.names["labbox.local"] = listOf("192.168.42.50")
        ports.announced = listOf(NsdService("printer", ip("192.168.42.60"), 22), NsdService("labbox", ip("192.168.42.120"), 22))
        val r = relocator()
        r.lookFor(alpha, DownReason.HostKeyChanged)
        assertEquals(RelocateState.Moved("192.168.42.120", 1_000), r.ended())
        assertEquals(listOf("192.168.42.50", "192.168.42.120"), ports.checked.toList(), "the announcement naming it comes before the other one")
        assertEquals(0, ports.scans.get())
    }

    @Test fun onTheSavedNetworkOtherAnnouncementsThenTheScanAreTriedOnTheSavedPort() = runBlocking<Unit> {
        ports.announced = listOf(NsdService("nas", ip("192.168.42.60"), 22), NsdService("labbox [aa:bb:cc:dd:ee:ff]", ip("192.168.42.61"), 9, nameOnly = true))
        ports.scanHits = listOf("192.168.42.60", "192.168.42.70", "192.168.42.120")
        ports.keyAt = "192.168.42.120"
        val r = relocator()
        r.lookFor(alpha.copy(port = 2222, detectedName = null, searchNetwork = true), DownReason.Network("Connection refused"))
        assertEquals(RelocateState.Moved("192.168.42.120", 1_000), r.ended())
        // The announced SSH service first, then the scan's answers (checked together, so in no fixed order); .60 is not checked twice.
        assertEquals("192.168.42.60", ports.checked.first())
        assertEquals(setOf("192.168.42.60", "192.168.42.70", "192.168.42.120"), ports.checked.toSet())
        assertEquals(3, ports.checked.size, "a workstation announcement is not an SSH service, and one address is checked once")
        assertEquals(1, ports.scans.get()); assertEquals(2222, ports.scannedPort); assertEquals(253, ports.scannedCount, "every host but the phone")
        assertTrue(ports.asked.isEmpty(), "no hostname read yet: no names asked")
    }

    @Test fun onAnotherNetworkNothingIsAskedByNameOrScannedAndOnlyTheAnnouncementNamingTheMachineIsChecked() = runBlocking<Unit> {
        ports.paths = listOf(LanPath("office", "wlan0", tunnel = false, subnets = listOf(Ipv4Subnet(ip("10.20.0.14"), 24))))
        ports.names["labbox.local"] = listOf("10.20.0.77")
        ports.announced = listOf(NsdService("build-server", ip("10.20.0.30"), 22))
        ports.scanHits = listOf("10.20.0.30")
        ports.keyAt = null
        // Even with the search switched on: it is for the network the machine lives on.
        val r = relocator()
        r.lookFor(alpha.copy(searchNetwork = true), DownReason.Timeout)
        assertTrue(r.ended() is RelocateState.NotFound)
        assertTrue(ports.asked.isEmpty(), "the machine's name is not told to another network's resolver or to everyone listening for mDNS")
        assertTrue(ports.checked.isEmpty(), "another network's machines are never connected to")
        assertEquals(0, ports.scans.get())
        assertTrue(ports.moves.isEmpty())
    }

    @Test fun theMachineAnnouncingItselfOnAnotherNetworkIsMoved() = runBlocking<Unit> {
        ports.paths = listOf(LanPath("office", "wlan0", tunnel = false, subnets = listOf(Ipv4Subnet(ip("10.20.0.14"), 24))))
        ports.announced = listOf(NsdService("labbox", ip("10.20.0.77"), 22), NsdService("build-server", ip("10.20.0.30"), 22))
        ports.keyAt = "10.20.0.77"
        val r = relocator()
        r.lookFor(alpha, DownReason.Timeout)
        assertEquals(RelocateState.Moved("10.20.0.77", 1_000), r.ended())
        assertEquals(listOf("10.20.0.77"), ports.checked.toList())
    }

    // ---- what the user allows, and what is left alone -------------------------------------------------------------------------------------------

    @Test fun withTheSearchSwitchOffOnlyTheMachinesOwnNameAndAnnouncementAreFollowedAndTheBannerSaysSo() = runBlocking<Unit> {
        ports.names["labbox.local"] = listOf("192.168.42.50")
        ports.announced = listOf(NsdService("nas", ip("192.168.42.60"), 22), NsdService("labbox", ip("192.168.42.61"), 22))
        ports.scanHits = listOf("192.168.42.70", "192.168.42.120")
        ports.keyAt = "192.168.42.120"
        val r = relocator()
        r.lookFor(alpha, DownReason.Timeout)
        assertEquals(RelocateState.NotFound(1_000, searchOff = true), r.ended())
        assertEquals(listOf("192.168.42.50", "192.168.42.61"), ports.checked.toList(), "the name's address and the announcement naming it; the nas and the scan are the user's to allow")
        assertEquals(0, ports.scans.get())
        assertTrue(ports.moves.isEmpty())
    }

    @Test fun withTheSearchSwitchOnTheMachineOffTheBeatenPathIsFound() = runBlocking<Unit> {
        ports.announced = listOf(NsdService("nas", ip("192.168.42.60"), 22))
        ports.scanHits = listOf("192.168.42.70", "192.168.42.120")
        val r = relocator()
        r.lookFor(alpha.copy(searchNetwork = true), DownReason.Timeout)
        assertEquals(RelocateState.Moved("192.168.42.120", 1_000), r.ended())
        assertEquals(1, ports.scans.get())
    }

    @Test fun theAddressesOfTheOtherSavedMachinesAreNeverLookedAt() = runBlocking<Unit> {
        ports.others = setOf("192.168.42.60", "192.168.42.70")
        ports.names["labbox.local"] = listOf("192.168.42.60")
        ports.announced = listOf(NsdService("nas", ip("192.168.42.60"), 22), NsdService("labbox", ip("192.168.42.70"), 22))
        ports.scanHits = listOf("192.168.42.60", "192.168.42.70", "192.168.42.80", "192.168.42.120")
        ports.keyAt = "192.168.42.120"
        val r = relocator()
        r.lookFor(alpha.copy(searchNetwork = true), DownReason.Timeout)
        assertEquals(RelocateState.Moved("192.168.42.120", 1_000), r.ended())
        assertEquals(setOf("192.168.42.80", "192.168.42.120"), ports.checked.toSet(), "no saved machine's sshd is knocked on, whatever announced or answered there")
    }

    @Test fun anAddressThatShowedAnotherMachinesKeyIsLeftAloneUntilTheWindowEndsAnAddressThatSaidNothingIsNot() = runBlocking<Unit> {
        ports.keyAt = null
        ports.otherKey += "192.168.42.70"
        ports.scanHits = listOf("192.168.42.70", "192.168.42.71")
        val r = relocator()
        val me = alpha.copy(detectedName = null, searchNetwork = true)
        r.lookFor(me, DownReason.Timeout)
        r.ended()
        assertEquals(setOf("192.168.42.70", "192.168.42.71"), ports.checked.toSet())
        ports.checked.clear()
        now.addAndGet(MachineRelocator.COOLDOWN_MILLIS)
        look(r, me)
        assertEquals(listOf("192.168.42.71"), ports.checked.toList(), "the other machine is not asked again, the silent address is")
        ports.checked.clear()
        // After the window, and after a network change, what answered with another key is asked again.
        now.addAndGet(MachineRelocator.MAX_COOLDOWN_MILLIS + MachineRelocator.REJECT_MILLIS)
        look(r, me)
        assertEquals(setOf("192.168.42.70", "192.168.42.71"), ports.checked.toSet(), "the window ended")
        ports.checked.clear()
        look(r, me, afterNetworkChange = true)
        assertEquals(setOf("192.168.42.70", "192.168.42.71"), ports.checked.toSet())
    }

    /** One more look at [me], once the cooldown has passed: waits for it to start and to end. */
    private suspend fun look(r: MachineRelocator, me: HostProfile, afterNetworkChange: Boolean = false) {
        val before = ports.listens.get()
        if (afterNetworkChange) r.networkChanged()
        r.lookFor(me, DownReason.Timeout)
        withTimeout(5_000) { while (ports.listens.get() == before) kotlinx.coroutines.delay(10) }
        r.ended()
    }

    /** Whether [me] is looked for again now: the listener is opened by every look that is not refused. */
    private suspend fun looksNow(r: MachineRelocator, me: HostProfile): Boolean {
        val before = ports.listens.get()
        r.lookFor(me, DownReason.Timeout)
        kotlinx.coroutines.delay(150)
        return ports.listens.get() != before
    }

    @Test fun eachLookThatFindsNothingDoublesTheWaitUpToTheCapAndNoMoreThanTwoLooksFallInTenMinutes() = runBlocking<Unit> {
        ports.keyAt = null
        val r = MachineRelocator(scope, { now.get() }, ports, cooldownMillis = 1_000, maxCooldownMillis = 3_000, listenMillis = 20, resolveMillis = 20)
        r.lookFor(alpha, DownReason.Timeout); r.ended()
        for (wait in listOf(1_000L, 2_000L, 3_000L, 3_000L)) {
            now.addAndGet(wait - 1)
            assertFalse(looksNow(r, alpha), "$wait ms is not over yet")
            now.addAndGet(1)
            // The look that follows ends in NotFound again and doubles the next wait.
            val before = ports.listens.get()
            r.lookFor(alpha, DownReason.Timeout)
            withTimeout(5_000) { while (ports.listens.get() == before) kotlinx.coroutines.delay(10) }
            r.ended()
        }
        assertEquals(5 * 60_000L, MachineRelocator.COOLDOWN_MILLIS, "two log lines a look, and fail2ban's aggressive mode bans at five in ten minutes: at most two looks fall in ten")
    }

    @Test fun connectingOpeningThePageAndAChangedAddressStartTheWaitingOver() = runBlocking<Unit> {
        ports.keyAt = null
        val r = relocator()
        r.lookFor(alpha, DownReason.Timeout); r.ended()
        now.addAndGet(MachineRelocator.COOLDOWN_MILLIS); look(r, alpha)
        // Two misses: the wait is twice as long now.
        now.addAndGet(MachineRelocator.COOLDOWN_MILLIS)
        assertFalse(looksNow(r, alpha))
        r.askAgain("alpha")
        assertTrue(looksNow(r, alpha), "opening the machine's page asks again at once")
        r.ended()
        now.addAndGet(MachineRelocator.COOLDOWN_MILLIS); look(r, alpha)
        r.connected("alpha"); now.addAndGet(MachineRelocator.COOLDOWN_MILLIS)
        assertTrue(looksNow(r, alpha), "after a connection the first wait is the short one again")
        r.ended()
        r.addressChanged("alpha")
        assertTrue(looksNow(r, alpha), "a new address gets a look of its own")
    }

    @Test fun nothingRunsForAFailureAMoveDoesNotCauseOrAMachineSavedByNameOrTailnetAddress() = runBlocking<Unit> {
        val r = relocator()
        r.lookFor(alpha, DownReason.AuthFailed)
        r.lookFor(alpha.copy(host = "100.101.102.103"), DownReason.Timeout)
        r.lookFor(alpha.copy(host = "labbox.local"), DownReason.Timeout)
        ports.front = false
        r.lookFor(alpha, DownReason.Timeout)
        assertTrue(r.states.value.isEmpty())
        assertTrue(ports.asked.isEmpty() && ports.checked.isEmpty())
    }

    @Test fun withoutAPinTheGrantOrALanNothingIsAskedOrChecked() = runBlocking<Unit> {
        for (setUp in listOf<FakePorts.() -> Unit>({ pinned = false }, { grant = false }, { paths = listOf(LanPath("vpn", "tun0", tunnel = true, subnets = listOf(home))) }, { paths = emptyList() })) {
            val p = FakePorts().apply(setUp)
            assertNull(MachineRelocator(scope, { now.get() }, p, listenMillis = 50, resolveMillis = 50).find(alpha).address)
            assertTrue(p.asked.isEmpty() && p.checked.isEmpty() && p.scans.get() == 0 && p.listens.get() == 0)
        }
    }

    @Test fun aLookIsNotRepeatedUntilTheCooldownOrANetworkChange() = runBlocking<Unit> {
        ports.keyAt = null
        val r = relocator()
        r.lookFor(alpha, DownReason.Timeout)
        assertTrue(r.ended() is RelocateState.NotFound)
        val listens = ports.listens.get()
        r.lookFor(alpha, DownReason.Timeout)
        kotlinx.coroutines.delay(100)
        assertEquals(listens, ports.listens.get(), "not again at once")
        assertTrue(r.states.value["alpha"] is RelocateState.NotFound)
        now.addAndGet(MachineRelocator.COOLDOWN_MILLIS)
        r.lookFor(alpha, DownReason.Timeout)
        withTimeout(5_000) { while (ports.listens.get() == listens) kotlinx.coroutines.delay(10) }
        r.ended()
        r.networkChanged()
        r.lookFor(alpha, DownReason.Timeout)
        withTimeout(5_000) { while (ports.listens.get() == listens + 1) kotlinx.coroutines.delay(10) }
    }

    @Test fun oneLookAtATimeAndConnectingAgainClearsTheBanner() = runBlocking<Unit> {
        ports.names["labbox.local"] = listOf("192.168.42.120")
        val hold = CompletableDeferred<Unit>()
        ports.holdCheck = hold
        val r = relocator()
        r.lookFor(alpha, DownReason.Timeout)
        withTimeout(5_000) { while (ports.checked.isEmpty()) kotlinx.coroutines.delay(10) }
        assertEquals(RelocateState.Looking, r.states.value["alpha"])
        r.lookFor(alpha, DownReason.Timeout)
        r.lookFor(alpha, DownReason.HostKeyChanged)
        hold.complete(Unit)
        r.ended()
        assertEquals(1, ports.checked.size)
        r.connected("alpha")
        assertTrue(r.states.value.isEmpty())
    }

    @Test fun aMoveTheBookRefusesLeavesTheUsersAddressAndSaysNothing() = runBlocking<Unit> {
        ports.names["labbox.local"] = listOf("192.168.42.120")
        ports.moveAllowed = false
        val r = relocator()
        r.lookFor(alpha, DownReason.Timeout)
        withTimeout(5_000) { while (ports.moves.isEmpty()) kotlinx.coroutines.delay(10) }
        withTimeout(5_000) { r.states.first { it.isEmpty() } }
        assertNull(r.moved.value)
    }

    @Test fun noMoreThanTheBudgetOfAddressesIsChecked() = runBlocking<Unit> {
        ports.scanHits = (10..60).map { "192.168.42.$it" }
        ports.keyAt = null
        val r = relocator(maxChecks = 8)
        r.lookFor(alpha.copy(detectedName = null, searchNetwork = true), DownReason.Timeout)
        assertTrue(r.ended() is RelocateState.NotFound)
        assertEquals(8, ports.checked.size)
    }

    @Test fun aResolverThatNeverAnswersOrAListenerThatFailsDoesNotStopTheLook() = runBlocking<Unit> {
        val stuck = object : MachineRelocator.Ports by ports {
            override suspend fun resolve(name: String): List<Inet4Address> = awaitCancellation()
            override fun announcements(): Flow<NsdService> = flow { throw SecurityException("no permission") }
        }
        ports.scanHits = listOf("192.168.42.120")
        val r = MachineRelocator(scope, { now.get() }, stuck, listenMillis = 100, resolveMillis = 100)
        r.lookFor(alpha.copy(searchNetwork = true), DownReason.Timeout)
        assertEquals(RelocateState.Moved("192.168.42.120", 1_000), r.ended())
    }

    @Test fun leavingThePhoneBeforeTheScanSkipsIt() = runBlocking<Unit> {
        val leaving = object : MachineRelocator.Ports by ports {
            override fun announcements(): Flow<NsdService> { ports.front = false; return flowOf() }
        }
        ports.scanHits = listOf("192.168.42.120")
        val r = MachineRelocator(scope, { now.get() }, leaving, listenMillis = 100, resolveMillis = 100)
        r.lookFor(alpha.copy(detectedName = null, searchNetwork = true), DownReason.Timeout)
        assertTrue(r.ended() is RelocateState.NotFound)
        assertEquals(0, ports.scans.get())
    }

    @Test fun forgettingAMachineStopsItsLookAndKeepsNothing() = runBlocking<Unit> {
        ports.names["labbox.local"] = listOf("192.168.42.120")
        ports.holdCheck = CompletableDeferred()
        val r = relocator()
        r.lookFor(alpha, DownReason.Timeout)
        withTimeout(5_000) { while (ports.checked.isEmpty()) kotlinx.coroutines.delay(10) }
        r.forget("alpha")
        assertTrue(r.states.value.isEmpty())
        kotlinx.coroutines.delay(100)
        assertTrue(ports.moves.isEmpty())
        assertTrue(r.states.value.isEmpty())
    }

    @Test fun aWorkstationAnnouncementWithTheMachinesHardwareAddressFindsItAfterARename() = runBlocking<Unit> {
        val mac = "02:00:5e:10:00:01"
        val withMac = alpha.copy(detectedName = "old-name", wake = WakeTarget(available = true, mac = mac, iface = "wlp0s20f3", capturedAtMillis = 5))
        ports.announced = listOf(NsdService("new-name [$mac]", ip("192.168.42.120"), 9, nameOnly = true))
        val r = relocator()
        r.lookFor(withMac, DownReason.Timeout)
        assertEquals(RelocateState.Moved("192.168.42.120", 1_000), r.ended())
        assertEquals(0, ports.scans.get())
    }
}
