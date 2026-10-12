package io.github.tuthan.paddock.nav

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import io.github.tuthan.paddock.host.ConnectAttempt
import io.github.tuthan.paddock.host.PairingLinkSaver
import io.github.tuthan.paddock.hostprofile.Boot
import io.github.tuthan.paddock.hostprofile.MachineCopy
import io.github.tuthan.paddock.hostprofile.MachineRemoval
import io.github.tuthan.paddock.hostprofile.PairingLink
import io.github.tuthan.paddock.ui.screens.AgentTab

/**
 * Where the router is and how it got there. The route, where each screen was opened from, the open terminal and the Add machine flow survive rotation
 * and process death ([Saver]); everything else is read from the graph. A screen that can be opened from more than one place (Machines, Snippets, Alert
 * relay, Add machine, Guarded answers) remembers which, and goes back there ([backTo]).
 */
internal class NavState {
    var route by mutableStateOf(Route.Home)
    var addFrom by mutableStateOf(Route.Home)
    var terminalId by mutableStateOf<String?>(null)
    var outputTab by mutableStateOf(AgentTab.Output)
    var snippetsFrom by mutableStateOf(Route.Settings)
    var guardedFrom by mutableStateOf(Route.Machine)
    /** Machines opens from Home's chip or from Settings and returns there; a machine's page returns to Machines. */
    var machinesFrom by mutableStateOf(Route.Home)
    var machineId by mutableStateOf<String?>(null)
    /** The alert relay returns to whichever opened it. */
    var alertRelayFrom by mutableStateOf(Route.Settings)
    var relayDismissed by mutableStateOf(false)
    /** "Not now" on the relay prompt is about the machine it was shown for: another machine watched means its own prompt, if it has one. */
    var dismissedFor by mutableStateOf<String?>(null)
    var reviewKey by mutableStateOf(false)
    /** What the last alert tap found ("State changed since the alert", "No longer observed"), or a notice from a removal. It stays until the user dismisses it. */
    var alertNotice by mutableStateOf<String?>(null)
    val add = AddMachineFlow()

    /** The route that is drawn: a Connect in flight, or Welcome and Add machine with no machine saved, outrank the chosen route. */
    fun effective(boot: Boot): Route = when {
        add.attempt != null -> Route.AddMachine
        boot == Boot.NoMachines && add.pairing == null && !add.welcomeChoice -> Route.Welcome
        boot == Boot.NoMachines -> Route.AddMachine
        else -> route
    }

    /** Where Back goes from [effective]. */
    fun backTo(effective: Route): Route = when (effective) {
        Route.AddMachine -> addFrom; Route.Compose -> Route.Output; Route.Snippets -> snippetsFrom; Route.AlertRelay -> alertRelayFrom; Route.Decision -> Route.Output
        Route.GuardedAnswers -> guardedFrom; Route.Machines -> machinesFrom; Route.Machine -> Route.Machines; else -> Route.Home
    }

    /** Manual input belongs to the agent screen and the composer opened from it; leaving them, or opening another agent, ends it. */
    fun onAgent(effective: Route): Boolean =
        (effective == Route.Output && outputTab == AgentTab.Output) || effective == Route.Compose || (effective == Route.Snippets && snippetsFrom == Route.Compose)

    /** The machine watched changed: a "Not now" on the relay prompt was for the one before. */
    fun watchedChanged(watchedId: String?) {
        if (dismissedFor != null && dismissedFor != watchedId) relayDismissed = false
        dismissedFor = watchedId
    }

    fun openAgent(id: String, tab: AgentTab = AgentTab.Output) { terminalId = id; outputTab = tab; route = Route.Output }

    fun openMachines(from: Route) { machinesFrom = from; route = Route.Machines }

    fun openMachine(id: String) { machineId = id; route = Route.Machine }

    fun openSnippets(from: Route) { snippetsFrom = from; route = Route.Snippets }

    fun openAlertRelay(from: Route) { alertRelayFrom = from; route = Route.AlertRelay }

    fun openGuardedAnswers(from: Route) { guardedFrom = from; route = Route.GuardedAnswers }

    /** Something another app shared is waiting for the person to choose where it goes (Phase 15): the chooser opens over whatever was showing and goes back to Home. */
    fun openShare() { route = Route.Share }

    /** Home's "Set up key" ([showCommand] false) or "Show the command" (true): Add machine for the watched machine, not another. */
    fun openAddMachineForKey(showCommand: Boolean) { add.editing = true; add.authorizeIntro = showCommand; add.pairing = null; addFrom = Route.Home; route = Route.AddMachine }

