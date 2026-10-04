package io.github.tuthan.paddock.ops

import io.github.tuthan.paddock.cli.CliOutcome
import io.github.tuthan.paddock.cli.CliResult
import io.github.tuthan.paddock.cli.HerdrCli
import io.github.tuthan.paddock.herdr.PaddockJson
import io.github.tuthan.paddock.herdr.SessionEntry
import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.identity.TargetRef
import io.github.tuthan.paddock.identity.TerminalKey
import io.github.tuthan.paddock.ports.ExecLimits
import io.github.tuthan.paddock.ports.ExecResult
import io.github.tuthan.paddock.ports.SshSession
import io.github.tuthan.paddock.relay.HostRefusal
import java.io.IOException
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

/** What [SessionOperations] did with a request: refused it here, with no call and no journal row, or ran it as a journaled operation. */
sealed interface SessionRun {
    data class Refused(val reason: String) : SessionRun
    data class Ran(val result: OperationResult<SessionEntry>) : SessionRun
}

/** What may be asked of a session, decided from the catalogue entry alone. The UI asks first; [SessionOperations] asks again before any call. */
object SessionRules {
    const val DEFAULT_DELETE = "The default session cannot be deleted."

    fun stopRefusal(entry: SessionEntry): String? = if (entry.running) null else "${entry.name} is already stopped."

    /** `default` is refused whatever its state; a running session has to be stopped first (herdr refuses that too, but a tap that cannot succeed is not offered). */
    fun deleteRefusal(entry: SessionEntry): String? = when {
        entry.default || entry.name == "default" -> DEFAULT_DELETE
        entry.running -> "Stop ${entry.name} before deleting it."
        else -> null
    }
}

/**
 * `session stop` and `session delete`, each one journaled operation on the exact session the user confirmed. They run herdr's own
 * CLI over the host's SSH session with the name as the only variable argument (validated, never free text); no process is ever
 * signalled, and a server that will not stop is reported with herdr's own error. The journal row is written before the command
 * leaves, the answer decides the outcome, and a link that goes away between the two leaves the row Unknown: a stop that may have
 * happened is never run a second time by the phone.
 *
 * The row's subject is `session:<name>` (see [Subject]) in the session it names, so a second stop of the same session waits
 * for the first and an unknown outcome blocks that session until the user re-reads the catalogue.
 */
