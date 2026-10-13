package io.github.tuthan.paddock.files

import io.github.tuthan.paddock.cli.argvToCommand
import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.identity.TargetRef
import io.github.tuthan.paddock.identity.TerminalKey
import io.github.tuthan.paddock.ops.InMemoryJournalStore
import io.github.tuthan.paddock.ops.OperationJournal
import io.github.tuthan.paddock.ops.OperationKind
import io.github.tuthan.paddock.ops.OperationOutcome
import io.github.tuthan.paddock.ops.OperationResult
import io.github.tuthan.paddock.ops.Subject
import io.github.tuthan.paddock.ports.ExecLimits
import io.github.tuthan.paddock.ports.ExecResult
import io.github.tuthan.paddock.ports.LinkState
import io.github.tuthan.paddock.ports.SshSession
import io.github.tuthan.paddock.ports.StreamChannel
import io.github.tuthan.paddock.relay.FakeSession
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * [FileTransfers] against a scripted [SshSession]: the journal ahead of the first byte, the exit status read as acknowledged, rejected or unknown, the
 * cuts, the deadline, a cancel, what a cut attachment holds, the questions and the read. The scripts themselves are run in [TransferScriptsTest].
 * Nothing here asserts on a file's content or name in a message.
 */
class FileTransfersTest {
    private var now = 10_000_000L
    private val store = InMemoryJournalStore()
    private val journal = OperationJournal(store) { now }
    private val key = TerminalKey(TargetRef(HostProfileId("h1"), "paddock-test", "term_1"), epoch = 1)
    private val max = 1_000_000L

    /** One scripted stream: it records what was written, can fail or hang at chosen points, and answers [exit]. */
    private class Wire(val argv: List<String>) : StreamChannel {
        val written = ByteArrayOutputStream()
        @Volatile var stdinClosed = false
        @Volatile var closed = false
        @Volatile var exit = 0
        @Volatile var failAfterBytes: Long? = null
        @Volatile var hangOnExit = false
        @Volatile var out: List<ByteArray> = emptyList()
        @Volatile var onFirstWrite: () -> Unit = {}
        private var first = true
        override val stdout: Flow<ByteArray> = flow { out.forEach { emit(it) } }
        override val stderr: Flow<ByteArray> = emptyFlow()
        override suspend fun write(bytes: ByteArray) {
            if (first) { first = false; onFirstWrite() }
            val limit = failAfterBytes
            if (limit != null && written.size() + bytes.size > limit) { written.write(bytes, 0, (limit - written.size()).toInt()); throw IOException("the link was lost") }
            written.write(bytes)
        }
        override suspend fun closeStdin() { stdinClosed = true }
        override suspend fun awaitExit(): Int { if (hangOnExit) awaitCancellation(); return exit }
        override suspend fun close() { closed = true }
        fun text() = written.toString(Charsets.UTF_8)
    }

    private class Link(val answer: (List<String>, String) -> ExecResult = { _, _ -> FakeSession.result(0) }) : SshSession {
        override val link: StateFlow<LinkState> = MutableStateFlow(LinkState.Up(0))
        val streams = CopyOnWriteArrayList<Wire>()
        val execs = CopyOnWriteArrayList<Pair<List<String>, String>>()
        @Volatile var openFails = false
        @Volatile var script: (Wire) -> Unit = {}
        // As the real session does: an argv no login shell would read the same way fails here, not on a device.
        override suspend fun exec(argv: List<String>, stdin: ByteArray?, limits: ExecLimits): ExecResult {
            argvToCommand(argv)
            val text = stdin?.toString(Charsets.UTF_8).orEmpty()
            execs += argv to text
            return answer(argv, text)
        }
        override suspend fun openStream(argv: List<String>): StreamChannel {
            argvToCommand(argv)
            if (openFails) throw IOException("no channel could be opened")
            return Wire(argv).also { script(it); streams += it }
        }
        override suspend fun close() = Unit
    }

    private val content = ByteArray(50_000) { (it * 7 + 3).toByte() }
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private val contentSha = sha(content)
    private fun source(b: ByteArray = content) = ByteSource { ByteArrayInputStream(b) }
    private fun transfers(link: Link) = FileTransfers(link, journal) { now }

    private suspend fun FileTransfers.putDefault(name: String = "shot.png", folder: String = "/work/proj", size: Long = content.size.toLong(), deadline: kotlin.time.Duration = 10.seconds) =
        put(key, folder, name, source(), size, contentSha, max, deadline)

    // ---- the write ----------------------------------------------------------------------------------------------------------

    @Test fun `the row is on disk as Sent, with the path, the size and the hash, before the first byte is written`() = runBlocking<Unit> {
        val link = Link()
        var seenAtFirstByte: io.github.tuthan.paddock.ops.OperationRecord? = null
        link.script = { w -> w.onFirstWrite = { seenAtFirstByte = journal.records.value.single() } }
        val r = transfers(link).putDefault()
        val before = assertNotNullRow(seenAtFirstByte)
        assertEquals(OperationKind.Attachment, before.kind)
        assertEquals(OperationOutcome.Sent, before.outcome, "markSent comes before the header")
        assertEquals("/work/proj/.paddock/inbox/shot.png", before.path)
        assertEquals(content.size.toLong(), before.size)
        assertEquals(contentSha, before.payloadSha256)
        assertNull(before.promptText, "a file's content is never kept")
        assertIs<OperationResult.Acknowledged<*>>(r)
    }

