package io.github.tuthan.paddock.hostprofile

import kotlin.coroutines.cancellation.CancellationException

/** One saved machine as the machine list on Home shows it. */
data class MachineRow(val id: String, val name: String, val endpoint: String, val session: String?, val watched: Boolean)

object MachineRoster {
    fun endpoint(p: HostProfile) = "${p.user}@${p.host}:${p.port}"

    /** The watched machine first, then the others in the order the store gives them (by name). */
    fun rows(profiles: List<HostProfile>, watchedId: String?): List<MachineRow> =
        profiles.map { MachineRow(it.id, it.name, endpoint(it), it.session, it.id == watchedId) }.sortedBy { if (it.watched) 0 else 1 }

    /** The machine to watch once [removedId] is gone: the first of the others, as at start-up. Null when none is left. */
    fun after(profiles: List<HostProfile>, removedId: String): HostProfile? = profiles.firstOrNull { it.id != removedId }
}

/** Sentences for the machine list and for Remove. One place, tested, so what the dialog says is what [MachineRemoval] does. */
object MachineCopy {
    const val TITLE = "Machines"

    const val INTRO = "Paddock watches one machine at a time. Tap another to watch it instead."

    const val WATCHING = "Watching"

    /** The state of a machine that is not watched, read by TalkBack with its name. */
    const val SWITCH_HINT = "Watch this machine"

    fun removeTitle(name: String) = "Remove $name?"

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
        "Forgotten" to "Its address and name, the host key this phone trusted for it, what the phone saw there, and its alert registration.",
        "Kept" to "What this phone sent to it (in Activity), this phone's key, and an imported key. Adding the same machine again finds them.",
        "On the machine" to "Nothing changes: this phone stays authorized and an alert relay set up there keeps its settings. To revoke this phone, delete its paddock@phone line from ~/.ssh/authorized_keys.",
    )

    const val REMOVE = "Remove"

    /** The rest was forgotten before the failure was known (every step runs), so the machine that stays listed is already partly gone: it says so. */
    fun removeFailed(failed: List<String>) =
        "Paddock could not forget everything about this machine (${failed.joinToString(", ")}). The rest is already forgotten, so it stays listed, will ask you to trust it again, and its alerts are off. Try Remove again."

    /** A switch abandons what is running on the machine being left: the calls end as unknown, so it waits for them. */
    const val SWITCH_BUSY = "An operation on this machine is still running. Switch once it finishes."
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
