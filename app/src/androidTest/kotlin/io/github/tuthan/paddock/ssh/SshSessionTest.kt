package io.github.tuthan.paddock.ssh

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tuthan.paddock.hostkey.FileHostKeyStore
import io.github.tuthan.paddock.hostkey.HostKeyPolicy
import io.github.tuthan.paddock.hostkey.PinnedHostKey
import io.github.tuthan.paddock.ports.Clock
import io.github.tuthan.paddock.ports.DownReason
import io.github.tuthan.paddock.ports.ExecLimits
import io.github.tuthan.paddock.ports.LinkState
import io.github.tuthan.paddock.ports.SshSession
import java.io.File
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Transport tests against the throwaway sshd from tools/test-sshd.sh, driven by tools/run-transport-tests.sh. */
class SshSessionTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val host = args.getString("host", "10.0.2.2")
    private val port = args.getString("port", "2222").toInt()
    private val proxyPort = args.getString("proxyPort", "2223").toInt()
    private val controlPort = args.getString("controlPort", "2224").toInt()
    private val user = args.getString("user", "jdoe")
    private val hostFp = args.getString("hostFp")

    private val clock = Clock { System.currentTimeMillis() }
    private val storeFile = File(ctx.cacheDir, "pins-${UUID.randomUUID()}.json")
    private val store = FileHostKeyStore(storeFile)
    private val policy = HostKeyPolicy(store, clock::nowMillis)
    private val opened = mutableListOf<SshSession>()

    private val phone = PhoneKey("paddock-phone-key-transport-test")
    private fun phoneAuth(key: PhoneKey = phone) = key.getOrCreate().let { SshAuth.Phone(key.privateKey(), it.publicKey) }
    private fun target(p: Int = port, profile: String = "profile-1") = SshTarget(profile, host, p, user)
    private fun note(msg: String) { Log.i("TRANSPORT", msg); println("TRANSPORT $msg") }

    @After
    fun cleanUp() = runBlocking<Unit> { opened.forEach { runCatching { it.close() } }; storeFile.delete() }

    private suspend fun session(
        port: Int = this.port, gate: ConnectGate = ConnectGate.Open, keepalive: kotlin.time.Duration = 15.seconds, auth: SshAuth = phoneAuth(),
        channelWait: kotlin.time.Duration = 30.seconds,
    ): SshSession = SshlibConnector(policy, clock, gate, keepalive = keepalive, channelWait = channelWait)
        .connect(target(port), auth) { true }.also { opened += it }

    private fun msSince(t0: Long) = (System.nanoTime() - t0) / 1_000_000

    /** Step 0 of the script: mint the phone key and write the public line for the host to authorize. */
    @Test
    fun t0_exportPhoneKey() {
        File(ctx.getExternalFilesDir(null)!!, "transport.pub").writeText(phone.publicLine("paddock-transport-test") + "\n")
        note("T0 exported; backing=${phone.getOrCreate().backing}")
    }

    @Test
    fun negotiatesAClassicKeyExchangeAndAnAeadOrCtrCipher() = runBlocking<Unit> {
        val info = (session() as SshlibSession).negotiated()
        note("T negotiated $info")
        assertTrue(info, Regex("kex=(curve25519|ecdh-sha2|diffie-hellman)").containsMatchIn(info))
        assertTrue(info, Regex("cipher=(aes\\d+-gcm@openssh.com|aes\\d+-ctr)/").containsMatchIn(info))
    }

    @Test
    fun execKeepsExitStdoutAndStderrApart() = runBlocking<Unit> {
        val r = session().exec(listOf("sh", "-c", "echo out; echo err 1>&2; exit 3"))
        assertEquals(3, r.exit)
        assertEquals("out\n", String(r.stdout)); assertEquals("err\n", String(r.stderr))
        assertFalse(r.stdoutTruncated); assertFalse(r.stderrTruncated)
    }

    @Test
    fun outputPastTheLimitsIsTruncatedAndFlagged() = runBlocking<Unit> {
        val r = session().exec(
            listOf("sh", "-c", "head -c 2000000 /dev/zero | tr '\\0' x; head -c 100000 /dev/zero | tr '\\0' e 1>&2"),
            limits = ExecLimits(stdoutMax = 1 shl 20, stderrMax = 64 shl 10, deadline = 30.seconds),
        )
        assertEquals(1 shl 20, r.stdout.size); assertTrue(r.stdoutTruncated)
        assertEquals(64 shl 10, r.stderr.size); assertTrue(r.stderrTruncated)
        assertEquals(0, r.exit)
        note("T truncation ok in ${r.elapsed}")
    }

    @Test
    fun freeTextTravelsOnStdinNotAsAnArgument() = runBlocking<Unit> {
        val text = "free text with 'quotes', \$(echo pwned), `id`, ; rm -rf /, and a\nnewline\n"
        assertArrayEquals(text.toByteArray(), session().exec(listOf("cat"), stdin = text.toByteArray()).stdout)
    }

    @Test
    fun hostileArgumentsReachTheRemoteProgramUnchanged() = runBlocking<Unit> {
        val argv = listOf("printf", "%s\\n", "a b", "it's", "\$(echo pwned)", "`id`", "*", "a;b", "")
        val r = session().exec(argv)
        assertEquals(argv.drop(2).joinToString("\n") + "\n", String(r.stdout))
    }

    @Test
    fun argumentsWithLineBreaksNeverLeaveThePhone() = runBlocking<Unit> {
        val s = session()
        try { s.exec(listOf("echo", "a\nb")); fail("line break accepted") } catch (_: IllegalArgumentException) { }
        try { s.exec(listOf("echo", "a\u0000b")); fail("NUL accepted") } catch (_: IllegalArgumentException) { }
        assertEquals("still up\n", String(s.exec(listOf("echo", "still up")).stdout))
    }

    @Test
    fun threeChannelsRunConcurrently() = runBlocking<Unit> {
        val s = session(); val t0 = System.nanoTime()
        val out = (1..3).map { n -> async { String(s.exec(listOf("sh", "-c", "sleep 2; echo n$n")).stdout) } }.awaitAll()
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(listOf("n1\n", "n2\n", "n3\n"), out)
        assertTrue("took $ms ms", ms < 5_000)
        note("T concurrent3 in $ms ms")
    }

    @Test
    fun moreCommandsThanChannelSlotsQueueAndAllSucceed() = runBlocking<Unit> {
        val s = session(); val t0 = System.nanoTime()
        val out = (1..20).map { n -> async { String(s.exec(listOf("sh", "-c", "sleep 1; echo $n")).stdout).trim() } }.awaitAll()
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals((1..20).map { it.toString() }, out)
        // 20 one-second commands through 8 slots take three waves, not twenty seconds and not one.
        assertTrue("took $ms ms", ms in 2_800..8_000)
        note("T queue20 in $ms ms")
    }

    /** The deadline ends the call and closes the channel. It does not kill `sleep` on the host: there is no pty and no signal. */
    @Test
    fun aCommandPastItsDeadlineFailsOnTimeAndTheSessionSurvives() = runBlocking<Unit> {
        val s = session(); val t0 = System.nanoTime()
        try { s.exec(listOf("sleep", "30"), limits = ExecLimits(deadline = 2.seconds)); fail("no timeout") } catch (_: ExecTimedOut) { }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("timeout took $ms ms", ms in 1_800..5_000)
        assertEquals("alive\n", String(s.exec(listOf("echo", "alive")).stdout))
    }

    /** With the link stalled the channel never opens; the deadline still ends the call, because it covers opening. */
    @Test
    fun theDeadlineCoversOpeningTheChannel() = runBlocking<Unit> {
        val s = session(port = proxyPort)
        assertEquals("up\n", String(s.exec(listOf("echo", "up")).stdout))
        control("freeze")
        try {
            val t0 = System.nanoTime()
            try { s.exec(listOf("echo", "late"), limits = ExecLimits(deadline = 2.seconds)); fail("no timeout") } catch (_: ExecTimedOut) { }
            val ms = msSince(t0)
            note("T deadline with a stalled open after $ms ms")
            assertTrue("timeout took $ms ms", ms in 1_800..4_000)
        } finally { control("thaw") }
    }

    // ---- streams: cancellation is prompt and closing frees the slot ------------------------------------------

    @Test
    fun cancellingTheCollectorOfASilentStreamReturnsAtOnce() = runBlocking<Unit> {
        val ch = session().openStream(listOf("sleep", "60"))
        try {
            val collector = launch(Dispatchers.Default) { ch.stdout.collect { } }
            delay(300)
            val t0 = System.nanoTime(); collector.cancelAndJoin(); val ms = msSince(t0)
            note("T silent stream collector cancelled in $ms ms")
            assertTrue("cancel took $ms ms", ms < 1_000)
        } finally {
            val t0 = System.nanoTime(); ch.close()
            assertTrue("close took ${msSince(t0)} ms", msSince(t0) < 1_000)
        }
    }

    /**
     * More streams than slots, one after another: each is collected, cancelled and closed. A slot that stayed taken would make
     * the ninth open fail with ChannelsBusy after the 3 s wait. `cat` stands in for a silent subscription; it ends on the host
     * when the channel closes (`sleep 60` would not, and would hold one of sshd's ten sessions for a minute).
     */
    @Test
    fun closingAStreamReleasesItsSlotEveryTime() = runBlocking<Unit> {
        val s = session(channelWait = 3.seconds); val t0 = System.nanoTime()
        repeat(SshlibSession.MAX_CHANNELS + 4) { n ->
            val ch = s.openStream(listOf("cat"))
            try {
                val collector = launch(Dispatchers.Default) { ch.stdout.collect { } }
                delay(50)
                val c0 = System.nanoTime(); collector.cancelAndJoin()
                assertTrue("cancel $n took ${msSince(c0)} ms", msSince(c0) < 1_000)
            } finally { ch.close() }
        }
        note("T ${SshlibSession.MAX_CHANNELS + 4} streams opened, cancelled and closed in ${msSince(t0)} ms")
        assertEquals("still\n", String(s.exec(listOf("echo", "still")).stdout))
    }

    @Test
    fun aTimeoutAroundTheFirstLineOfASilentStreamFiresOnTime() = runBlocking<Unit> {
        val ch = session().openStream(listOf("cat"))
        try {
            val t0 = System.nanoTime()
            try { withTimeout(1.seconds) { ch.stdout.first() }; fail("a silent stream produced output") } catch (_: kotlinx.coroutines.TimeoutCancellationException) { }
            val ms = msSince(t0)
            note("T withTimeout(1 s) on a silent stream fired after $ms ms")
            assertTrue("timeout took $ms ms", ms in 900..1_800)
        } finally { ch.close() }
    }

    @Test
    fun streamsLeftOpenRunOutOfSlotsWithAClearErrorNotAHang() = runBlocking<Unit> {
        val s = session(channelWait = 2.seconds)
        val held = (1..SshlibSession.MAX_CHANNELS).map { s.openStream(listOf("cat")) }
        try {
            val t0 = System.nanoTime()
            try { s.openStream(listOf("cat")).close(); fail("a ninth channel opened") } catch (e: ChannelsBusy) { note("T ninth stream: ${e.message} after ${msSince(t0)} ms") }
            assertTrue("waited ${msSince(t0)} ms", msSince(t0) in 1_800..4_000)
        } finally { held.forEach { it.close() } }
        assertEquals("freed\n", String(s.exec(listOf("echo", "freed")).stdout))
    }

    @Test
    fun streamsDeliverOutputAndExitSeparately() = runBlocking<Unit> {
        val ch = session().openStream(listOf("sh", "-c", "read x; echo got:\$x; echo e 1>&2; exit 7"))
        ch.write("hello\n".toByteArray()); ch.closeStdin()
        val out = StringBuilder(); ch.stdout.collect { out.append(String(it)) }
        val err = StringBuilder(); ch.stderr.collect { err.append(String(it)) }
        assertEquals("got:hello\n", out.toString()); assertEquals("e\n", err.toString())
        assertEquals(7, ch.awaitExit())
        ch.close()
    }

    @Test
    fun linkReportsUpThenDownClosed() = runBlocking<Unit> {
        val s = session()
        assertTrue(s.link.value is LinkState.Up)
        s.close()
        assertEquals(DownReason.Closed, (s.link.value as LinkState.Down).reason)
        try { s.exec(listOf("echo", "x")); fail("exec on a closed session") } catch (e: io.github.tuthan.paddock.ssh.SessionDown) { assertEquals(DownReason.Closed, e.reason) }
    }

    // ---- host keys ----------------------------------------------------------------------------------------

    @Test
    fun firstContactAsksOncePinsAndLaterContactIsSilent() = runBlocking<Unit> {
        val asked = AtomicInteger(); var seenFp = ""
        val connector = SshlibConnector(policy, clock)
        connector.connect(target(), phoneAuth()) { p -> asked.incrementAndGet(); seenFp = p.fingerprint; assertEquals("ssh-ed25519", p.algorithm); true }.also { opened += it }
        assertEquals(1, asked.get())
        if (hostFp != null) assertEquals(hostFp, seenFp)
        assertTrue(store.find("profile-1") != null)
        connector.connect(target(), phoneAuth()) { fail("a pinned host must not ask again"); true }.also { opened += it }
    }

    @Test
    fun declineOnFirstContactConnectsNothingAndPinsNothing() = runBlocking<Unit> {
        try { SshlibConnector(policy, clock).connect(target(), phoneAuth()) { false }; fail("connected") }
        catch (e: ConnectFailure.HostKeyDeclined) { note("T declined ${e.presented.algorithm} ${e.presented.fingerprint}") }
        assertNull(store.find("profile-1"))
    }

    @Test
    fun aChangedHostKeyBlocksBeforeAuthenticationAndShowsBothFingerprints() = runBlocking<Unit> {
        val stale = io.github.tuthan.paddock.hostkey.PresentedHostKey("ssh-ed25519", ByteArray(51) { 7 })
        policy.acceptUnknown("profile-1", "$host:$port", stale)
        val asked = AtomicInteger()
        try { SshlibConnector(policy, clock).connect(target(), phoneAuth()) { asked.incrementAndGet(); true }; fail("connected") }
        catch (e: ConnectFailure.HostKeyChanged) {
            assertEquals(stale.fingerprint, e.pin.fingerprint)
            assertTrue(e.presented.fingerprint != e.pin.fingerprint)
            assertEquals(DownReason.HostKeyChanged, e.reason)
            note("T changed pinned=${e.pin.fingerprint} presented=${e.presented.fingerprint}")
        }
        assertEquals(0, asked.get())
        assertEquals(stale.fingerprint, store.find("profile-1")!!.fingerprint)
    }

    /** sshlib's key-exchange timer runs through the question; on first contact it gets the long bound, and the pin waits for KEX. */
    @Test
    fun aFirstContactAnswerSlowerThanTheConnectTimeoutStillConnectsAndPinsOnlyAfterTheKeyExchange() = runBlocking<Unit> {
        val connector = SshlibConnector(policy, clock, connectTimeout = 2.seconds)
        var pinnedWhileAsking: Boolean? = null
        val t0 = System.nanoTime()
        val s = connector.connect(target(), phoneAuth()) { delay(4_000); pinnedWhileAsking = store.find("profile-1") != null; true }.also { opened += it }
        note("T first-trust answered after 4 s with a 2 s connect timeout: connected in ${msSince(t0)} ms")
        assertEquals(false, pinnedWhileAsking)
        assertTrue(store.find("profile-1") != null)
        assertEquals("ok\n", String(s.exec(listOf("echo", "ok")).stdout))
    }

    @Test
    fun aFirstContactQuestionIsWithdrawnWhenItsTimerRunsOutAndNothingIsPinned() = runBlocking<Unit> {
        val connector = SshlibConnector(policy, clock, connectTimeout = 2.seconds, firstContactTimeout = 3.seconds)
        val withdrawn = CompletableDeferred<Unit>()
        val t0 = System.nanoTime()
        try {
            connector.connect(target(), phoneAuth()) { try { awaitCancellation() } finally { withdrawn.complete(Unit) } }
            fail("connected")
        } catch (e: ConnectFailure.TimedOut) { }
        val ms = msSince(t0)
        withTimeout(1_000) { withdrawn.await() }
        note("T unanswered first-trust question withdrawn when the timer ran out after $ms ms")
        assertTrue("gave up after $ms ms", ms in 2_800..6_000)
        assertNull(store.find("profile-1"))
    }

    /** The app went to the background mid-question: the connect is cancelled, the dialog goes, and a stale tap answers nothing. */
    @Test
    fun cancellingAConnectWithdrawsItsQuestionAndOnlyTheRetrysOwnAnswerPins() = runBlocking<Unit> {
        val broker = io.github.tuthan.paddock.hostkey.HostKeyBroker()
        val connector = SshlibConnector(policy, clock)
        val ask: suspend (io.github.tuthan.paddock.hostkey.PresentedHostKey) -> Boolean = { p -> broker.askFirstTrust("profile-1", "$host:$port", p) }
        val attempt = launch(Dispatchers.Default) { connector.connect(target(), phoneAuth(), ask).also { opened += it } }
        withTimeout(10_000) { while (broker.firstTrust.value == null) delay(20) }
        val stale = broker.firstTrust.value!!.id
        val t0 = System.nanoTime(); attempt.cancelAndJoin(); val ms = msSince(t0)
        withTimeout(1_000) { while (broker.firstTrust.value != null) delay(10) }
        note("T cancelled connect returned after $ms ms and its question was withdrawn")
        assertTrue("cancel took $ms ms", ms < 1_000)
        assertNull(store.find("profile-1"))
        assertFalse(broker.answerFirstTrust(stale, true))

        val retry = async(Dispatchers.Default) { connector.connect(target(), phoneAuth(), ask) }
        withTimeout(10_000) { while (broker.firstTrust.value == null) delay(20) }
        assertTrue(broker.firstTrust.value!!.id != stale)
        assertTrue(broker.answerFirstTrust(broker.firstTrust.value!!.id, true))
        val s = retry.await().also { opened += it }
        assertTrue(store.find("profile-1") != null)
        assertEquals("ok\n", String(s.exec(listOf("echo", "ok")).stdout))
    }

    // ---- authentication -----------------------------------------------------------------------------------

    /** A Keystore failure while signing is not a network error and must not leave the socket open with the link Connecting. */
    @Test
    fun aSigningFailureClosesTheConnectionAndIsKeyUnavailable() = runBlocking<Unit> {
        val broken = object : java.security.PrivateKey {
            override fun getAlgorithm() = "EC"; override fun getFormat(): String? = null; override fun getEncoded(): ByteArray? = null
        }
        val t0 = System.nanoTime()
        try { SshlibConnector(policy, clock).connect(target(), SshAuth.Phone(broken, phone.getOrCreate().publicKey)) { true }; fail("connected") }
        catch (e: ConnectFailure.KeyUnavailable) {
            assertEquals(DownReason.KeyUnavailable, e.reason)
            note("T signing failure -> KeyUnavailable (${e.cause?.javaClass?.simpleName}) after ${msSince(t0)} ms")
        }
    }

    @Test
    fun anUnauthorizedKeyIsAuthFailedNotAHang() = runBlocking<Unit> {
        val stranger = PhoneKey("paddock-phone-key-unauthorized")
        try { SshlibConnector(policy, clock).connect(target(), phoneAuth(stranger)) { true }; fail("connected") }
        catch (e: ConnectFailure.AuthFailed) { assertEquals(DownReason.AuthFailed, e.reason) }
        finally { runCatching { stranger.delete() } }
    }

    @Test
    fun anImportedPassphraseProtectedEd25519KeyConnects() = runBlocking<Unit> {
        val key = File("/data/local/tmp/spike_imported"); assumeTrue("imported key not pushed", key.canRead())
        val auth = SshAuth.Imported(key.readText().toCharArray(), "spikepass")
        val s = session(auth = auth)
        assertTrue("the key text is wiped once the key pair is built", auth.pem.all { it == '\u0000' })
        assertEquals("imp\n", String(s.exec(listOf("echo", "imp")).stdout))
    }

    @Test
    fun aWrongPassphraseIsAFailureNotACrash() = runBlocking<Unit> {
        val key = File("/data/local/tmp/spike_imported"); assumeTrue("imported key not pushed", key.canRead())
        try { session(auth = SshAuth.Imported(key.readText().toCharArray(), "nope")); fail("connected") }
        catch (e: ConnectFailure.BadKey) { assertEquals(DownReason.AuthFailed, e.reason); note("T wrong passphrase -> BadKey: ${e.detail}") }
    }

    // ---- the gate -----------------------------------------------------------------------------------------

    @Test
    fun aRefusingGateStopsBeforeAnySocketOpens() = runBlocking<Unit> {
        val gate = ConnectGate { DownReason.PermissionDenied }
        try { SshlibConnector(policy, clock, gate).connect(target(), phoneAuth()) { true }; fail("connected") }
        catch (e: ConnectFailure.Refused) { assertEquals(DownReason.PermissionDenied, e.reason) }
        assertNull(store.find("profile-1"))
    }

    /** The server never answers (stalled proxy) and the gate reports the grant missing: the timeout carries the local-network hint. */
    @Test
    fun aConnectTimeoutWhereTheGateHintsCarriesTheLocalNetworkReason() = runBlocking<Unit> {
        // A pin makes the short timer apply (first contact would wait for a person).
        policy.acceptUnknown("profile-1", "$host:$proxyPort", io.github.tuthan.paddock.hostkey.PresentedHostKey("ssh-ed25519", ByteArray(51) { 7 }))
        val hinting = object : ConnectGate {
            override suspend fun check(target: SshTarget): DownReason? = null
            override suspend fun timeoutHint(target: SshTarget): DownReason? = DownReason.LocalNetworkTimeout
        }
        control("freeze")
        try {
            val t0 = System.nanoTime()
            try { SshlibConnector(policy, clock, hinting, connectTimeout = 2.seconds).connect(target(proxyPort), phoneAuth()) { true }; fail("connected") }
            catch (e: ConnectFailure.TimedOut) { assertEquals(DownReason.LocalNetworkTimeout, e.reason); note("T stalled connect -> ${e.reason} after ${msSince(t0)} ms") }
        } finally { control("thaw") }
        // Without a hint the same timeout stays a plain Timeout.
        control("freeze")
        try {
            try { SshlibConnector(policy, clock, connectTimeout = 2.seconds).connect(target(proxyPort), phoneAuth()) { true }; fail("connected") }
            catch (e: ConnectFailure.TimedOut) { assertEquals(DownReason.Timeout, e.reason) }
        } finally { control("thaw") }
    }

    // ---- dead link -----------------------------------------------------------------------------------------

    private fun control(cmd: String) = Socket(host, controlPort).use { it.getOutputStream().write("$cmd\n".toByteArray()); it.getInputStream().read(ByteArray(64)) }

    @Test
    fun aStalledLinkIsDeclaredDownWithin30SecondsAndCloseDoesNotHang() = runBlocking<Unit> {
        val s = session(port = proxyPort)
        assertEquals("up\n", String(s.exec(listOf("echo", "up")).stdout))
        control("freeze"); val t0 = System.nanoTime()
        val down = try {
            withTimeout(60_000) { s.link.first { it is LinkState.Down } as LinkState.Down }
        } finally { control("thaw") }
        val ms = (System.nanoTime() - t0) / 1_000_000
        note("T dead link detected after $ms ms reason=${down.reason}")
        assertEquals(DownReason.Timeout, down.reason)
        assertTrue("detected after $ms ms", ms <= 30_000)
        val c0 = System.nanoTime(); s.close()
        assertTrue("close took ${(System.nanoTime() - c0) / 1_000_000} ms", (System.nanoTime() - c0) / 1_000_000 < 2_000)
        try { s.exec(listOf("echo", "x")); fail("exec on a dead link") } catch (_: io.github.tuthan.paddock.ssh.SessionDown) { }
    }
}