    private fun assertNotNullRow(r: io.github.tuthan.paddock.ops.OperationRecord?): io.github.tuthan.paddock.ops.OperationRecord = r ?: error("no row was written before the first byte")

    @Test fun `exit 0 is acknowledged, names the path under the folder, and streams the header and every byte then ends the input`() = runBlocking<Unit> {
        val link = Link()
        val r = transfers(link).putDefault()
        val ok = assertIs<OperationResult.Acknowledged<String>>(r)
        assertEquals(".paddock/inbox/shot.png", ok.value, "what the prompt's phrase names")
        val w = link.streams.single()
        assertEquals(TransferScripts.putArgv(max), w.argv)
        assertTrue(w.text().startsWith("/work/proj\nshot.png\n${content.size}\n"), "folder, name and size on stdin, never in argv")
        assertContentEquals(content, w.written.toByteArray().copyOfRange("/work/proj\nshot.png\n${content.size}\n".length, w.written.size()))
        assertTrue(w.stdinClosed && w.closed)
        assertTrue(w.argv.none { "/work/proj" in it || "shot.png" in it }, "free text is not in argv")
        val row = journal.records.value.single()
        assertEquals(OperationOutcome.Acknowledged, row.outcome)
        assertFalse(row.awaitsReread || row.awaitsInboxCheck)
    }

    @Test fun `each refusal's exit status is a rejection with its code and sentence, and a status that is not one is unknown`() = runBlocking<Unit> {
        for (why in TransferRefusal.entries) {
            val link = Link().also { it.script = { w -> w.exit = why.exit } }
            val r = transfers(link).putDefault(name = "n-${why.exit}.png")
            val rejected = assertIs<OperationResult.Rejected>(r, why.name)
            assertEquals(why.code, rejected.code)
            assertEquals(why.sentence, rejected.message)
            assertEquals(OperationOutcome.Rejected, rejected.record.outcome)
            assertFalse("n-${why.exit}" in rejected.record.note + rejected.record.code.orEmpty(), "no file name in a row's code or note")
        }
        for (exit in listOf(-1, 1, 2, 126, 127, 128, 143, 255, 9)) {
            val link = Link().also { it.script = { w -> w.exit = exit } }
            val r = transfers(link).putDefault(name = "u$exit.png".replace("-", "m"))
            val unknown = assertIs<OperationResult.Unknown>(r, "status $exit")
            assertEquals(OperationOutcome.Unknown, unknown.record.outcome)
            assertTrue(unknown.record.awaitsInboxCheck && !unknown.record.awaitsReread)
            settle(unknown.record)
        }
    }

    private fun settle(r: io.github.tuthan.paddock.ops.OperationRecord) { journal.resolve(r.id, "settled") }

    @Test fun `a stream that throws in the middle leaves an unknown row that says how far it got, and the inbox settles it`() = runBlocking<Unit> {
        val link = Link().also { it.script = { w -> w.failAfterBytes = 20_000 } }
        now = 5_000L
        val t = transfers(link)
        val r = t.putDefault()
        val unknown = assertIs<OperationResult.Unknown>(r)
        assertTrue(unknown.record.note.startsWith("cut after "), unknown.record.note)
        assertTrue("of ${content.size} bytes" in unknown.record.note)
        assertTrue(link.streams.single().closed, "the channel is closed")
        assertTrue(journal.unresolvedAttachments(key).single().awaitsInboxCheck)
        assertTrue(journal.unresolvedUnknown(key).isEmpty(), "and a re-read of the agent has nothing to free")

        // The user reads the inbox: the machine has the file, whole, which settles the row and says so; the row itself stays Unknown.
        val asked = Link { _, _ -> FakeSession.result(0, "present\t${content.size}\n") }
        val found = assertIs<TransferReply.Ok<InboxCheck>>(transfers(asked).check(key, "/work/proj", "shot.png"))
        assertEquals(InboxCheck.Present(content.size.toLong()), found.value)
        assertEquals(listOf(listOf("/bin/sh", "-c", TransferScripts.CHECK, "paddock-check")), asked.execs.map { it.first })
        assertEquals("/work/proj\nshot.png\n", asked.execs.single().second, "the folder and the name are on stdin")
        val row = journal.get(unknown.record.id)!!
        assertEquals(OperationOutcome.Unknown, row.outcome)
        assertTrue(row.resolvedAt != null && !row.awaitsInboxCheck && row.note.startsWith("inbox read: there"))
    }

    @Test fun `a check settles only the cut attachments for that path, and one that did not arrive settles nothing`() = runBlocking<Unit> {
        val link = Link().also { it.script = { w -> w.exit = 143 } }
        val a = assertIs<OperationResult.Unknown>(transfers(link).putDefault(name = "a.png")).record
        settle(a)
        val b = assertIs<OperationResult.Unknown>(transfers(link).putDefault(name = "b.png")).record
        val absent = Link { _, _ -> FakeSession.result(0, "absent\n") }
        assertEquals(InboxCheck.Absent, assertIs<TransferReply.Ok<InboxCheck>>(transfers(absent).check(key, "/work/proj", "a.png")).value)
        assertTrue(journal.get(b.id)!!.awaitsInboxCheck, "b.png is another file")
        val down = Link { _, _ -> throw IOException("link lost") }
        assertIs<TransferReply.Failed>(transfers(down).check(key, "/work/proj", "b.png"))
        assertTrue(journal.get(b.id)!!.awaitsInboxCheck, "no answer, no settling")
        val refused = Link { _, _ -> FakeSession.result(TransferRefusal.Symlink.exit) }
        assertEquals(TransferReply.Refused(TransferRefusal.Symlink), transfers(refused).check(key, "/work/proj", "b.png"))
        assertTrue(journal.get(b.id)!!.awaitsInboxCheck)
        assertEquals(InboxCheck.Absent, assertIs<TransferReply.Ok<InboxCheck>>(transfers(absent).check(key, "/work/proj", "b.png")).value)
        assertFalse(journal.get(b.id)!!.awaitsInboxCheck)
        assertTrue("not there" in journal.get(b.id)!!.note)
    }

