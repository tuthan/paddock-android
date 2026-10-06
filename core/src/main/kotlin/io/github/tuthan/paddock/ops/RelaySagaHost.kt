package io.github.tuthan.paddock.ops

import io.github.tuthan.paddock.cli.CliOutcome
import io.github.tuthan.paddock.cli.CliResult
import io.github.tuthan.paddock.cli.HerdrCli
import io.github.tuthan.paddock.herdr.AgentStartedResult
import io.github.tuthan.paddock.herdr.AgentStatus
import io.github.tuthan.paddock.herdr.PaneInfoResult
import io.github.tuthan.paddock.herdr.TabCreatedResult
import io.github.tuthan.paddock.herdr.WorktreeCreatedResult
import io.github.tuthan.paddock.ports.ExecLimits
import io.github.tuthan.paddock.ports.SshSession
import io.github.tuthan.paddock.relay.HerdrError
import io.github.tuthan.paddock.relay.HostRefusal
import io.github.tuthan.paddock.relay.RelayClient
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * The saga's port over the real thing: the relay for every herdr call (so a branch name, the only free text, travels inside the
 * request's JSON and never in argv) and the SSH session for the one question herdr cannot answer, whether an agent's executable is
 * installed. The mutating members call [before] right before the request's first byte, as [RelayClient.call] does.
 */
class RelaySagaHost(private val relay: RelayClient, private val session: SshSession, private val cli: HerdrCli) : SagaHost {
    override suspend fun executableAvailable(candidates: List<String>): Boolean {
        require(candidates.isNotEmpty() && candidates.all { EXECUTABLE.matches(it) }) { "invalid executable name" }
        val small = ExecLimits(stdoutMax = 4096, stderrMax = 4096, deadline = 10.seconds)
        // A non-interactive SSH command often has a short PATH, so the usual user bin directories are added first.
        if (session.exec(listOf("/bin/sh", "-c", PLAIN, "paddock-avail") + candidates, limits = small).exit == 0) return true
        // Then the user's own login shell, which reads the profile the pane's shell reads; only shells whose `-lc` and `command -v` are standard.
        val shell = session.exec(listOf("/bin/sh", "-c", "printf %s \"\$SHELL\""), limits = small).stdout.toString(Charsets.UTF_8).trim()
        if (!shell.startsWith("/") || shell.substringAfterLast('/') !in LOGIN_SHELLS || shell.any { it.isISOControl() || it == ' ' }) return false
        return session.exec(listOf(shell, "-lc", candidates.joinToString(" || ") { "command -v $it >/dev/null 2>&1" }), limits = small).exit == 0
    }

    override suspend fun createWorktree(parentWorkspaceId: String, branch: String, trust: Boolean, before: suspend () -> Unit): Created {
        val r = relay.call("worktree.create", buildJsonObject {
            put("workspace_id", parentWorkspaceId); put("branch", branch); put("focus", false)
            if (trust) put("trust_repository", true)
        }, timeout = 60.seconds, beforeWrite = before).decode<WorktreeCreatedResult>("worktree_created")
        val root = r.rootPane
        return Created(r.workspace.workspaceId, root.tabId, root.paneId, root.terminalId, root.cwd, worktreePath = r.worktree.path,
            repository = r.worktree.label)
    }

    override suspend fun createTab(workspaceId: String, before: suspend () -> Unit): Created {
        val r = relay.call("tab.create", buildJsonObject { put("workspace_id", workspaceId); put("focus", false) }, timeout = 20.seconds, beforeWrite = before)
            .decode<TabCreatedResult>("tab_created")
        return Created(null, r.tab.tabId, r.rootPane.paneId, r.rootPane.terminalId, r.rootPane.cwd)
    }

