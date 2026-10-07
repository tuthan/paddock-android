package io.github.tuthan.paddock.hostprofile

import io.github.tuthan.paddock.billing.GateContext
import io.github.tuthan.paddock.probe.ChipGlance
import io.github.tuthan.paddock.settings.AppSettings
import kotlin.coroutines.cancellation.CancellationException

/**
 * One saved machine as the Machines screen, its page and Home's chip row show it. [returnFree]: this is the machine the user chose themselves
 * (`AppSettings.chosenProfileId`) and an alert has since moved the phone to another one, so watching it again is free. Without that, a Free
 * phone with two machines would be watched by whichever one alerted last, with Pro the only way back.
 */
data class MachineRow(val id: String, val name: String, val endpoint: String, val session: String?, val watched: Boolean, val returnFree: Boolean, val os: HostOs? = null)

/**
 * One saved machine on Home's chip row (decision D4). [locked]: watching it is Pro for this phone and it is neither the watched machine nor the free
 * return to the machine the user chose ([MachineRow.returnFree]), so its chip carries a lock ([MachineRoster.watchLocked]).
 */
data class MachineChip(
    val id: String, val name: String, val watched: Boolean, val locked: Boolean = false, val os: HostOs? = null,
    /** What the probe found on a machine that is not watched (Pro, `hosts.merged`); null for the watched machine and while nothing has been read. */
    val glance: ChipGlance? = null,
)

/**
 * What a user's own tap to watch a saved machine does before anything is switched (decision D1), for the Watch on the Machines screen, the Watch on a
 * machine's page and another machine's chip on Home alike. Pure, so the order is tested without a screen or a graph.
 */
object MachineSwitch {
    enum class Decision {
        /** Ask the Pro gate (`hosts.switch`): the sheet from an idle app, or one sentence saying why not now. This tap switches nothing. */
        AskGate,

        /** An operation is still running on the watched machine: leaving it would end that call as unknown (the journal's rule), so the tap waits. */
        Busy,

        /** Watch it; it becomes the chosen machine. */
        Switch,
    }

    /**
     * A free return to the chosen machine ([returnFree]) never asks the gate: an alert moved the phone off it, and going back must not be Pro. Any
     * other switch asks it while [switchLocked]. Whatever the gate allows still waits for an operation in flight ([GateContext.OPERATION_IN_FLIGHT]);
     * a locked row is not told [Decision.Busy], because the gate's own deferral sentence already says when Pro is offered.
     */
    fun decide(returnFree: Boolean, switchLocked: Boolean, context: GateContext): Decision = when {
        switchLocked && !returnFree -> Decision.AskGate
        context == GateContext.OPERATION_IN_FLIGHT -> Decision.Busy
        else -> Decision.Switch
    }
}

object MachineRoster {
    fun endpoint(p: HostProfile) = "${p.user}@${p.host}:${p.port}"

    /** The watched machine first, then the others in the order the store gives them (by name). The chosen machine is a free return unless it is the watched one. */
    fun rows(profiles: List<HostProfile>, watchedId: String?, chosenId: String?): List<MachineRow> =
        profiles.map { MachineRow(it.id, it.name, endpoint(it), it.session, watched = it.id == watchedId, returnFree = it.id == chosenId && it.id != watchedId, os = it.shownOs) }
            .sortedBy { if (it.watched) 0 else 1 }

    /**
     * The machine to watch once [removedId] is gone: the chosen machine ([chosenId]) when it is another saved one, because an alert may have moved
     * the phone off it and the user would otherwise land on a machine they never picked; else the first of the others by name, as at start-up.
     * Null when none is left.
     */
    fun after(profiles: List<HostProfile>, removedId: String, chosenId: String?): HostProfile? =
        profiles.firstOrNull { it.id == chosenId && it.id != removedId } ?: profiles.firstOrNull { it.id != removedId }

    /**
     * What the machine list says about waking [profile] from this phone. [readyHere] is the app's "a packet can be sent from where the phone is now"
     * (a read hardware address and a path: `AppGraph.wakeReady`). No reading at all is [WakeWord.NotRead]; a reading that cannot be used from here
     * (no hardware address, a relay saved before any reading, or no path from this network) is [WakeWord.NotAvailable].
     */
    fun wakeWord(profile: HostProfile, readyHere: Boolean): WakeWord = when {
        profile.wake == null -> WakeWord.NotRead
        readyHere -> WakeWord.Ready
        else -> WakeWord.NotAvailable
    }