    @Test fun `a check refused because the agent's folder is gone, or the file is not there to count, settles the cut attachment as absent`() = runBlocking<Unit> {
        val link = Link().also { it.script = { w -> w.exit = 143 } }
        val gone = assertIs<OperationResult.Unknown>(transfers(link).putDefault(name = "gone.png")).record
        // A failure says nothing about the disk: the row still holds the next attachment.
        assertIs<TransferReply.Failed>(transfers(Link { _, _ -> throw IOException("link lost") }).check(key, "/work/proj", "gone.png"))
        assertTrue(journal.get(gone.id)!!.awaitsInboxCheck)
        // The worktree was removed: the script cannot find the folder, so no file can be under it.
        val removed = answer(exit = TransferRefusal.NotDirectory.exit)
        assertEquals(TransferReply.Refused(TransferRefusal.NotDirectory), transfers(removed).check(key, "/work/proj", "gone.png"), "the refusal is still what is said")
        val settled = journal.get(gone.id)!!
        assertFalse(settled.awaitsInboxCheck, "the row no longer holds the next attachment")
        assertEquals(OperationOutcome.Unknown, settled.outcome)
        assertNull(settled.inboxSize, "nothing was found there")
        assertTrue("not there" in settled.note)

        val vanished = assertIs<OperationResult.Unknown>(transfers(link).putDefault(name = "vanished.png")).record
        transfers(answer(exit = TransferRefusal.NotFound.exit)).check(key, "/work/proj", "vanished.png")
        assertFalse(journal.get(vanished.id)!!.awaitsInboxCheck)
        // A refusal that does not prove the file absent (a link where the inbox should be) settles nothing.
        val linked = assertIs<OperationResult.Unknown>(transfers(link).putDefault(name = "linked.png")).record
        transfers(answer(exit = TransferRefusal.Symlink.exit)).check(key, "/work/proj", "linked.png")
        assertTrue(journal.get(linked.id)!!.awaitsInboxCheck)
    }

    // ---- a prompt's file sent again ------------------------------------------------------------------------------------------

    private suspend fun FileTransfers.attachDefault(name: String = "shot.png", b: ByteArray = content) =
        attach(key, "/work/proj", name, source(b), b.size.toLong(), sha(b), max, 10.seconds)

    private fun names(link: Link) = link.streams.map { it.text().lines()[1] }

    /** Another file's bytes, one per [seed]. */
    private fun body(seed: Int) = ByteArray(1_000) { (it * 13 + seed).toByte() }

    @Test fun `attach writes nothing when the journal shows this exact file under the name, and the result names it`() = runBlocking<Unit> {
        val link = Link()
        val first = assertIs<OperationResult.Acknowledged<String>>(transfers(link).attachDefault())
        val again = assertIs<OperationResult.Acknowledged<String>>(transfers(link).attachDefault())
        assertEquals(".paddock/inbox/shot.png", again.value)
        assertEquals(first.record.id, again.record.id, "the row that shows it there")
        assertEquals(1, link.streams.size, "nothing was written the second time")
        assertEquals(1, journal.records.value.size, "and nothing was journaled")
        // Another folder is another inbox, and another terminal's rows are not this one's.
        assertIs<OperationResult.Acknowledged<String>>(transfers(link).attach(key, "/work/other", "shot.png", source(), content.size.toLong(), contentSha, max, 10.seconds))
        val other = TerminalKey(TargetRef(HostProfileId("h1"), "paddock-test", "term_2"), epoch = 1)
        assertIs<OperationResult.Acknowledged<String>>(transfers(link).attach(other, "/work/proj", "shot.png", source(), content.size.toLong(), contentSha, max, 10.seconds))
        assertEquals(3, link.streams.size)
    }

    @Test fun `attach passes over a name another file holds, by the journal or by the machine's refusal, and stops when every name is taken`() = runBlocking<Unit> {
        val link = Link()
        assertIs<OperationResult.Acknowledged<String>>(transfers(link).attachDefault())
        // Another file, the same name: the journal says the name holds something else, so it goes straight to the next one.
        val other = body(1)
        val second = assertIs<OperationResult.Acknowledged<String>>(transfers(link).attachDefault(b = other))
        assertEquals(".paddock/inbox/shot-2.png", second.value)
        assertEquals(listOf("shot.png", "shot-2.png"), names(link))
        // A third file: both names are known taken, and the machine refuses shot-3.png (a file with no row here), so shot-4.png.
        link.script = { w -> if (link.streams.size == 2) w.exit = TransferRefusal.Exists.exit }
        val third = assertIs<OperationResult.Acknowledged<String>>(transfers(link).attachDefault(b = body(2)))
        assertEquals(".paddock/inbox/shot-4.png", third.value)
        assertEquals(listOf("shot.png", "shot-2.png", "shot-3.png", "shot-4.png"), names(link))
        // And sending the second file again finds it under shot-2.png.
        assertEquals(".paddock/inbox/shot-2.png", assertIs<OperationResult.Acknowledged<String>>(transfers(link).attachDefault(b = other)).value)
        assertEquals(4, link.streams.size)
        // A fourth file: shot-5.png is free.
        link.script = {}
        assertEquals(".paddock/inbox/shot-5.png", assertIs<OperationResult.Acknowledged<String>>(transfers(link).attachDefault(b = body(3))).value)
        // A fifth: every name is taken by the journal, so nothing is written and the refusal is the machine's own sentence.
        val full = assertIs<OperationResult.NotSent>(transfers(link).attachDefault(b = body(4)))
        assertEquals(TransferRefusal.Exists.code, full.reason)
        assertEquals(TransferRefusal.Exists.sentence, full.message)
        assertEquals(5, link.streams.size)
    }

