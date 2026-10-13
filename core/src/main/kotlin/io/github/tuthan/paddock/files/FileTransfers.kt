package io.github.tuthan.paddock.files

import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.identity.TargetRef
import io.github.tuthan.paddock.identity.TerminalKey
import io.github.tuthan.paddock.ops.Begin
import io.github.tuthan.paddock.ops.JournalWriteFailed
import io.github.tuthan.paddock.ops.Operation
import io.github.tuthan.paddock.ops.OperationJournal
import io.github.tuthan.paddock.ops.OperationKind
import io.github.tuthan.paddock.ops.OperationOutcome
import io.github.tuthan.paddock.ops.OperationRecord
import io.github.tuthan.paddock.ops.OperationResult
import io.github.tuthan.paddock.ops.Subject
import io.github.tuthan.paddock.ports.Clock
import io.github.tuthan.paddock.ports.ExecLimits
import io.github.tuthan.paddock.ports.SshSession

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Where the bytes of a file to send come from, opened once per send. `:app` backs it with the intake's snapshot; `:core` knows no Android type. */
fun interface ByteSource { fun open(): InputStream }

/**
 * Where the bytes of a fetched file go, opened once per [FileTransfers.get] and closed by it. After anything but [GetResult.Fetched] it holds a partial
 * file, which the caller deletes: nothing here ever calls a short or changed file whole.
 */
fun interface ByteSink { fun open(): OutputStream }

/** What [FileTransfers.stat] saw, on the real path: the size from `wc -c`, the time in seconds, and the real path the sheet shows and Get names. */
data class RemoteStat(val size: Long, val mtimeSeconds: Long, val path: String)

/** One row of the outbox or the drop folder's list. */
data class RemoteFile(val name: String, val size: Long, val mtimeSeconds: Long)

/** What the inbox holds under a name: the facts a person needs to decide, never a verdict on whether the earlier send worked. */
sealed interface InboxCheck {
    data class Present(val size: Long) : InboxCheck
    data object Absent : InboxCheck
}

enum class ListWhich { Outbox, Drop }

/** How a read-only question to the machine ended. [Refused] is the script's own refusal; [Failed] is everything else (a lost link, an answer that does not parse). */
sealed interface TransferReply<out T> {
    data class Ok<T>(val value: T) : TransferReply<T>
    data class Refused(val refusal: TransferRefusal) : TransferReply<Nothing>
    data class Failed(val message: String) : TransferReply<Nothing>
}

sealed interface GetResult {
    /** Whole and counted: [size] bytes of the stat's size, [sha256] of what arrived, and the journal's `Fetch` row (or [OperationJournal.NOT_RECORDED]). */
    data class Fetched(val recordId: Long, val size: Long, val sha256: String) : GetResult
    data class Refused(val refusal: TransferRefusal) : GetResult
    /** A short or longer read, a file that changed, a deadline, a lost link: no `Fetch` row, and the sink holds a partial file. */
    data class Failed(val message: String) : GetResult
}

/**
 * Part B's phone side: one file to the machine ([put] into an agent's inbox, [drop] into the machine's drop folder), a few questions ([check], [stat],
 * [resolveDrop], [list]) and one file back ([get]), each over the [SshSession] by a constant script in [TransferScripts] with the free text on stdin.
 *
 * A write is journaled ahead of its first byte and never resent by itself. The row is an [OperationKind.Attachment] with the destination path, the size and
 * the file's SHA-256; it goes Requested, Sent (right before the header), then by the script's exit status:
 * - 0: Acknowledged. The machine counted every byte before it gave the file its name, which is more than any prompt's acknowledgement says.
 * - a [TransferRefusal]'s status: Rejected with that refusal's code and sentence. Nothing was kept.
 * - anything else, a lost link, a deadline, a cancel: Unknown. A part may be there; it is never published, and the inbox's name settles it ([check]).
 * A refusal the phone can see before opening a channel (the size, the name, the folder) is NotSent with the same code and sentence.
 *
 * The caps and the deadline are the caller's ([maxBytes], `deadline`): they come from spike S2 and live in slice 9's limits. Every call that opens a
 * channel takes one of the connection's eight, so the caller runs one transfer per machine at a time.
 */
class FileTransfers(private val session: SshSession, private val journal: OperationJournal, private val clock: Clock) {