    override suspend fun inspectPane(paneId: String): PaneFacts {
        val pane = try {
            relay.call("pane.get", buildJsonObject { put("pane_id", paneId) }, timeout = 10.seconds).decode<PaneInfoResult>("pane_info").pane
        } catch (e: HerdrError) {
            if (e.code.endsWith("not_found")) return PaneFacts(exists = false) else throw e
        }
        val info = relay.call("pane.process_info", buildJsonObject { put("pane_id", paneId) }, timeout = 10.seconds).result["process_info"] as? JsonObject
        val processes = (info?.get("foreground_processes") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val names = processes.map { (it["name"] as? JsonPrimitive)?.contentOrNull.orEmpty() }
        // The shell alone in the foreground is a prompt; a running job, or nothing readable, is not.
        val atPrompt = names.size == 1 && names.single() in SHELLS
        return PaneFacts(true, pane.terminalId, pane.tabId, pane.workspaceId, pane.revision, pane.cwd, hasAgent = pane.agent != null || pane.agentStatus != AgentStatus.Unknown, atShellPrompt = atPrompt)
    }

    /**
     * Through the CLI, not the relay: on 0.9.1 the relay's `agent.start` answers at once with `launch_pending` and an unknown status
     * (measured, whatever `timeout_ms` says), while `herdr agent start` waits until the agent is detected and ready or fails with
     * `timeout`, `agent_name_taken` or `agent_pane_busy`. Its answer is herdr's own envelope, so each failure keeps herdr's code.
     */
    override suspend fun startAgent(name: String, kind: String, paneId: String, timeoutMs: Int, before: suspend () -> Unit): StartedAgent {
        val argv = cli.agentStart(name, kind, paneId, timeoutMs)
        before()
        val r = session.exec(argv, limits = ExecLimits(stdoutMax = 256 * 1024, stderrMax = 16 * 1024, deadline = timeoutMs.milliseconds + 20.seconds))
        return when (val o = CliResult.classify(r)) {
            is CliOutcome.Ok -> CliResult.decode<AgentStartedResult>(o, "agent_started").agent.let { StartedAgent(it.terminalId, it.paneId) }
            is CliOutcome.Failure -> throw HerdrError(o.code, o.message)
            is CliOutcome.ClientBug -> throw HostRefusal("cli_usage", o.stderr.ifBlank { "herdr rejected the command line" })
            is CliOutcome.Unparsed ->
                if (o.exit == 0) throw java.io.IOException("herdr's answer to agent start was cut off")
                else throw HostRefusal("cli_exit_${o.exit}", o.stderr.ifBlank { "herdr exited with status ${o.exit}" })
        }
    }

    override suspend fun closeWorkspace(workspaceId: String, before: suspend () -> Unit) = closeCall("workspace.close", buildJsonObject { put("workspace_id", workspaceId) }, before)
    override suspend fun closeTab(tabId: String, before: suspend () -> Unit) = closeCall("tab.close", buildJsonObject { put("tab_id", tabId) }, before)
    override suspend fun closePane(paneId: String, before: suspend () -> Unit) = closeCall("pane.close", buildJsonObject { put("pane_id", paneId) }, before)

    private suspend fun closeCall(method: String, params: JsonObject, before: suspend () -> Unit) {
        val m = relay.call(method, params, timeout = 15.seconds, beforeWrite = before)
        if (m.type != "ok") throw io.github.tuthan.paddock.herdr.ProtocolError.WrongResultType("ok", m.type)
    }

    companion object {
        val EXECUTABLE = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,39}")
        val SHELLS = setOf("bash", "zsh", "fish", "sh", "dash", "ksh", "ash", "nu", "pwsh", "tcsh", "csh", "xonsh", "elvish")
        private val LOGIN_SHELLS = setOf("bash", "zsh", "fish", "sh", "dash", "ksh")
        const val PLAIN = "PATH=\"\$HOME/.local/bin:\$HOME/.cargo/bin:/opt/homebrew/bin:/usr/local/bin:/home/linuxbrew/.linuxbrew/bin:\$PATH\"; for c in \"\$@\"; do command -v \"\$c\" >/dev/null 2>&1 && exit 0; done; exit 1"
    }
}