    @Test fun `attach takes a cut file the inbox showed whole as there, and one shown at another size or not at all as not this file`() = runBlocking<Unit> {
        val cutting = Link().also { it.script = { w -> w.exit = 143 } }
        val size = content.size.toLong()
        suspend fun cutAndRead(name: String, said: String) {
            assertIs<OperationResult.Unknown>(transfers(cutting).attachDefault(name = name))
            transfers(answer(out = said)).check(key, "/work/proj", name)
        }
        val link = Link()
        cutAndRead("whole.png", "present\t$size\n")
        assertEquals(".paddock/inbox/whole.png", assertIs<OperationResult.Acknowledged<String>>(transfers(link).attachDefault(name = "whole.png")).value)
        assertTrue(link.streams.isEmpty(), "a published file is whole: nothing is written")

        cutAndRead("other.png", "present\t${size - 1}\n")
        assertEquals(".paddock/inbox/other-2.png", assertIs<OperationResult.Acknowledged<String>>(transfers(link).attachDefault(name = "other.png")).value)
        cutAndRead("absent.png", "absent\n")
        assertEquals(".paddock/inbox/absent.png", assertIs<OperationResult.Acknowledged<String>>(transfers(link).attachDefault(name = "absent.png")).value, "a name that is free is used")
        assertEquals(listOf("other-2.png", "absent.png"), names(link))
    }

    @Test fun `a cancel while the row is being written settles it as not sent, so the terminal is not left busy`() = runBlocking<Unit> {
        val gate = HeldStore()
        val held = OperationJournal(gate) { now }
        val link = Link()
        val job = async(Dispatchers.Default) { FileTransfers(link, held) { now }.put(key, "/work/proj", "a.png", source(), content.size.toLong(), contentSha, max, 5.seconds) }
        assertTrue(gate.entered.await(5, java.util.concurrent.TimeUnit.SECONDS), "the row is being written")
        job.cancel()
        gate.release.countDown()
        assertFailsWith<CancellationException> { job.await() }
        val row = held.records.value.single()
        assertEquals(OperationOutcome.NotSent, row.outcome, "a row that was begun is always settled")
        assertEquals("cancelled", row.code)
        assertTrue(link.streams.isEmpty(), "nothing was sent")
        assertIs<OperationResult.Acknowledged<String>>(FileTransfers(link, held) { now }.put(key, "/work/proj", "b.png", source(), content.size.toLong(), contentSha, max, 5.seconds), "the next one is not Busy")
    }

    /** A store whose first save does not return until the test lets it: a slow disk under the row's write. */
    private class HeldStore : io.github.tuthan.paddock.ops.JournalStore {
        private val inner = InMemoryJournalStore()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        @Volatile private var first = true
        override fun load() = inner.load()
        override fun save(data: io.github.tuthan.paddock.ops.JournalData) {
            if (first) { first = false; entered.countDown(); release.await() }
            inner.save(data)
        }
        override fun discardUnreadable() = inner.discardUnreadable()
    }

    @Test fun `the deadline cuts the transfer, closes the channel and leaves the row unknown`() = runBlocking<Unit> {
        val link = Link().also { it.script = { w -> w.hangOnExit = true } }
        val r = withTimeout(5.seconds) { transfers(link).putDefault(deadline = 150.milliseconds) }
        val unknown = assertIs<OperationResult.Unknown>(r)
        assertTrue("no answer in" in unknown.cause)
        assertTrue(link.streams.single().closed, "the channel is closed, which is all the session can do: it signals nothing")
        assertEquals(OperationOutcome.Unknown, journal.records.value.single().outcome)
    }

    @Test fun `a cancel closes the channel and leaves the row unknown, never acknowledged or lost`() = runBlocking<Unit> {
        val link = Link()
        val started = CompletableDeferred<Unit>()
        link.script = { w -> w.hangOnExit = true; w.onFirstWrite = { started.complete(Unit) } }
        val job = async(Dispatchers.Default) { transfers(link).putDefault() }
        withTimeout(5.seconds) { started.await() }
        job.cancel()
        assertFailsWith<CancellationException> { job.await() }
        val w = link.streams.single()
        withTimeout(5.seconds) { while (!w.closed || journal.records.value.single().outcome == OperationOutcome.Sent) delay(5) }
        val row = journal.records.value.single()
        assertEquals(OperationOutcome.Unknown, row.outcome)
        assertTrue("cancelled" in row.note)
    }

