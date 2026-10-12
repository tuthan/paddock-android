package io.github.tuthan.paddock

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import io.github.tuthan.paddock.alerts.AlertCopy
import io.github.tuthan.paddock.alerts.AlertEvent
import io.github.tuthan.paddock.alerts.AlertInbox
import io.github.tuthan.paddock.alerts.AlertOutcome
import io.github.tuthan.paddock.alerts.AlertService
import io.github.tuthan.paddock.alerts.AlertState
import io.github.tuthan.paddock.answers.AnswerRules
import io.github.tuthan.paddock.applock.AppLock
import io.github.tuthan.paddock.attention.StateWord
import io.github.tuthan.paddock.billing.ProCapabilities
import io.github.tuthan.paddock.billing.ProService
import io.github.tuthan.paddock.files.ShareCopy
import io.github.tuthan.paddock.files.ShareInbox
import io.github.tuthan.paddock.host.ConnectAttempt
import io.github.tuthan.paddock.host.ConnectOutcome
import io.github.tuthan.paddock.host.ConnectOutcomes
import io.github.tuthan.paddock.host.HostUiModel
import io.github.tuthan.paddock.hostkey.HostKeyBroker
import io.github.tuthan.paddock.hostprofile.Boot
import io.github.tuthan.paddock.hostprofile.MachineBook
import io.github.tuthan.paddock.hostprofile.PairingCopy
import io.github.tuthan.paddock.hostprofile.PairingEvent
import io.github.tuthan.paddock.hostprofile.PairingInbox
import io.github.tuthan.paddock.live.ConnectFix
import io.github.tuthan.paddock.live.DownReasonText
import io.github.tuthan.paddock.live.HostPhase
import io.github.tuthan.paddock.nav.NavState
import io.github.tuthan.paddock.nav.Route
import io.github.tuthan.paddock.ui.screens.AgentTab
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** How long Add machine waits for the connection to decide before saying it is still waiting. Not counted while a host key question is on screen (the connector allows that its own, longer time). */
private const val CONNECT_WAIT_MILLIS = 45_000L

/**
 * Where Review lands for [terminalId]: the Output tab, which carries the Yes/No entry, when this machine can answer that agent's prompts from the phone
 * ([AnswerRules.canAnswerFromPhone]); otherwise the Terminal tab, observing. Read when the tap happens, from what the app knows then.
 */
internal fun reviewTab(hostUi: HostUiModel, proService: ProService, terminalId: String): AgentTab {
    val host = (hostUi.view.value.phase as? HostPhase.Monitoring)?.host ?: return AgentTab.Terminal
    val kind = host.reconciler.installed.value?.snapshot?.agents?.firstOrNull { it.terminalId == terminalId }?.agent
    return if (AnswerRules.canAnswerFromPhone(kind, wired = host.answers != null, locked = proService.locked(ProCapabilities.GUARDED_ANSWERS))) AgentTab.Output else AgentTab.Terminal
}

/** What an alert tap leads to. The herd shows while the connection and the read come up; the alert is resolved against that read, never a cached one. */
@Composable
internal fun AlertEvents(alerts: AlertInbox, alertService: AlertService, hostUi: HostUiModel, proService: ProService, nav: NavState, boot: Boot) {
    val alertEvent by alerts.event.collectAsState()
    LaunchedEffect(alertEvent, boot) {
        val e = alertEvent ?: return@LaunchedEffect
        if (boot == Boot.Loading) return@LaunchedEffect
        when (e) {
            is AlertEvent.Invalid -> { nav.alertNotice = AlertCopy.INVALID_LINK; alerts.consume(e) }
            is AlertEvent.MachineWoke -> {
                // A push that named no terminal: the herd of that machine, read fresh, and what that read says in counts.
                nav.alertNotice = AlertCopy.OPENING
                nav.route = Route.Home
                val r = alertService.resolveMachine(e)
                val machineOutcome = r.outcome
                nav.alertNotice = when {
                    r.unknownMachine -> AlertCopy.UNKNOWN_MACHINE
                    machineOutcome == null -> AlertCopy.unreachable(r.machine)
                    else -> AlertCopy.machineNotice(machineOutcome, r.machine)
                }
                alerts.consume(e)
            }
            is AlertEvent.Arrived -> {
                // The herd shows while the connection and the read come up; the alert is resolved against that read, never a cached one.
                nav.alertNotice = AlertCopy.OPENING
                nav.route = Route.Home
                val resolved = alertService.resolveAlert(e)
                val outcome = resolved.outcome
                if (outcome == null) nav.alertNotice = AlertCopy.unreachable(resolved.machine)
                else {
                    nav.alertNotice = AlertCopy.notice(outcome, e.hint, System.currentTimeMillis(), resolved.machine)
                    when (outcome) {
                        is AlertOutcome.Current -> {
                            // Opening a Done from its alert is the user acknowledging it, as tapping its row is.
                            if (outcome.state == AlertState.Done) resolved.row?.takeIf { it.state == StateWord.Done }?.let { resolved.host?.markSeen(it) }
                            // A blocked agent opens where Review prompt does: its Output tab when the phone can answer it, else its Terminal tab, observing.
                            nav.openAgent(outcome.terminalId, if (outcome.state == AlertState.Blocked) reviewTab(hostUi, proService, outcome.terminalId) else AgentTab.Output)
                        }
                        is AlertOutcome.Changed -> nav.openAgent(outcome.terminalId)
                        is AlertOutcome.NoLongerObserved -> nav.route = Route.Home
                    }
                }
                alerts.consume(e)
            }
        }
    }
}

