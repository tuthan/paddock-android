package io.github.tuthan.paddock.ops

import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.identity.StalePane
import io.github.tuthan.paddock.identity.TargetRef
import io.github.tuthan.paddock.identity.TerminalKey
import io.github.tuthan.paddock.reconcile.Installed
import io.github.tuthan.paddock.relay.RelayClient
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `workspace focus` and `tab focus` (Phase 09): move the desktop's cursor to a workspace or a tab. Each is one journaled operation on
 * a `workspace:<id>` or `tab:<id>` subject, resolved against the installed snapshot first (an id the snapshot does not hold is
 * stale, not sent), with nothing but an id on the wire. Like agent focus, it changes what the desktop shows and nothing the
 * agents are doing.
 */
class SpaceOperations(
    private val relay: RelayClient,
    private val journal: OperationJournal,
    private val installed: () -> Installed?,
    private val host: HostProfileId,
    private val session: String,
    private val timeout: Duration = 10.seconds,
) {
    private fun key(subject: String, epoch: Long) = TerminalKey(TargetRef(host, session, subject), epoch)

    suspend fun focusWorkspace(workspaceId: String): OperationResult<Unit> {
        val i = installed()
        val key = key(Subject.workspace(workspaceId), i?.epoch ?: 0)
        return Operation.run(journal, key, OperationKind.FocusWorkspace,
            resolveTarget = { if (i?.snapshot?.workspaces?.any { it.workspaceId == workspaceId } == true) workspaceId else throw StalePane(key) },
            send = { id, before -> ok(relay.call("workspace.focus", buildJsonObject { put("workspace_id", id) }, timeout = timeout, beforeWrite = before).type) })
    }

    suspend fun focusTab(tabId: String): OperationResult<Unit> {
        val i = installed()
        val key = key(Subject.tab(tabId), i?.epoch ?: 0)
        return Operation.run(journal, key, OperationKind.FocusTab,
            resolveTarget = { if (i?.snapshot?.tabs?.any { it.tabId == tabId } == true) tabId else throw StalePane(key) },
            send = { id, before -> ok(relay.call("tab.focus", buildJsonObject { put("tab_id", id) }, timeout = timeout, beforeWrite = before).type) })
    }

    // workspace.focus answers workspace_info and tab.focus tab_info: any success means herdr moved its cursor.
    private fun ok(type: String) { check(type.isNotEmpty()) }
}