    @Test fun `what the phone can see is refused before a channel is opened, and journaled as not sent with the script's own sentence`() = runBlocking<Unit> {
        val link = Link()
        val t = transfers(link)
        val cases = listOf<Triple<String, suspend () -> OperationResult<String>, TransferRefusal>>(
            Triple("over the cap", { t.put(key, "/work/proj", "a.png", source(), max + 1, contentSha, max, 5.seconds) }, TransferRefusal.OverCap),
            Triple("nothing to send", { t.put(key, "/work/proj", "a.png", source(), 0, contentSha, max, 5.seconds) }, TransferRefusal.BadCount),
            Triple("a bad name", { t.put(key, "/work/proj", "../a.png", source(), 5, contentSha, max, 5.seconds) }, TransferRefusal.BadName),
            Triple("a dotted name", { t.put(key, "/work/proj", ".a.png", source(), 5, contentSha, max, 5.seconds) }, TransferRefusal.BadName),
            Triple("a relative folder", { t.put(key, "proj", "a.png", source(), 5, contentSha, max, 5.seconds) }, TransferRefusal.BadPath),
            Triple("a folder with a line break", { t.put(key, "/work/a\nb", "a.png", source(), 5, contentSha, max, 5.seconds) }, TransferRefusal.BadPath),
            Triple("no folder", { t.put(key, "", "a.png", source(), 5, contentSha, max, 5.seconds) }, TransferRefusal.BadPath),
        )
        for ((what, call, why) in cases) {
            val r = assertIs<OperationResult.NotSent>(call(), what)
            assertEquals(why.code, r.reason, what)
            assertEquals(why.sentence, r.message, what)
        }
        assertTrue(link.streams.isEmpty(), "no channel was opened for any of them")
        assertTrue(journal.records.value.all { it.outcome == OperationOutcome.NotSent })
    }

    @Test fun `a file is journaled by its SHA-256 only, and the source holds at most the size and may hold less`() = runBlocking<Unit> {
        val link = Link()
        for (bad in listOf("", "not-a-hash", contentSha.uppercase(), contentSha.dropLast(1), "$contentSha\n")) {
            assertFailsWith<IllegalArgumentException> { transfers(link).put(key, "/work/proj", "a.png", source(), 5, bad, max, 5.seconds) }
        }
        assertTrue(link.streams.isEmpty() && journal.records.value.isEmpty(), "nothing was started")

        // A source with more than the declared size gives the machine the size and not a byte more.
        val head = "/work/proj\nlong.bin\n1000\n"
        val long = Link()
        assertIs<OperationResult.Acknowledged<*>>(transfers(long).put(key, "/work/proj", "long.bin", source(), 1_000, contentSha, max, 5.seconds))
        assertEquals(head.length + 1_000, long.streams.single().written.size(), "the header and exactly the size")

        // A source with less: the input ends early, the machine counts and refuses it as short, and that refusal is what is said.
        val short = Link().also { it.script = { w -> w.exit = TransferRefusal.Truncated.exit } }
        val r = assertIs<OperationResult.Rejected>(transfers(short).put(key, "/work/proj", "short.bin", source(content.copyOf(100)), 1_000, contentSha, max, 5.seconds))
        assertEquals(TransferRefusal.Truncated.code, r.code)
        val w = short.streams.single()
        assertEquals("/work/proj\nshort.bin\n1000\n".length + 100, w.written.size())
        assertTrue(w.stdinClosed && w.closed)
    }

    @Test fun `a journal that cannot be written sends nothing, before the row and before the first byte`() = runBlocking<Unit> {
        // The row is the write-ahead rule: without it not a byte leaves, and no source is read.
        val link = Link()
        var opened = false
        store.failSaves = true
        val r1 = transfers(link).put(key, "/work/proj", "a.png", ByteSource { opened = true; ByteArrayInputStream(content) }, content.size.toLong(), contentSha, max, 5.seconds)
        assertIs<OperationResult.JournalFailed>(r1)
        assertTrue(link.streams.isEmpty() && !opened, "no channel and no source")
        store.failSaves = false
        assertEquals(emptyList(), journal.records.value)

        // The row is there but cannot be marked Sent: the header is not written either, the row says nothing left, and the channel is closed.
        val r2 = transfers(link).put(key, "/work/proj", "b.png", ByteSource { store.failSaves = true; ByteArrayInputStream(content) }, content.size.toLong(), contentSha, max, 5.seconds)
        store.failSaves = false
        val notSent = assertIs<OperationResult.NotSent>(r2)
        assertEquals("journal_unwritable", notSent.reason)
        val w = link.streams.single()
        assertEquals(0, w.written.size(), "not even the header")
        assertTrue(w.closed)
        assertEquals(OperationOutcome.NotSent, journal.records.value.single().outcome)
    }

    @Test fun `a source that cannot be opened and a channel that cannot be opened are not sent, and the source is closed`() = runBlocking<Unit> {
        val link = Link()
        val unreadable = ByteSource { throw IOException("gone") }
        val r1 = assertIs<OperationResult.NotSent>(transfers(link).put(key, "/work/proj", "a.png", unreadable, 5, contentSha, max, 5.seconds))
        assertEquals("source_unreadable", r1.reason)
        assertTrue(link.streams.isEmpty())

        var closed = false
        val tracked = ByteSource { object : InputStream() { override fun read() = -1; override fun close() { closed = true } } }
        link.openFails = true
        val r2 = assertIs<OperationResult.NotSent>(transfers(link).put(key, "/work/proj", "b.png", tracked, 5, contentSha, max, 5.seconds))
        assertEquals("transport_failed", r2.reason)
        assertTrue(closed, "the file is not left open")
        assertTrue(journal.records.value.all { it.outcome == OperationOutcome.NotSent })
    }

