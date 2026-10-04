package io.github.tuthan.paddock.widget

import io.github.tuthan.paddock.attention.AttentionModel
import io.github.tuthan.paddock.attention.SeenLookup
import io.github.tuthan.paddock.cli.CliOutcome
import io.github.tuthan.paddock.cli.CliResult
import io.github.tuthan.paddock.cli.HerdrCli
import io.github.tuthan.paddock.cli.HerdrLocator
import io.github.tuthan.paddock.herdr.Budgets
import io.github.tuthan.paddock.herdr.SnapshotResult
import io.github.tuthan.paddock.hostprofile.HostProfile
import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.ports.Clock
import io.github.tuthan.paddock.ports.ExecLimits
import io.github.tuthan.paddock.ports.SshSession
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

/**
 * The widgets' background read: one short SSH command, `herdr --session S api snapshot`, on a connection the caller opened and will release,
 * turned into the same counts and rows the herd shows and written to the cache. It installs nothing on the host, holds no socket after it
 * returns, and a read that fails leaves the cache exactly as it was, so the widgets keep saying when their numbers are from.
 */
class WidgetRefresher(
    private val store: WidgetCacheStore,
    private val clock: Clock,
    /** Which Done agents the user already saw on this phone; none when the phone has no installed read of that session yet. */
    private val seen: (HostProfileId, String) -> SeenLookup = { _, _ -> SeenLookup { null } },
) {
    sealed interface Outcome {
        data class Updated(val cache: WidgetCache) : Outcome
        /** Nothing was written; [reason] is for the log and the evidence, never for a widget. */
        data class Unchanged(val reason: String) : Outcome
    }

    suspend fun refresh(profile: HostProfile, session: SshSession, sessionName: String): Outcome {
        if (!HerdrCli.SESSION_NAME.matches(sessionName)) return Outcome.Unchanged("the session name is not one herdr uses")
        val herdr = try { HerdrLocator.find(session) } catch (e: IOException) { return Outcome.Unchanged("the host could not be asked: ${e.message}") }
            ?: return Outcome.Unchanged("herdr was not found on the host")
        val r = try {
            session.exec(HerdrCli(herdr, sessionName).apiSnapshot(), limits = ExecLimits(stdoutMax = Budgets.SNAPSHOT_LINE, stderrMax = 4096, deadline = 20.seconds))
        } catch (e: IOException) {
            return Outcome.Unchanged("the host could not be asked: ${e.message}")
        }
        val ok = CliResult.classify(r) as? CliOutcome.Ok ?: return Outcome.Unchanged("herdr did not answer the snapshot (exit ${r.exit})")
        val snapshot = try { CliResult.decode<SnapshotResult>(ok, "session_snapshot").snapshot } catch (e: Exception) { return Outcome.Unchanged("the snapshot was not readable") }
        val now = clock.nowMillis()
        val home = AttentionModel.home(snapshot, now, profile.hostId, sessionName, epoch = 0, seen = seen(profile.hostId, sessionName))
        val cache = WidgetCacheBuilder.from(home, profile.id, profile.name, sessionName, now)
        try { store.save(cache) } catch (e: IOException) { return Outcome.Unchanged("the cache could not be written: ${e.message}") }
        return Outcome.Updated(cache)
    }
}
