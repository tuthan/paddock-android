package io.github.tuthan.paddock.routes

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import io.github.tuthan.paddock.files.DropFolderCheck
import io.github.tuthan.paddock.ui.screens.GetCopy
import io.github.tuthan.paddock.hostprofile.HostProfile
import io.github.tuthan.paddock.live.HostPhase
import io.github.tuthan.paddock.output.SafeText
import io.github.tuthan.paddock.reconcile.Freshness
import io.github.tuthan.paddock.ui.screens.DropFilesUi
import io.github.tuthan.paddock.ui.screens.DropFolderActions
import io.github.tuthan.paddock.ui.screens.DropFolderCopy
import io.github.tuthan.paddock.ui.screens.DropFolderUi
import io.github.tuthan.paddock.ui.screens.DropRowUi
import io.github.tuthan.paddock.ui.screens.DropTold
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.tuthan.paddock.alerts.PushWords
import io.github.tuthan.paddock.billing.ProCapabilities
import io.github.tuthan.paddock.billing.ProService
import io.github.tuthan.paddock.host.HostUiModel
import io.github.tuthan.paddock.discovery.MachineRelocator
import io.github.tuthan.paddock.hostprofile.MachineAddress
import io.github.tuthan.paddock.hostprofile.MachineBook
import io.github.tuthan.paddock.hostprofile.MachineCopy
import io.github.tuthan.paddock.hostprofile.MachineRoster
import io.github.tuthan.paddock.hostprofile.MachineRow
import io.github.tuthan.paddock.hostprofile.MachineSwitch
import io.github.tuthan.paddock.net.AndroidLanPaths
import io.github.tuthan.paddock.notify.UnifiedPushConnector
import io.github.tuthan.paddock.ports.Clock
import io.github.tuthan.paddock.settings.SettingsController
import io.github.tuthan.paddock.ui.CopyKind
import io.github.tuthan.paddock.ui.components.HostHealth
import io.github.tuthan.paddock.ui.copyText
import io.github.tuthan.paddock.ui.screens.MachineCard
import io.github.tuthan.paddock.ui.screens.MachineListState
import io.github.tuthan.paddock.ui.screens.MachinePage
import io.github.tuthan.paddock.ui.screens.MachinePageState
import io.github.tuthan.paddock.ui.screens.MachinesScreen
import io.github.tuthan.paddock.ui.screens.chipHealth
import io.github.tuthan.paddock.ui.screens.clockLabel
import io.github.tuthan.paddock.wake.WakeService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The user asked to watch [row], from Home's chip row, the Machines screen or a machine's page: one rule for all three ([MachineSwitch.decide]). Going
 * back to the machine the user chose (an alert moved the phone off it) is free and asks no gate; any other switch is Pro, so a phone without it is
 * shown the sheet (or told why not now) and nothing is switched. Leaving a machine ends whatever is running on it as unknown (the journal's rule), so
 * a switch waits for an operation in flight. Then the machine is watched and becomes the chosen one, and the notice says so.
 */
internal fun switchMachine(proService: ProService, machineBook: MachineBook, appScope: CoroutineScope, row: MachineRow, onNotice: (String) -> Unit) {
    when (MachineSwitch.decide(row.returnFree, proService.locked(ProCapabilities.SWITCH_MACHINE), proService.gateContext(pendingAnswerOnScreen = false))) {
        // Locked, the gate never lets this tap through: it opens the sheet from an idle app, or the notice says when Pro is offered.
        MachineSwitch.Decision.AskGate -> proService.requestCapability(ProCapabilities.SWITCH_MACHINE.id, pendingAnswerOnScreen = false)
        MachineSwitch.Decision.Busy -> onNotice(MachineCopy.SWITCH_BUSY)
        MachineSwitch.Decision.Switch -> appScope.launch { machineBook.watch(row.id, chosen = true)?.let { onNotice(MachineCopy.nowWatching(it.name)) } }
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
private fun watchedHealth(hostUi: HostUiModel, machineBook: MachineBook, clock: Clock): HostHealth {
    val view by hostUi.view.collectAsState()
    val profile by machineBook.profile.collectAsState()
    val now = clock.nowMillis()
    return chipHealth(io.github.tuthan.paddock.host.HomeUiMapper.map(profile?.name.orEmpty(), view, now).state, now).first
}

/**
 * The Machines screen (decision D3): every saved machine with its state, a Watch on the ones not watched, and Add another machine. The registrations
 * and the phone's networks are read again on every return, since an alert setup or a network change may have happened meanwhile.
 */
@Composable
internal fun MachinesRoute(
    machineBook: MachineBook, prefs: SettingsController, hostUi: HostUiModel, proService: ProService, push: UnifiedPushConnector, wakes: WakeService,
    appScope: CoroutineScope, clock: Clock, busy: Boolean, onBack: () -> Unit, onOpen: (MachineRow) -> Unit, onAdd: () -> Unit, onNotice: (String) -> Unit) {
    val machines by machineBook.machines.collectAsState()
    val profile by machineBook.profile.collectAsState()
    val settings by prefs.settings.collectAsState()
    val view by hostUi.view.collectAsState()
    val pro by proService.view.collectAsState()
    val registrations by push.registrations.collectAsState()
    LaunchedEffect(Unit) { push.refresh() }
    val resumes = rememberResumes()
    val health = watchedHealth(hostUi, machineBook, clock)
    val ready = remember(machines, resumes, view.phase) { machines.associate { it.id to wakes.ready(it) } }
    val cards = MachineRoster.rows(machines, profile?.id, settings.chosenProfileId).mapNotNull { row ->
        machines.firstOrNull { it.id == row.id }?.let { p -> MachineCard(row, machineStateLine(row, p, health, ready[p.id] == true, registrations)) }
    }
    MachinesScreen(
        MachineListState(cards, switchLocked = proService.locked(ProCapabilities.SWITCH_MACHINE, pro), busy = busy),
        onBack = onBack, onOpen = onOpen, onWatch = { switchMachine(proService, machineBook, appScope, it, onNotice) }, onAdd = onAdd,
    )
}

/**
 * One saved machine's page (decision D3): Watch through [switchMachine], Wake-on-LAN for this machine whether it is watched or not (D2, through
 * `WakeService.wake(id)`), its alert registration in words, and Remove (`MachineBook.requestRemoval`; the root says what it came to). A machine that
 * is gone (removed here or elsewhere) returns to the list.
 */
@Composable
internal fun MachineRoute(
    machineBook: MachineBook, prefs: SettingsController, hostUi: HostUiModel, proService: ProService, push: UnifiedPushConnector, wakes: WakeService,
    appScope: CoroutineScope, clock: Clock, lanPaths: AndroidLanPaths, relocator: MachineRelocator, machineId: String?, removingId: String?, onBack: () -> Unit, onAlertRelay: () -> Unit, onGuardedAnswers: () -> Unit, onNotice: (String) -> Unit,
) {
    val machines by machineBook.machines.collectAsState()
    val profile by machineBook.profile.collectAsState()
    val settings by prefs.settings.collectAsState()
    val view by hostUi.view.collectAsState()
    val pro by proService.view.collectAsState()
    val registrations by push.registrations.collectAsState()
    val machine = machines.firstOrNull { it.id == machineId }
    if (machine == null) { LaunchedEffect(machineId) { onBack() }; return }
    LaunchedEffect(Unit) { push.refresh() }
    // Opening the page is asking again: the next failed connection is looked into without waiting out an earlier look's cooldown.
    LaunchedEffect(machineId) { machineId?.let { relocator.askAgain(it) } }
    val health = watchedHealth(hostUi, machineBook, clock)
    val now = rememberNow()
    // Read again when the connection changes and on every return: the phone may have joined or left a network meanwhile.
    val resumes = rememberResumes()
    val ready = remember(machine, resumes, view.phase) { wakes.ready(machine) }
    val facts by remember(machine.id) { wakes.facts(machine.id) }.collectAsState()
    val row = MachineRoster.rows(machines, profile?.id, settings.chosenProfileId).first { it.id == machine.id }
    val wake = io.github.tuthan.paddock.host.WakeWordsMapper.words(
        machine, facts, now, wakeReady = ready,
        phoneSuggestion = remember(resumes) { io.github.tuthan.paddock.wake.WakeRelay.suggestionFrom(lanPaths.paths()) },
        clockLabel = { m -> wakeClock(m, now) },
    )
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // Where files shared with no agent go (Phase 15, X10): judged by the machine itself, so only the machine this phone watches, while it is connected, can be asked.
    val transfers = (view.phase as? HostPhase.Monitoring)?.host?.transfers
    val askable = row.watched && transfers != null && view.freshness == Freshness.Live
    var editingDrop by rememberSaveable(machine.id) { mutableStateOf(false) }
    var asking by remember(machine.id) { mutableStateOf(false) }
    var told by remember(machine.id) { mutableStateOf<DropTold?>(null) }
    var dropFiles by remember(machine.id) { mutableStateOf<DropFilesUi>(DropFilesUi.Idle) }
    val readDrop: () -> Unit = {
        val t = transfers
        if (t != null) scope.launch {
            dropFiles = DropFilesUi.Reading
            dropFiles = dropFilesUi(DropFolderCheck.check(t, machine.dropFolderValue), now = System.currentTimeMillis())
        }
    }
    // The edit sheet stays open when the connection goes (a few seconds after the app leaves the front): its text is kept, and the sheet waits to be asked again.
    LaunchedEffect(askable, machine.dropFolderValue) { if (askable) readDrop() else dropFiles = DropFilesUi.Idle }
    androidx.compose.runtime.key(machine.id) {
        MachinePage(
            MachinePageState(
                row, machineStateLine(row, machine, health, ready, registrations), switchLocked = proService.locked(ProCapabilities.SWITCH_MACHINE, pro), wake = wake,
                alerts = PushWords.state(registrations.firstOrNull { it.profile == machine.id }, machine.name),
                next = MachineRoster.after(machines, machine.id, settings.chosenProfileId)?.name, busy = removingId != null,
                otherNames = machines.filter { it.id != machine.id }.map { it.name }, chosenOs = machine.os, detectedOs = machine.detectedOs,
                guardedLocked = proService.locked(ProCapabilities.GUARDED_ANSWERS, pro),
                profile = machine, otherProfiles = machines.filter { it.id != machine.id },
                drop = DropFolderUi(machine.dropFolderValue, isDefault = machine.dropFolder == null, askable = askable, files = dropFiles, editing = editingDrop && askable, asking = asking, told = told),
            ),
            dropActions = DropFolderActions(
                onReRead = readDrop,
                onEdit = { told = null; editingDrop = true },
                onCloseEdit = { editingDrop = false; told = null },
                onCheck = { value ->
                    val t = transfers
                    if (t == null) told = DropTold(value, DropFolderCopy.NOT_CONNECTED, ok = false)
                    else {
                        asking = true
                        scope.launch {
                            told = when (val r = DropFolderCheck.check(t, value)) {
                                is DropFolderCheck.Result.Ok -> DropTold(value, DropFolderCheck.sentence(r), ok = true)
                                is DropFolderCheck.Result.Problem -> DropTold(value, r.sentence, ok = false)
                            }
                            asking = false
                        }
                    }
                },
                // In the graph's scope: leaving the page must not drop a save halfway.
                onSave = { value ->
                    appScope.launch { machineBook.setDropFolder(machine.id, value) }
                    editingDrop = false; told = null
                    onNotice(DropFolderCopy.saved(machine.name, value ?: HostProfile.DEFAULT_DROP_FOLDER))
                },
            ),
            onBack = onBack,
            onWatch = { switchMachine(proService, machineBook, appScope, row, onNotice) },
            onRemove = { machineBook.requestRemoval(machine.id, machine.name) },
            onAlertRelay = onAlertRelay,
            onGuardedAnswers = onGuardedAnswers,
            onCopyWakeCommand = { cmd -> copyText(ctx, CopyKind.WakeCommand, cmd) },
            onSaveWakeRelay = { relay -> scope.launch { machineBook.setWakeRelay(machine.id, relay) } },
            onWake = { wakes.wake(machine.id) },
            onRename = { name -> scope.launch { if (machineBook.rename(machine.id, name)) onNotice(MachineCopy.renamed(name)) } },
            onSetOs = { os -> scope.launch { machineBook.setOs(machine.id, os) } },
            onSetSearchNetwork = { on -> scope.launch { machineBook.setSearchNetwork(machine.id, on); relocator.askAgain(machine.id) } },
            // In the graph's scope, as a switch is: leaving the page must not stop a move halfway (the watch stopped, the new one not started).
            onChangeAddress = { host, port ->
                if (MachineAddress.waits(row.watched, proService.gateContext(pendingAnswerOnScreen = false))) onNotice(MachineCopy.ADDRESS_BUSY)
                else appScope.launch {
                    if (machineBook.setAddress(machine.id, host, port)) onNotice(MachineCopy.addressChanged(machine.name, MachineRoster.endpoint(machine.copy(host = host, port = port))))
                }
            },
        )
    }
}

/** What the page shows of a look at the drop folder: its newest files (ten at most), against [now], and what its read said when it did not work. */
internal fun dropFilesUi(r: DropFolderCheck.Result, now: Long): DropFilesUi = when (r) {
    is DropFolderCheck.Result.Problem -> DropFilesUi.Failed(r.sentence)
    is DropFolderCheck.Result.Ok -> DropFilesUi.Listed(
        r.files.take(DROP_SHOWN).map { DropRowUi(SafeText.clean(it.name), GetCopy.sizeAndAge(it.size, it.mtimeSeconds, now)) },
        more = (r.files.size - DROP_SHOWN).coerceAtLeast(0), where = SafeText.clean(r.real), exists = r.exists,
        readAtTime = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(now)),
    )
}

private const val DROP_SHOWN = 10
