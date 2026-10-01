package io.github.tuthan.paddock.cli

import io.github.tuthan.paddock.herdr.AgentListResult
import io.github.tuthan.paddock.herdr.ProtocolError
import io.github.tuthan.paddock.ports.ExecResult
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class HerdrCliTest {
    private val dir = File(System.getProperty("paddock.repoRoot"), "fixtures/herdr-0.9.1")
    private val cli = HerdrCli("/usr/bin/herdr", "paddock-test")
    private fun result(exit: Int, out: String = "", err: String = "", truncated: Boolean = false) =
        ExecResult(exit, out.toByteArray(), err.toByteArray(), truncated, false, kotlin.time.Duration.ZERO)

    @Test fun buildersPutTheAbsolutePathFirstAndTheSessionSecond() {
        for (argv in listOf(cli.status(), cli.apiSnapshot(), cli.agentList(), cli.agentGet("w2:p1"), cli.tabList(), cli.workspaceList(), cli.paneGet("w2:p1"), cli.agentRead("w2:p1", ReadSource.Detection))) {
            assertEquals(listOf("/usr/bin/herdr", "--session", "paddock-test"), argv.take(3))
        }
        assertEquals(listOf("/usr/bin/herdr", "--session", "paddock-test", "agent", "read", "w2:p1", "--source", "recent-unwrapped", "--lines", "200", "--format", "ansi"),
            cli.agentRead("w2:p1", ReadSource.RecentUnwrapped, 200, ansi = true))
        assertEquals(listOf("/usr/bin/herdr", "session", "list", "--json"), cli.sessionList())
    }

    @Test fun theTerminalBuildersCarryThePaneAndTheGeometryAndNothingElse() {
        assertEquals(listOf("/usr/bin/herdr", "--session", "paddock-test", "pane", "process-info", "--pane", "w2:p1"), cli.paneProcessInfo("w2:p1"))
        assertEquals(listOf("/usr/bin/herdr", "--session", "paddock-test", "terminal", "session", "observe", "w2:p1", "--cols", "60", "--rows", "20"), cli.terminalObserve("w2:p1", 60, 20))
        val control = cli.terminalControl("python3", "/home/u/.local/share/paddock/paddock-control.py", 15, "w2:p1", 60, 20, takeover = false)
        assertEquals(listOf("python3", "/home/u/.local/share/paddock/paddock-control.py", "15", "/usr/bin/herdr", "paddock-test", "w2:p1", "60", "20"), control)
        assertEquals(control + "takeover", cli.terminalControl("python3", "/home/u/.local/share/paddock/paddock-control.py", 15, "w2:p1", 60, 20, takeover = true))
        for (argv in listOf(cli.terminalObserve("w2:p1", 60, 20), control)) argvToCommand(argv)
    }

    @Test fun terminalBuildersRefuseWhatTheHelperOrHerdrWouldRefuse() {
        for ((c, r) in listOf(0 to 20, 60 to 0, 1001 to 20, 60 to 501, -1 to 5)) {
            assertFailsWith<IllegalArgumentException> { cli.terminalObserve("w2:p1", c, r) }
            assertFailsWith<IllegalArgumentException> { cli.terminalControl("python3", "/x/paddock-control.py", 15, "w2:p1", c, r, false) }
        }
        for (lease in listOf(0, 4, 121)) assertFailsWith<IllegalArgumentException> { cli.terminalControl("python3", "/x/paddock-control.py", lease, "w2:p1", 60, 20, false) }
        assertFailsWith<IllegalArgumentException> { cli.terminalControl("python3", "relative/paddock-control.py", 15, "w2:p1", 60, 20, false) }
        assertFailsWith<IllegalArgumentException> { cli.terminalObserve("--takeover", 60, 20) }
        assertFailsWith<IllegalArgumentException> { cli.terminalControl("python3", "/x/paddock-control.py", 15, "w2:p1; id", 60, 20, false) }
    }

    @Test fun everyBuiltCommandSurvivesQuotingUnchanged() {
        for (argv in listOf(cli.status(), cli.agentGet("w2:p1"), cli.agentRead("w2:p1", ReadSource.Recent, 40))) argvToCommand(argv)
    }

    @Test fun idsThatCouldBeFlagsOrShellOrFreeTextAreRefused() {
        for (bad in listOf("", "-rf", "--source", "w2:p1; reboot", "a b", "w2:p1\n", "x".repeat(65), "\$(id)", "'")) {
            assertFailsWith<IllegalArgumentException>(bad) { cli.agentGet(bad) }
            assertFailsWith<IllegalArgumentException>(bad) { cli.paneGet(bad) }
        }
        assertFailsWith<IllegalArgumentException> { cli.agentRead("w2:p1", ReadSource.Recent, 0) }
        assertFailsWith<IllegalArgumentException> { cli.agentRead("w2:p1", ReadSource.Recent, HerdrCli.MAX_READ_LINES + 1) }
        assertFailsWith<IllegalArgumentException> { HerdrCli("herdr", "paddock-test") }
        assertFailsWith<IllegalArgumentException> { HerdrCli("/usr/bin/herdr", "--default") }
    }

    @Test fun okJsonDecodesThroughTheEnvelope() {
        val ok = assertIs<CliOutcome.Ok>(CliResult.classify(result(0, File(dir, "agent-list-blocked.json").readText())))
        assertEquals(1, CliResult.decode<AgentListResult>(ok, "agent_list").agents.size)
        assertFailsWith<ProtocolError.WrongResultType> { CliResult.decode<AgentListResult>(ok, "tab_list") }
    }

    @Test fun nonZeroExitWithJsonErrorBecomesAFailureOnEitherStream() {
        val json = File(dir, "error-agent-get.json").readText()
        for (r in listOf(result(1, err = json), result(1, out = json))) {
            val f = assertIs<CliOutcome.Failure>(CliResult.classify(r)); assertEquals("agent_not_found", f.code)
        }
    }

    @Test fun usageErrorIsAClientBugAndTruncationIsNeverOk() {
        val bug = assertIs<CliOutcome.ClientBug>(CliResult.classify(result(2, err = File(dir, "cli-usage-error.txt").readText())))
        assertTrue(bug.stderr.contains("unknown option"))
        assertIs<CliOutcome.Unparsed>(CliResult.classify(result(0, "{", truncated = true)))
        assertIs<CliOutcome.Unparsed>(CliResult.classify(result(1, err = "boom")))
    }

    // ---- status text (AC-03.8) -------------------------------------------------------------------------------

    @Test fun statusFixtureParses() {
        val s = StatusParser.parse(File(dir, "status.txt").readText())
        assertEquals("0.9.1", s.clientVersion); assertEquals("stable", s.channel); assertEquals(22, s.protocol); assertEquals(1, s.endpointProtocolGeneration)
        assertTrue(s.serverRunning); assertEquals("0.9.1", s.serverVersion); assertEquals(true, s.endpointCompatible); assertEquals(22, s.privateProtocol)
        assertEquals(true, s.privateProtocolCompatible); assertTrue(s.socket!!.endsWith("/sessions/paddock-test/herdr.sock"))
        assertEquals(false, s.restartNeeded); assertEquals(false, s.serverBinaryStale)
    }

    @Test fun extraUnknownLinesAndSectionsAreIgnored() {
        val text = File(dir, "status.txt").readText() + "\nfuture:\n  shiny: yes\nstray line without colon\n  client_extra: 1\n"
        val s = StatusParser.parse(text.replace("  channel: stable", "  channel: stable\n  new_flag: maybe"))
        assertEquals(22, s.protocol); assertTrue(s.serverRunning)
    }

    @Test fun aStoppedServerIsAFactNotAnError() {
        val s = StatusParser.parse("client:\n  version: 0.9.1\n  protocol: 22\n\nserver:\n  status: not running\n")
        assertFalse(s.serverRunning); assertNull(s.socket); assertNull(s.endpointCompatible)
    }

    @Test fun missingClientVersionOrProtocolIsADecodeError() {
        assertFailsWith<ProtocolError.Decode> { StatusParser.parse("server:\n  status: running\n") }
        assertFailsWith<ProtocolError.Decode> { StatusParser.parse("client:\n  version: 0.9.1\n") }
    }
}