/** A pairing link (a tap, or Paste a pairing link): Add machine opens pre-filled. Nothing connects and nothing is trusted from here. */
@Composable
internal fun PairingEvents(pairing: PairingInbox, nav: NavState, boot: Boot) {
    val pairingEvent by pairing.event.collectAsState()
    LaunchedEffect(pairingEvent, boot) {
        val e = pairingEvent ?: return@LaunchedEffect
        if (boot == Boot.Loading) return@LaunchedEffect
        when (e) {
            is PairingEvent.Invalid -> nav.alertNotice = PairingCopy.invalid(e.reason)
            is PairingEvent.Link -> nav.openPairing(e.link, boot)
        }
        pairing.consume(e)
    }
}

/**
 * Something another app shared (Phase 15, X13): the chooser opens once the app is unlocked and a machine is saved. While the lock is up the item waits (its
 * snapshot is already taken and private); with no machine saved there is nowhere to send it, so it is dropped and the notice says so. What the intake refused,
 * or what a text lost, is said once through the notice bar.
 */
@Composable
internal fun ShareEvents(shares: ShareInbox, shareNotice: MutableStateFlow<String?>, appLock: AppLock, nav: NavState, boot: Boot) {
    val pending by shares.pending.collectAsState()
    val locked by appLock.locked.collectAsState()
    val said by shareNotice.collectAsState()
    LaunchedEffect(said, boot) {
        val sentence = said ?: return@LaunchedEffect
        if (boot == Boot.Loading) return@LaunchedEffect
        nav.alertNotice = sentence
        shareNotice.value = null
    }
    LaunchedEffect(pending, locked, boot) {
        if (pending == null || locked || boot == Boot.Loading) return@LaunchedEffect
        if (boot == Boot.NoMachines) { shares.discard(); nav.alertNotice = ShareCopy.NO_MACHINE; return@LaunchedEffect }
        nav.openShare()
    }
}

/**
 * What a Remove… confirmed on a machine's page came to. Read from the graph, not from a callback the page captured: the removal runs in the graph's
 * scope and can end after a rotation or while the app is away, and whichever composition is here then says it once.
 */
@Composable
internal fun RemovalOutcomes(machineBook: MachineBook, nav: NavState) {
    val removal by machineBook.removalOutcome.collectAsState()
    LaunchedEffect(removal) {
        val r = removal ?: return@LaunchedEffect
        nav.removalShown(r.id, r.name, r.result)
        machineBook.consumeRemoval(r)
    }
}

/** A machine Paddock found at another address and moved ([MachineRelocator]): the notice bar says where, once. */
@Composable
internal fun Relocations(relocator: io.github.tuthan.paddock.discovery.MachineRelocator, nav: NavState) {
    val moved by relocator.moved.collectAsState()
    LaunchedEffect(moved) {
        val r = moved ?: return@LaunchedEffect
        nav.alertNotice = io.github.tuthan.paddock.discovery.RelocateCopy.moved(r)
        relocator.consumeMoved(r)
    }
}

/**
 * Follows the connection from Connect until it is live: a live or set-up-needing host ends the attempt, a failure stays on the form with its fix.
 * It keeps following after a failure (a retry that succeeds, or a host key trusted late, must still end the attempt) and after the wait ran out.
 */
@Composable
internal fun FollowConnectAttempt(broker: HostKeyBroker, hostUi: HostUiModel, nav: NavState) {
    val add = nav.add
    val attemptKey = add.attempt?.let { it.profileId to it.connecting }
    LaunchedEffect(attemptKey) {
        val (id, connecting) = attemptKey ?: return@LaunchedEffect
        coroutineScope {
            // The time is counted only while no question is on screen: comparing a fingerprint with the desktop's takes as long as the user needs.
            if (connecting) launch {
                broker.firstTrust.map { it != null }.distinctUntilChanged().collectLatest { asking ->
                    if (!asking) {
                        delay(CONNECT_WAIT_MILLIS)
                        if (add.attempt?.profileId == id && add.attempt?.connecting == true) add.attempt = ConnectAttempt(id, ConnectOutcomes.STILL_WAITING, ConnectFix.Retry)
                    }
                }
            }
            hostUi.view.map { it.phase }.distinctUntilChanged().collect { phase ->
                when (val outcome = ConnectOutcomes.outcome(phase, id, hostUi.phaseBeforeAttempt)) {
                    ConnectOutcome.Connected -> {
                        nav.connected()
                        this@coroutineScope.cancel()
                    }
                    // Only a form that is still waiting (or only said it was) takes a failure: a sentence already shown stays put while the owner retries.
                    is ConnectOutcome.Failed -> add.attempt?.takeIf { it.profileId == id && (it.connecting || it.error == ConnectOutcomes.STILL_WAITING) }
                        ?.let { add.attempt = ConnectAttempt(id, DownReasonText.sentence(outcome.reason), DownReasonText.fix(outcome.reason)) }
                    null -> {}
                }
            }
        }
    }
}
