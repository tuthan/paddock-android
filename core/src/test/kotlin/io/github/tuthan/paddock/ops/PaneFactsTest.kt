package io.github.tuthan.paddock.ops

import io.github.tuthan.paddock.herdr.Envelope
import io.github.tuthan.paddock.herdr.Message
import io.github.tuthan.paddock.herdr.PaneInfoResult
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import org.junit.Test

/**
 * What the saga's pane check makes of herdr's `pane.get` and `pane.process_info`. The Mac answers are herdr 0.9.3 on macOS, pasted from a user's terminal
 * (2026-10-12): the pane had scrolled output yet `"revision":0`, which stopped every start with "did not reach a shell prompt". They are inline, not
 * under `fixtures/`, because those are pinned to the installed 0.9.1.
 */
class PaneFactsTest {
    private fun success(line: String) = assertIs<Message.Success>(Envelope.parse(line.trim()))
    private fun pane(line: String) = success(line).decode<PaneInfoResult>("pane_info").pane
    private fun info(line: String) = success(line).result["process_info"] as JsonObject
    private fun fixture(name: String) = File(System.getProperty("paddock.repoRoot"), "fixtures/herdr-0.9.1/$name").readText()

    private val macPane = """{"id":"cli:pane:get","result":{"pane":{"agent_status":"unknown","cwd":"/Users/jdoe/Projects/webapp","focused":true,"foreground_cwd":"/Users/jdoe/Projects/webapp","pane_id":"w1:p5","revision":0,"scroll":{"max_offset_from_bottom":41,"offset_from_bottom":0,"viewport_rows":43},"tab_id":"w1:t5","terminal_id":"term_65da1e1083d6e6","workspace_id":"w1"},"type":"pane_info"}}"""
    private fun foreground(vararg names: String) =
        """{"id":"cli:pane:process_info","result":{"process_info":{"foreground_process_group_id":1,"foreground_processes":[${names.joinToString(",") { """{"argv":["$it"],"name":"$it","pid":2}""" }}],"pane_id":"w1:p5","shell_pid":3},"type":"pane_process_info"}}"""

    @Test fun `a Mac pane whose revision stays 0 is a shell prompt when the shell is alone in the foreground`() {
        val p = pane(macPane)
        assertEquals(0, p.revision)
        val f = RelaySagaHost.paneFacts(p, info(foreground("zsh")))
        assertTrue(f.atShellPrompt && !f.foregroundBusy && f.exists)
        assertEquals("/Users/jdoe/Projects/webapp", f.cwd)
        assertEquals("w1:t5", f.tabId)
    }

    @Test fun `a login shell is named with a leading dash and may carry a path`() {
        for (name in listOf("-zsh", "-bash", "/bin/zsh", "/opt/homebrew/bin/fish", "-/bin/zsh"))
            assertTrue(RelaySagaHost.paneFacts(pane(macPane), info(foreground(name))).atShellPrompt, name)
    }

    @Test fun `the answer the Mac gave while herdr itself ran in the pane is a busy foreground`() {
        val given = """{"id":"cli:pane:process_info","result":{"process_info":{"foreground_process_group_id":31120,"foreground_processes":[{"argv":["herdr","pane","process-info","--pane","w1:p5"],"argv0":"herdr","cmdline":"herdr pane process-info --pane w1:p5","cwd":"/Users/jdoe/Projects/webapp","name":"herdr","pid":31120}],"pane_id":"w1:p5","shell_pid":24543},"type":"pane_process_info"}}"""
        val f = RelaySagaHost.paneFacts(pane(macPane), info(given))
        assertFalse(f.atShellPrompt)
        assertTrue(f.foregroundBusy)
    }

    @Test fun `a job beside the shell, or a program that is not one, is busy and nothing readable yet is only not ready`() {
        assertTrue(RelaySagaHost.paneFacts(pane(macPane), info(foreground("zsh", "sleep"))).foregroundBusy)
        assertTrue(RelaySagaHost.paneFacts(pane(macPane), info(foreground("vim"))).foregroundBusy)
        val starting = RelaySagaHost.paneFacts(pane(macPane), info(foreground()))
        assertFalse(starting.atShellPrompt)
        assertFalse(starting.foregroundBusy)
        assertFalse(RelaySagaHost.paneFacts(pane(macPane), null).foregroundBusy)
    }

    @Test fun `herdr 0_9_1 on Linux still reads as a prompt`() {
        val f = RelaySagaHost.paneFacts(pane(fixture("pane-get-fresh-shell.json")), info(fixture("pane-process-info-shell.json")))
        assertTrue(f.atShellPrompt && !f.foregroundBusy && !f.hasAgent)
    }
}
