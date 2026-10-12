package io.github.tuthan.paddock.files

import io.github.tuthan.paddock.ops.InMemoryJournalStore
import io.github.tuthan.paddock.ops.OperationJournal
import io.github.tuthan.paddock.ports.ExecLimits
import io.github.tuthan.paddock.ports.ExecResult
import io.github.tuthan.paddock.ports.LinkState
import io.github.tuthan.paddock.ports.SshSession
import io.github.tuthan.paddock.ports.StreamChannel
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking

/** What the machine's page says about a drop folder (X10): resolve, then list, and nothing else; the machine's words are cleaned. */
class DropFolderCheckTest {
    private class Link(val answer: (List<String>, String) -> ExecResult) : SshSession {
        override val link: StateFlow<LinkState> = MutableStateFlow(LinkState.Up(0))
        val execs = CopyOnWriteArrayList<Pair<List<String>, String>>()
        override suspend fun exec(argv: List<String>, stdin: ByteArray?, limits: ExecLimits): ExecResult {
            val text = stdin?.toString(Charsets.UTF_8).orEmpty()
            execs += argv to text
            return answer(argv, text)
        }
        override suspend fun openStream(argv: List<String>): StreamChannel = error("a check never opens a stream")
        override suspend fun close() = Unit
    }

    private fun res(exit: Int = 0, out: String = "") = ExecResult(exit, out.toByteArray(), ByteArray(0), false, false, Duration.ZERO)
    private fun transfers(link: Link) = FileTransfers(link, OperationJournal(InMemoryJournalStore()) { 1L }) { 1L }
    private fun machine(resolve: ExecResult, list: ExecResult) = Link { argv, _ -> if (argv == TransferScripts.resolveDropArgv()) resolve else list }

    @Test fun `a folder that is there says what is in it, newest first`() = runBlocking<Unit> {
        val link = machine(res(out = "/home/jdoe/Paddock\n"), res(out = "b.png\t2048\t1700000100\na.txt\t5\t1700000000\n"))
        val ok = assertIs<DropFolderCheck.Result.Ok>(DropFolderCheck.check(transfers(link), "~/Paddock"))
        assertEquals("/home/jdoe/Paddock", ok.real)
        assertTrue(ok.exists)
        assertEquals(listOf("b.png", "a.txt"), ok.files.map { it.name })
        assertEquals("~/Paddock is /home/jdoe/Paddock on this machine.", DropFolderCheck.sentence(ok))
        assertEquals(listOf(TransferScripts.resolveDropArgv(), TransferScripts.listArgv()), link.execs.map { it.first })
        assertEquals("~/Paddock\n", link.execs[0].second)
        assertEquals("/home/jdoe/Paddock\n", link.execs[1].second, "the list asks about the real path the machine answered, not the typed value")
    }

    @Test fun `a folder that is not there yet is said to be created by the first file, and lists nothing`() = runBlocking<Unit> {
        val link = machine(res(out = "/home/jdoe/Paddock\n"), res(exit = TransferRefusal.NotFound.exit))
        val ok = assertIs<DropFolderCheck.Result.Ok>(DropFolderCheck.check(transfers(link), "~/Paddock"))
        assertFalse(ok.exists)
        assertTrue(ok.files.isEmpty())
        assertEquals("~/Paddock is /home/jdoe/Paddock on this machine and will be created when the first file arrives.", DropFolderCheck.sentence(ok))
    }

    @Test fun `the machine's refusal of the folder is the sentence, and the list is never asked`() = runBlocking<Unit> {
        for (why in listOf(TransferRefusal.Home, TransferRefusal.Hidden, TransferRefusal.Symlink, TransferRefusal.OutsideFolder)) {
            val link = machine(res(exit = why.exit), res())
            assertEquals(DropFolderCheck.Result.Problem(why.sentence), DropFolderCheck.check(transfers(link), "~/x"), why.name)
            assertEquals(1, link.execs.size, "only the resolve was asked")
        }
    }

    @Test fun `a value the phone refuses by itself reaches the machine not at all`() = runBlocking<Unit> {
        val link = machine(res(), res())
        for (v in listOf("", "relative", "~other/x", "~/a\u0001")) assertEquals(DropFolderCheck.Result.Problem(TransferRefusal.BadPath.sentence), DropFolderCheck.check(transfers(link), v))
        assertTrue(link.execs.isEmpty())
    }

    @Test fun `a lost link or an answer that makes no sense is a problem in a person's words`() = runBlocking<Unit> {
        val lost = DropFolderCheck.check(transfers(machine(res(exit = 143), res())), "~/Paddock")
        assertTrue(assertIs<DropFolderCheck.Result.Problem>(lost).sentence.startsWith("Could not ask the machine"))
        val odd = DropFolderCheck.check(transfers(machine(res(out = "relative\n"), res())), "~/Paddock")
        assertIs<DropFolderCheck.Result.Problem>(odd)
        val listLost = DropFolderCheck.check(transfers(machine(res(out = "/home/jdoe/Paddock\n"), res(exit = 143))), "~/Paddock")
        assertIs<DropFolderCheck.Result.Problem>(listLost)
    }

    @Test fun `a failure's own words are cleaned and bounded before they are shown`() = runBlocking<Unit> {
        val hostile = "the link \u202Elost \u001B[31m" + "x".repeat(500)
        val lost = DropFolderCheck.check(transfers(Link { _, _ -> throw java.io.IOException(hostile) }), "~/Paddock")
        val sentence = assertIs<DropFolderCheck.Result.Problem>(lost).sentence
        assertTrue(sentence.startsWith("Could not ask the machine"), sentence)
        assertTrue(sentence.none { it == '\u202E' || it == '\u001B' }, sentence)
        assertTrue(sentence.length < 200, "bounded: ${sentence.length}")
    }

    @Test fun `the machine's words are cleaned before they are shown`() {
        val ok = DropFolderCheck.Result.Ok("~/Paddock", "/home/j‮doe/Pad\u001B[31mdock", exists = true, files = emptyList())
        val line = DropFolderCheck.sentence(ok)
        assertTrue(line.none { it == '‮' || it == '\u001B' }, line)
    }
}
