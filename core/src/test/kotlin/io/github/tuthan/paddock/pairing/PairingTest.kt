package io.github.tuthan.paddock.pairing

import io.github.tuthan.paddock.ports.Clock
import io.github.tuthan.paddock.ports.JavaSockets
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SID = "abcdefghijklmnopqrstuv"
private const val KEY = "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBHbQmB6uL1YzM+8VwX0GdEoW0lQb8gq3cNtF8M+2a7uN7j1Qq2b3uY2WxX0kGhC2fK5m4r0m1Pz6vQ4n3TcQxK8= paddock@phone"

class PairingWireTest {
    @Test fun requestsAreOneAsciiLineWithTheVersionFirst() {
        assertEquals("paddock-pair/1 key $SID $KEY\n", String(PairingWire.keyRequest(SID, KEY)))
        assertEquals("paddock-pair/1 status $SID\n", String(PairingWire.statusRequest(SID)))
    }

    @Test fun aBadHandleOrKeyLineDoesNotBuild() {
        for (sid in listOf("", "short", "a".repeat(23), "abcdefghijklmnopqrstu!"))
            assertFailsWith<IllegalArgumentException>(sid) { PairingWire.keyRequest(sid, KEY) }
        for (line in listOf("", "a\nb", "a\rb", "café", "x".repeat(5000)))
            assertFailsWith<IllegalArgumentException> { PairingWire.keyRequest(SID, line) }
    }

    @Test fun everyReplyWordDecodesAndAnythingElseIsMalformed() {
        val words = mapOf("pending" to PairingReply.Pending, "ok" to PairingReply.Ok, "rejected" to PairingReply.Rejected, "expired" to PairingReply.Expired,
            "refused" to PairingReply.Refused, "busy" to PairingReply.Busy, "none" to PairingReply.None)
        for ((w, r) in words) assertEquals(r, PairingWire.decodeReply("paddock-pair/1 $w"), w)
        for (bad in listOf(null, "", "ok", "paddock-pair/2 ok", "paddock-pair/1", "paddock-pair/1 ok extra", "paddock-pair/1 OK", "paddock-pair/1 approved"))
            assertEquals(PairingReply.Malformed, PairingWire.decodeReply(bad), bad.toString())
    }
}

class PendingPairingStoreTest {
    private val pending = PendingPairing(SID, "box", 22, "SHA256:x", KEY, 1, 2, 3)

    @Test fun memoryAndFileStoresRoundTripAndClear() = runBlocking {
        val dir = Files.createTempDirectory("pdk-pending").toFile()
        try {
            for (store in listOf<PendingPairingStore>(MemoryPendingPairingStore(), FilePendingPairingStore(File(dir, "p/pending.json")))) {
                assertNull(store.load())
                store.save(pending)
                assertEquals(pending, store.load())
                store.save(null)
                assertNull(store.load())
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun writersRacingOnTheSameFileDoNotTripOverEachOthersTemporaryFile() = runBlocking {
        val dir = Files.createTempDirectory("pdk-pending").toFile()
        try {
            val store = FilePendingPairingStore(File(dir, "pending.json"))
            kotlinx.coroutines.coroutineScope { (1..200).map { i -> launch(Dispatchers.IO) { store.save(pending.copy(generation = i.toLong())) } } }
            assertNotNull(store.load())
            assertEquals(listOf("pending.json"), dir.list()!!.toList(), "no temporary file is left behind")
        } finally { dir.deleteRecursively() }
    }

    @Test fun anUnreadableFileIsNoRequest() = runBlocking {
        val dir = Files.createTempDirectory("pdk-pending").toFile()
        try {
            val f = File(dir, "pending.json").apply { writeText("{not json") }
            assertNull(FilePendingPairingStore(f).load())
        } finally { dir.deleteRecursively() }
    }
}

class PairingClientTest {
    private val servers = mutableListOf<ServerSocket>()

    @AfterTest fun close() { servers.forEach { runCatching { it.close() } } }

    /** A desktop stand-in: reads one line, records it, and answers [reply] (nothing when null), then closes. */
    private fun desktop(reply: String?, seen: MutableList<String> = mutableListOf()): Int {
        val s = ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1")).also { servers += it }
        thread(isDaemon = true) {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: Exception) { return@thread }
                val line = c.getInputStream().bufferedReader(Charsets.US_ASCII).readLine()
                seen += line.orEmpty()
                if (reply != null) c.getOutputStream().apply { write("$reply\n".toByteArray()); flush() }
                c.close()
            }
        }
        return s.localPort
    }

    private val client = PairingClient(JavaSockets(), connectTimeoutMillis = 500, replyTimeoutMillis = 500)

    @Test fun keyAndStatusReachTheDesktopAndItsWordComesBack() = runBlocking {
        val seen = mutableListOf<String>()
        val port = desktop("paddock-pair/1 pending", seen)
        assertEquals(ClientResult.Reply(PairingReply.Pending), client.key("127.0.0.1", port, SID, KEY))
        assertEquals(ClientResult.Reply(PairingReply.Pending), client.status("127.0.0.1", port, SID))
        assertEquals(listOf("paddock-pair/1 key $SID $KEY", "paddock-pair/1 status $SID"), seen)
    }

    @Test fun aClosedPortIsUnreachableAndNothingWasSent() = runBlocking {
        val r = client.key("127.0.0.1", 1, SID, KEY)
        assertTrue(r is ClientResult.Unreachable, r.toString())
    }

    @Test fun aConnectionThatAnswersNothingOrNonsenseIsALostReply() = runBlocking {
        assertEquals(ClientResult.NoReply, client.key("127.0.0.1", desktop(null), SID, KEY))
        assertEquals(ClientResult.NoReply, client.key("127.0.0.1", desktop("hello"), SID, KEY))
    }

    @Test fun anUnknownHostIsUnreachable() = runBlocking {
        assertTrue(client.status("no-such-host.invalid", 22, SID) is ClientResult.Unreachable)
    }
}

class PairingCoordinatorTest {
    private val now = AtomicLong(1_000)
    private val clock = Clock { now.get() }
    private val store = MemoryPendingPairingStore()
    private val scopes = mutableListOf<CoroutineScope>()

