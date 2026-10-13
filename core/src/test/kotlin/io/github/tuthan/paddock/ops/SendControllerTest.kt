package io.github.tuthan.paddock.ops

import io.github.tuthan.paddock.files.ByteSource
import io.github.tuthan.paddock.files.FileTransfers
import io.github.tuthan.paddock.files.TransferRefusal
import io.github.tuthan.paddock.files.TransferReply
import io.github.tuthan.paddock.herdr.Pane
import io.github.tuthan.paddock.herdr.Snapshot
import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.identity.TargetRef
import io.github.tuthan.paddock.identity.TerminalKey
import io.github.tuthan.paddock.reconcile.Installed
import io.github.tuthan.paddock.relay.FakeSession
import io.github.tuthan.paddock.relay.FakeStream
import io.github.tuthan.paddock.relay.RelayClient
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SendControllerTest {
    private val journal = OperationJournal(InMemoryJournalStore()) { 5_000_000L }
    private val key = TerminalKey(TargetRef(HostProfileId("h1"), "paddock-test", "term_1"), epoch = 1)
    private val methods = CopyOnWriteArrayList<String>()
    private val prompts = CopyOnWriteArrayList<String>()
    @Volatile private var held: FakeStream? = null
    @Volatile private var hold = false
    @Volatile private var status = "idle"

    private fun agent(id: String) = """{"terminal_id":"term_1","agent":"claude","agent_status":"$status","workspace_id":"w1","tab_id":"w1:t1","pane_id":"w1:p1","focused":false,"revision":1,"state_change_seq":4}"""

    private val session = FakeSession(onStream = { s ->
        s.onRequest = { line ->
            val req = Json.parseToJsonElement(line).jsonObject
            val id = req["id"]!!.jsonPrimitive.content
            val method = req["method"]!!.jsonPrimitive.content
            methods += method
            if (method == "agent.prompt") prompts += req["params"]!!.jsonObject["text"]!!.jsonPrimitive.content
            when {
                method == "agent.get" -> s.feed("""{"id":"$id","result":{"type":"agent_info","agent":${agent(id)}}}""" + "\n")
                hold -> held = s
                method == "agent.prompt" -> s.feed("""{"id":"$id","result":{"type":"agent_prompted","agent":${agent(id)}}}""" + "\n")
                else -> s.feed("""{"id":"$id","result":{"type":"ok"}}""" + "\n")
            }
        }
    })

    private val installed = Installed(Snapshot("0.9.1", 22, panes = listOf(Pane(paneId = "w1:p1", terminalId = "term_1", workspaceId = "w1", tabId = "w1:t1"))), 5_000_000L, 1)
    private val ops = AgentOperations(RelayClient(session, "/relay.py", "/sock"), journal, { installed }, { 5_000_000L })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private suspend fun until(what: String, cond: () -> Boolean) {
        try { withTimeout(5_000) { while (!cond()) delay(5) } } catch (e: kotlinx.coroutines.TimeoutCancellationException) { throw AssertionError("timed out waiting for $what") }
    }

    @Test fun aPromptRunsInTheControllersScopeAndItsOutcomeIsKeptUntilDismissed() = runBlocking<Unit> {
        val c = SendController(scope, ops)
        c.prompt(key, "hello", keepText = false)
        until("the outcome") { c.outcomes.value["term_1"] != null }
        val o = c.outcomes.value.getValue("term_1")
        assertEquals(OperationKind.Prompt, o.kind)
        assertIs<OperationResult.Acknowledged<*>>(o.result)
        assertTrue(c.running.value.isEmpty())
        c.dismiss("term_1")
        assertNull(c.outcomes.value["term_1"])
        scope.cancel()
    }

    @Test fun aSecondTapWhileOneRunsNeverStartsAnotherCall() = runBlocking<Unit> {
        hold = true
        val c = SendController(scope, ops)
        c.prompt(key, "hello", false)
        until("the prompt to be written") { methods.contains("agent.prompt") }
        assertEquals(setOf("term_1"), c.running.value)
        c.prompt(key, "hello", false)
        c.prompt(key, "hello again", false)
        delay(150)
        assertEquals(1, methods.count { it == "agent.prompt" })
        val id = held!!.written.last().toString(Charsets.UTF_8).let { Json.parseToJsonElement(it.trim()).jsonObject["id"]!!.jsonPrimitive.content }
        held!!.feed("""{"id":"$id","result":{"type":"agent_prompted","agent":${agent(id)}}}""" + "\n")
        until("the outcome") { c.outcomes.value["term_1"] != null }
        assertEquals(1, journal.records.value.size)
        assertEquals(1, methods.count { it == "agent.prompt" })
        scope.cancel()
    }

    @Test fun aRefusedPromptIsKeptAsItsOutcome() = runBlocking<Unit> {
        status = "working"
        val c = SendController(scope, ops)
        c.prompt(key, "hello", false)
        until("the outcome") { c.outcomes.value["term_1"] != null }
        val r = assertIs<OperationResult.NotSent>(c.outcomes.value.getValue("term_1").result)
        assertEquals(NotReadyReason.Working.code, r.reason)
        assertEquals(0, methods.count { it == "agent.prompt" })
        scope.cancel()
    }

    @Test fun startingAnotherOperationClearsTheEarlierOutcomeWhileItRuns() = runBlocking<Unit> {
        val c = SendController(scope, ops)
        c.sendKey(key, OperationKind.Esc)
        until("the Esc outcome") { c.outcomes.value["term_1"]?.kind == OperationKind.Esc }
        hold = true
        c.sendKey(key, OperationKind.CtrlC)
        until("the Ctrl+C to be written") { methods.count { it == "agent.send_keys" } == 2 }
        assertNull(c.outcomes.value["term_1"], "the old card is gone while the new send runs")
        scope.cancel()
    }

    @Test fun focusIsAControllerOperationToo() = runBlocking<Unit> {
        val c = SendController(scope, ops)
        c.focus(key)
        until("the outcome") { c.outcomes.value["term_1"]?.kind == OperationKind.Focus }
        assertTrue(methods.contains("agent.focus"))
        scope.cancel()
    }


    private suspend fun makeUnknown(c: SendController): OperationRecord {
        hold = true
        c.prompt(key, "hello", keepText = true)
        until("the prompt to be written") { methods.contains("agent.prompt") }
        held!!.end()                                                    // the link dies with the answer still owed
        until("the unknown outcome") { c.outcomes.value["term_1"]?.result is OperationResult.Unknown }
        hold = false
        return journal.records.value.single()
    }

    @Test fun aRereadFreesTheWaitingRowAndItsReportIsKeptUntilDismissed() = runBlocking<Unit> {
        val c = SendController(scope, ops)
        val unknown = makeUnknown(c)
        assertTrue(unknown.awaitsReread)
        c.reread(key)
        until("the re-read") { c.rereads.value["term_1"] != null }
        val done = assertIs<RereadOutcome.Done>(c.rereads.value.getValue("term_1"))
        assertEquals(listOf(unknown.id), done.report.resolved.map { it.id })
        assertTrue(journal.records.value.single().resolvedAt != null)
        assertTrue(c.running.value.isEmpty())
        assertNull(c.outcomes.value["term_1"], "the unknown card from the send is gone: it would offer a re-read for a row that is freed")
        assertEquals(1, methods.count { it == "agent.prompt" }, "a re-read writes nothing")
        c.dismissReread("term_1")
        assertNull(c.rereads.value["term_1"])
        scope.cancel()
    }

    @Test fun aRereadOfAnAgentThatIsGoneSaysSoAndLeavesTheRowWaiting() = runBlocking<Unit> {
        val c = SendController(scope, ops)
        val unknown = makeUnknown(c)
        val gone = TerminalKey(TargetRef(HostProfileId("h1"), "paddock-test", "term_1"), epoch = 99)
        c.reread(gone)
        until("the re-read") { c.rereads.value["term_1"] != null }
        assertTrue(assertIs<RereadOutcome.Failed>(c.rereads.value.getValue("term_1")).gone)
        assertTrue(journal.get(unknown.id)!!.awaitsReread)
        scope.cancel()
    }

    @Test fun aTapOnRereadWhileAnotherCallRunsIsIgnoredAndANewOperationClearsTheReport() = runBlocking<Unit> {
        val c = SendController(scope, ops)
        makeUnknown(c)
        c.reread(key)
        until("the re-read") { c.rereads.value["term_1"] != null }
        c.sendKey(key, OperationKind.Esc)
        until("the Esc outcome") { c.outcomes.value["term_1"]?.kind == OperationKind.Esc }
        assertNull(c.rereads.value["term_1"], "starting another operation clears the earlier report")
        scope.cancel()
    }

    // ---- Phase 15: a prompt with an attachment ---------------------------------------------------------------------------------

    private val fileBytes = ByteArray(2_000) { (it * 3).toByte() }
    private val sourceOf = ByteSource { java.io.ByteArrayInputStream(fileBytes) }
    private fun sha256Of(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private val file = io.github.tuthan.paddock.files.Snapshot("snap-1", "2026-10-11-143205-shot.png", "image/png", fileBytes.size.toLong(), sha256Of(fileBytes))

    private fun withFiles(exit: Int = 0): Pair<SendController, FakeSession> {
        val files = FakeSession(onStream = { it.exit = exit })
        return SendController(scope, ops, FileTransfers(files, journal) { 5_000_000L }) to files
    }

    @Test fun aFileIsPutFirstAndThenThePromptNamesItOnALineOfItsOwnBeforeTheUsersText() = runBlocking<Unit> {
        val (c, files) = withFiles()
        c.promptWithAttachment(key, "what does this say?", file, sourceOf, keepText = false, folder = "/work/proj")
        until("the outcome") { c.outcomes.value["term_1"] != null }
        val outcome = c.outcomes.value.getValue("term_1")
        assertEquals(OperationKind.Prompt, outcome.kind)
        assertIs<OperationResult.Acknowledged<*>>(outcome.result)
        assertEquals(listOf("Attached file: .paddock/inbox/2026-10-11-143205-shot.png\nwhat does this say?"), prompts)
        assertTrue(files.streams.single().writtenText().startsWith("/work/proj\n2026-10-11-143205-shot.png\n${fileBytes.size}\n"))
        val rows = journal.records.value
        assertEquals(listOf(OperationKind.Attachment, OperationKind.Prompt), rows.map { it.kind })
        assertTrue(rows.all { it.outcome == OperationOutcome.Acknowledged })
        val prompt = rows.last()
        assertEquals(OperationJournal.sha256Hex("what does this say?"), prompt.textSha256, "the draft compares the user's own words")
        assertEquals(OperationJournal.sha256Hex(prompts.single()), prompt.payloadSha256)
        assertEquals(rows.first().payloadSha256, file.sha256)
        assertEquals(1, methods.count { it == "agent.prompt" })
        assertTrue(files.streams.size == 1 && files.streams.single().closed)
        scope.cancel()
    }

    @Test fun aPhotoWithNoWordsIsSentAsJustItsPhraseAndTheDraftComparesTheEmptyText() = runBlocking<Unit> {
        val (c, _) = withFiles()
        c.promptWithAttachment(key, "", file, sourceOf, keepText = false, folder = "/work/proj")
        until("the outcome") { c.outcomes.value["term_1"] != null }
        assertEquals(listOf("Attached file: .paddock/inbox/2026-10-11-143205-shot.png"), prompts, "no line break after it: that would be a second Enter for some agents")
        assertEquals(OperationJournal.sha256Hex(""), journal.records.value.last().textSha256)
        scope.cancel()
    }

    @Test fun aRefusedPutSendsNoPromptAndTheOutcomeIsThePutsOwn() = runBlocking<Unit> {
        val (c, _) = withFiles(exit = TransferRefusal.Unwritable.exit)
        c.promptWithAttachment(key, "hello", file, sourceOf, keepText = false, folder = "/work/proj")
        until("the outcome") { c.outcomes.value["term_1"] != null }
        val o = c.outcomes.value.getValue("term_1")
        assertEquals(OperationKind.Attachment, o.kind)
        val rejected = assertIs<OperationResult.Rejected>(o.result)
        assertEquals("unwritable", rejected.code)
        assertEquals(0, methods.count { it == "agent.prompt" })
        assertEquals(emptyList(), prompts)
        assertEquals(listOf(OperationKind.Attachment), journal.records.value.map { it.kind })
        assertTrue(c.running.value.isEmpty())
        scope.cancel()
    }

    @Test fun aPutRefusedAsExistingUnderEveryNameSendsNoPromptAndSaysSo() = runBlocking<Unit> {
        val (c, files) = withFiles(exit = TransferRefusal.Exists.exit)
        c.promptWithAttachment(key, "hello", file, sourceOf, keepText = false, folder = "/work/proj")
        until("the outcome") { c.outcomes.value["term_1"] != null }
        val o = c.outcomes.value.getValue("term_1")
        assertEquals(OperationKind.Attachment, o.kind)
        assertEquals("exists", assertIs<OperationResult.Rejected>(o.result).code)
        assertEquals(FileTransfers.inboxNames(file.name), streamNames(files), "each name is tried once, in order")
        assertEquals(emptyList(), prompts)
        scope.cancel()
    }

    @Test fun aCutPutSendsNoPromptAndLeavesTheAttachmentUnknownWithoutHoldingTheTerminal() = runBlocking<Unit> {
        val (c, _) = withFiles(exit = 143)
        c.promptWithAttachment(key, "hello", file, sourceOf, keepText = false, folder = "/work/proj")
        until("the outcome") { c.outcomes.value["term_1"] != null }
        assertIs<OperationResult.Unknown>(c.outcomes.value.getValue("term_1").result)
        assertEquals(0, methods.count { it == "agent.prompt" })
        val cut = journal.records.value.single()
        assertTrue(cut.awaitsInboxCheck)
        // A plain prompt is not held by it.
        c.prompt(key, "no file this time", keepText = false)
        until("the plain prompt") { c.outcomes.value["term_1"]?.kind == OperationKind.Prompt }
        assertIs<OperationResult.Acknowledged<*>>(c.outcomes.value.getValue("term_1").result)
        assertEquals(listOf("no file this time"), prompts)
        scope.cancel()
    }

    @Test fun withoutAFolderTheAgentsOwnIsReadAndNoFolderIsRefusedBeforeAnythingIsSent() = runBlocking<Unit> {
        val (c, files) = withFiles()
        // The agent.get answer carries no folder: the put is refused, journaled and said, and no channel opens.
        c.promptWithAttachment(key, "hello", file, sourceOf, keepText = false)
        until("the outcome") { c.outcomes.value["term_1"] != null }
        val o = c.outcomes.value.getValue("term_1")
        assertEquals(OperationKind.Attachment, o.kind)
        assertEquals(TransferRefusal.BadPath.code, assertIs<OperationResult.NotSent>(o.result).reason)
        assertTrue(files.streams.isEmpty())
        assertEquals(0, methods.count { it == "agent.prompt" })
        scope.cancel()
    }

    private val entered = java.util.concurrent.CountDownLatch(1)
    private val release = java.util.concurrent.CountDownLatch(1)

    /** 500 bytes of the file, then a read that does not return until the test lets it (a slow local read, a source that stops mid-stream), then the rest. */
    private val blocking = ByteSource {
        object : java.io.InputStream() {
            private var pos = 0
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (pos == 500) { entered.countDown(); release.await() }
                if (pos >= fileBytes.size) return -1
                val n = minOf(len, fileBytes.size - pos, if (pos < 500) 500 - pos else Int.MAX_VALUE)
                fileBytes.copyInto(b, off, pos, pos + n); pos += n
                return n
            }
        }
    }

    @Test fun cancellingAFileOnItsWayLeavesItsRowUnknownWithTheCutNoPromptAndNothingRunning() = runBlocking<Unit> {
        val (c, files) = withFiles()
        c.promptWithAttachment(key, "hello", file, blocking, keepText = false, folder = "/work/proj")
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS), "the put reached the middle of the file")
        assertEquals(setOf("term_1"), c.running.value)
        c.cancel("term_1")
        release.countDown()
        until("the call to end") { c.running.value.isEmpty() }
        val row = journal.records.value.single()
        assertEquals(OperationKind.Attachment, row.kind)
        assertEquals(OperationOutcome.Unknown, row.outcome, "bytes may have left: the inbox settles it")
        assertTrue(row.note.startsWith("cut after ") && "500 of ${fileBytes.size} bytes" in row.note && row.note.endsWith("cancelled"), "the cut is noted")
        assertTrue(row.awaitsInboxCheck)
        assertEquals(emptyList(), prompts, "no prompt names a file that may be half there")
        assertEquals(0, methods.count { it == "agent.prompt" })
        assertNull(c.outcomes.value["term_1"], "a cancelled call keeps no outcome")
        assertTrue(files.streams.single().closed)
        // Another call on the terminal can start again: a plain prompt is not held by a cut attachment.
        c.prompt(key, "no file this time", keepText = false)
        until("the plain prompt") { c.outcomes.value["term_1"]?.kind == OperationKind.Prompt }
        scope.cancel()
    }

    @Test fun cancellingWhenNothingRunsOrForAnotherTerminalLeavesTheRunningCallAlone() = runBlocking<Unit> {
        val (c, _) = withFiles()
        c.cancel("term_1") // nothing is running
        assertTrue(c.running.value.isEmpty())
        c.promptWithAttachment(key, "hello", file, blocking, keepText = false, folder = "/work/proj")
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        c.cancel("term_other")
        delay(100)
        assertEquals(setOf("term_1"), c.running.value, "another terminal's cancel is not this one's")
        release.countDown()
        until("the outcome") { c.outcomes.value["term_1"]?.kind == OperationKind.Prompt }
        assertIs<OperationResult.Acknowledged<*>>(c.outcomes.value.getValue("term_1").result)
        until("nothing running") { c.running.value.isEmpty() }
        c.cancel("term_1") // already over: nothing to cancel, and the next call is not touched by it
        c.prompt(key, "again", keepText = false)
        until("the second outcome") { prompts.size == 2 && c.running.value.isEmpty() }
        assertIs<OperationResult.Acknowledged<*>>(c.outcomes.value.getValue("term_1").result)
        scope.cancel()
    }

    @Test fun theTransferDeadlineIsAskedWithTheFilesSizeAndGovernsThePut() = runBlocking<Unit> {
        val seen = CopyOnWriteArrayList<Long>()
        val files = FakeSession(onStream = { it.exit = 0 })
        val c = SendController(scope, ops, FileTransfers(files, journal) { 5_000_000L }, transferDeadline = { size -> seen += size; kotlin.time.Duration.ZERO })
        c.promptWithAttachment(key, "hello", file, sourceOf, keepText = false, folder = "/work/proj")
        until("the outcome") { c.outcomes.value["term_1"] != null }
        assertEquals(listOf(file.size), seen)
        val o = c.outcomes.value.getValue("term_1")
        assertEquals(OperationKind.Attachment, o.kind)
        assertIs<OperationResult.Unknown>(o.result, "no time at all is a put that was cut, not one that was refused or finished")
        assertEquals(emptyList(), prompts)
        scope.cancel()
    }

    // ---- sending the same file again: the machine's inbox, as the put script keeps it ----------------------------------------------

    /** Names the machine's inbox holds. A put on one of them is refused as existing, as the script's `link` refuses it; otherwise the name is given. */
    private val published = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** A machine whose put script publishes into [published]; [lostAnswer] publishes the file but loses the exit status with the link (143). */
    private fun machine(lostAnswer: Boolean = false, onExec: (List<String>, ByteArray?) -> io.github.tuthan.paddock.ports.ExecResult = { _, _ -> FakeSession.result(0) }) =
        FakeSession(onExec = onExec, onStream = { s ->
            var header = true
            s.beforeWrite = { bytes ->
                if (header) {
                    header = false
                    val name = bytes.toString(Charsets.UTF_8).lines()[1]
                    s.exit = if (!published.add(name)) TransferRefusal.Exists.exit else if (lostAnswer) 143 else 0
                }
            }
        })

    private fun streamNames(s: FakeSession) = s.streams.map { it.writtenText().lines()[1] }

    @Test fun aPromptThatDidNotGoAfterItsFileLandedIsSentAgainWithoutWritingTheFileAgain() = runBlocking<Unit> {
        val files = machine()
        val c = SendController(scope, ops, FileTransfers(files, journal) { 5_000_000L })
        status = "working"
        c.promptWithAttachment(key, "hello", file, sourceOf, keepText = false, folder = "/work/proj")
        until("the first outcome") { c.outcomes.value["term_1"] != null && c.running.value.isEmpty() }
        assertIs<OperationResult.NotSent>(c.outcomes.value.getValue("term_1").result, "the file landed, the prompt did not go")
        assertEquals(listOf(OperationKind.Attachment, OperationKind.Prompt), journal.records.value.map { it.kind })

        // The draft still holds the same snapshot; Send again.
        status = "idle"
        c.promptWithAttachment(key, "hello", file, sourceOf, keepText = false, folder = "/work/proj")
        until("the second outcome") { c.outcomes.value["term_1"]?.kind == OperationKind.Prompt && c.running.value.isEmpty() && prompts.isNotEmpty() }
        assertIs<OperationResult.Acknowledged<*>>(c.outcomes.value.getValue("term_1").result)
        assertEquals(1, files.streams.size, "the file is not written again: the journal shows it there")
        assertEquals(listOf("Attached file: .paddock/inbox/${file.name}\nhello"), prompts)
        assertEquals(listOf(OperationKind.Attachment, OperationKind.Prompt, OperationKind.Prompt), journal.records.value.map { it.kind })
        scope.cancel()
    }

    @Test fun aCutFileTheInboxShowedThereWholeIsNotWrittenAgainAndOnlyThePromptGoes() = runBlocking<Unit> {
        val files = machine(lostAnswer = true, onExec = { _, _ -> FakeSession.result(0, "present\t${file.size}\n") })
        val transfers = FileTransfers(files, journal) { 5_000_000L }
        val c = SendController(scope, ops, transfers)
        c.promptWithAttachment(key, "hello", file, sourceOf, keepText = false, folder = "/work/proj")
        until("the cut") { c.outcomes.value["term_1"]?.result is OperationResult.Unknown && c.running.value.isEmpty() }
        assertTrue(journal.records.value.single().awaitsInboxCheck)
        // "Read the inbox": the file is there, at its whole size.
        assertIs<TransferReply.Ok<*>>(transfers.check(key, "/work/proj", file.name))

        c.promptWithAttachment(key, "hello", file, sourceOf, keepText = false, folder = "/work/proj")
        until("the prompt") { c.outcomes.value["term_1"]?.kind == OperationKind.Prompt && c.running.value.isEmpty() }
        assertIs<OperationResult.Acknowledged<*>>(c.outcomes.value.getValue("term_1").result)
        assertEquals(1, files.streams.size, "a published file is whole: the script names it only after every byte")
        assertEquals(listOf("Attached file: .paddock/inbox/${file.name}\nhello"), prompts)
        scope.cancel()
    }

    @Test fun aNameAnotherFileHoldsIsPassedOverAndThePromptNamesTheFileWhereItLanded() = runBlocking<Unit> {
        val files = machine()
        published += file.name // another file got there first, with no row on this phone
        val c = SendController(scope, ops, FileTransfers(files, journal) { 5_000_000L })
        c.promptWithAttachment(key, "hello", file, sourceOf, keepText = false, folder = "/work/proj")
        until("the outcome") { c.outcomes.value["term_1"] != null && c.running.value.isEmpty() }
        val second = io.github.tuthan.paddock.files.FileNames.retryName(file.name, 2)
        assertEquals(listOf(file.name, second), streamNames(files))
        assertIs<OperationResult.Acknowledged<*>>(c.outcomes.value.getValue("term_1").result)
        assertEquals(listOf("Attached file: .paddock/inbox/$second\nhello"), prompts, "the prompt names the file that was written")
        val row = journal.records.value.last()
        assertEquals(OperationKind.Prompt, row.kind)
        assertEquals("/work/proj/.paddock/inbox/$second", row.path, "the prompt's row keeps the file it named")
        assertEquals(file.size, row.size)
        // And the draft lets go of this file for it, under the name the inbox gave.
        val draft = PromptDraft().typed("term_1", "hello").attached("term_1", DraftAttachment(file.id, file.name, file.size, file.mime))
        assertNull(draft.accepted("term_1", row).attachmentFor("term_1"))
        scope.cancel()
    }

    @Test fun aControllerWithoutTransfersRefusesAnAttachmentAndAPromptThatCannotFitIsRefusedAtOnce() = runBlocking<Unit> {
        val plain = SendController(scope, ops)
        assertFailsWith<IllegalStateException> { plain.promptWithAttachment(key, "x", file, sourceOf, false, "/w") }
        val (c, _) = withFiles()
        assertFailsWith<IllegalArgumentException> { c.promptWithAttachment(key, "x".repeat(AgentOperations.MAX_PROMPT_CHARS), file, sourceOf, false, "/w") }
        assertFailsWith<IllegalArgumentException> { c.promptWithAttachment(key, "x", file.copy(name = "bad name;rm"), sourceOf, false, "/w") }
        assertEquals(emptyList(), journal.records.value, "nothing was started")
        scope.cancel()
    }
}
