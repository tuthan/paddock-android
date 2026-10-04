package io.github.tuthan.paddock.live

import io.github.tuthan.paddock.cli.CliOutcome
import io.github.tuthan.paddock.cli.CliResult
import io.github.tuthan.paddock.cli.HerdrCli
import io.github.tuthan.paddock.herdr.PaddockJson
import io.github.tuthan.paddock.herdr.SessionCatalog
import io.github.tuthan.paddock.herdr.SessionEntry
import io.github.tuthan.paddock.herdr.decodeResult
import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.ops.OperationJournal
import io.github.tuthan.paddock.ops.OperationKind
import io.github.tuthan.paddock.ops.SavedLayoutReader
import io.github.tuthan.paddock.ops.SavedLayoutResult
import io.github.tuthan.paddock.ops.SessionCopy
import io.github.tuthan.paddock.ops.SessionOperations
import io.github.tuthan.paddock.ops.SessionRun
import io.github.tuthan.paddock.ports.Clock
import io.github.tuthan.paddock.ports.ExecLimits
import io.github.tuthan.paddock.ports.SshSession
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One line of the Spaces list: a session herdr knows, and for a stopped one what it saved. */
data class SessionRow(
    val entry: SessionEntry,
    /** Null for a running session, which shows its live herd instead of a file. */
    val layout: SavedLayoutResult? = null,
    /** True while an operation on this session is running or has an unknown outcome the user has not re-read yet. */
    val busy: Boolean = false,
    val awaitsReread: Boolean = false,
)

sealed interface SpacesState {
    data object Loading : SpacesState
    /** [readAtMillis] is when the catalogue was read: the list is that moment's, never a live view. */
    data class Ready(val rows: List<SessionRow>, val readAtMillis: Long) : SpacesState
    data class Failed(val message: String) : SpacesState
}

/**
 * One host's sessions, for the Spaces screen: the catalogue (`session list --json`, host-wide and independent of which session the
 * phone monitors), the saved layout of each stopped one, and stop and delete as journaled operations. It runs herdr's CLI over the
 * host's SSH session and holds no socket. Every operation ends with a fresh read, so the list shows what is there and not what the
 * phone expected; an unknown outcome stays marked on its row until the user chooses Re-read.
 */
class HostSpaces(
    private val session: SshSession,
    herdr: String,
    private val journal: OperationJournal,
    private val host: HostProfileId,
    private val clock: Clock,
    epoch: () -> Long = { 0L },
) {
    private val cli = HerdrCli(herdr, "default")
    private val reader = SavedLayoutReader(session)
    val sessionOps = SessionOperations(session, herdr, journal, host, epoch)

    private val _state = MutableStateFlow<SpacesState>(SpacesState.Loading)
    val state: StateFlow<SpacesState> = _state.asStateFlow()

    /** What the last operation said, in words, until the user dismisses it or starts another. */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()
    fun dismissNotice() { _notice.value = null }

    suspend fun refresh() {
        val outcome = try { CliResult.classify(session.exec(cli.sessionList(), limits = ExecLimits(stdoutMax = 256 * 1024, stderrMax = 16 * 1024, deadline = 20.seconds))) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { _state.value = SpacesState.Failed("The host could not be asked: ${e.message ?: e.javaClass.simpleName}".take(200)); return }
        val catalog = (outcome as? CliOutcome.Ok)?.let { PaddockJson.decodeResult<SessionCatalog>(it.stdout).getOrNull() }
        if (catalog == null) { _state.value = SpacesState.Failed("herdr did not give a readable session list."); return }
        val at = clock.nowMillis()
        val rows = catalog.sessions.sortedWith(compareBy<SessionEntry>({ !it.default }, { !it.running }, { it.name })).map { e ->
            val key = sessionOps.key(e.name)
            val records = journal.forTerminal(key)
            SessionRow(e, layout = if (e.running) null else reader.read(e), busy = records.any { it.inFlight }, awaitsReread = records.any { it.awaitsReread })
        }
        _state.value = SpacesState.Ready(rows, at)
    }

    suspend fun stop(entry: SessionEntry) = act(OperationKind.SessionStop, entry) { sessionOps.stop(entry) }
    suspend fun delete(entry: SessionEntry) = act(OperationKind.SessionDelete, entry) { sessionOps.delete(entry) }

    /** The user chose Re-read after an unknown outcome: read the list again, then free the session's rows. */
    suspend fun reread(name: String) {
        refresh()
        if (_state.value is SpacesState.Ready) { sessionOps.resolve(name); refresh() }
    }

    private suspend fun act(kind: OperationKind, entry: SessionEntry, run: suspend () -> SessionRun): SessionRun {
        _notice.value = null
        val r = run()
        _notice.value = when (r) {
            is SessionRun.Refused -> r.reason
            is SessionRun.Ran -> SessionCopy.outcome(kind, entry.name, r.result)
        }
        // Whatever it said, the list is read again: the host's answer is not the same thing as what is there now.
        refresh()
        return r
    }
}
