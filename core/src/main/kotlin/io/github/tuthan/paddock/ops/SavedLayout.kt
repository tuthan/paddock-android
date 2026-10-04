package io.github.tuthan.paddock.ops

import io.github.tuthan.paddock.herdr.PaddockJson
import io.github.tuthan.paddock.herdr.SessionEntry
import io.github.tuthan.paddock.ports.ExecLimits
import io.github.tuthan.paddock.ports.SshSession
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * What a stopped session left behind, as far as Paddock can tell: the folders of the workspaces herdr saved, and when the file
 * was written. Names are basenames only, so no path of the host's leaves it, and the whole thing is for showing: it is never an
 * input to a mutation.
 */
data class SavedLayout(val workspaces: List<String>, val savedAtMillis: Long) {
    val summary: String get() = if (workspaces.isEmpty()) "no workspaces" else workspaces.joinToString(", ")
}

sealed interface SavedLayoutResult {
    data class Layout(val layout: SavedLayout) : SavedLayoutResult
    /** Anything the reader cannot vouch for: missing file, another version, garbage, too large. The screen says "saved contents unavailable". */
    data class Unavailable(val reason: String) : SavedLayoutResult
}

/**
 * `<session_dir>/session.json`, version 3 only (herdr 0.9.1). Everything else, including a version 4 that may be perfectly fine,
 * is unavailable rather than guessed at. A workspace shows the basename of its `identity_cwd`; its custom name is not shown,
 * because a name is a person's text and the folder is what identifies the work.
 */
object SavedLayoutParser {
    const val SUPPORTED_VERSION = 3
    const val MAX_BYTES = 256 * 1024
    const val MAX_WORKSPACES = 50
    const val MAX_NAME = 64
    const val UNAVAILABLE = "saved contents unavailable"

    fun parse(text: String, savedAtMillis: Long): SavedLayoutResult {
        if (text.length > MAX_BYTES) return SavedLayoutResult.Unavailable("the file is larger than $MAX_BYTES bytes")
        val root = try { PaddockJson.parseToJsonElement(text) as? JsonObject } catch (_: RuntimeException) { null }
            ?: return SavedLayoutResult.Unavailable("not a JSON object")
        val version = (root["version"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        if (version != SUPPORTED_VERSION) return SavedLayoutResult.Unavailable("version ${version ?: "missing"}, only $SUPPORTED_VERSION is read")
        val list = root["workspaces"] as? JsonArray ?: return SavedLayoutResult.Unavailable("no workspaces list")
        val names = list.take(MAX_WORKSPACES).map { w ->
            val cwd = ((w as? JsonObject)?.get("identity_cwd") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            basename(cwd) ?: "(no folder)"
        }
        return SavedLayoutResult.Layout(SavedLayout(names, savedAtMillis))
    }

    /** The last path segment, with control characters removed and the length bounded; null when there is none. `/` and `/home/x/` read as `/` and `x`. */
    fun basename(path: String?): String? {
        val trimmed = path?.trimEnd('/') ?: return null
        if (path.isEmpty()) return null
        if (trimmed.isEmpty()) return "/"
        val last = trimmed.substringAfterLast('/')
        val clean = last.filter { !it.isISOControl() && it != ' ' && it != ' ' }.take(MAX_NAME)
        return clean.ifEmpty { null }
    }
}

/**
 * Reads a stopped session's `session.json` over SSH with one bounded command: its modification time in seconds on the first line,
 * then at most [SavedLayoutParser.MAX_BYTES] + 1 bytes of the file. The directory comes from the host's own catalogue and is
 * passed as an argument, never spliced into the script; the file name is fixed.
 */
class SavedLayoutReader(private val session: SshSession) {
    suspend fun read(entry: SessionEntry): SavedLayoutResult {
        if (entry.running) return SavedLayoutResult.Unavailable("the session is running")
        val dir = entry.sessionDir
        if (!dir.startsWith("/") || dir.any { it.isISOControl() }) return SavedLayoutResult.Unavailable("the session directory is not an absolute path")
        val r = try {
            session.exec(listOf("/bin/sh", "-c", SCRIPT, "paddock-layout", dir),
                limits = ExecLimits(stdoutMax = SavedLayoutParser.MAX_BYTES + 64, stderrMax = 1024, deadline = 10.seconds))
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            return SavedLayoutResult.Unavailable("the host could not be asked (${e.message ?: e.javaClass.simpleName})")
        }
        if (r.exit != 0) return SavedLayoutResult.Unavailable(if (r.exit == 3) "no saved file" else "the read failed (exit ${r.exit})")
        if (r.stdoutTruncated) return SavedLayoutResult.Unavailable("the file is larger than ${SavedLayoutParser.MAX_BYTES} bytes")
        val out = r.stdout.toString(Charsets.UTF_8)
        val nl = out.indexOf('\n')
        val mtime = if (nl > 0) out.substring(0, nl).trim().toLongOrNull() else null
        if (mtime == null || mtime < 0) return SavedLayoutResult.Unavailable("no modification time")
        return SavedLayoutParser.parse(out.substring(nl + 1), mtime * 1000)
    }

    companion object {
        /** `stat -c` on Linux, `stat -f` elsewhere; exit 3 when the file is not there. `head -c` bounds what `cat` could have sent. */
        const val SCRIPT = "f=\"\$1/session.json\"; [ -f \"\$f\" ] || exit 3; (stat -c %Y \"\$f\" 2>/dev/null || stat -f %m \"\$f\") || exit 4; head -c ${SavedLayoutParser.MAX_BYTES + 1} \"\$f\""
    }
}
