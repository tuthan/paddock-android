package io.github.tuthan.paddock.probe

import io.github.tuthan.paddock.attention.AgeText
import io.github.tuthan.paddock.cli.CliOutcome
import io.github.tuthan.paddock.cli.CliResult
import io.github.tuthan.paddock.cli.HerdrCli
import io.github.tuthan.paddock.cli.HerdrLocator
import io.github.tuthan.paddock.herdr.AgentListResult
import io.github.tuthan.paddock.herdr.AgentStatus
import io.github.tuthan.paddock.herdr.PaddockJson
import io.github.tuthan.paddock.herdr.SessionCatalog
import io.github.tuthan.paddock.herdr.decodeResult
import io.github.tuthan.paddock.hostprofile.HostProfile
import io.github.tuthan.paddock.lifecycle.ConnectionOwner
import io.github.tuthan.paddock.live.chooseSession
import io.github.tuthan.paddock.ports.Clock
import io.github.tuthan.paddock.ports.SshSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/** What one look at a machine counted: how many agents were blocked on it, and when the phone started that read. */
data class ProbeReading(val needYou: Int, val atMillis: Long)

/**
 * Why the last look at a machine did not read it. Only the words differ: the probe never repairs anything, because a repair is the watched machine's work
 * (its prompts, its install) and the chip's tap is how the user gets there.
 */
enum class ProbeProblem(val word: String) {
    /** No answer from the host: asleep, off the network, refused, timed out. */
    Unreachable("not reachable"),

    /** Only the user can fix it, and the probe never asks: a key this phone has not trusted yet, a changed key, a refused sign-in, an unreadable key, a missing grant. */
    NeedsLook("open to check"),

    /** herdr is not there, or did not answer. */
    NoHerdr("herdr not available"),

    /** No running session to read: none is running, or several are and none was chosen for this machine. */
    NoSession("no session to read"),
}

/**
 * What the phone knows about one machine it is not watching. [reading] is the last good count (kept while a later look fails, so its age can be said);
 * [problem] is why the latest look did not read; [failures] counts looks in a row that failed.
 */
data class ProbeState(val reading: ProbeReading? = null, val problem: ProbeProblem? = null, val failures: Int = 0)

/** When to look again. Pure, so the cadence is a test and not a feeling. */
object ProbeSchedule {
    /** Between two good looks at one machine. */
    const val INTERVAL_MILLIS = 30_000L

    /** The slowest a machine is looked at again after failing. */
    const val SLOWEST_MILLIS = 5 * 60_000L

    /** The most one look may take, connect included. A machine that is slower than this is unreachable for the chip's purpose. */
    const val LOOK_TIMEOUT_MILLIS = 25_000L

    /** At most this many machines are looked at at once: the phone holds a handful of short SSH sessions, never one per machine at the same instant. */
    const val PARALLEL = 2

    /**
     * The wait after a look. A good look: [INTERVAL_MILLIS]. An unreachable machine backs off 30 s, 1, 2, 4 min and then every [SLOWEST_MILLIS]; a machine
     * that needs the user, has no herdr or no session is not going to change by itself, so it waits [SLOWEST_MILLIS] from the start.
     */
    fun nextDelayMillis(problem: ProbeProblem?, failures: Int): Long = when (problem) {
        null -> INTERVAL_MILLIS
        ProbeProblem.Unreachable -> minOf(SLOWEST_MILLIS, INTERVAL_MILLIS shl (failures - 1).coerceIn(0, 4))
        else -> SLOWEST_MILLIS
    }
}

/** Which machines the probe looks at. Pure, so "only in front, only unlocked, only with Pro, never the watched one" is a test and not a reading of the wiring. */
object ProbeTargets {
    /**
     * Every saved machine except the watched one, while Paddock is in front ([inFront]), not covered by the app lock ([covered]) and `hosts.merged` is
     * not locked ([locked]); otherwise none, which stops the probe and closes what it holds.
     */
    fun of(inFront: Boolean, covered: Boolean, locked: Boolean, machines: List<HostProfile>, watchedId: String?): List<HostProfile> =
        if (inFront && !covered && !locked) machines.filter { it.id != watchedId } else emptyList()
}

/** The words a machine's chip says about what the probe found. */
data class ChipGlance(
    /** Drawn after the machine's name: "2 need you · 20 s ago", "all clear · just now", "not reachable". */
    val text: String,
    /** Added to the chip's spoken name; says more than [text] where the sighted view relies on position or colour. */
    val spoken: String,
    /** At least one agent is blocked: the text is drawn in the attention colour (the words carry the meaning too). */
    val attention: Boolean,
)

object ProbeCopy {
    /** The chip's detail for [state] at [nowMillis]; null before the first look has said anything, so a new chip is just its name. */
    fun glance(state: ProbeState?, nowMillis: Long): ChipGlance? {
        if (state == null) return null
        val reading = state.reading
        val problem = state.problem
        val age = reading?.let { AgeText.span(nowMillis - it.atMillis) }
        if (problem != null) {
            val last = if (reading == null) "" else "; last read $age, " + spokenCount(reading.needYou)
            return ChipGlance(problem.word, problem.word + last, attention = false)
        }
        if (reading == null) return null
        return ChipGlance(
            text = (if (reading.needYou == 0) "all clear" else if (reading.needYou == 1) "1 needs you" else "${reading.needYou} need you") + " · $age",
            spoken = spokenCount(reading.needYou) + ", read $age",
            attention = reading.needYou > 0,
        )
    }

