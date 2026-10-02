package io.github.tuthan.paddock.ops

import io.github.tuthan.paddock.herdr.Agent
import io.github.tuthan.paddock.herdr.AgentInfoResult
import io.github.tuthan.paddock.herdr.ProtocolError
import io.github.tuthan.paddock.identity.PaneResolver
import io.github.tuthan.paddock.identity.StalePane
import io.github.tuthan.paddock.identity.TerminalKey
import io.github.tuthan.paddock.ports.Clock
import io.github.tuthan.paddock.reconcile.Installed
import io.github.tuthan.paddock.relay.RelayClient
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** An agent as herdr described it in a read the phone just made; [readAtMillis] is when that read started. */
data class AgentRead(val agent: Agent, val readAtMillis: Long)

/**
 * herdr's answer to a write named another terminal than the one the phone meant. The write goes to a pane id in a request
 * of its own, after the read that checked the pane, and herdr's agent calls take no terminal id, so a pane that changed hands
 * between the two (a move) takes the write. The answer is the first and only place that shows it. The row becomes Unknown,
 * never Acknowledged, and its note starts with [PREFIX] so every screen can say where the write really went.
 */
class Misdelivered(expected: String, actual: String) : Exception("$PREFIX: herdr answered for terminal $actual, not $expected") {
    companion object { const val PREFIX = "misdelivered" }
}

/**
 * The phone's mutations on an agent: a prompt, Esc, Ctrl+C and desktop focus. Each is one [Operation]: the pane is
 * resolved from the installed snapshot in the epoch the screen opened in, the row is written, a fresh `agent.get`
 * confirms the pane still holds that terminal (and, for a prompt, that [isReady] holds), and one request goes out
 * through the relay. Free text travels in the request's JSON on the relay's stdin, never in argv. `--wait` is not
 * used: the screen waits for observations, because a wait observes settled state and is not a receipt for a prompt.
 */
class AgentOperations(
    private val relay: RelayClient,
    private val journal: OperationJournal,
    private val installed: () -> Installed?,
    private val clock: Clock,
    private val requireHints: Boolean = false,
    private val readTimeout: Duration = 5.seconds,
    private val sendTimeout: Duration = 10.seconds,
) {
    /** The pane id [key] has in the installed snapshot, or [StalePane] when the epoch moved on or the terminal is gone. */
    fun resolve(key: TerminalKey): String {
        val i = installed() ?: throw StalePane(key)
        return PaneResolver(i.snapshot, i.epoch).require(key)
    }

    /**
     * A fresh `agent.get` for the terminal, for the composer and Manual input to show and for a re-read after an unknown
     * outcome. A pane that now holds another terminal is [StalePane], like any other lost identity.
     */
    suspend fun read(key: TerminalKey): AgentRead {
        val pane = resolve(key)
        val read = readPane(pane)
        if (read.agent.terminalId != key.target.terminalId) throw StalePane(key)
        return read
    }

    /** The user re-read after an unknown outcome: the row stays unknown and stops blocking the terminal. */
    suspend fun reread(key: TerminalKey, operationId: Long): AgentRead = read(key).also { journal.resolve(operationId, "re-read") }

    /**
     * The user chose Re-read: one fresh read of the agent, then every unknown row of the terminal stops blocking it. The
     * rows stay Unknown. A read that fails leaves them all waiting. [key] carries the epoch of the connection now installed,
     * not the one the rows were written in: after a restart that is a different epoch, and a re-read looks at the agent as
     * it is now.
     */
    suspend fun rereadAll(key: TerminalKey): ReReadReport {
        val waiting = journal.unresolvedUnknown(key)
        val read = read(key)
        val resolved = waiting.map { journal.resolve(it.id, "re-read") }
        return ReReadReport(key.target.terminalId, read.readAtMillis, read.agent.agentStatus, resolved)
    }

    suspend fun prompt(key: TerminalKey, text: String, keepText: Boolean = false): OperationResult<Agent> {
        require(text.isNotBlank()) { "an empty prompt is never sent" }
        require(text.length <= MAX_PROMPT_CHARS) { "a prompt is at most $MAX_PROMPT_CHARS characters" }
        return Operation.run(journal, key, OperationKind.Prompt, text, keepText,
            resolveTarget = { resolve(key) },
            preflight = { pane ->
                val started = clock.nowMillis()
                val agent = readPane(pane).agent
                when {
                    agent.terminalId != key.target.terminalId -> movedPane()
                    else -> when (val verdict = isReady(agent, started, clock.nowMillis(), requireHints)) {
                        is Readiness.Ready -> Preflight.Go(agent.stateChangeSeq)
                        is Readiness.NotReady -> Preflight.Refuse(verdict.primary.code, verdict.primary.sentence)
                    }
                }
            },
            send = { pane, before ->
                relay.call("agent.prompt", buildJsonObject { put("target", pane); put("text", text) }, timeout = sendTimeout, beforeWrite = before)
                    .decode<AgentInfoResult>("agent_prompted").agent.answeredFor(key)
            })
    }

    /** Esc or Ctrl+C. The caller offers these only in Manual input mode, after a fresh read; a key says nothing about readiness. */
    suspend fun sendKey(key: TerminalKey, which: OperationKind): OperationResult<Unit> {
        val name = when (which) { OperationKind.Esc -> "esc"; OperationKind.CtrlC -> "ctrl+c"; else -> throw IllegalArgumentException("$which is not a key") }
        return Operation.run(journal, key, which,
            resolveTarget = { resolve(key) },
            preflight = { pane -> identity(key, pane) },
            send = { pane, before ->
                val m = relay.call("agent.send_keys", buildJsonObject { put("target", pane); put("keys", JsonArray(listOf(JsonPrimitive(name)))) }, timeout = sendTimeout, beforeWrite = before)
                if (m.type != "ok") throw ProtocolError.WrongResultType("ok", m.type)
            })
    }

    /** `agent focus`: moves the desktop's cursor to the agent and marks a completion seen on the server. */
    suspend fun focus(key: TerminalKey): OperationResult<Agent> = Operation.run(journal, key, OperationKind.Focus,
        resolveTarget = { resolve(key) },
        preflight = { pane -> identity(key, pane) },
        send = { pane, before ->
            relay.call("agent.focus", buildJsonObject { put("target", pane) }, timeout = sendTimeout, beforeWrite = before)
                .decode<AgentInfoResult>("agent_info").agent.answeredFor(key)
        })

    private suspend fun identity(key: TerminalKey, pane: String): Preflight {
        val agent = readPane(pane).agent
        return if (agent.terminalId != key.target.terminalId) movedPane() else Preflight.Go(agent.stateChangeSeq)
    }

    /** The agent herdr answered for must be the terminal this operation meant: the write has already happened, so a mismatch is reported, not undone. */
    private fun Agent.answeredFor(key: TerminalKey): Agent =
        if (terminalId == key.target.terminalId) this else throw Misdelivered(key.target.terminalId, terminalId)

    private fun movedPane() = Preflight.Refuse("pane_moved", "The pane now holds another terminal. Re-read and try again.")

    private suspend fun readPane(pane: String): AgentRead {
        val started = clock.nowMillis()
        val agent = relay.call("agent.get", buildJsonObject { put("target", pane) }, timeout = readTimeout).decode<AgentInfoResult>("agent_info").agent
        return AgentRead(agent, started)
    }

    companion object { const val MAX_PROMPT_CHARS = 20_000 }
}
