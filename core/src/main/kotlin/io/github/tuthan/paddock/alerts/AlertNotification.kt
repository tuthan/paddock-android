package io.github.tuthan.paddock.alerts

import io.github.tuthan.paddock.herdr.Agent
import io.github.tuthan.paddock.herdr.AgentStatus
import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.identity.TargetRef

/** The notification channels. The ids are what the system keeps the user's choices under, so they never change. */
enum class AlertChannel(val id: String, val title: String, val description: String) {
    NeedsYou("needs_you", "Needs you", "An agent is blocked and waiting for you."),
    Done("done", "Done", "An agent finished. Grouped by machine."),
    Machines("machines", "Machines", "Reserved for notices about a machine, such as it becoming unreachable. Paddock does not send these yet."),
    Watching("watching", "Watching", "Reserved for the ongoing notice of a background watch. Paddock does not watch in the background yet.");
}

/** Every notification action is one of these two until guarded answers exist (Phase 08), and both only open the app. */
enum class AlertAction(val label: String) { Open("Open"), Review("Review") }

/**
 * A notification Paddock raises itself while it is open: what it is about, with the agent's title when there is one.
 * [sequence] is the agent's `state_change_seq` (the dedupe id; relay messages use the relay's own count).
 */
data class LocalAlert(
    val target: TargetRef,
    val paneId: String,
    val state: AlertState,
    val sequence: Long,
    val atSeconds: Long,
    val machine: String,
    val agentTitle: String?,
)

/**
 * What the platform layer draws. [publicTitle] and [publicText] are generic and are all the lock screen shows while
 * [hideOnLockScreen] is set; [title] and [text] carry the agent's title and are shown once the phone is unlocked (or
 * everywhere, when the user turned the redaction off). Only [actions] can be tapped, and they only open [link].
 */
data class NotificationContent(
    val id: Int,
    val channel: AlertChannel,
    val publicTitle: String,
    val publicText: String,
    val title: String,
    val text: String,
    val hideOnLockScreen: Boolean,
    val link: String,
    /** A notification group key for Done alerts (one stack per machine); null for Needs-you, which should not be folded away. */
    val group: String?,
    val whenMillis: Long,
    val actions: List<AlertAction> = listOf(AlertAction.Open, AlertAction.Review),
)

object AlertContent {
    const val MAX_TITLE = 80

    /** The generic words, the same ones the host relay sends, so a notification reads alike whichever path raised it. */
    fun genericTitle(machine: String) = "Paddock: attention on $machine"
    fun genericText(state: AlertState) = if (state == AlertState.Blocked) "An agent needs you." else "An agent finished."

    fun of(alert: LocalAlert, hideOnLockScreen: Boolean): NotificationContent {
        val channel = if (alert.state == AlertState.Blocked) AlertChannel.NeedsYou else AlertChannel.Done
        val title = alert.agentTitle?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_TITLE)
        val hint = AlertHint(alert.target, alert.paneId, alert.state, alert.atSeconds, alert.sequence)
        val word = if (alert.state == AlertState.Blocked) "Needs you" else "Done"
        return NotificationContent(
            id = idFor(alert.target),
            channel = channel,
            publicTitle = genericTitle(alert.machine),
            publicText = genericText(alert.state),
            title = title ?: genericTitle(alert.machine),
            text = "$word · ${alert.machine}",
            hideOnLockScreen = hideOnLockScreen,
            link = DeepLink.build(hint),
            group = if (channel == AlertChannel.Done) "paddock-done-" + alert.target.host.value else null,
            whenMillis = alert.atSeconds * 1000,
        )
    }

    const val PUSH_TEXT = "An agent needs your attention."

    /**
     * The notification for a push, which names no terminal and no state: the generic words only, in the Needs-you channel (the
     * push cannot say Done), one per machine so a burst of pushes is one notification. Its link opens that machine's herd.
     */
    fun ofPush(machine: String, hint: MachineHint, whenMillis: Long, hideOnLockScreen: Boolean) = NotificationContent(
        id = idForMachine(hint.host),
        channel = AlertChannel.NeedsYou,
        publicTitle = genericTitle(machine),
        publicText = PUSH_TEXT,
        title = genericTitle(machine),
        text = PUSH_TEXT,
        hideOnLockScreen = hideOnLockScreen,
        link = DeepLink.buildMachine(hint),
        group = null,
        whenMillis = whenMillis,
    )

    fun idForMachine(host: HostProfileId): Int = ("push\u0000" + host.value).hashCode() and 0x7fffffff

    /** One notification per terminal: a new state of the same agent replaces the old one instead of stacking. */
    fun idFor(target: TargetRef): Int = (target.host.value + "\u0000" + target.session + "\u0000" + target.terminalId).hashCode() and 0x7fffffff
}

/**
 * The rule for raising a notification from what the open app observed. Only a terminal newly Blocked or Done raises one;
 * not while the app is in front (the herd on screen is the alert), not when alerts are off, and once per state of a terminal.
 */
class LocalAlertRules(private val capacity: Int = 256) {
    private val raised = LinkedHashSet<String>()

    /** Remembers [alert] and says whether it is new. Bounded: the oldest are forgotten first. */
    @Synchronized fun firstTime(alert: LocalAlert): Boolean {
        val key = "${alert.target.host.value}|${alert.target.session}|${alert.target.terminalId}|${alert.state.wire}|${alert.sequence}"
        if (!raised.add(key)) return false
        while (raised.size > capacity) raised.remove(raised.first())
        return true
    }

    /** The alert for an agent that just changed to [to], or null when nothing should be raised. [agent] is the fresh read's record of it. */
    fun alertFor(to: AgentStatus, agent: Agent?, target: TargetRef, machine: String, nowSeconds: Long, enabled: Boolean, interactive: Boolean, title: String?): LocalAlert? {
        if (!enabled || interactive || agent == null) return null
        val state = when (to) { AgentStatus.Blocked -> AlertState.Blocked; AgentStatus.Done -> AlertState.Done; else -> return null }
        if (agent.agentStatus != to) return null
        return LocalAlert(target, agent.paneId, state, agent.stateChangeSeq ?: 0, nowSeconds, machine, title)
            .takeIf { firstTime(it) }
    }
}

/** Where a notification is drawn. The Android implementation lives in the app; tests record. */
interface AlertNotifier {
    fun show(content: NotificationContent)
    fun cancelAll()
    /** Cancels the notifications that are for [profileId]'s machine and no other's. */
    fun cancelFor(profileId: String)
}
