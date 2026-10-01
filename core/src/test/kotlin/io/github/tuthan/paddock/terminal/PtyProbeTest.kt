package io.github.tuthan.paddock.terminal

import io.github.tuthan.paddock.cli.HerdrCli
import io.github.tuthan.paddock.ports.ExecLimits
import io.github.tuthan.paddock.ports.ExecResult
import io.github.tuthan.paddock.ports.LinkState
import io.github.tuthan.paddock.ports.SshSession
import io.github.tuthan.paddock.ports.StreamChannel
import kotlin.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PtyProbeTest {
    /** Captured from herdr 0.9.1 and the host's `stty` on 2026-10-02 (a 60x20 pane of the throwaway session); see resources/terminal/host/README.md. */
    private fun host(name: String) = javaClass.getResource("/terminal/host/$name")!!.readText()
    private val processInfo = host("pane-process-info.json")
    private val cli = HerdrCli("/usr/bin/herdr", "paddock-test")

    private class Host(val answers: (List<String>) -> ExecResult) : SshSession {
        override val link = MutableStateFlow<LinkState>(LinkState.Up(0))
        val seen = mutableListOf<List<String>>()
        override suspend fun exec(argv: List<String>, stdin: ByteArray?, limits: ExecLimits): ExecResult { seen += argv; return answers(argv) }
        override suspend fun openStream(argv: List<String>): StreamChannel = error("not used")
        override suspend fun close() = Unit
    }
    private fun ok(out: String) = ExecResult(0, out.toByteArray(), ByteArray(0), false, false, Duration.ZERO)
    private fun failed(code: Int = 1, err: String = "") = ExecResult(code, ByteArray(0), err.toByteArray(), false, false, Duration.ZERO)

    @Test fun theShellPidIsReadFromTheRealProcessInfoAnswer() {
        assertEquals(2126114, PtyProbe.shellPid(processInfo))
    }

    @Test fun anAnswerWithoutAShellPidIsNull() {
        for (text in listOf("", "not json", """{"id":"x","result":{"type":"pane_process_info","process_info":{}}}""",
            """{"id":"x","result":{"type":"pane_process_info"}}""", """{"id":"x","result":{"process_info":{"shell_pid":0}}}""",
            """{"id":"x","result":{"process_info":{"shell_pid":"12"}}}""", """{"id":"x","error":{"code":"pane_not_found","message":"no"}}""")) {
            assertNull(PtyProbe.shellPid(text), text)
        }
    }

    @Test fun sttyPrintsRowsThenColumnsAndTheProbeReturnsThemTheOtherWayRound() {
        assertEquals(PtySize(cols = 60, rows = 20), PtyProbe.parseStty(host("stty-size.txt")))
        assertEquals(PtySize(120, 40), PtyProbe.parseStty(" 40   120 \n"))
    }

    @Test fun sizesThatAreNotSizesAreRefused() {
        for (text in listOf("", "0 0", "20", "20 60 1", "a b", "-1 80", "20 60x", "999999 80", "40 5000")) assertNull(PtyProbe.parseStty(text), text)
    }

    @Test fun theProbeAsksHerdrForTheShellThenReadsItsTerminalSize() {
        val host = Host { argv -> if (argv.first() == "stty") ok("20 60\n") else ok(processInfo) }
        assertEquals(PtySize(60, 20), runBlocking { PtyProbe.probe(host, cli, "w2:p5P") })
        assertEquals(listOf("/usr/bin/herdr", "--session", "paddock-test", "pane", "process-info", "--pane", "w2:p5P"), host.seen[0])
        assertEquals(listOf("stty", "-F", "/proc/2126114/fd/0", "size"), host.seen[1])
    }

    @Test fun anyFailureIsANullAndNothingIsGuessed() {
        assertNull(runBlocking { PtyProbe.probe(Host { failed(1, "pane not found") }, cli, "w2:p5P") })
        assertNull(runBlocking { PtyProbe.probe(Host { argv -> if (argv.first() == "stty") failed(1, "stty: invalid argument") else ok(processInfo) }, cli, "w2:p5P") })
        assertNull(runBlocking { PtyProbe.probe(Host { argv -> if (argv.first() == "stty") ok("garbage") else ok(processInfo) }, cli, "w2:p5P") })
        assertNull(runBlocking { PtyProbe.probe(Host { throw java.io.IOException("link dropped") }, cli, "w2:p5P") })
    }

    @Test fun aPaneIdThatCouldBeAFlagNeverReachesTheHost() {
        val host = Host { ok(processInfo) }
        assertNull(runBlocking { PtyProbe.probe(host, cli, "--help") })
        assertEquals(emptyList(), host.seen)
    }
}