    private fun spokenCount(n: Int) = when (n) { 0 -> "no agent needs you"; 1 -> "1 agent needs you"; else -> "$n agents need you" }
}

/**
 * A light look at the machines the phone is not watching, so Home's chip for each can say how many agents on it are blocked (Pro, `hosts.merged`). It
 * is not a second monitor: no stream, no heartbeat, no install, no alert and no queue. Each look opens one short SSH session through [open], runs
 * `herdr session list` and `herdr agent list` on it, counts the blocked agents and closes it. Push alerts remain what reaches the user in the background;
 * the probe runs only while [run] is, and the app runs it only in the foreground.
 *
 * [open] must not ask the user anything: a host key this phone has not trusted is a failure ([ProbeProblem.NeedsLook]), never a prompt over Home.
 * Every look has a deadline ([ProbeSchedule.LOOK_TIMEOUT_MILLIS]), at most [ProbeSchedule.PARALLEL] run at once, and a failing machine is looked at
 * less and less often ([ProbeSchedule.nextDelayMillis]).
 */
class MachineProbe(
    private val clock: Clock,
    private val open: suspend (HostProfile) -> SshSession,
    private val tickMillis: Long = 2_000,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val parallel: Int = ProbeSchedule.PARALLEL,
    private val lookTimeoutMillis: Long = ProbeSchedule.LOOK_TIMEOUT_MILLIS,
) {
    private val _states = MutableStateFlow<Map<String, ProbeState>>(emptyMap())

    /** Machine id to what the probe knows. A machine with no entry has not been looked at since the app started. */
    val states: StateFlow<Map<String, ProbeState>> = _states.asStateFlow()

    private val dueAt = ConcurrentHashMap<String, Long>()
    private val herdrPath = ConcurrentHashMap<String, String>()
    private val looking = ConcurrentHashMap.newKeySet<String>()

    /**
     * Looks at every machine in [targets] when it is due, until cancelled. A machine looked at moments ago is not looked at again when this restarts:
     * the due time outlives the call. Cancelling it ends every look at once and closes the sessions they hold.
     */
    suspend fun run(targets: List<HostProfile>) = coroutineScope {
        val permits = Semaphore(parallel)
        while (true) {
            val now = clock.nowMillis()
            for (profile in targets) {
                if ((dueAt[profile.id] ?: Long.MIN_VALUE) > now || !looking.add(profile.id)) continue
                launch {
                    try { permits.withPermit { look(profile) } } finally { looking.remove(profile.id) }
                }
            }
            sleep(tickMillis)
        }
    }

    /** A machine was removed from the phone: nothing about it is kept. */
    fun forget(profileId: String) {
        dueAt.remove(profileId); herdrPath.remove(profileId)
        _states.update { it - profileId }
    }

    /** Looks again at every machine at the next tick: the app came back to the front, or the network changed. */
    fun lookAgainNow() = dueAt.clear()

    private suspend fun look(profile: HostProfile) {
        val startedAt = clock.nowMillis()
        val found = withTimeoutOrNull(lookTimeoutMillis) { read(profile) } ?: Found.Problem(ProbeProblem.Unreachable)
        val failed = found as? Found.Problem
        _states.update { all ->
            val before = all[profile.id] ?: ProbeState()
            all + (profile.id to when (found) {
                is Found.Blocked -> ProbeState(ProbeReading(found.count, startedAt))
                is Found.Problem -> before.copy(problem = found.problem, failures = before.failures + 1)
            })
        }
        val after = _states.value[profile.id]
        dueAt[profile.id] = clock.nowMillis() + ProbeSchedule.nextDelayMillis(failed?.problem, after?.failures ?: 0)
    }

    private sealed interface Found {
        data class Blocked(val count: Int) : Found
        data class Problem(val problem: ProbeProblem) : Found
    }

    private suspend fun read(profile: HostProfile): Found {
        val session = try {
            open(profile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val (_, needsUser) = ConnectionOwner.connectFailureOf(e)
            return Found.Problem(if (needsUser) ProbeProblem.NeedsLook else ProbeProblem.Unreachable)
        }
        try {
            val herdr = herdrPath[profile.id] ?: HerdrLocator.find(session)?.also { herdrPath[profile.id] = it }
                ?: return Found.Problem(ProbeProblem.NoHerdr)
            val listed = CliResult.classify(session.exec(HerdrCli(herdr, "default").sessionList()))
            val catalog = (listed as? CliOutcome.Ok)?.let { PaddockJson.decodeResult<SessionCatalog>(it.stdout).getOrNull() }
            if (catalog == null) { herdrPath.remove(profile.id); return Found.Problem(ProbeProblem.NoHerdr) }
            val chosen = chooseSession(catalog, profile.session) ?: return Found.Problem(ProbeProblem.NoSession)
            val agents = CliResult.classify(session.exec(HerdrCli(herdr, chosen.name).agentList()))
            val list = (agents as? CliOutcome.Ok)?.let { runCatching { CliResult.decode<AgentListResult>(it, "agent_list") }.getOrNull() }
                ?: return Found.Problem(ProbeProblem.NoHerdr)
            return Found.Blocked(list.agents.count { it.agentStatus == AgentStatus.Blocked })
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            herdrPath.remove(profile.id)
            return Found.Problem(ProbeProblem.Unreachable)
        } finally {
            withContext(NonCancellable) { runCatching { session.close() } }
        }
    }
}