    /**
     * The chosen machine once the saved ones are known: the saved choice while that machine still exists, otherwise the watched one. That covers a
     * phone whose settings were written before there was a choice, and a chosen machine removed while another was watched. Null when nothing is watched.
     */
    fun chosen(profiles: List<HostProfile>, chosenId: String?, watchedId: String?): String? =
        chosenId?.takeIf { id -> profiles.any { it.id == id } } ?: watchedId

    /**
     * What start-up saves for the chosen machine: [settings] with [chosen]'s answer, or null when there is nothing to save. Nothing is saved when the
     * list could not be read ([listRead] false) or read no machine at all: that says nothing about which machines exist, and saving from it would
     * write `chosenProfileId = null` over a choice that is still good, so a failed read at one start would make the next alert's machine the only
     * free return. A saved choice that is still a saved machine needs no write either. [watchedId] is the machine start-up watches.
     */
    fun startupChoice(listRead: Boolean, all: List<HostProfile>, settings: AppSettings, watchedId: String?): AppSettings? {
        if (!listRead || all.isEmpty()) return null
        val chosen = chosen(all, settings.chosenProfileId, watchedId)
        return if (chosen == settings.chosenProfileId) null else settings.copy(chosenProfileId = chosen)
    }

    /**
     * Whether saving [saved] from Add a machine makes it the chosen machine. A plain add (no [fixing]) does: the user picked it. Setting up the key of the watched machine ([fixing], Home's "Set up key" or "Show the command") that kept the same machine does
     * not: after an alert moved the phone to B, fixing B's key must not take away the free return to A. A fix that turned into another machine (the
     * address was changed to a different one) is an add.
     */
    fun choosesOnAdd(saved: HostProfile, fixing: HostProfile?): Boolean = fixing == null || saved.id != fixing.id

    /**
     * Whether watching [row] asks the Pro gate: `hosts.switch` is locked for this phone ([switchLocked]) and the row is neither the watched machine
     * nor the free return to the chosen one. One rule for the list's Watch, the page's Watch and a chip's lock, so the three never disagree.
     */
    fun watchLocked(row: MachineRow, switchLocked: Boolean): Boolean = switchLocked && !row.returnFree && !row.watched

    /**
     * Home's chips, one per row in the [rows]' order (the watched machine first), each locked by [watchLocked]. [glances] is what the probe found per
     * machine id, already worded for now; the watched machine never carries one (its own chip is the live one).
     */
    fun chips(rows: List<MachineRow>, switchLocked: Boolean, glances: Map<String, ChipGlance> = emptyMap()): List<MachineChip> =
        rows.map { MachineChip(it.id, it.name, watched = it.watched, locked = watchLocked(it, switchLocked), os = it.os, glance = glances[it.id].takeUnless { _ -> it.watched }) }
}

/** Waking a machine as its card on the Machines screen says it, after "Not watched". Read with the rest of the card, so the words carry the meaning, not a colour. */
enum class WakeWord(val text: String) { Ready("wake ready"), NotRead("wake not read"), NotAvailable("wake not available") }

/** Sentences for the machine list, the machine page, Home's chip row and Remove. One place, tested, so what the screens say is what [MachineRemoval] does. */
object MachineCopy {
    const val TITLE = "Machines"

    /** Every card opens its machine's page, where Watch, Wake and Remove live, so the intro names all three. */
    const val INTRO = "Paddock watches one machine at a time. Open a machine to watch it, wake it or remove it."

    /** The watched machine's connection in one word, as the Home chip and the machine list say it (`HostHealth`). */
    const val LIVE = "live"
    const val NOT_LIVE = "not live"
    const val CONNECTING = "connecting"

    const val NOT_WATCHED = "Not watched"

    /** Shown on a card and the page when a UnifiedPush registration with an address exists for the machine on this phone. */
    const val ALERTS_SET_UP = "alerts set up"

    /** The state line of the watched machine: "Watching · live". [health] is one of [LIVE], [NOT_LIVE], [CONNECTING]. */
    fun watchingLine(health: String) = "$WATCHING · $health"

