package io.github.tuthan.paddock.routes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.github.tuthan.paddock.host.HostUiModel
import io.github.tuthan.paddock.hostprofile.MachineBook
import io.github.tuthan.paddock.identity.TargetRef
import io.github.tuthan.paddock.identity.TerminalKey
import io.github.tuthan.paddock.ledger.Activity
import io.github.tuthan.paddock.ledger.ActivityFilter
import io.github.tuthan.paddock.ledger.ActivityPresenter
import io.github.tuthan.paddock.ledger.Ledger
import io.github.tuthan.paddock.live.HostPhase
import io.github.tuthan.paddock.ops.OperationJournal
import io.github.tuthan.paddock.ops.SagaRecord
import io.github.tuthan.paddock.ui.screens.ActivityLog

@Composable
internal fun ActivityRoute(
    ledger: Ledger, journal: OperationJournal, hostUi: HostUiModel, machineBook: MachineBook, savedSagas: () -> List<SagaRecord>,
    onOpenAgent: (terminalId: String) -> Unit,
) {
    val profile by machineBook.profile.collectAsState()
    val view by hostUi.view.collectAsState()
    val host = (view.phase as? HostPhase.Monitoring)?.host
    var filter by rememberSaveable { mutableStateOf(ActivityFilter.All) }
    val now = rememberNow()
    // Re-read each second: the ledger is small and in memory. An unknown row offers Re-read only for an agent the connected host lists now.
    val presenter = remember(profile, host) {
        ActivityPresenter(
            java.time.ZoneId.systemDefault(), java.util.Locale.getDefault(), hostName = { profile?.name ?: it },
            titleOf = { _, _, tid -> hostUi.view.value.lastHome?.rows?.firstOrNull { it.key.target.terminalId == tid }?.title },
            canReread = { h, s, tid -> host?.sends != null && h == host.profile.hostId.value && s == host.sessionName && host.home.value?.rows?.any { it.key.target.terminalId == tid } == true },
        )
    }
    val sections = remember(filter, now / 1_000) { presenter.present(Activity.build(ledger.observations(), ledger.actions(), filter, journal.records.value, host?.saga?.sagas?.value ?: savedSagas()), now) }
    // Re-read, then open the agent: its screen is where the result is shown. Never a resend from here.
    val onReread: (Long) -> Unit = { id ->
        val record = journal.get(id)
        val epoch = host?.reconciler?.installed?.value?.epoch
        if (record != null && host != null && epoch != null) {
            host.sends?.reread(TerminalKey(TargetRef(host.profile.hostId, record.session, record.terminalId), epoch))
            onOpenAgent(record.terminalId)
        }
    }
    ActivityLog(sections, filter, { filter = it }, onReread = if (host?.sends != null) onReread else null)
}