    @Test fun `a second write waits for the first, and a cut one holds only the next attachment until its inbox is read`() = runBlocking<Unit> {
        val link = Link()
        val started = CompletableDeferred<Unit>()
        link.script = { w -> w.hangOnExit = true; w.onFirstWrite = { started.complete(Unit) } }
        val first = async(Dispatchers.Default) { transfers(link).putDefault(name = "one.png") }
        withTimeout(5.seconds) { started.await() }
        assertIs<OperationResult.Busy>(transfers(link).putDefault(name = "two.png"), "one transfer per agent at a time")
        first.cancel(); runCatching { first.await() }
        withTimeout(5.seconds) { while (journal.records.value.single().outcome == OperationOutcome.Sent) delay(5) }

        val cut = journal.records.value.single()
        assertTrue(cut.awaitsInboxCheck)
        val again = assertIs<OperationResult.NeedsReread>(transfers(Link()).putDefault(name = "three.png"))
        assertEquals(cut.id, again.unknown.id)
        journal.resolve(cut.id, "read")
        assertIs<OperationResult.Acknowledged<*>>(transfers(Link()).putDefault(name = "four.png"))
    }

    @Test fun `a drop is journaled under its host profile with no terminal and no session, and names the path in the drop folder`() = runBlocking<Unit> {
        val link = Link()
        val profile = HostProfileId("h1")
        val r = transfers(link).drop(profile, "~/Paddock", "note.txt", source(), content.size.toLong(), contentSha, max, 10.seconds)
        val ok = assertIs<OperationResult.Acknowledged<String>>(r)
        assertEquals("~/Paddock/note.txt", ok.value)
        val w = link.streams.single()
        assertEquals(TransferScripts.dropArgv(max), w.argv)
        assertTrue(w.text().startsWith("~/Paddock\nnote.txt\n${content.size}\n"))
        val row = journal.records.value.single()
        assertEquals(Subject.drop("h1"), row.terminalId)
        assertEquals("", row.session)
        assertFalse(row.aboutTerminal, "a drop is about no terminal")
        assertEquals(0, row.epoch)
        assertEquals("~/Paddock/note.txt", row.path)
        // A cut drop holds nothing: there is no prompt to hold and no screen to read the folder from, so the next drop to the same machine goes, and no terminal is held.
        val cutLink = Link().also { it.script = { x -> x.exit = 143 } }
        val cut = assertIs<OperationResult.Unknown>(transfers(cutLink).drop(profile, "~/Paddock", "b.txt", source(), 5, contentSha, max, 5.seconds))
        assertEquals(OperationOutcome.Unknown, cut.record.outcome)
        assertIs<OperationResult.Acknowledged<*>>(transfers(link).drop(profile, "~/Paddock", "c.txt", source(), 5, contentSha, max, 5.seconds), "the cut one does not hold the next")
        assertEquals(OperationOutcome.Unknown, journal.get(cut.record.id)!!.outcome, "and it stays Unknown in the log")
        assertIs<OperationResult.Acknowledged<*>>(transfers(Link()).drop(HostProfileId("h2"), "~/Paddock", "c.txt", source(), 5, contentSha, max, 5.seconds), "another machine is another subject")
        assertIs<OperationResult.Acknowledged<*>>(transfers(Link()).putDefault(), "and no terminal is held")
        val local = assertIs<OperationResult.NotSent>(transfers(Link()).drop(HostProfileId("h3"), "relative", "c.txt", source(), 5, contentSha, max, 5.seconds))
        assertEquals(TransferRefusal.BadPath.code, local.reason)
    }

    // ---- the read ------------------------------------------------------------------------------------------------------------

    private class Sink : ByteSink {
        val bytes = ByteArrayOutputStream()
        @Volatile var closed = false
        override fun open() = object : java.io.OutputStream() {
            override fun write(b: Int) = bytes.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = bytes.write(b, off, len)
            override fun close() { closed = true }
        }
    }

    private fun chunks(b: ByteArray, n: Int) = b.toList().chunked(maxOf(1, b.size / n)).map { it.toByteArray() }

    @Test fun `a read that arrives whole is hashed, written to the sink and recorded as a fetch`() = runBlocking<Unit> {
        val link = Link().also { it.script = { w -> w.out = chunks(content, 5) } }
        val sink = Sink()
        val r = transfers(link).get(key, "/work/proj", "/work/proj/out/report.pdf", content.size.toLong(), sink, max, 10.seconds)
        val ok = assertIs<GetResult.Fetched>(r)
        assertEquals(contentSha, ok.sha256)
        assertEquals(content.size.toLong(), ok.size)
        assertContentEquals(content, sink.bytes.toByteArray())
        assertTrue(sink.closed && link.streams.single().closed)
        assertEquals(TransferScripts.getArgv(max), link.streams.single().argv)
        assertEquals("/work/proj\n/work/proj/out/report.pdf\n${content.size}\n", link.streams.single().text(), "the path and the looked-at size are on stdin")
        val row = journal.get(ok.recordId)!!
        assertEquals(OperationKind.Fetch, row.kind)
        assertEquals(OperationOutcome.Acknowledged, row.outcome)
        assertEquals("/work/proj/out/report.pdf", row.path)
        assertEquals(content.size.toLong(), row.size)
        assertEquals(contentSha, row.payloadSha256)
        assertNull(row.promptText)
    }

