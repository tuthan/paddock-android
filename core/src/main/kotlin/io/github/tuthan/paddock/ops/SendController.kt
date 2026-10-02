package io.github.tuthan.paddock.ops

import io.github.tuthan.paddock.identity.StalePane
import io.github.tuthan.paddock.identity.TerminalKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How the last operation on a terminal ended, kept until the user dismisses it or starts another. */
data class SendOutcome(val kind: OperationKind, val result: OperationResult<*>)

/**
 * Runs the phone's operations in the host's scope, so turning the phone or leaving the screen never cancels a send that
 * is already on its way (a cancellation after the write would leave it Unknown for nothing). One call per terminal at a
 * time: a second tap while one runs is ignored here, and the journal refuses it too. The newest outcome per terminal is
 * kept for the screen to show.
 */
class SendController(private val scope: CoroutineScope, private val ops: AgentOperations) {
    private val _outcomes = MutableStateFlow<Map<String, SendOutcome>>(emptyMap())
    val outcomes: StateFlow<Map<String, SendOutcome>> = _outcomes.asStateFlow()

    private val _running = MutableStateFlow<Set<String>>(emptySet())
    /** Terminal ids with a call from this phone still running. */
    val running: StateFlow<Set<String>> = _running.asStateFlow()

    private val _rereads = MutableStateFlow<Map<String, RereadOutcome>>(emptyMap())
    /** The newest re-read per terminal, for the screen to show; a new operation on the terminal clears it. */
    val rereads: StateFlow<Map<String, RereadOutcome>> = _rereads.asStateFlow()

    fun prompt(key: TerminalKey, text: String, keepText: Boolean) { start(key, OperationKind.Prompt) { ops.prompt(key, text, keepText) } }
    fun sendKey(key: TerminalKey, kind: OperationKind) { start(key, kind) { ops.sendKey(key, kind) } }
    fun focus(key: TerminalKey) { start(key, OperationKind.Focus) { ops.focus(key) } }

    /**
     * The user chose Re-read for a terminal with an unknown outcome. It is one call per terminal like any other (a tap while
     * one is running is ignored), runs in the host's scope, and either frees the waiting rows or leaves them all waiting.
     */
    fun reread(key: TerminalKey) {
        val id = key.target.terminalId
        var accepted = false
        _running.update { if (id in it) it else { accepted = true; it + id } }
        if (!accepted) return
        _rereads.update { it - id }
        scope.launch {
            try {
                val outcome = try {
                    RereadOutcome.Done(ops.rereadAll(key))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: StalePane) {
                    RereadOutcome.Failed(gone = true, detail = e.message.orEmpty())
                } catch (e: Exception) {
                    RereadOutcome.Failed(gone = false, detail = e.message ?: e::class.simpleName.orEmpty())
                }
                _rereads.update { it + (id to outcome) }
            } finally {
                _running.update { it - id }
            }
        }
    }

    /** The screen showed the outcome and the user is done with it. */
    fun dismiss(terminalId: String) { _outcomes.update { it - terminalId } }

    fun dismissReread(terminalId: String) { _rereads.update { it - terminalId } }

    private fun start(key: TerminalKey, kind: OperationKind, call: suspend () -> OperationResult<*>) {
        val id = key.target.terminalId
        var accepted = false
        _running.update { if (id in it) it else { accepted = true; it + id } }
        if (!accepted) return
        _outcomes.update { it - id }
        _rereads.update { it - id }
        scope.launch {
            try {
                val result = call()
                _outcomes.update { it + (id to SendOutcome(kind, result)) }
            } finally {
                _running.update { it - id }
            }
        }
    }
}
