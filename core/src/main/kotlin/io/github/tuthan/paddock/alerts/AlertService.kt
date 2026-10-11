package io.github.tuthan.paddock.alerts

import io.github.tuthan.paddock.attention.AgentRowModel
import io.github.tuthan.paddock.attention.AttentionModel
import io.github.tuthan.paddock.herdr.Snapshot
import io.github.tuthan.paddock.hostprofile.HostProfile
import io.github.tuthan.paddock.live.MonitoredHost
import io.github.tuthan.paddock.ports.Clock
import io.github.tuthan.paddock.reconcile.Observation
import io.github.tuthan.paddock.settings.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** What an arrived alert turned into. [row] is the agent's row when there is one; [host] lets the caller acknowledge a Done. */
class AlertResolution(val outcome: AlertOutcome?, val machine: String, val row: AgentRowModel?, val host: MonitoredHost?)

/** What a push for a whole machine turned into. [outcome] is null when the read did not come in time; [unknownMachine] when this phone has no such machine. */
class MachineResolution(val outcome: MachineOutcome?, val machine: String, val unknownMachine: Boolean)

/**
 * What the app does with an alert once it has arrived, and with the changes it sees itself while it is open.
 *
 * - The arrival rule (Phase 07): [resolveAlert] and [resolveMachine] switch to the machine the alert names ([switchTo], which does not change the
 *   machine the user chose), wait for a read this phone made after the alert arrived on a live connection ([monitored] says which machine is being
 *   monitored), and resolve against that read.
 * - Watching mode: [raise] turns what the open app sees (a terminal that became Blocked or Done) into a notification ([show]), unless alerts are off or
 *   the herd is in front ([interactive]).
 */
class AlertService(
    private val clock: Clock,
    private val settings: () -> AppSettings,
    private val interactive: () -> Boolean,
    private val show: (NotificationContent) -> Unit,
    private val switchTo: suspend (String) -> HostProfile?,
    private val monitored: Flow<MonitoredHost?>,
    private val rules: LocalAlertRules = LocalAlertRules(),
    private val arrivalTimeoutMillis: Long = AlertArrival.TIMEOUT_MILLIS,
    /**
     * The name a machine has now (by profile id), or null when the phone has no such machine. A connection keeps the profile it was made for, and a
     * default name that followed the hostname did not start a new connection ([HostProfile.sameConnectionAs]), so a notification is titled by this, not by
     * the connection's copy.
     */
    private val machineName: (String) -> String? = { null },
) {
    /**
     * The arrival rule for an alert (Phase 07): switch to the machine it names, wait for a read this phone made after the alert
     * arrived on a live connection, and resolve the target against that read. A null outcome means no such read came in time.
     */
    suspend fun resolveAlert(event: AlertEvent.Arrived): AlertResolution {
        val hint = event.hint
        val fresh = freshHerd(hint.target.host.value, event.arrivedAtMillis)
            ?: return AlertResolution(AlertOutcome.NoLongerObserved(Unobserved.UnknownMachine), "this phone", null, null)
        val snapshot = fresh.snapshot ?: return AlertResolution(null, fresh.profile.name, null, fresh.host)
        val outcome = AlertResolver.resolve(hint, fresh.profile.hostId, fresh.host!!.sessionName, snapshot)
        val terminal = when (outcome) { is AlertOutcome.Current -> outcome.terminalId; is AlertOutcome.Changed -> outcome.terminalId; is AlertOutcome.NoLongerObserved -> null }
        return AlertResolution(outcome, fresh.profile.name, terminal?.let { id -> fresh.host.home.value?.rows?.firstOrNull { it.key.target.terminalId == id } }, fresh.host)
    }

    /** What a push that named no terminal found: the same arrival rule, answered with counts from the fresh read. A null outcome means no such read came in time. */
    suspend fun resolveMachine(event: AlertEvent.MachineWoke): MachineResolution {
        val fresh = freshHerd(event.hint.host.value, event.arrivedAtMillis) ?: return MachineResolution(null, "this phone", unknownMachine = true)
        return MachineResolution(fresh.snapshot?.let { AlertResolver.resolveMachine(it) }, fresh.profile.name, unknownMachine = false)
    }

    private class FreshHerd(val profile: HostProfile, val host: MonitoredHost?, val snapshot: Snapshot?)

    /**
     * The arrival rule: switch to [profileId]'s machine, wait for a read the phone made after [arrivedAtMillis] on a live
     * connection. Null when the phone has no such machine; a null snapshot when no such read came within the arrival timeout.
     */
    private suspend fun freshHerd(profileId: String, arrivedAtMillis: Long): FreshHerd? {
        // Not chosen: an alert moves the phone, it does not change the machine the user chose, so going back stays free.
        val profile = switchTo(profileId) ?: return null
        val deadline = clock.nowMillis() + arrivalTimeoutMillis
        val host = withTimeoutOrNull(arrivalTimeoutMillis) { monitored.first { it != null && it.profile.id == profile.id } }
            ?: return FreshHerd(profile, null, null)
        host.refresh()
        val installed = AlertArrival.freshRead(AlertReads(host.sessionName, host.freshness, host.reconciler.installed), arrivedAtMillis, (deadline - clock.nowMillis()).coerceAtLeast(1_000))
        return FreshHerd(profile, host, installed?.snapshot)
    }

    /**
     * Watching mode: a terminal the open app saw become Blocked or Done raises a notification, unless alerts are off or the
     * herd is in front. The reconciler only reports changes between two reads of one epoch, so a reconnect invents none.
     */
    suspend fun raise(host: MonitoredHost) =
        raise(host.reconciler.observations, { machineName(host.profile.id) ?: host.profile.name }) { host.reconciler.installed.value?.snapshot }

    internal suspend fun raise(observations: Flow<Observation>, machine: String, snapshot: () -> Snapshot?) = raise(observations, { machine }, snapshot)

    internal suspend fun raise(observations: Flow<Observation>, machine: () -> String, snapshot: () -> Snapshot?) {
        observations.collect { o ->
            if (o !is Observation.StatusChanged) return@collect
            val current = settings()
            val agent = snapshot()?.agents?.firstOrNull { it.terminalId == o.key.target.terminalId }
            val alert = rules.alertFor(
                o.to, agent, o.key.target, machine(), clock.nowMillis() / 1000, current.localAlerts, interactive(), agent?.let { AttentionModel.title(it) },
            ) ?: return@collect
            runCatching { show(AlertContent.of(alert, current.hidePromptOnLockScreen)) }
        }
    }
}