    @AfterTest fun close() { scopes.forEach { it.cancel() } }

    private class Script(val onKey: (Int) -> ClientResult, val onStatus: (Int) -> ClientResult) : PairingClient(JavaSockets()) {
        val calls = mutableListOf<String>()
        var keys = 0
        var statuses = 0
        override suspend fun key(host: String, port: Int, sid: String, keyLine: String): ClientResult { calls += "key"; return onKey(++keys) }
        override suspend fun status(host: String, port: Int, sid: String): ClientResult { calls += "status"; return onStatus(++statuses) }
    }

    private fun reply(r: PairingReply) = ClientResult.Reply(r)

    private fun coordinator(client: PairingClient, windowMillis: Long = 120_000, store: PendingPairingStore = this.store): PairingCoordinator {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        return PairingCoordinator(store, client, clock, scope, pollMillis = 1, windowMillis = windowMillis)
    }

    private suspend fun PairingCoordinator.start() = start("box", 4000, SID, KEY, "SHA256:abc")

    private suspend fun PairingCoordinator.terminal(): PairingState = withTimeout(5_000) { state.first { it.isTerminal } }

    @Test fun theRecordIsOnDiskBeforeTheFirstByteIsSent() = runBlocking {
        var onDisk: PendingPairing? = null
        val c = Script({ onDisk = store.saved; reply(PairingReply.Ok) }, { reply(PairingReply.Ok) })
        coordinator(c).also { it.start(); it.terminal() }
        assertNotNull(onDisk)
        assertEquals(SID, onDisk!!.sid)
    }

    @Test fun waitingThenTheOwnerApproves() = runBlocking {
        val c = Script({ reply(PairingReply.Pending) }, { n -> reply(if (n < 3) PairingReply.Pending else PairingReply.Ok) })
        val p = coordinator(c)
        p.start()
        assertTrue(p.terminal() is PairingState.Approved)
        assertEquals(listOf("key", "status", "status", "status"), c.calls)
    }

    @Test fun rejectedExpiredRefusedAndBusyAreEachFinal() = runBlocking {
        for ((word, check) in listOf<Pair<PairingReply, (PairingState) -> Boolean>>(
            PairingReply.Rejected to { it is PairingState.Rejected }, PairingReply.Expired to { it is PairingState.Expired },
            PairingReply.Refused to { it is PairingState.Refused }, PairingReply.Busy to { it is PairingState.Busy },
        )) {
            val c = Script({ reply(word) }, { reply(word) })
            val p = coordinator(c)
            p.start()
            assertTrue(check(p.terminal()), word.name)
            assertEquals(listOf("key"), c.calls, "no further request after a final answer")
        }
    }