    @Test fun `a short read, a longer one, a deadline, a refusal and a bad exit are each a failure with no fetch row`() = runBlocking<Unit> {
        val size = content.size.toLong()
        fun get(script: (Wire) -> Unit, s: Long = size, deadline: kotlin.time.Duration = 5.seconds): GetResult = runBlocking {
            transfers(Link().also { it.script = script }).get(key, "/work/proj", "/work/proj/f", s, Sink(), max, deadline)
        }
        assertEquals("${size - 1} of $size bytes arrived", assertIs<GetResult.Failed>(get({ w -> w.out = chunks(content.copyOf((size - 1).toInt()), 4) })).message)
        assertTrue("grew" in assertIs<GetResult.Failed>(get({ w -> w.out = chunks(content + byteArrayOf(1), 4) })).message)
        assertEquals(GetResult.Refused(TransferRefusal.Changed), get({ w -> w.exit = TransferRefusal.Changed.exit }))
        assertEquals(GetResult.Refused(TransferRefusal.Symlink), get({ w -> w.exit = TransferRefusal.Symlink.exit }))
        assertTrue("status 143" in assertIs<GetResult.Failed>(get({ w -> w.out = chunks(content, 3); w.exit = 143 })).message)
        assertTrue("longer than" in assertIs<GetResult.Failed>(get({ w -> w.out = chunks(content, 3); w.hangOnExit = true }, deadline = 150.milliseconds)).message)
        assertEquals(emptyList(), journal.records.value, "none of them is a fetch")
        // A link that dies while it reads is a failure too.
        val dying = Link { _, _ -> FakeSession.result(0) }.also { it.openFails = true }
        assertIs<GetResult.Failed>(transfers(dying).get(key, "/work/proj", "/work/proj/f", 5, Sink(), max, 5.seconds))
        assertEquals(emptyList(), journal.records.value)
    }

    @Test fun `a read over the cap or with a bad folder is refused before a channel is opened`() = runBlocking<Unit> {
        val link = Link()
        val t = transfers(link)
        assertEquals(GetResult.Refused(TransferRefusal.OverCap), t.get(key, "/work/proj", "/work/proj/f", max + 1, Sink(), max, 5.seconds))
        assertEquals(GetResult.Refused(TransferRefusal.BadPath), t.get(key, "proj", "/work/proj/f", 5, Sink(), max, 5.seconds))
        assertEquals(GetResult.Refused(TransferRefusal.BadPath), t.get(key, "/work/proj", "f\n", 5, Sink(), max, 5.seconds))
        assertTrue(link.streams.isEmpty())
    }

    // ---- the questions ----------------------------------------------------------------------------------------------------------

    private fun answer(exit: Int = 0, out: String = "", truncated: Boolean = false) = Link { _, _ ->
        ExecResult(exit, out.toByteArray(), ByteArray(0), truncated, false, kotlin.time.Duration.ZERO)
    }

    @Test fun `stat reads the size, the time and the real path, sends them on stdin, and refuses an answer that does not parse`() = runBlocking<Unit> {
        val link = answer(out = "1234\t1700000000\t/work/proj/out/a b.txt\n")
        val r = assertIs<TransferReply.Ok<RemoteStat>>(transfers(link).stat("/work/proj", "/work/proj/out/../out/a b.txt", max))
        assertEquals(RemoteStat(1234, 1_700_000_000, "/work/proj/out/a b.txt"), r.value)
        assertEquals(TransferScripts.statArgv(max), link.execs.single().first)
        assertEquals("/work/proj\n/work/proj/out/../out/a b.txt\n", link.execs.single().second)
        for (bad in listOf("", "12\t34\n", "x\t1\t/a\n", "1\ty\t/a\n", "1\t2\trelative\n", "1\t2\t/a\u0001b\n", "1\t2\t/a\t/b\n", "-1\t2\t/a\n", "1\t2\t/a\n/b\n")) {
            assertIs<TransferReply.Failed>(transfers(answer(out = bad)).stat("/work/proj", "/work/proj/a", max), "an answer of ${bad.length} characters")
        }
        for (why in listOf(TransferRefusal.Symlink, TransferRefusal.OutsideFolder, TransferRefusal.OverCap, TransferRefusal.NotRegular)) {
            assertEquals(TransferReply.Refused(why), transfers(answer(exit = why.exit)).stat("/work/proj", "/work/proj/a", max))
        }
        assertIs<TransferReply.Failed>(transfers(answer(exit = 143)).stat("/work/proj", "/work/proj/a", max))
        // A status that is neither 0 nor a refusal is not an answer, whatever was said before it.
        for (exit in listOf(1, 2, 143)) {
            assertIs<TransferReply.Failed>(transfers(answer(exit = exit, out = "1234\t1700000000\t/work/proj/a\n")).stat("/work/proj", "/work/proj/a", max), "status $exit")
            assertIs<TransferReply.Failed>(transfers(answer(exit = exit, out = "absent\n")).check(key, "/work/proj", "a.png"), "status $exit")
            assertIs<TransferReply.Failed>(transfers(answer(exit = exit, out = "a.txt\t5\t1700000000\n")).list("/work/proj", ListWhich.Outbox), "status $exit")
            assertIs<TransferReply.Failed>(transfers(answer(exit = exit, out = "/home/u/Paddock\n")).resolveDrop("~/Paddock"), "status $exit")
        }
        val never = answer()
        for ((f, p) in listOf("proj" to "/work/proj/a", "/work/proj" to "a", "/work/proj" to "/work/a\nb", "" to "/a")) assertEquals(TransferReply.Refused(TransferRefusal.BadPath), transfers(never).stat(f, p, max))
        assertTrue(never.execs.isEmpty(), "nothing the script would refuse is asked")
    }