class SessionOperations(
    private val session: SshSession,
    herdr: String,
    private val journal: OperationJournal,
    private val host: HostProfileId,
    private val epoch: () -> Long = { 0L },
    private val limits: ExecLimits = ExecLimits(stdoutMax = 64 shl 10, stderrMax = 16 shl 10, deadline = 30.seconds),
) {
    // Only the host-wide commands are built from this, which never name a session of their own.
    private val cli = HerdrCli(herdr, "default")

    /** The journal identity of [name]; host-level rows carry the epoch the monitor is in, or 0 when none is. */
    fun key(name: String) = TerminalKey(TargetRef(host, name, Subject.session(name)), epoch())

    suspend fun stop(entry: SessionEntry): SessionRun {
        SessionRules.stopRefusal(entry)?.let { return SessionRun.Refused(it) }
        return SessionRun.Ran(run(entry, OperationKind.SessionStop, "stopped") { cli.sessionStop(it) })
    }

    suspend fun delete(entry: SessionEntry): SessionRun {
        SessionRules.deleteRefusal(entry)?.let { return SessionRun.Refused(it) }
        return SessionRun.Ran(run(entry, OperationKind.SessionDelete, "deleted") { cli.sessionDelete(it) })
    }

    /** The user re-read the session list after an unknown outcome: the rows of that session stop blocking it; they stay Unknown. */
    fun resolve(name: String): Int = journal.unresolvedUnknown(key(name)).onEach { journal.resolve(it.id, "re-read") }.size

    private suspend fun run(entry: SessionEntry, kind: OperationKind, flag: String, argv: (String) -> List<String>): OperationResult<SessionEntry> =
        Operation.run(journal, key(entry.name), kind,
            resolveTarget = { entry.name },
            send = { name, before ->
                before()
                answer(kind, flag, session.exec(argv(name), limits = limits))
            })

    /**
     * Exit 0 with `{"<flag>":true,"session":{…}}` is success. herdr's own `{"error":{…}}` is a refusal (certain). A non-zero exit
     * with nothing parseable is shown as it is, with the CLI's text, and is still certain: herdr exited non-zero. An exit 0 that
     * does not say what it did (cut off, not JSON, flag false) throws a plain exception, so the row is Unknown rather than a guess.
     */
    private fun answer(kind: OperationKind, flag: String, r: ExecResult): SessionEntry = when (val o = CliResult.classify(r)) {
        is CliOutcome.Ok -> {
            val obj = try { PaddockJson.parseToJsonElement(o.stdout.trim()).jsonObject } catch (e: RuntimeException) { throw IOException("herdr's answer to ${kind.wire} could not be read") }
            if ((obj[flag] as? JsonPrimitive)?.booleanOrNull != true) throw IOException("herdr's answer to ${kind.wire} did not say it was done")
            decodeEntry(obj) ?: throw IOException("herdr's answer to ${kind.wire} named no session")
        }
        is CliOutcome.Failure -> throw HostRefusal(o.code, o.message)
        is CliOutcome.ClientBug -> throw HostRefusal("cli_usage", o.stderr.ifBlank { "herdr rejected the command line" })
        is CliOutcome.Unparsed ->
            if (o.exit == 0) throw IOException("herdr's answer to ${kind.wire} was cut off")
            else throw HostRefusal("cli_exit_${o.exit}", o.stderr.ifBlank { "herdr exited with status ${o.exit}" })
    }

    private fun decodeEntry(obj: JsonObject): SessionEntry? =
        (obj["session"] as? JsonObject)?.let { try { PaddockJson.decodeFromJsonElement<SessionEntry>(it) } catch (_: RuntimeException) { null } }
}

/** The wording of the stop and delete dialogs and of the refusal lines. One place, so a test can pin each sentence. */
object SessionCopy {
    fun stopTitle(name: String) = "Stop session $name?"
    fun stopBody(name: String, hostName: String, isDefault: Boolean) =
        "On $hostName. herdr stops the session's server and everything running in it ends with it." +
            if (isDefault) " This is the default session, the one a plain herdr command on that machine uses." else ""
    const val STOP_CONFIRM = "Stop session"

    fun deleteTitle(name: String) = "Delete session $name?"
    fun deleteBody(name: String, hostName: String) =
        "On $hostName. herdr removes the stopped session and its saved layout from that machine. This cannot be undone."
    const val DELETE_CONFIRM = "Delete session"
    const val CANCEL = "Cancel"

    const val RESTART_DISABLED = "Restart is not offered yet: a stopped herdr session has no server, and starting one headlessly is a separate design."

    /** One line for how a session operation ended, in the vocabulary of the journal. */
    fun outcome(kind: OperationKind, name: String, result: OperationResult<*>): String = when (result) {
        is OperationResult.Acknowledged -> when (kind) {
            OperationKind.SessionStop -> "herdr stopped session $name. The list below is read again."
            else -> "herdr says session $name is deleted. The list below is read again."
        }
        is OperationResult.Rejected -> "herdr refused: ${result.message.ifBlank { result.code }}"
        is OperationResult.NotSent -> "Nothing was sent (${result.reason}). ${result.message}".trim()
        is OperationResult.Unknown -> "${kind.wire} $name: outcome unknown. The link went away after the request left. Re-read the list before doing anything else with this session."
        is OperationResult.Busy -> "Another operation on session $name is still running."
        is OperationResult.NeedsReread -> "An earlier operation on session $name has an unknown outcome. Re-read the list first."
        is OperationResult.Stale -> "This screen is out of date. Nothing was sent."
        is OperationResult.JournalFailed -> "Nothing was sent: the operation record could not be written on this phone."
        is OperationResult.JournalUnreadable -> "Nothing was sent: the record of earlier operations on this phone cannot be read. Retry or reset it in Settings."
    }
}
