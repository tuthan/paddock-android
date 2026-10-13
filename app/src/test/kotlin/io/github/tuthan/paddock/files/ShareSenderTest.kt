package io.github.tuthan.paddock.files

import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.ops.InMemoryJournalStore
import io.github.tuthan.paddock.ops.OperationJournal
import io.github.tuthan.paddock.ops.OperationKind
import io.github.tuthan.paddock.ops.Subject
import io.github.tuthan.paddock.ports.ExecLimits
import io.github.tuthan.paddock.ports.ExecResult
import io.github.tuthan.paddock.ports.LinkState
import io.github.tuthan.paddock.ports.SshSession
import io.github.tuthan.paddock.ports.StreamChannel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ShareSender] over a scripted session and the real [FileTransfers]: a shared file goes to a folder's inbox or the drop folder with no agent. The instruction-file
 * refusal (SEC-4) is checked at the wire: nothing is asked and nothing is opened. What the scripts do on a machine is `:core`'s own test.
 */
class ShareSenderTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun stop() { scope.cancel() }

    private val journal = OperationJournal(InMemoryJournalStore()) { 1_000L }
    private val profile = HostProfileId("m1")
    private val content = ByteArray(30_000) { (it * 11 + 1).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
    private val snapshot = Snapshot("snap1", "2026-10-11-143205-notes.txt", "text/plain", content.size.toLong(), sha)

    private class Wire(val argv: List<String>) : StreamChannel {
        val written = ByteArrayOutputStream()
        @Volatile var exit = 0
        @Volatile var failAfter: Long? = null
        @Volatile var hang = false
        override val stdout: Flow<ByteArray> = emptyFlow()
        override val stderr: Flow<ByteArray> = emptyFlow()
        override suspend fun write(bytes: ByteArray) {
            val limit = failAfter
            if (limit != null && written.size() + bytes.size > limit) throw IOException("the link was lost")
            written.write(bytes)
        }
        override suspend fun closeStdin() = Unit
        override suspend fun awaitExit(): Int { if (hang) awaitCancellation(); return exit }
        override suspend fun close() = Unit
        fun text() = written.toString(Charsets.UTF_8)
    }

    private class Link(val resolve: ExecResult) : SshSession {
        override val link: StateFlow<LinkState> = MutableStateFlow(LinkState.Up(0))
        val asked = CopyOnWriteArrayList<String>()
        val wires = CopyOnWriteArrayList<Wire>()
        /** The exit status of the n-th stream (0-based), 0 when not set. */
        @Volatile var exits: List<Int> = emptyList()
        @Volatile var failAfter: Long? = null
        @Volatile var hang = false
        override suspend fun exec(argv: List<String>, stdin: ByteArray?, limits: ExecLimits): ExecResult {
            asked += stdin?.toString(Charsets.UTF_8).orEmpty()
            return resolve
        }
        override suspend fun openStream(argv: List<String>): StreamChannel =
            Wire(argv).also { it.exit = exits.getOrElse(wires.size) { 0 }; it.failAfter = failAfter; it.hang = hang; wires += it }
        override suspend fun close() = Unit
    }

    private fun answer(exit: Int = 0, out: String = "/home/jdoe/Paddock\n") = ExecResult(exit, out.toByteArray(), ByteArray(0), false, false, Duration.ZERO)
    private fun sender(sources: (String) -> ByteSource? = { ByteSource { ByteArrayInputStream(content) } }) =
        ShareSender(scope, sources, maxBytes = 1_000_000, deadline = { 5.seconds })
    private fun transfers(link: Link) = FileTransfers(link, journal) { 1_000L }

    private suspend fun ShareSender.settled(): ShareSend {
        withTimeout(5_000) { while (state.value is ShareSend.Idle || state.value is ShareSend.Sending) delay(5) }
        return state.value
    }

    @Test fun aFileGoesToAFoldersInboxAndTheResultNamesTheWholePath() = runBlocking<Unit> {
        val link = Link(answer())
        val s = sender()
        s.toFolder(transfers(link), profile, "paddock-test", "/work/proj", snapshot)
        val done = s.settled() as ShareSend.Done
        assertTrue(done.message, done.ok)
        assertEquals("/work/proj/.paddock/inbox/${snapshot.name}", done.path)
        assertEquals(ShareSendCopy.SENT, done.message)
        val wire = link.wires.single()
        assertTrue(wire.text().startsWith("/work/proj\n${snapshot.name}\n${content.size}\n"))
        // The row is the profile's own, not an agent's: it holds no terminal and so blocks no later send (core's rule for a subject).
        val row = journal.records.value.single()
        assertEquals(OperationKind.Attachment, row.kind)
        assertTrue(Subject.isSubject(row.terminalId))
    }

    @Test fun aDropResolvesTheFolderFirstAndWritesUnderTheRealPathWithTheSendersOwnName() = runBlocking<Unit> {
        val link = Link(answer())
        val s = sender()
        s.toDrop(transfers(link), profile, "paddock-test", "~/Paddock", "My Notes (1).txt", snapshot)
        val done = s.settled() as ShareSend.Done
        assertTrue(done.message, done.ok)
        assertEquals("the typed value is what the machine resolves", "~/Paddock\n", link.asked.single())
        val name = (FileNames.dropName("My Notes (1).txt") as DropName.Accepted).name
        assertEquals("/home/jdoe/Paddock/$name", done.path)
        assertTrue("the real path, never the typed one, goes to the write", link.wires.single().text().startsWith("/home/jdoe/Paddock\n$name\n${content.size}\n"))
    }

    @Test fun anInstructionFileIsRefusedBeforeAnythingIsAskedOrOpened() = runBlocking<Unit> {
        for (shared in listOf("CLAUDE.md", "claude.md", "AGENTS.md", ".mcp.json", "settings.json", "CLAUDE.md.", " CLAUDE.md")) {
            val link = Link(answer())
            var opened = 0
            val s = sender { opened++; ByteSource { ByteArrayInputStream(content) } }
            s.toDrop(transfers(link), profile, "paddock-test", "~/Paddock", shared, snapshot)
            val done = s.settled() as ShareSend.Done
            assertFalse(shared, done.ok)
            assertNull(done.path)
            assertTrue(done.message, done.message.endsWith("Nothing was sent."))
            assertTrue("$shared: nothing was asked of the machine", link.asked.isEmpty())
            assertTrue("$shared: no channel was opened", link.wires.isEmpty())
            assertEquals("$shared: the file was not even opened", 0, opened)
            assertTrue("$shared: no journal row", journal.records.value.none { it.kind == OperationKind.Attachment && it.path?.endsWith(shared.trim()) == true })
        }
    }

    @Test fun aNameThatIsTakenIsRetriedUnderTheNextNameAndNeverOverwritten() = runBlocking<Unit> {
        val link = Link(answer()).also { it.exits = listOf(TransferRefusal.Exists.exit, TransferRefusal.Exists.exit, 0) }
        val s = sender()
        s.toDrop(transfers(link), profile, "paddock-test", "~/Paddock", "notes.txt", snapshot)
        val done = s.settled() as ShareSend.Done
        assertTrue(done.message, done.ok)
        assertEquals("/home/jdoe/Paddock/notes-3.txt", done.path)
        assertEquals(listOf("notes.txt", "notes-2.txt", "notes-3.txt"), link.wires.map { it.text().lines()[1] })
    }

    @Test fun fiveTakenNamesEndTheSendWithTheMachinesSentence() = runBlocking<Unit> {
        val link = Link(answer()).also { it.exits = List(10) { TransferRefusal.Exists.exit } }
        val s = sender()
        s.toDrop(transfers(link), profile, "paddock-test", "~/Paddock", "notes.txt", snapshot)
        val done = s.settled() as ShareSend.Done
        assertFalse(done.ok)
        assertEquals(ShareSender.MAX_TRIES, link.wires.size)
        assertTrue(done.message, done.message.contains(TransferRefusal.Exists.sentence))
    }

    @Test fun aRefusedFolderIsTheMachinesSentenceAndNoFileIsSent() = runBlocking<Unit> {
        for (why in listOf(TransferRefusal.Home, TransferRefusal.Hidden, TransferRefusal.Symlink)) {
            val link = Link(answer(exit = why.exit))
            val s = sender()
            s.toDrop(transfers(link), profile, "paddock-test", "~/.ssh", "notes.txt", snapshot)
            val done = s.settled() as ShareSend.Done
            assertFalse(done.ok)
            assertEquals(why.sentence, done.message)
            assertTrue(link.wires.isEmpty())
        }
    }

    @Test fun aLinkLostMidwayEndsTheSendAsNotSentAndSaysSoInWords() = runBlocking<Unit> {
        val link = Link(answer()).also { it.failAfter = 10_000 }
        val s = sender()
        s.toFolder(transfers(link), profile, "paddock-test", "/work/proj", snapshot)
        val done = s.settled() as ShareSend.Done
        assertFalse(done.ok)
        assertNull("a cut send names no place the file is", done.path)
        assertTrue(done.message.isNotBlank())
    }

    @Test fun aSnapshotThatIsGoneIsSaidAndNothingConnects() = runBlocking<Unit> {
        val link = Link(answer())
        val s = sender { null }
        s.toFolder(transfers(link), profile, "paddock-test", "/work/proj", snapshot)
        assertEquals(ShareSend.Done(snapshot.id, false, ShareSendCopy.GONE), s.settled())
        s.toDrop(transfers(link), profile, "paddock-test", "~/Paddock", "a.txt", snapshot)
        assertEquals(ShareSend.Done(snapshot.id, false, ShareSendCopy.GONE), s.settled())
        assertTrue(link.asked.isEmpty() && link.wires.isEmpty())
    }

    @Test fun oneSendAtATimeAndCancelLeavesNothingRunning() = runBlocking<Unit> {
        val link = Link(answer()).also { it.hang = true }
        val s = sender()
        s.toFolder(transfers(link), profile, "paddock-test", "/work/proj", snapshot)
        withTimeout(5_000) { while (link.wires.isEmpty()) delay(5) }
        assertTrue(s.busy)
        s.toFolder(transfers(link), profile, "paddock-test", "/work/other", snapshot)
        s.toDrop(transfers(link), profile, "paddock-test", "~/Paddock", "a.txt", snapshot)
        delay(100)
        assertEquals("the second and third were ignored", 1, link.wires.size)
        s.cancel("another-item")
        assertTrue("a cancel for another item leaves this send running", s.busy)
        s.cancel(snapshot.id)
        assertEquals(ShareSend.Idle, s.state.value)
        assertFalse(s.busy)
        s.clear(snapshot.id)
        assertEquals(ShareSend.Idle, s.state.value)
    }

    @Test fun aResultIsKeptUntilItIsReadAndClearDoesNotTouchARunningSend() = runBlocking<Unit> {
        val link = Link(answer())
        val s = sender()
        s.toFolder(transfers(link), profile, "paddock-test", "/work/proj", snapshot)
        assertTrue(s.settled() is ShareSend.Done)
        s.clear(snapshot.id)
        assertEquals(ShareSend.Idle, s.state.value)
    }

    // ---- a send and its result belong to the item that was sent ----

    private val other = Snapshot("snap2", "2026-10-11-143300-other.txt", "text/plain", content.size.toLong(), sha)
    private fun file(s: Snapshot) = SharedItem.File(s, senderName = s.name)

    @Test fun aResultNamesItsItemAndOnlyThatItemShowsItOrClearsIt() = runBlocking<Unit> {
        val link = Link(answer())
        val s = sender()
        s.toFolder(transfers(link), profile, "paddock-test", "/work/proj", snapshot)
        val done = s.settled() as ShareSend.Done
        assertEquals(snapshot.id, done.item)
        assertEquals("the item it was sent for sees its result", done, done.forItem(file(snapshot)))
        assertNull("a newer item never sees an earlier one's result", done.forItem(file(other)))
        assertNull("a text has no send", done.forItem(SharedItem.Text("a path")))
        assertNull("nothing waits, nothing shows", done.forItem(null))
        s.clear(other.id)
        s.cancel(other.id)
        assertEquals("another item's Done or close leaves this result alone", done, s.state.value)
        s.clear(snapshot.id)
        assertEquals(ShareSend.Idle, s.state.value)
    }

    /**
     * A cancel cannot stop a read that is already under way on a blocking stream. When that read returns, after the next send has begun, what it counted must
     * not land on the next send's progress: only the run in hand writes the state.
     */
    @Test fun aCancelledSendsLateProgressNeverLandsOnTheNextSend() = runBlocking<Unit> {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holdB = CountDownLatch(1)
        val slowA = ByteSource {
            object : InputStream() {
                override fun read(): Int = throw UnsupportedOperationException()
                override fun read(b: ByteArray, off: Int, len: Int): Int { entered.countDown(); release.await(); return len } // ignores the cancel, as a blocking read does
            }
        }
        val stuckB = ByteSource {
            object : InputStream() {
                override fun read(): Int = throw UnsupportedOperationException()
                override fun read(b: ByteArray, off: Int, len: Int): Int { holdB.await(); return -1 }
            }
        }
        val s = sender { id -> if (id == snapshot.id) slowA else stuckB }
        try {
            s.toFolder(transfers(Link(answer())), profile, "paddock-test", "/work/a", snapshot)
            assertTrue("A's read began", withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            s.cancel(snapshot.id)
            assertFalse(s.busy)
            // B goes to another machine: A's row is still in flight on this one's journal subject until A's read returns, and would hold a send here.
            s.toFolder(transfers(Link(answer())), HostProfileId("m2"), "paddock-test", "/work/b", other)
            withTimeout(5_000) { while (s.state.value !is ShareSend.Sending) delay(5) }
            assertEquals(ShareSend.Sending(other.id, other.name, 0, other.size), s.state.value)
            release.countDown() // A's read returns now, with bytes it counts
            delay(300)
            assertEquals("B's progress is still B's own", ShareSend.Sending(other.id, other.name, 0, other.size), s.state.value)
        } finally {
            release.countDown()
            holdB.countDown()
            s.cancel(other.id)
        }
    }

    @Test fun followStopsTheSendOfAnItemThatIsNoLongerWaitingAndForgetsItsResult() = runBlocking<Unit> {
        val inbox = ShareInbox()
        val s = sender()
        val following = scope.launch { s.follow(inbox.pending) }
        try {
            // B arrives while A is sending: A's send stops, and nothing of it is left to show under B.
            inbox.offer(file(snapshot))
            val hanging = Link(answer()).also { it.hang = true }
            s.toFolder(transfers(hanging), profile, "paddock-test", "/work/a", snapshot)
            withTimeout(5_000) { while (hanging.wires.isEmpty()) delay(5) }
            assertTrue(s.busy)
            inbox.offer(file(other))
            withTimeout(5_000) { while (s.busy || s.state.value != ShareSend.Idle) delay(5) }

            // B's own result stays while B waits; a newer item C forgets it, and C's chooser can never close it.
            s.toFolder(transfers(Link(answer())), profile, "paddock-test", "/work/b", other)
            val done = s.settled() as ShareSend.Done
            assertEquals(other.id, done.item)
            delay(100)
            assertEquals("the waiting item's result is kept", done, s.state.value)
            inbox.offer(SharedItem.Text("something else"))
            withTimeout(5_000) { while (s.state.value != ShareSend.Idle) delay(5) }
        } finally {
            following.cancel()
        }
    }
}