    @Test fun `resolving a drop folder shows its real path or the script's refusal`() = runBlocking<Unit> {
        val link = answer(out = "/home/u/Paddock\n")
        assertEquals("/home/u/Paddock", assertIs<TransferReply.Ok<String>>(transfers(link).resolveDrop("~/Paddock")).value)
        assertEquals(TransferScripts.resolveDropArgv(), link.execs.single().first)
        assertEquals("~/Paddock\n", link.execs.single().second)
        assertEquals(TransferReply.Refused(TransferRefusal.Hidden), transfers(answer(exit = TransferRefusal.Hidden.exit)).resolveDrop("~/.ssh"))
        assertEquals(TransferReply.Refused(TransferRefusal.Home), transfers(answer(exit = TransferRefusal.Home.exit)).resolveDrop("~"))
        assertIs<TransferReply.Failed>(transfers(answer(out = "relative\n")).resolveDrop("~/x"))
        assertIs<TransferReply.Failed>(transfers(answer(out = "")).resolveDrop("~/x"))
        val never = answer()
        for (v in listOf("", "relative", "~other/x", "~/a\u0001")) assertEquals(TransferReply.Refused(TransferRefusal.BadPath), transfers(never).resolveDrop(v))
        assertTrue(never.execs.isEmpty())
    }

    @Test fun `a list is read row by row, newest first as the machine sent it, bounded to 50, and a row that does not parse is left out`() = runBlocking<Unit> {
        val rows = (1..60).joinToString("") { "f$it.txt\t$it\t${1_700_000_000 + it}\n" }
        // The rows that do not parse come first, so a bound at 50 cannot be what leaves them out.
        val link = answer(out = "bad line\nno\ttab\n/slash\t1\t2\na/b\t1\t2\n\t1\t2\nx\t1\nnum\tx\t2\nnum\t1\ty\nnum\t-1\t2\n" + rows)
        val r = assertIs<TransferReply.Ok<List<RemoteFile>>>(transfers(link).list("/work/proj", ListWhich.Outbox))
        assertEquals(50, r.value.size)
        assertEquals(RemoteFile("f1.txt", 1, 1_700_000_001), r.value.first())
        val few = answer(out = "bad line\nno\ttab\n/slash\t1\t2\na/b\t1\t2\n\t1\t2\nx\t1\nnum\tx\t2\nnum\t1\ty\nnum\t-1\t2\nok.txt\t1\t2\n")
        assertEquals(listOf(RemoteFile("ok.txt", 1, 2)), assertIs<TransferReply.Ok<List<RemoteFile>>>(transfers(few).list("/work/proj", ListWhich.Outbox)).value, "only the row that parses is kept")
        assertEquals("/work/proj/.paddock/outbox\n", link.execs.single().second, "the outbox is under the agent's folder")
        assertEquals(TransferScripts.listArgv(), link.execs.single().first)
        val drop = answer(out = "a.txt\t5\t1700000000\n")
        assertEquals(listOf(RemoteFile("a.txt", 5, 1_700_000_000)), assertIs<TransferReply.Ok<List<RemoteFile>>>(transfers(drop).list("/home/u/Paddock", ListWhich.Drop)).value)
        assertEquals("/home/u/Paddock\n", drop.execs.single().second)
        assertEquals(TransferReply.Refused(TransferRefusal.NotFound), transfers(answer(exit = TransferRefusal.NotFound.exit)).list("/work/proj", ListWhich.Outbox))
        assertIs<TransferReply.Failed>(transfers(answer(out = rows, truncated = true)).list("/work/proj", ListWhich.Outbox))
        assertEquals(TransferReply.Refused(TransferRefusal.BadPath), transfers(answer()).list("relative", ListWhich.Drop))
        // A name with a control character never gets in, even if a machine sent one.
        val hostile = answer(out = "ok.txt\t1\t2\nev\u001bil\t1\t2\n")
        assertEquals(listOf("ok.txt"), assertIs<TransferReply.Ok<List<RemoteFile>>>(transfers(hostile).list("/work/proj", ListWhich.Outbox)).value.map { it.name })
    }

    @Test fun `check refuses an answer that is neither present with a size nor absent`() = runBlocking<Unit> {
        for (bad in listOf("", "maybe\n", "present\n", "present\tx\n", "present\t1\t2\n", "absent\t1\n", "present\t99999999999999999999\n", "present\t-5\n", "present\t+5\n", "present\t 5\n")) {
            assertIs<TransferReply.Failed>(transfers(answer(out = bad)).check(key, "/work/proj", "a.png"), "an answer of ${bad.length} characters")
        }
        val never = answer()
        assertEquals(TransferReply.Refused(TransferRefusal.BadName), transfers(never).check(key, "/work/proj", "../a.png"))
        assertEquals(TransferReply.Refused(TransferRefusal.BadPath), transfers(never).check(key, "work", "a.png"))
        assertTrue(never.execs.isEmpty())
    }
}