    /** The state line of a machine that is not watched: "Not watched · wake ready · alerts set up". Unknown facts are left out, never guessed. */
    fun otherLine(wake: WakeWord?, alerts: Boolean) = listOfNotNull(NOT_WATCHED, wake?.text, ALERTS_SET_UP.takeIf { alerts }).joinToString(" · ")

    const val DEFAULT_SESSION = "default session"

    /** The session as a card's second line says it ("session work", or [DEFAULT_SESSION]). */
    fun session(name: String?) = name?.let { "session $it" } ?: DEFAULT_SESSION

    /** Settings' Machines row: "2 saved · watching Laptop". Settings is not reachable with no machine, but the words stay true if it were. */
    fun settingsRow(count: Int, watched: String?) = listOfNotNull(if (count == 0) "None saved" else "$count saved", watched?.let { "watching $it" }).joinToString(" · ")

    /** What a finished Remove says. */
    fun removed(name: String) = "Removed $name."

    /** What a user's own switch says once it is made. */
    fun nowWatching(name: String) = "Watching $name."

    /** The spoken name of a Watch control and a chip's tap action ("Watch Beta server"); a locked one adds " · Pro" where it is drawn. */
    fun watchLabel(name: String) = "Watch $name"

    /** The tap action of a machine's card on the Machines screen. */
    fun openLabel(name: String) = "Open $name"

    /**
     * The machine page of a machine that is not watched: the relay screen reads the machine through the live connection (the script, the address
     * file), so its alerts are set up or checked once it is watched.
     */
    const val ALERTS_WATCH_FIRST = "Watch this machine to set up or check its alerts."
    const val GUARDED_WATCH_FIRST = "Watch this machine to set up or check its guarded answers."

    /**
     * TalkBack's name for the watched machine's chip, the single chip (`HostChip`, Home with one machine and Spaces) and the first chip of Home's chip
     * row alike, so one chip is never read two ways: "Alpha, watching, live · 3 s"; the health word is said only when the status does not start with it.
     */
    fun chipWatched(name: String, health: String, status: String) =
        listOf(name, WATCHING.lowercase(), if (status.startsWith(health)) status else "$health, $status").joinToString(", ")

    /**
     * TalkBack's name for another machine's chip: "Beta, not watched", then what the probe found when it found something ("2 agents need you, read 20 s
     * ago", [ChipGlance.spoken]), and ", Pro" last when watching it is Pro for this phone.
     */
    fun chipOther(name: String, locked: Boolean, glance: ChipGlance? = null) =
        listOfNotNull(name, NOT_WATCHED.lowercase(), glance?.spoken, "Pro".takeIf { locked }).joinToString(", ")

    /**
     * Under the intro when an alert moved a phone without Pro off the machine the user chose: every other Watch says Pro, so the list says why this
     * one does not.
     */
    fun displaced(chosen: String, watched: String) = "An alert moved this phone to $watched. Returning to $chosen is free."

    const val WATCHING = "Watching"

    fun removeTitle(name: String) = "Remove $name?"

    /** The spoken name of the page's Remove… ("Remove Beta server…"): the page's button says only "Remove…", so TalkBack hears which machine. */
    fun removeLabel(name: String) = "Remove $name…"

    fun removeBody(name: String, watched: Boolean, next: String?): String {
        val after = when {
            !watched -> ""
            next != null -> " Paddock then watches $next."
            else -> " Paddock then has no machine to watch and asks you to add one."
        }
        return "Paddock forgets $name on this phone.$after"
    }

    /** What Remove forgets, what it keeps and what it leaves alone, as the dialog's facts (label to text). */
    val REMOVE_FACTS: List<Pair<String, String>> = listOf(
        "Forgotten" to "Its address and name, the host key this phone trusted for it, what the phone saw there, and its alert registration and setup.",
        "Kept" to "What this phone sent to it (in Activity), this phone's key, and an imported key. Adding the same machine again finds them.",
        "On the machine" to "Nothing changes: this phone stays authorized and an alert relay set up there keeps its settings. To revoke this phone, delete its paddock@phone line from ~/.ssh/authorized_keys.",
    )

    const val REMOVE = "Remove"

