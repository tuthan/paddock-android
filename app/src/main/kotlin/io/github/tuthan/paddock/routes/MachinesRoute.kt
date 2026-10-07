package io.github.tuthan.paddock.routes

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.tuthan.paddock.AppGraph
import io.github.tuthan.paddock.alerts.PushWords
import io.github.tuthan.paddock.billing.ProCapabilities
import io.github.tuthan.paddock.hostprofile.MachineCopy
import io.github.tuthan.paddock.hostprofile.MachineRoster
import io.github.tuthan.paddock.hostprofile.MachineRow
import io.github.tuthan.paddock.hostprofile.MachineSwitch
import io.github.tuthan.paddock.ui.components.HostHealth
import io.github.tuthan.paddock.ui.screens.MachineCard
import io.github.tuthan.paddock.ui.screens.MachineListState
import io.github.tuthan.paddock.ui.screens.MachinePage
import io.github.tuthan.paddock.ui.screens.MachinePageState
import io.github.tuthan.paddock.ui.screens.MachinesScreen
import io.github.tuthan.paddock.ui.screens.chipHealth
import io.github.tuthan.paddock.ui.screens.clockLabel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The user asked to watch [row], from Home's chip row, the Machines screen or a machine's page: one rule for all three ([MachineSwitch.decide]). Going
 * back to the machine the user chose (an alert moved the phone off it) is free and asks no gate; any other switch is Pro, so a phone without it is
 * shown the sheet (or told why not now) and nothing is switched. Leaving a machine ends whatever is running on it as unknown (the journal's rule), so
 * a switch waits for an operation in flight. Then the machine is watched and becomes the chosen one, and the notice says so.
 */
internal fun switchMachine(graph: AppGraph, row: MachineRow, onNotice: (String) -> Unit) {
    when (MachineSwitch.decide(row.returnFree, graph.proService.locked(ProCapabilities.SWITCH_MACHINE), graph.proService.gateContext(pendingAnswerOnScreen = false))) {
        // Locked, the gate never lets this tap through: it opens the sheet from an idle app, or the notice says when Pro is offered.
        MachineSwitch.Decision.AskGate -> graph.proService.requestCapability(ProCapabilities.SWITCH_MACHINE.id, pendingAnswerOnScreen = false)
        MachineSwitch.Decision.Busy -> onNotice(MachineCopy.SWITCH_BUSY)
        MachineSwitch.Decision.Switch -> graph.scope.launch { graph.machineBook.watch(row.id, chosen = true)?.let { onNotice(MachineCopy.nowWatching(it.name)) } }
    }
}

/** One machine's state line (`MachineCopy`): the watched machine's health, or for another what is known about waking it and its alerts. */
private fun machineStateLine(
    row: MachineRow, profile: io.github.tuthan.paddock.hostprofile.HostProfile, health: HostHealth, wakeReady: Boolean,
    registrations: List<io.github.tuthan.paddock.alerts.PushRegistration>,
): String =
    if (row.watched) MachineCopy.watchingLine(health.word)
    else MachineCopy.otherLine(MachineRoster.wakeWord(profile, wakeReady), alerts = registrations.any { it.profile == profile.id && it.endpoint != null })

/** A wake time as the Wake-on-LAN section says it: the clock today, the day and the clock before. */
private fun wakeClock(millis: Long, nowMillis: Long): String =
    if (java.time.LocalDate.ofEpochDay(millis / 86_400_000L) == java.time.LocalDate.ofEpochDay(nowMillis / 86_400_000L)) clockLabel(millis)
    else java.time.format.DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.ofEpochMilli(millis))

/** The watched machine's health as Home's chip shows it, so the Machines screen's "Watching · live" never disagrees with the chip. */
@Composable
private fun watchedHealth(graph: AppGraph): HostHealth {
    val view by graph.hostUi.view.collectAsState()
    val profile by graph.machineBook.profile.collectAsState()
    val now = graph.clock.nowMillis()
    return chipHealth(io.github.tuthan.paddock.host.HomeUiMapper.map(profile?.name.orEmpty(), view, now).state, now).first
}

/**
 * The Machines screen (decision D3): every saved machine with its state, a Watch on the ones not watched, and Add another machine. The registrations
 * and the phone's networks are read again on every return, since an alert setup or a network change may have happened meanwhile.
 */