    /** Machines' "Add another machine". */
    fun openAddMachineFromMachines() { add.editing = false; add.pairing = null; addFrom = Route.Machines; route = Route.AddMachine }

    /** A pairing link arrived: Add machine opens pre-filled, and returns to where the user was (Home from a phone with no machine, or from the agent screen). */
    fun openPairing(link: PairingLink, boot: Boot) {
        alertNotice = null
        add.pairing = link; add.editing = false
        if (route != Route.AddMachine) { addFrom = if (boot == Boot.Ready && route != Route.Output) route else Route.Home; route = Route.AddMachine }
    }

    /** A removal confirmed on a machine's page came to [result]; the root says so once. Back to the list once the machine is gone, unless the user is elsewhere by now. */
    fun removalShown(id: String, name: String, result: MachineRemoval.Result) {
        when (result) {
            MachineRemoval.Result.Removed -> {
                alertNotice = MachineCopy.removed(name)
                if (route == Route.Machine && machineId == id) route = Route.Machines
            }
            is MachineRemoval.Result.Incomplete -> alertNotice = MachineCopy.removeFailed(result.failed)
        }
    }

    /** The connection came up: the attempt is over and the form's leftovers go. */
    fun connected() {
        add.attempt = null; add.editing = false; add.authorizeIntro = false; add.pairing = null; route = Route.Home; relayDismissed = false
    }

    /** Back (the system's, or an arrow) from a screen: [to] is [backTo] of the screen that was drawn. */
    fun back(to: Route) { add.attempt = null; add.authorizeIntro = false; route = to }

    /** Add machine's own back: with a machine saved it goes where it was opened from, with none it returns to Welcome. */
    fun leaveAddMachine(boot: Boot, to: Route) {
        add.attempt = null; add.editing = false; add.authorizeIntro = false; add.pairing = null
        if (boot == Boot.Ready) route = to else add.welcomeChoice = false
    }

    /** With no machine yet, Back from the form returns to Welcome instead of leaving the app. */
    fun backToWelcome() { add.welcomeChoice = false; add.pairing = null }

    companion object {
        private inline fun <reified E : Enum<E>> enumOr(name: Any?, default: E): E = (name as? String)?.let { n -> runCatching { enumValueOf<E>(n) }.getOrNull() } ?: default

        /** Survives rotation and process death. */
        val Saver: Saver<NavState, Any> = Saver(
            save = { s ->
                listOf(
                    s.route.name, s.addFrom.name, s.terminalId, s.outputTab.name, s.snippetsFrom.name, s.guardedFrom.name, s.machinesFrom.name, s.machineId,
                    s.alertRelayFrom.name, s.relayDismissed, s.dismissedFor, s.reviewKey, s.alertNotice,
                    s.add.editing, s.add.authorizeIntro, s.add.welcomeChoice, s.add.welcomeFinder, s.add.welcomeScan,
                    with(PairingLinkSaver) { save(s.add.pairing) }, with(ConnectAttempt.Saver) { save(s.add.attempt) },
                )
            },
            restore = { v ->
                val l = v as? List<*>
                if (l == null || l.size != 20) null else NavState().also { s ->
                    s.route = enumOr(l[0], Route.Home); s.addFrom = enumOr(l[1], Route.Home); s.terminalId = l[2] as? String; s.outputTab = enumOr(l[3], AgentTab.Output)
                    s.snippetsFrom = enumOr(l[4], Route.Settings); s.guardedFrom = enumOr(l[5], Route.Machine); s.machinesFrom = enumOr(l[6], Route.Home); s.machineId = l[7] as? String
                    s.alertRelayFrom = enumOr(l[8], Route.Settings); s.relayDismissed = l[9] == true; s.dismissedFor = l[10] as? String; s.reviewKey = l[11] == true; s.alertNotice = l[12] as? String
                    s.add.editing = l[13] == true; s.add.authorizeIntro = l[14] == true; s.add.welcomeChoice = l[15] == true; s.add.welcomeFinder = l[16] == true; s.add.welcomeScan = l[17] == true
                    s.add.pairing = l[18]?.let { with(PairingLinkSaver) { restore(it) } }
                    s.add.attempt = l[19]?.let { with(ConnectAttempt.Saver) { restore(it) } }
                }
            },
        )
    }
}