    @Test fun aLostFirstReplyIsSettledByAskingStatusAndResendingOnlyWhenTheDesktopNeverGotIt() = runBlocking {
        val c = Script({ n -> if (n == 1) ClientResult.NoReply else reply(PairingReply.Pending) }, { n -> reply(if (n == 1) PairingReply.None else PairingReply.Ok) })
        val p = coordinator(c)
        p.start()
        assertTrue(p.terminal() is PairingState.Approved)
        assertEquals(listOf("key", "status", "key", "status"), c.calls)
    }

    @Test fun aClientThatThrowsIsAFailedExchangeNotTheEndOfTheLoop() = runBlocking {
        // A NoSuchMethodError on an old Android (a JDK-only method in the client) once killed the job and left the page on "Sending" for good.
        for (boom in listOf<() -> Nothing>({ throw IllegalStateException("boom") }, { throw NoSuchMethodError("toString(Charset)") })) {
            val c = Script({ now.addAndGet(50_000); boom() }, { now.addAndGet(50_000); boom() })
            val p = coordinator(c)
            p.start()
            val end = p.terminal()
            assertTrue(end is PairingState.CannotReach, "$end")
            assertTrue(c.calls.size >= 2, "it kept trying until the window ended: ${c.calls}")
        }
    }

    @Test fun aLostReplyThatTheDesktopHadReceivedIsNotResent() = runBlocking {
        val c = Script({ ClientResult.NoReply }, { reply(PairingReply.Ok) })
        val p = coordinator(c)
        p.start()
        assertTrue(p.terminal() is PairingState.Approved)
        assertEquals(listOf("key", "status"), c.calls)
    }

    @Test fun neverHearingBackFromAReachedDesktopEndsAsNotConfirmedNeverRetriedByItself() = runBlocking {
        val c = Script({ now.addAndGet(30_000); ClientResult.NoReply }, { now.addAndGet(30_000); ClientResult.NoReply })
        val p = coordinator(c)
        p.start()
        assertTrue(p.terminal() is PairingState.NotConfirmed)
    }

    @Test fun aDesktopThatIsNeverReachedEndsAsCannotReach() = runBlocking {
        val c = Script({ now.addAndGet(30_000); ClientResult.Unreachable("ConnectException") }, { ClientResult.Unreachable(null) })
        val p = coordinator(c)
        p.start()
        val s = p.terminal()
        assertTrue(s is PairingState.CannotReach && s.detail == "ConnectException", s.toString())
    }

    @Test fun aWindowThatEndsWhileWaitingIsExpired() = runBlocking {
        val c = Script({ reply(PairingReply.Pending) }, { now.addAndGet(60_000); reply(PairingReply.Pending) })
        val p = coordinator(c)
        p.start()
        assertTrue(p.terminal() is PairingState.Expired)
    }

    @Test fun aDesktopThatAnsweredAndThenWentQuietIsNotConfirmedNeverExpired() = runBlocking {
        // The owner approved and closed the popup: its listener is gone, so every later ask is unreachable. "The desktop did not approve in
        // time" would be false (the key may be authorized already); the truthful end is the one that offers Connect to find out.
        val c = Script({ reply(PairingReply.Pending) }, { now.addAndGet(60_000); ClientResult.Unreachable("ConnectException") })
        val p = coordinator(c)
        p.start()
        assertTrue(p.terminal() is PairingState.NotConfirmed)
    }

    @Test fun aDesktopStillSayingPendingAtTheEndOfTheWindowIsExpired() = runBlocking {
        val c = Script({ reply(PairingReply.Pending) }, { now.addAndGet(60_000); reply(PairingReply.Pending) })
        val p = coordinator(c)
        p.start()
        assertTrue(p.terminal() is PairingState.Expired)
    }

    /** A store whose write of [blockOn] (null clears) waits until [release] completes, and says when it got there. */
    private class GatedStore(private val blockOnClear: Boolean) : PendingPairingStore {
        @Volatile var saved: PendingPairing? = null
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        override suspend fun load() = saved
        override suspend fun save(pending: PendingPairing?) {
            if ((pending == null) == blockOnClear) { entered.complete(Unit); release.await() }
            saved = pending
        }
    }