@Composable
internal fun MachinesRoute(graph: AppGraph, busy: Boolean, onBack: () -> Unit, onOpen: (MachineRow) -> Unit, onAdd: () -> Unit, onNotice: (String) -> Unit) {
    val machines by graph.machineBook.machines.collectAsState()
    val profile by graph.machineBook.profile.collectAsState()
    val settings by graph.settings.collectAsState()
    val view by graph.hostUi.view.collectAsState()
    val pro by graph.proService.view.collectAsState()
    val registrations by graph.push.registrations.collectAsState()
    LaunchedEffect(Unit) { graph.push.refresh() }
    val resumes = rememberResumes()
    val health = watchedHealth(graph)
    val ready = remember(machines, resumes, view.phase) { machines.associate { it.id to graph.wakes.ready(it) } }
    val cards = MachineRoster.rows(machines, profile?.id, settings.chosenProfileId).mapNotNull { row ->
        machines.firstOrNull { it.id == row.id }?.let { p -> MachineCard(row, machineStateLine(row, p, health, ready[p.id] == true, registrations)) }
    }
    MachinesScreen(
        MachineListState(cards, switchLocked = graph.proService.locked(ProCapabilities.SWITCH_MACHINE, pro), busy = busy),
        onBack = onBack, onOpen = onOpen, onWatch = { switchMachine(graph, it, onNotice) }, onAdd = onAdd,
    )
}

/**
 * One saved machine's page (decision D3): Watch through [switchMachine], Wake-on-LAN for this machine whether it is watched or not (D2, through
 * `AppGraph.wake(profileId)`), its alert registration in words, and Remove (`AppGraph.requestRemoval`; the root says what it came to). A machine that
 * is gone (removed here or elsewhere) returns to the list.
 */
@Composable
internal fun MachineRoute(
    graph: AppGraph, machineId: String?, removingId: String?, onBack: () -> Unit, onAlertRelay: () -> Unit, onGuardedAnswers: () -> Unit, onNotice: (String) -> Unit,
) {
    val machines by graph.machineBook.machines.collectAsState()
    val profile by graph.machineBook.profile.collectAsState()
    val settings by graph.settings.collectAsState()
    val view by graph.hostUi.view.collectAsState()
    val pro by graph.proService.view.collectAsState()
    val registrations by graph.push.registrations.collectAsState()
    val machine = machines.firstOrNull { it.id == machineId }
    if (machine == null) { LaunchedEffect(machineId) { onBack() }; return }
    LaunchedEffect(Unit) { graph.push.refresh() }
    val health = watchedHealth(graph)
    val now = rememberNow()
    // Read again when the connection changes and on every return: the phone may have joined or left a network meanwhile.
    val resumes = rememberResumes()
    val ready = remember(machine, resumes, view.phase) { graph.wakes.ready(machine) }
    val facts by remember(machine.id) { graph.wakes.facts(machine.id) }.collectAsState()
    val row = MachineRoster.rows(machines, profile?.id, settings.chosenProfileId).first { it.id == machine.id }
    val wake = io.github.tuthan.paddock.host.WakeWordsMapper.words(
        machine, facts, now, wakeReady = ready,
        phoneSuggestion = remember(resumes) { io.github.tuthan.paddock.wake.WakeRelay.suggestionFrom(graph.lanPaths.paths()) },
        clockLabel = { m -> wakeClock(m, now) },
    )
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    androidx.compose.runtime.key(machine.id) {
        MachinePage(
            MachinePageState(
                row, machineStateLine(row, machine, health, ready, registrations), switchLocked = graph.proService.locked(ProCapabilities.SWITCH_MACHINE, pro), wake = wake,
                alerts = PushWords.state(registrations.firstOrNull { it.profile == machine.id }, machine.name),
                next = MachineRoster.after(machines, machine.id, settings.chosenProfileId)?.name, busy = removingId != null,
                otherNames = machines.filter { it.id != machine.id }.map { it.name }, chosenOs = machine.os, detectedOs = machine.detectedOs,
                guardedLocked = graph.proService.locked(ProCapabilities.GUARDED_ANSWERS, pro),
            ),
            onBack = onBack,
            onWatch = { switchMachine(graph, row, onNotice) },
            onRemove = { graph.machineBook.requestRemoval(machine.id, machine.name) },
            onAlertRelay = onAlertRelay,
            onGuardedAnswers = onGuardedAnswers,
            onCopyWakeCommand = { cmd -> (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Paddock wake command", cmd)) },
            onSaveWakeRelay = { relay -> scope.launch { graph.machineBook.setWakeRelay(machine.id, relay) } },
            onWake = { graph.wakes.wake(machine.id) },
            onRename = { name -> scope.launch { if (graph.machineBook.rename(machine.id, name)) onNotice(MachineCopy.renamed(name)) } },
            onSetOs = { os -> scope.launch { graph.machineBook.setOs(machine.id, os) } },
        )
    }
}