    /** The rest was forgotten before the failure was known (every step runs), so the machine that stays listed is already partly gone: it says so. */
    fun removeFailed(failed: List<String>) =
        "Paddock could not forget everything about this machine (${failed.joinToString(", ")}). The rest is already forgotten, so it stays listed, will ask you to trust it again, and its alerts are off. Try Remove again."

    /** A switch abandons what is running on the machine being left: the calls end as unknown, so it waits for them. */
    const val SWITCH_BUSY = "An operation on this machine is still running. Switch once it finishes."

    /** The machine page's name field: the name is this phone's label for the machine, so it says so, and says that the machine is not touched. */
    const val NAME_LABEL = "Name on this phone"
    const val NAME_HINT = "Shown on Home, in the list and in alerts. It is only a label on this phone: nothing on the machine changes."
    const val NAME_SAVE = "Save name"
    fun renamed(name: String) = "Renamed to $name."

    /** The machine page's icon row: Automatic follows what the machine said, the others are the user's pick. */
    const val OS_LABEL = "Icon"
    const val OS_HINT = "Linux, macOS and Windows are read from the machine when Paddock connects. Pick one here if it cannot tell, or got it wrong."
    fun osAuto(detected: HostOs?) = if (detected == null) "Automatic" else "Automatic (${detected.label})"

    /** The spoken name of an OS glyph; a machine with none says nothing about it. */
    fun osSpoken(os: HostOs?) = os?.let { "${it.label} machine" }

    /** One line for the page's Facts: what the glyph is and where it came from. */
    fun osFact(chosen: HostOs?, detected: HostOs?) = when {
        chosen != null -> "${chosen.label} (your pick)"
        detected != null -> "${detected.label} (read from the machine)"
        else -> "Not known yet"
    }
}

/** What a name typed for a machine comes to. Pure, so the rules are tested without a screen; [HostProfile]'s own check is the last word. */
object MachineName {
    const val MAX = 60

    sealed interface Result {
        data class Ok(val name: String) : Result
        data object Empty : Result
        data object TooLong : Result
        data object BadCharacter : Result
        data object Taken : Result
    }

    /** [raw] trimmed; [others] are the names of the other saved machines (compared without regard to case, so two cards are never told apart only by a capital). */
    fun check(raw: String, others: List<String>): Result {
        val name = raw.trim()
        return when {
            name.isEmpty() -> Result.Empty
            name.length > MAX -> Result.TooLong
            name.any { Character.isISOControl(it) } -> Result.BadCharacter
            others.any { it.trim().equals(name, ignoreCase = true) } -> Result.Taken
            else -> Result.Ok(name)
        }
    }

    fun message(result: Result): String? = when (result) {
        is Result.Ok -> null
        Result.Empty -> "Give the machine a name."
        Result.TooLong -> "Use at most $MAX characters."
        Result.BadCharacter -> "Use letters, numbers and punctuation only."
        Result.Taken -> "Another machine already has that name."
    }
}

/**
 * Removing a machine from this phone: everything that hangs off its profile id goes first, and the profile itself last. A step that fails leaves the
 * machine listed with whatever is left, so Remove can be tried again; every step is safe to run twice and for an id it knows nothing about. The phone
 * key, an imported key (one slot every machine shares) and the operation journal are not here on purpose: see [MachineCopy.REMOVE_FACTS].
 */
class MachineRemoval(private val forget: List<Forget>, private val removeProfile: suspend (String) -> Unit) {
    /** One thing the phone holds about a machine, named for the failure notice ("the host key"). */
    class Forget(val what: String, val run: suspend (String) -> Unit)

    sealed interface Result {
        data object Removed : Result

        /** [failed] names the steps that did not finish; the profile was not removed. */
        data class Incomplete(val failed: List<String>) : Result
    }

    suspend fun remove(id: String): Result {
        val failed = mutableListOf<String>()
        for (step in forget) {
            try { step.run(id) } catch (e: CancellationException) { throw e } catch (e: Exception) { failed += step.what }
        }
        if (failed.isNotEmpty()) return Result.Incomplete(failed)
        try { removeProfile(id) } catch (e: CancellationException) { throw e } catch (e: Exception) { return Result.Incomplete(listOf("the saved machine")) }
        return Result.Removed
    }
}
