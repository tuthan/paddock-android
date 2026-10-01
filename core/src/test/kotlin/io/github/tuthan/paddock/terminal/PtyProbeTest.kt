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

    @Test fun theControllingTerminalIsReadFromTheRealStatLine() {
        assertEquals(18, PtyProbe.controllingPts(host("proc-stat.txt")))
    }

    @Test fun theStatLineIsCountedFromItsLastBracketSoANameWithSpacesAndBracketsCannotShiftIt() {
        assertEquals(18, PtyProbe.controllingPts("12 (a b) c) S 1 12 12 34834 12 4194304"))
        assertEquals(256 + 3, PtyProbe.controllingPts("12 (x) S 1 12 12 ${(137 shl 8) or 3} 12 0"), "pts numbers past 255 use the next major")
        assertEquals(300, PtyProbe.controllingPts("12 (x) S 1 12 12 ${(137 shl 8) or (300 - 256)} 12 0"))
        assertEquals(1000, PtyProbe.controllingPts("12 (x) S 1 12 12 ${(139 shl 8) or 232} 12 0"))
    }

    @Test fun aProcessWithoutAPtyHasNone() {
        for (stat in listOf("", "12 x S 1 12 12 0 12", "12 (x) S 1 12 12 0 12", "12 (x) S 1 12 12 1025 12 0", "12 (x) S 1 12 12 ${4 shl 8} 12 0", "12 (x) S 1 12", "12 (x) S 1 12 12 notanumber 12")) assertNull(PtyProbe.controllingPts(stat), stat)
    }

    @Test fun sttyPrintsRowsThenColumnsAndTheProbeReturnsThemTheOtherWayRound() {
        assertEquals(PtySize(cols = 60, rows = 20), PtyProbe.parseStty(host("stty-size.txt")))
        assertEquals(PtySize(120, 40), PtyProbe.parseStty(" 40   120 \n"))
    }

    @Test fun sizesThatAreNotSizesAreRefused() {
        for (text in listOf("", "0 0", "20", "20 60 1", "a b", "-1 80", "20 60x", "999999 80", "40 5000")) assertNull(PtyProbe.parseStty(text), text)
    }

    private fun answers(stat: (Int) -> ExecResult = { ok(host("proc-stat.txt")) }, stty: (List<String>) -> ExecResult = { ok("20 60\n") }) = Host { argv ->
        when (argv.first()) { "cat" -> stat(argv[1].substringAfter("/proc/").substringBefore("/").toInt()); "stty" -> stty(argv); else -> ok(processInfo) }
    }

    @Test fun theProbeAsksHerdrForAProcessThenForItsControllingTerminalAndThatTerminalsSize() {
        val host = answers()
        assertEquals(PtySize(60, 20), runBlocking { PtyProbe.probe(host, cli, "w2:p5P") })
        assertEquals(listOf("/usr/bin/herdr", "--session", "paddock-test", "pane", "process-info", "--pane", "w2:p5P"), host.seen[0])
        assertEquals(listOf("cat", "/proc/2126114/stat"), host.seen[1])
        assertEquals(listOf("stty", "-F", "/dev/pts/18", "size"), host.seen[2])
        assertEquals(3, host.seen.size)
    }

    @Test fun aJobThatEndedBetweenTheCallsIsRetriedOnce() {
        var calls = 0
        val host = answers(stat = { calls++; if (calls == 1) failed(1, "cat: No such file or directory") else ok(host("proc-stat.txt")) })
        assertEquals(PtySize(60, 20), runBlocking { PtyProbe.probe(host, cli, "w2:p5P") })
        assertEquals(2, host.seen.count { it.first() == "cat" })
    }

    @Test fun anyOtherFailureIsANullAfterTwoAttemptsAndNothingIsGuessed() {
        assertNull(runBlocking { PtyProbe.probe(Host { failed(1, "pane not found") }, cli, "w2:p5P") })
        assertNull(runBlocking { PtyProbe.probe(answers(stat = { failed(1) }), cli, "w2:p5P") })
        assertNull(runBlocking { PtyProbe.probe(answers(stat = { ok("12 (x) S 1 12 12 0 12") }), cli, "w2:p5P") })
        assertNull(runBlocking { PtyProbe.probe(answers(stty = { failed(1, "stty: invalid argument") }), cli, "w2:p5P") })
        assertNull(runBlocking { PtyProbe.probe(answers(stty = { ok("garbage") }), cli, "w2:p5P") })
        assertNull(runBlocking { PtyProbe.probe(Host { throw java.io.IOException("link dropped") }, cli, "w2:p5P") })
    }

    @Test fun aPaneIdThatCouldBeAFlagNeverReachesTheHost() {
        val host = Host { ok(processInfo) }
        assertNull(runBlocking { PtyProbe.probe(host, cli, "--help") })
        assertEquals(emptyList(), host.seen)
    }
}
