package io.github.tuthan.paddock.terminal

import io.github.tuthan.paddock.cli.CliOutcome
import io.github.tuthan.paddock.cli.CliResult
import io.github.tuthan.paddock.cli.HerdrCli
import io.github.tuthan.paddock.herdr.Envelope
import io.github.tuthan.paddock.herdr.Message
import io.github.tuthan.paddock.herdr.ProtocolError
import io.github.tuthan.paddock.ports.ExecLimits
import io.github.tuthan.paddock.ports.SshSession
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/** A terminal's size in cells, as its PTY has it. */
data class PtySize(val cols: Int, val rows: Int)

/**
 * Finds a pane's real size, which herdr's API does not report (`pane get` gives rows only, and the layout rectangle is the
 * desktop's idea, not the PTY's). The shell's controlling terminal has it: `pane process-info` names the shell's pid and
 * `stty -F /proc/<pid>/fd/0 size` reads the terminal's window size. Both are read-only. Attaching a controller sets the
 * terminal to the controller's size, so knowing this size is what lets Paddock request control without resizing the desktop.
 * Any failure (not Linux, no `stty -F`, a pane without a shell) is a null, and the caller falls back to saying so.
 */
object PtyProbe {
    private val SMALL = ExecLimits(stdoutMax = 64 shl 10, stderrMax = 4096, deadline = 5.seconds)

    suspend fun probe(session: SshSession, cli: HerdrCli, paneId: String): PtySize? {
        return try {
            val info = CliResult.classify(session.exec(cli.paneProcessInfo(paneId), limits = SMALL)) as? CliOutcome.Ok ?: return null
            val pid = shellPid(info.stdout) ?: return null
            val stty = session.exec(listOf("stty", "-F", "/proc/$pid/fd/0", "size"), limits = ExecLimits(stdoutMax = 256, stderrMax = 1024, deadline = 5.seconds))
            if (stty.exit != 0) null else parseStty(stty.stdout.toString(Charsets.UTF_8))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** `result.process_info.shell_pid` of a `pane process-info` answer, or null when it is not there. */
    fun shellPid(stdout: String): Int? {
        val message = try { Envelope.parse(stdout.trim()) } catch (_: ProtocolError) { return null }
        val info = ((message as? Message.Success)?.result?.get("process_info") as? JsonObject) ?: return null
        return (info["shell_pid"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
    }

    /** `stty size` prints `rows cols`. */
    fun parseStty(text: String): PtySize? {
        val parts = text.trim().split(Regex("\\s+"))
        if (parts.size != 2) return null
        val rows = parts[0].toIntOrNull() ?: return null
        val cols = parts[1].toIntOrNull() ?: return null
        return if (TerminalLimits.validGeometry(cols, rows)) PtySize(cols, rows) else null
    }
}