    /**
     * Writes [source] (exactly [size] bytes whose SHA-256 is [sha256]) to `<folder>/.paddock/inbox/<name>` for the agent [key] names. The value of an
     * Acknowledged result is the path under the agent's folder, `.paddock/inbox/<name>`, which is what the prompt's phrase names.
     */
    suspend fun put(key: TerminalKey, folder: String, name: String, source: ByteSource, size: Long, sha256: String, maxBytes: Long, deadline: Duration): OperationResult<String> =
        putAs(key, folder, name, source, size, sha256, maxBytes, deadline, known = null)

    /** [put], or the refusal [known] already (one the phone can see), journaled as not sent before any channel. */
    private suspend fun putAs(
        key: TerminalKey, folder: String, name: String, source: ByteSource, size: Long, sha256: String, maxBytes: Long, deadline: Duration, known: TransferRefusal?,
    ): OperationResult<String> {
        val dest = inboxPath(folder, name)
        val local = refusalBeforeSending(folder, name, size, maxBytes, folderMustBeAbsolute = true) ?: known
        return write(key, dest, TransferScripts.putArgv(maxBytes), "$folder\n$name\n$size\n", inboxRelPath(name), source, size, sha256, deadline, local)
    }

    /**
     * Writes [source] to the machine's drop folder (X10): [folder] is its value (`~/Paddock`) or, better, the real path [resolveDrop] answered, and the
     * value of an Acknowledged result is `<folder>/<name>`. The row's subject is the host profile's ([Subject.drop]) and has no terminal.
     */
    suspend fun drop(profile: HostProfileId, folder: String, name: String, source: ByteSource, size: Long, sha256: String, maxBytes: Long, deadline: Duration): OperationResult<String> {
        val key = TerminalKey(TargetRef(profile, session = "", terminalId = Subject.drop(profile.value)), epoch = 0)
        val dest = "${folder.trimEnd('/')}/$name"
        val local = refusalBeforeSending(folder, name, size, maxBytes, folderMustBeAbsolute = false)
        return write(key, dest, TransferScripts.dropArgv(maxBytes), "$folder\n$name\n$size\n", dest, source, size, sha256, deadline, local)
    }