    @Test fun aCancelThatIsStillBeingSavedStillDiscardsALateOk() = runBlocking {
        // The generation is raised before the record is cleared; with the order the other way round, an ok that arrives while the clear is
        // still being written would pass the generation check and turn Cancelled into Approved.
        val gated = GatedStore(blockOnClear = true)
        val late = CompletableDeferred<Unit>()
        val c = object : PairingClient(JavaSockets()) {
            override suspend fun key(host: String, port: Int, sid: String, keyLine: String): ClientResult { withContext(NonCancellable) { late.await() }; return ClientResult.Reply(PairingReply.Ok) }
        }
        val p = coordinator(c, store = gated)
        p.start()
        withTimeout(2_000) { p.state.first { it is PairingState.Sending } }
        val cancelling = launch(Dispatchers.Default) { p.cancel() }
        withTimeout(2_000) { gated.entered.await() }
        late.complete(Unit)
        delay(150)
        assertTrue(p.state.value is PairingState.Cancelled, "the ok that arrived during the clear changed nothing: ${p.state.value}")
        gated.release.complete(Unit)
        cancelling.join()
        assertNull(gated.saved)
    }

    @Test fun aStartWhoseWriteIsSlowCannotComeBackAfterACancel() = runBlocking {
        // start() writes the record, the user cancels while that write is still in flight: the cancel's clear must land after it, not before,
        // or the cancelled request is on disk for resume() to reopen.
        val gated = GatedStore(blockOnClear = false)
        val p = coordinator(Script({ reply(PairingReply.Pending) }, { reply(PairingReply.Pending) }), store = gated)
        val starting = launch(Dispatchers.Default) { p.start() }
        withTimeout(2_000) { gated.entered.await() }
        val cancelling = launch(Dispatchers.Default) { p.cancel() }
        delay(100)
        gated.release.complete(Unit)
        starting.join(); cancelling.join()
        assertNull(gated.saved, "a cancelled request is not left on disk")
        assertTrue(p.state.value is PairingState.Cancelled)
    }

    @Test fun cancelRaisesTheGenerationFirstSoALateOkStoresNothing() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val c = object : PairingClient(JavaSockets()) {
            override suspend fun key(host: String, port: Int, sid: String, keyLine: String): ClientResult { withContext(NonCancellable) { gate.await() }; return ClientResult.Reply(PairingReply.Ok) }
        }
        val p = coordinator(c)
        p.start()
        withTimeout(2_000) { p.state.first { it is PairingState.Sending } }
        assertNotNull(store.saved)
        p.cancel()
        assertTrue(p.state.value is PairingState.Cancelled)
        assertNull(store.saved)
        gate.complete(Unit)
        delay(100)
        assertTrue(p.state.value is PairingState.Cancelled, "a late ok changed nothing")
        assertNull(store.saved)
    }

    @Test fun startingAgainDiscardsTheEarlierRequestsLateReply() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        var first = true
        val c = object : PairingClient(JavaSockets()) {
            override suspend fun key(host: String, port: Int, sid: String, keyLine: String): ClientResult {
                if (first) { first = false; entered.complete(Unit); withContext(NonCancellable) { gate.await() }; return ClientResult.Reply(PairingReply.Ok) }
                return ClientResult.Reply(PairingReply.Rejected)
            }
        }
        val p = coordinator(c)
        p.start()
        withTimeout(2_000) { entered.await() }
        p.start()
        assertTrue(p.terminal() is PairingState.Rejected)
        gate.complete(Unit)
        delay(100)
        assertTrue(p.state.value is PairingState.Rejected, "the first request's late ok was discarded")
    }

    @Test fun afterARestartTheRequestOnDiskIsReopenedAndStatusIsAskedFirst() = runBlocking {
        store.save(PendingPairing(SID, "box", 4000, "SHA256:abc", KEY, 500, now.get() + 60_000, 7))
        val c = Script({ reply(PairingReply.Pending) }, { reply(PairingReply.Ok) })
        val p = coordinator(c)
        assertTrue(p.resume())
        assertTrue(p.terminal() is PairingState.Approved)
        assertEquals("status", c.calls.first())
    }

    @Test fun aRequestWhoseWindowHasPassedIsNotReopened() = runBlocking {
        store.save(PendingPairing(SID, "box", 4000, "SHA256:abc", KEY, 500, now.get() - 1, 7))
        val p = coordinator(Script({ error("no") }, { error("no") }))
        assertFalse(p.resume())
        assertNull(store.saved)
        assertTrue(p.state.value is PairingState.Idle)
    }

    @Test fun clearForgetsTheRequestAfterAConnect() = runBlocking {
        val p = coordinator(Script({ reply(PairingReply.Ok) }, { reply(PairingReply.Ok) }))
        p.start()
        p.terminal()
        p.clear()
        assertNull(store.saved)
        assertTrue(p.state.value is PairingState.Idle)
    }
}
