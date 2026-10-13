package io.github.tuthan.paddock.files

import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.identity.TargetRef
import io.github.tuthan.paddock.identity.TerminalKey
import io.github.tuthan.paddock.ops.InMemoryJournalStore
import io.github.tuthan.paddock.ops.OperationJournal
import io.github.tuthan.paddock.ops.OperationKind
import io.github.tuthan.paddock.ports.ExecLimits
import io.github.tuthan.paddock.ports.ExecResult
import io.github.tuthan.paddock.ports.LinkState
import io.github.tuthan.paddock.ports.SshSession
import io.github.tuthan.paddock.ports.StreamChannel
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [GetController] over a scripted session and the real [FileTransfers] and [TransferCache]: the look, the offer, the read into a name of the cache's own,
 * the refusals as sentences, a cancel that leaves no part, and one Get at a time. What the scripts themselves do is [io.github.tuthan.paddock.files.FileTransfers]'
 * own test in `:core`.
 */
class GetControllerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun stop() { scope.cancel() }

    private val key = TerminalKey(TargetRef(HostProfileId("m1"), "paddock-test", "term_1"), epoch = 1)
    private val journal = OperationJournal(InMemoryJournalStore()) { 1_000L }
    private val content = ByteArray(40_000) { (it * 13 + 5).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }

    private class Wire : StreamChannel {
        @Volatile var out: List<ByteArray> = emptyList()
        @Volatile var exit = 0
        @Volatile var hang = false
        @Volatile var closed = false
        val written = StringBuilder()
        override val stdout: Flow<ByteArray> = flow { out.forEach { emit(it) }; if (hang) awaitCancellation() }
        override val stderr: Flow<ByteArray> = emptyFlow()
        override suspend fun write(bytes: ByteArray) { written.append(bytes.toString(Charsets.UTF_8)) }
        override suspend fun closeStdin() = Unit
        override suspend fun awaitExit(): Int = exit
        override suspend fun close() { closed = true }
    }

    private class Link(val stat: (String) -> ExecResult) : SshSession {
        override val link: StateFlow<LinkState> = MutableStateFlow(LinkState.Up(0))
        val asked = CopyOnWriteArrayList<String>()
        val wires = CopyOnWriteArrayList<Wire>()
        @Volatile var wire: () -> Wire = { Wire() }
        override suspend fun exec(argv: List<String>, stdin: ByteArray?, limits: ExecLimits): ExecResult {
            val text = stdin?.toString(Charsets.UTF_8).orEmpty()
            asked += text
            return stat(text)
        }
        override suspend fun openStream(argv: List<String>): StreamChannel { if (closedDown) throw IOException("no channel"); return wire().also { wires += it } }
        @Volatile var closedDown = false
        override suspend fun close() = Unit
    }

    private fun answer(exit: Int, out: String = "") = ExecResult(exit, out.toByteArray(), ByteArray(0), false, false, Duration.ZERO)
    private fun chunks(b: ByteArray, n: Int) = b.toList().chunked(b.size / n + 1).map { it.toByteArray() }

    private fun controller(link: Link, apk: (File) -> List<String> = { emptyList() }, deadline: (Long) -> Duration = { 5.seconds }): GetController {
        val transfers = FileTransfers(link, journal) { 1_000L }
        return GetController(scope, { transfers }, TransferCache(tmp.root), "m1", apk, maxBytes = 1_000_000, deadline = deadline, newId = { "fetch1" })
    }

    private fun statOf(path: String, size: Long = content.size.toLong()) = Link { answer(0, "$size\t1791700000\t$path\n") }

    private suspend fun GetController.until(what: String, test: (GetState?) -> Boolean): GetState? {
        try { withTimeout(5_000) { while (!test(state.value)) delay(5) } } catch (_: kotlinx.coroutines.TimeoutCancellationException) { throw AssertionError("never reached $what; state is ${state.value}") }
        return state.value
    }

    @Test fun aTapOnARelativePathAsksTheMachineAboutThePathUnderTheFolderAndOffersIt() = runBlocking<Unit> {
        val link = statOf("/work/proj/out/report.pdf")
        val c = controller(link)
        c.look("/work/proj", key, "out/report.pdf")
        val offer = c.until("an offer") { it is GetState.Offer } as GetState.Offer

        assertTrue("the folder and the resolved path went to the machine on stdin", link.asked.single().startsWith("/work/proj\n/work/proj/out/report.pdf\n"))
        assertEquals("report.pdf", offer.look.shownName)
        assertFalse(offer.look.nameChanged)
        assertEquals("/work/proj/out", offer.look.shownFolder)
        assertEquals(OpenableTypes.Kind.Openable("application/pdf"), offer.look.kind)
    }

    /** The controller is the machine's and the agent's, not one connection's: while the connection is away it says so, and it uses the transfers that come back. */
    @Test fun withNoConnectionALookOrAReadSaysSoAndTheTransfersThatComeBackAreUsed() = runBlocking<Unit> {
        val link = statOf("/work/proj/out/report.pdf")
        var now: FileTransfers? = null
        val c = GetController(scope, { now }, TransferCache(tmp.root), "m1", maxBytes = 1_000_000, newId = { "fetch1" })
        c.look("/work/proj", key, "out/report.pdf")
        assertEquals(GetState.Failed(GetController.NOT_CONNECTED), c.state.value)
        c.dismiss()
        now = FileTransfers(link, journal) { 1_000L }
        c.look("/work/proj", key, "out/report.pdf")
        assertTrue(c.until("an offer") { it is GetState.Offer } is GetState.Offer)
        now = null
        c.get()
        assertEquals("a read with the connection gone says so", GetState.Failed(GetController.NOT_CONNECTED), c.state.value)
        assertTrue("nothing was kept", File(tmp.root, "transfers").walkTopDown().none { it.isFile })
    }

    @Test fun aPathOutsideTheFolderIsRefusedHereWithoutAskingTheMachine() = runBlocking<Unit> {
        val link = statOf("/etc/passwd")
        val c = controller(link)
        for (token in listOf("/etc/passwd", "../x.txt", "..", "/work/proj", "/work/proj/../other/f.txt")) {
            c.look("/work/proj", key, token)
            assertEquals(token, GetState.Failed(GetController.NOT_UNDER_FOLDER), c.state.value)
            c.dismiss()
        }
        assertTrue("nothing was asked", link.asked.isEmpty())
    }

    @Test fun everyRefusalOfTheStatIsASentenceAndNothingIsKept() = runBlocking<Unit> {
        for (r in listOf(TransferRefusal.NotFound, TransferRefusal.OutsideFolder, TransferRefusal.Symlink, TransferRefusal.NotRegular, TransferRefusal.OverCap, TransferRefusal.NotYours)) {
            val c = controller(Link { answer(r.exit) })
            c.look("/work/proj", key, "f.txt")
            assertEquals(GetState.Failed(r.sentence), c.until("a refusal") { it is GetState.Failed })
        }
    }

    @Test fun aStatThatDoesNotMakeSenseOrALostLinkIsAFailureWithAReason() = runBlocking<Unit> {
        val garbled = controller(Link { answer(0, "nonsense") })
        garbled.look("/work/proj", key, "f.txt")
        assertTrue((garbled.until("a failure") { it is GetState.Failed } as GetState.Failed).sentence.startsWith("Could not look at the file"))
    }

    @Test fun aNameWithABidirectionalOverrideIsShownCleanedAndSavedUnderTheReducedName() = runBlocking<Unit> {
        val c = controller(statOf("/work/proj/gpj.‮txt"))
        c.look("/work/proj", key, "gpj.txt")
        val offer = c.until("an offer") { it is GetState.Offer } as GetState.Offer
        assertEquals("gpj.txt", offer.look.shownName)
        assertTrue("the change is said", offer.look.nameChanged)
        assertEquals("a run of anything else is one underscore", "gpj._txt", offer.look.cachedName)
    }

    @Test fun getReadsTheWholeFileIntoANameOfTheCachesOwnAndKeepsAFetchRow() = runBlocking<Unit> {
        val link = statOf("/work/proj/out/report.txt")
        link.wire = { Wire().also { it.out = chunks(content, 7) } }
        val c = controller(link)
        c.look("/work/proj", key, "out/report.txt")
        c.until("an offer") { it is GetState.Offer }
        c.get()
        val ready = c.until("the file") { it is GetState.Ready } as GetState.Ready
        assertEquals(File(tmp.root, "transfers/m1/fetch1/report.txt").canonicalFile, ready.file.canonicalFile)
        assertTrue(content.contentEquals(ready.file.readBytes()))
        val row = journal.records.value.single()
        assertEquals(OperationKind.Fetch, row.kind)
        assertEquals(sha, row.payloadSha256)
        assertEquals("/work/proj/out/report.txt", row.path)
        assertEquals("/work/proj\n/work/proj/out/report.txt\n${content.size}\n", link.wires.single().written.toString())
        assertTrue("the channel was closed", link.wires.single().closed)
    }

    @Test fun anApkIsReadForItsFactsAndAnythingElseIsNotAsked() = runBlocking<Unit> {
        val asked = CopyOnWriteArrayList<String>()
        for ((name, expectedFacts) in listOf("tool.apk" to listOf("Package x"), "report.txt" to emptyList())) {
            val link = statOf("/work/proj/$name")
            link.wire = { Wire().also { it.out = chunks(content, 3) } }
            val c = controller(link, apk = { f -> asked += f.name; listOf("Package x") })
            c.look("/work/proj", key, name)
            c.until("an offer") { it is GetState.Offer }
            c.get()
            assertEquals(name, expectedFacts, (c.until("the file") { it is GetState.Ready } as GetState.Ready).apk)
        }
        assertEquals("only the APK was looked into", listOf("tool.apk"), asked.toList())
    }

    @Test fun aShortReadIsAFailureAndLeavesNoPartInTheCache() = runBlocking<Unit> {
        val link = statOf("/work/proj/f.txt")
        link.wire = { Wire().also { it.out = chunks(content.copyOf(content.size - 100), 5) } }
        val c = controller(link)
        c.look("/work/proj", key, "f.txt")
        c.until("an offer") { it is GetState.Offer }
        c.get()
        val failed = c.until("a failure") { it is GetState.Failed } as GetState.Failed
        assertTrue(failed.sentence, failed.sentence.startsWith("The file did not arrive"))
        assertFalse("no part stays", File(tmp.root, "transfers/m1/fetch1").exists())
        assertTrue("no Fetch row: nothing arrived whole", journal.records.value.isEmpty())
    }

    @Test fun theMachinesRefusalOfTheReadIsItsSentenceAndLeavesNoPart() = runBlocking<Unit> {
        val link = statOf("/work/proj/f.txt")
        link.wire = { Wire().also { it.exit = TransferRefusal.Changed.exit } }
        val c = controller(link)
        c.look("/work/proj", key, "f.txt")
        c.until("an offer") { it is GetState.Offer }
        c.get()
        assertEquals(GetState.Failed(TransferRefusal.Changed.sentence), c.until("a refusal") { it is GetState.Failed })
        assertFalse(File(tmp.root, "transfers/m1/fetch1").exists())
    }

    @Test fun cancellingTheReadClosesTheSheetAndDeletesThePart() = runBlocking<Unit> {
        val link = statOf("/work/proj/f.txt")
        link.wire = { Wire().also { it.out = chunks(content.copyOf(10_000), 2); it.hang = true } }
        val c = controller(link, deadline = { 60.seconds })
        c.look("/work/proj", key, "f.txt")
        c.until("an offer") { it is GetState.Offer }
        c.get()
        c.until("some bytes") { (it as? GetState.Reading)?.read?.let { n -> n > 0 } == true }
        c.cancel()
        assertNull(c.state.value)
        withTimeout(5_000) { while (File(tmp.root, "transfers/m1/fetch1").exists()) delay(5) }
        assertTrue("the channel was closed", link.wires.single().closed)
        assertTrue("no Fetch row", journal.records.value.isEmpty())
    }

    @Test fun aSecondTapWhileOneIsRunningIsIgnored() = runBlocking<Unit> {
        val link = statOf("/work/proj/f.txt")
        link.wire = { Wire().also { it.hang = true } }
        val c = controller(link, deadline = { 60.seconds })
        c.look("/work/proj", key, "f.txt")
        c.until("an offer") { it is GetState.Offer }
        c.get()
        c.until("reading") { it is GetState.Reading }
        withTimeout(5_000) { while (link.wires.isEmpty()) delay(5) }
        c.look("/work/proj", key, "other.txt")
        c.get()
        assertEquals("one stat and one channel", 1, link.asked.size)
        assertEquals(1, link.wires.size)
        assertTrue(c.state.value is GetState.Reading)
        c.cancel()
    }

    @Test fun theReadHasTheDeadlineTheCallerGaveForTheStatsSize() = runBlocking<Unit> {
        val link = statOf("/work/proj/f.txt")
        link.wire = { Wire().also { it.hang = true } }
        var asked: Long? = null
        val c = controller(link, deadline = { n -> asked = n; 150.milliseconds })
        c.look("/work/proj", key, "f.txt")
        c.until("an offer") { it is GetState.Offer }
        c.get()
        val failed = c.until("a failure") { it is GetState.Failed } as GetState.Failed
        assertEquals(content.size.toLong(), asked)
        assertTrue(failed.sentence, "longer than" in failed.sentence)
        assertFalse(File(tmp.root, "transfers/m1/fetch1").exists())
    }

    @Test fun closingTheControllerCancelsARunningReadAndDeletesItsPart() = runBlocking<Unit> {
        val link = statOf("/work/proj/f.txt")
        link.wire = { Wire().also { it.out = chunks(content.copyOf(5_000), 1); it.hang = true } }
        val c = controller(link, deadline = { 60.seconds })
        c.look("/work/proj", key, "f.txt")
        c.until("an offer") { it is GetState.Offer }
        c.get()
        c.until("some bytes") { (it as? GetState.Reading)?.read?.let { n -> n > 0 } == true }
        c.close()
        withTimeout(5_000) { while (File(tmp.root, "transfers/m1/fetch1").exists()) delay(5) }
        assertNotNull(link.wires.single())
    }

    private val Int.milliseconds get() = kotlin.time.Duration.parse("${this}ms")
}