    /**
     * Whether `<folder>/.paddock/inbox/<name>` is there, and how long: what a person reads to settle a cut attachment (X9). Every cut attachment of [key]
     * for that path stops holding the prompt that would name it, whatever the answer was (it stays Unknown, with the answer as its note and the size
     * found as its [OperationRecord.inboxSize]); an answer that did not arrive settles nothing. A refusal that proves no file can be at the path (the
     * agent's folder is gone, as a removed worktree's is, or the file is not there to count) settles it as an absent one does, and is still what is
     * returned; any other refusal settles nothing. Creates nothing on the machine.
     */
    suspend fun check(key: TerminalKey, folder: String, name: String): TransferReply<InboxCheck> {
        if (!validFolder(folder, absolute = true)) return TransferReply.Refused(TransferRefusal.BadPath)
        if (!TransferScripts.NAME.matches(name)) return TransferReply.Refused(TransferRefusal.BadName)
        val r = ask(TransferScripts.checkArgv(), "$folder\n$name\n", SMALL)
        if (r is Raw.Refused && r.refusal in PROVES_ABSENT) {
            settleCut(key, folder, name) { "inbox read: ${if (r.refusal == TransferRefusal.NotDirectory) "the agent's folder is not there" else "not there"}" }
            return TransferReply.Refused(r.refusal)
        }
        if (r !is Raw.Out) return r.asReply()
        val parts = r.text.trimEnd('\n').split('\t')
        val found = when {
            parts == listOf("absent") -> InboxCheck.Absent
            parts.size == 2 && parts[0] == "present" && DIGITS.matches(parts[1]) -> InboxCheck.Present(parts[1].toLongOrNull() ?: return TransferReply.Failed("the machine's answer did not make sense"))
            else -> return TransferReply.Failed("the machine's answer did not make sense")
        }
        settleCut(key, folder, name, (found as? InboxCheck.Present)?.size) { row ->
            when (found) { is InboxCheck.Present -> "inbox read: there, ${found.size} bytes (sent ${row.size})"; InboxCheck.Absent -> "inbox read: not there" }
        }
        return TransferReply.Ok(found)
    }

    /** Settles every cut attachment of [key] for `<folder>/.paddock/inbox/<name>` with the note [said] gives it and the size found there, if any. */
    private suspend fun settleCut(key: TerminalKey, folder: String, name: String, found: Long? = null, said: (OperationRecord) -> String) {
        val dest = inboxPath(folder, name)
        withContext(NonCancellable + Dispatchers.IO) {
            for (row in journal.unresolvedAttachments(key).filter { it.path == dest }) journal.resolve(row.id, said(row), inboxSize = found)
        }
    }

    /**
     * [put] for the file of a prompt, which the composer sends again whole when the prompt after it did not go (X9, X12). Each name the file may take
     * in the inbox ([inboxNames]: [name], then `name-2`...) is judged by this terminal's journal before anything is written:
     * - a name that holds this exact file is used as it is, and nothing is written: an attachment row there with the same SHA-256 and size that was
     *   acknowledged, or that was cut and then read there at its whole size (the script gives a file its name only once every byte arrived). The
     *   result is Acknowledged with that row;
     * - a name that holds another file (acknowledged, read there, or refused as existing) is passed over, and so is one the machine refuses as existing;
     * - the first that is neither is written to, and its outcome is the result.
     * The value of an Acknowledged result is the path the prompt's phrase names, `.paddock/inbox/<the name used>`. When the journal shows every name
     * taken, the last is journaled as not sent ([TransferRefusal.Exists]) and nothing is written.
     */
    suspend fun attach(key: TerminalKey, folder: String, name: String, source: ByteSource, size: Long, sha256: String, maxBytes: Long, deadline: Duration): OperationResult<String> {
        val names = inboxNames(name)
        var last: OperationResult<String>? = null
        for (candidate in names) {
            val rows = journal.forTerminal(key).filter { it.kind == OperationKind.Attachment && it.path == inboxPath(folder, candidate) }
            rows.lastOrNull { holds(it, size, sha256) }?.let { return OperationResult.Acknowledged(it, inboxRelPath(candidate)) }
            if (rows.any(::taken)) continue
            val r = put(key, folder, candidate, source, size, sha256, maxBytes, deadline)
            // The script never overwrites: a name that is taken is the only refusal worth another try, under the next name.
            if (!(r is OperationResult.Rejected && r.code == TransferRefusal.Exists.code)) return r
            last = r
        }
        return last ?: putAs(key, folder, names.last(), source, size, sha256, maxBytes, deadline, known = TransferRefusal.Exists)
    }

    /** Whether attachment row [row] shows the file of [size] bytes and [sha256] under its path: acknowledged there, or cut and then read there whole. */
    private fun holds(row: OperationRecord, size: Long, sha256: String) = row.payloadSha256 == sha256 && row.size == size &&
        (row.outcome == OperationOutcome.Acknowledged || (row.outcome == OperationOutcome.Unknown && row.inboxSize == size))

    /** Whether attachment row [row] shows a file under its path, whichever it is: one it wrote, one read there, or one the machine said was there. */
    private fun taken(row: OperationRecord) = row.outcome == OperationOutcome.Acknowledged || row.inboxSize != null || row.code == TransferRefusal.Exists.code

    /** A path under the agent's [folder], looked at on its real path (X2): the size, the time and the real path, or the script's refusal. */
    suspend fun stat(folder: String, path: String, maxBytes: Long): TransferReply<RemoteStat> {
        if (!validFolder(folder, absolute = true) || !validFolder(path, absolute = true)) return TransferReply.Refused(TransferRefusal.BadPath)
        val r = ask(TransferScripts.statArgv(maxBytes), "$folder\n$path\n", SMALL)
        if (r !is Raw.Out) return r.asReply()
        val parts = r.text.trimEnd('\n').split('\t')
        val size = parts.getOrNull(0)?.takeIf(DIGITS::matches)?.toLongOrNull()
        val time = parts.getOrNull(1)?.takeIf(DIGITS::matches)?.toLongOrNull()
        val real = parts.getOrNull(2)?.takeIf { it.startsWith("/") && it.none(Char::isISOControl) }
        return if (parts.size == 3 && size != null && time != null && real != null) TransferReply.Ok(RemoteStat(size, time, real))
        else TransferReply.Failed("the machine's answer did not make sense")
    }

    /** What a drop folder's value resolves to, shown before it is saved (X10): the real path, or the script's refusal (home itself, hidden, outside home, a link). */
    suspend fun resolveDrop(value: String): TransferReply<String> {
        if (!validFolder(value, absolute = false)) return TransferReply.Refused(TransferRefusal.BadPath)
        val r = ask(TransferScripts.resolveDropArgv(), "$value\n", SMALL)
        if (r !is Raw.Out) return r.asReply()
        val real = r.text.trimEnd('\n')
        return if (real.startsWith("/") && real.none(Char::isISOControl)) TransferReply.Ok(real) else TransferReply.Failed("the machine's answer did not make sense")
    }

    /**
     * The newest 50 regular files of the agent's outbox ([which] is [ListWhich.Outbox] and [folder] the agent's) or of the drop folder ([folder] its real
     * path), newest first. A row that does not parse is left out, not guessed at. An outbox that does not exist is [TransferRefusal.NotFound], which is an empty list.
     */
    suspend fun list(folder: String, which: ListWhich): TransferReply<List<RemoteFile>> {
        if (!validFolder(folder, absolute = true)) return TransferReply.Refused(TransferRefusal.BadPath)
        val dir = if (which == ListWhich.Outbox) "${folder.trimEnd('/')}/.paddock/outbox" else folder
        val r = ask(TransferScripts.listArgv(), "$dir\n", LIST_LIMITS)
        if (r !is Raw.Out) return r.asReply()
        if (r.truncated) return TransferReply.Failed("the machine's list was longer than Paddock reads")
        val rows = r.text.split('\n').mapNotNull { line ->
            val p = line.split('\t')
            val size = p.getOrNull(1)?.takeIf(DIGITS::matches)?.toLongOrNull()
            val time = p.getOrNull(2)?.takeIf(DIGITS::matches)?.toLongOrNull()
            if (p.size == 3 && p[0].isNotEmpty() && p[0].none(Char::isISOControl) && '/' !in p[0] && size != null && time != null) RemoteFile(p[0], size, time) else null
        }
        return TransferReply.Ok(rows.take(MAX_LIST))
    }

    /**
     * Reads [path], the real path [stat] answered under the agent's [folder], into [sink]. The script re-judges the path and refuses a file whose size is no
     * longer [size]; the count that arrives must equal [size] exactly, and then its SHA-256 and a `Fetch` row are made. A longer or shorter stream, a deadline
     * or a lost link is a [GetResult.Failed] with no row.
     */
    suspend fun get(key: TerminalKey, folder: String, path: String, size: Long, sink: ByteSink, maxBytes: Long, deadline: Duration): GetResult {
        if (!validFolder(folder, absolute = true) || !validFolder(path, absolute = true)) return GetResult.Refused(TransferRefusal.BadPath)
        if (size < 0 || size > maxBytes) return GetResult.Refused(TransferRefusal.OverCap)
        val channel = try { session.openStream(TransferScripts.getArgv(maxBytes)) } catch (e: CancellationException) { throw e } catch (e: Exception) { return GetResult.Failed(cause(e)) }
        try {
            var count = 0L
            val digest = MessageDigest.getInstance("SHA-256")
            var grew = false
            val exit = withTimeoutOrNull(deadline) {
                channel.write("$folder\n$path\n$size\n".toByteArray())
                channel.closeStdin()
                withContext(Dispatchers.IO) { sink.open() }.use { out ->
                    channel.stdout.takeWhile { chunk ->
                        count += chunk.size
                        if (count > size) { grew = true; false } else { digest.update(chunk); withContext(Dispatchers.IO) { out.write(chunk) }; true }
                    }.collect {}
                }
                if (grew) -1 else channel.awaitExit()
            } ?: return GetResult.Failed("it took longer than $deadline")
            TransferRefusal.forExit(exit)?.let { return GetResult.Refused(it) }
            return when {
                grew -> GetResult.Failed("the file grew while it was read")
                exit != 0 -> GetResult.Failed("the machine ended the read with status $exit")
                count != size -> GetResult.Failed("$count of $size bytes arrived")
                else -> {
                    val sha = digest.digest().joinToString("") { "%02x".format(it) }
                    GetResult.Fetched(withContext(NonCancellable + Dispatchers.IO) { journal.recordFetch(key, path, size, sha) }, size, sha)
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { return GetResult.Failed(cause(e)) }
        finally { withContext(NonCancellable) { channel.close() } }
    }

    // ---- the write ----------------------------------------------------------------------------------------------------

    private fun refusalBeforeSending(folder: String, name: String, size: Long, maxBytes: Long, folderMustBeAbsolute: Boolean): TransferRefusal? = when {
        size < 1 -> TransferRefusal.BadCount
        size > maxBytes -> TransferRefusal.OverCap
        !TransferScripts.NAME.matches(name) -> TransferRefusal.BadName
        !validFolder(folder, folderMustBeAbsolute) -> TransferRefusal.BadPath
        else -> null
    }

    /** A write's whole life: row, source, channel, first byte, last byte, exit. [early] is a refusal the phone saw before any channel. */
    private suspend fun write(
        key: TerminalKey, dest: String, argv: List<String>, header: String, value: String, source: ByteSource, size: Long, sha256: String, deadline: Duration, early: TransferRefusal?,
    ): OperationResult<String> {
        require(SHA256.matches(sha256)) { "a file is journaled by its SHA-256" }
        val row = try {
            when (val b = Operation.begin(journal) { journal.begin(key, OperationKind.Attachment, path = dest, size = size, fileSha256 = sha256) }) {
                is Begin.Started -> b.record
                is Begin.InFlight -> return OperationResult.Busy(b.first)
                is Begin.NeedsReread -> return OperationResult.NeedsReread(b.unknown)
                is Begin.Unreadable -> return OperationResult.JournalUnreadable(b.reason)
            }
        } catch (e: JournalWriteFailed) { return OperationResult.JournalFailed(e.message.orEmpty()) }
        if (early != null) return notSent(row, early.code, early.sentence)

        val input = try { withContext(Dispatchers.IO) { source.open() } }
        catch (e: CancellationException) { settle { journal.notSent(row.id, "cancelled") }; throw e }
        catch (e: Exception) { return notSent(row, "source_unreadable", cause(e)) }
        val channel = try { session.openStream(argv) }
        catch (e: CancellationException) { runCatching { input.close() }; settle { journal.notSent(row.id, "cancelled") }; throw e }
        catch (e: Exception) { runCatching { input.close() }; return notSent(row, "transport_failed", cause(e)) }

        val started = clock.nowMillis()
        var sent = 0L
        try {
            io { journal.markSent(row.id) }
            val exit = withTimeoutOrNull(deadline) {
                channel.write(header.toByteArray())
                val buf = ByteArray(CHUNK)
                input.use { ins ->
                    while (sent < size) {
                        val n = withContext(Dispatchers.IO) { ins.read(buf, 0, minOf(buf.size.toLong(), size - sent).toInt()) }
                        if (n < 0) break
                        channel.write(buf.copyOf(n))
                        sent += n
                    }
                }
                channel.closeStdin()
                channel.awaitExit()
            } ?: return cut(row, "no answer in $deadline", sent, size, started)
            return when {
                exit == 0 -> OperationResult.Acknowledged(settle { journal.acknowledged(row.id) }, value)
                TransferRefusal.forExit(exit) != null -> { val why = TransferRefusal.forExit(exit)!!; OperationResult.Rejected(settle { journal.rejected(row.id, why.code, why.sentence) }, why.code, why.sentence) }
                else -> cut(row, "the machine ended with status $exit", sent, size, started)
            }
        } catch (e: JournalWriteFailed) {
            return notSent(row, "journal_unwritable", e.message.orEmpty())
        } catch (e: CancellationException) {
            settle { lose(row, "cancelled", sent, size, started) }
            throw e
        } catch (e: Exception) {
            return cut(row, cause(e), sent, size, started)
        } finally {
            runCatching { input.close() }
            withContext(NonCancellable) { channel.close() }
        }
    }

    /** What is left after a write that may have started: Unknown when the row says it was Sent, else certain that nothing left. */
    private suspend fun cut(row: OperationRecord, why: String, sent: Long, size: Long, startedAt: Long): OperationResult<String> {
        val after = settle { lose(row, why, sent, size, startedAt) }
        return if (after.outcome == OperationOutcome.Unknown) OperationResult.Unknown(after, why) else OperationResult.NotSent(after, "transport_failed", why)
    }

    private fun lose(row: OperationRecord, why: String, sent: Long, size: Long, startedAt: Long): OperationRecord {
        val note = "cut after ${(clock.nowMillis() - startedAt) / 1000} s, $sent of $size bytes: $why"
        return when (journal.get(row.id)?.outcome) {
            OperationOutcome.Sent -> journal.unknown(row.id, note)
            OperationOutcome.Requested -> journal.notSent(row.id, "transport_failed", why)
            else -> journal.get(row.id) ?: row
        }
    }

    private suspend fun notSent(row: OperationRecord, code: String, message: String): OperationResult<String> =
        OperationResult.NotSent(settle { journal.notSent(row.id, code, message) }, code, message)

    // ---- the questions ------------------------------------------------------------------------------------------------

    private sealed interface Raw {
        data class Out(val text: String, val truncated: Boolean) : Raw
        data class Refused(val refusal: TransferRefusal) : Raw
        data class Failed(val message: String) : Raw
        fun <T> asReply(): TransferReply<T> = when (this) {
            is Refused -> TransferReply.Refused(refusal)
            is Failed -> TransferReply.Failed(message)
            is Out -> error("an answer is read, not converted")
        }
    }

    private suspend fun ask(argv: List<String>, stdin: String, limits: ExecLimits): Raw {
        val r = try { session.exec(argv, stdin.toByteArray(), limits) } catch (e: CancellationException) { throw e } catch (e: Exception) { return Raw.Failed(cause(e)) }
        TransferRefusal.forExit(r.exit)?.let { return Raw.Refused(it) }
        if (r.exit != 0) return Raw.Failed("the machine ended with status ${r.exit}")
        return Raw.Out(r.stdout.toString(Charsets.UTF_8), r.stdoutTruncated)
    }

    /** What every script accepts of a folder or path before it looks at the disk: no control character, and absolute (a drop's value may also start with `~`). */
    private fun validFolder(value: String, absolute: Boolean) = value.isNotEmpty() && value.none(Char::isISOControl) &&
        if (absolute) value.startsWith("/") else (value.startsWith("/") || value == "~" || value.startsWith("~/"))

    private fun cause(e: Throwable) = (e.message ?: e::class.simpleName).orEmpty().lineSequence().first().take(OperationJournal.MAX_NOTE)

    private suspend fun <R> io(block: () -> R): R = withContext(Dispatchers.IO) { block() }

    /** Journal moves after a send or a cancel must finish even when the caller was cancelled. */
    private suspend fun <R> settle(block: () -> R): R = withContext(NonCancellable + Dispatchers.IO) { block() }

    companion object {
        /** The size [put] and [get] cap at until spike S2 sets the real numbers (X3: 25 MB to the machine, 50 MB to the phone). PROVISIONAL; slice 9's limits replace it. */
        const val PROVISIONAL_MAX_BYTES = 25L shl 20
        /** How long a transfer may run before it is cut. PROVISIONAL, from nothing measured: S2 scales it from the rate. */
        val PROVISIONAL_DEADLINE: Duration = 10.minutes

        /** At most this many rows of a list. */
        const val MAX_LIST = 50

        /** The destination a prompt's phrase names: under the agent's folder, in the inbox. */
        fun inboxRelPath(name: String) = ".paddock/inbox/$name"
        private fun inboxPath(folder: String, name: String) = "${folder.trimEnd('/')}/${inboxRelPath(name)}"

        /** How many names [attach] tries for one file: its own, then `-2` up to this. */
        const val INBOX_NAMES = 5

        /** The names [attach] may give a file called [name] in the inbox, in the order it tries them: [name], then [FileNames.retryName]'s `-2`, `-3`... */
        fun inboxNames(name: String): List<String> = listOf(name) + (2..INBOX_NAMES).map { FileNames.retryName(name, it) }

        /**
         * What [check]'s script refuses only when no file can be at the path: the agent's folder is not a folder, or the file was not there to count. [check]
         * settles the cut row for these as it does for Absent, so the caller says the file is not there.
         */
        val PROVES_ABSENT = setOf(TransferRefusal.NotDirectory, TransferRefusal.NotFound)

        private const val CHUNK = 32 * 1024
        private val DIGITS = Regex("[0-9]{1,15}")
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val SMALL = ExecLimits(stdoutMax = 8 * 1024, stderrMax = 4 * 1024, deadline = 15.seconds)
        private val LIST_LIMITS = ExecLimits(stdoutMax = 64 * 1024, stderrMax = 4 * 1024, deadline = 20.seconds)
    }
}
