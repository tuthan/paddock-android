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
 * desktop's idea, not the PTY's). `pane process-info` names a process of the pane (`shell_pid`, which herdr takes from the
 * terminal's foreground process group, so it is a running job's leader while one runs, and that job's stdin may be a pipe);
 * the process's controlling terminal is the pane's PTY whatever its descriptors are, so the probe reads it from
 * `/proc/<pid>/stat` and asks `stty -F /dev/pts/<n> size`. All three calls are read-only. Attaching a controller sets the
 * terminal to the controller's size, so knowing this size is what lets Paddock request control without resizing the desktop.
 * The job can end between the calls, so a failed attempt is made once more. Any remaining failure (not Linux, no pts, a pane
 * without a process) is a null, and the caller falls back to saying so.
 */
object PtyProbe {
    private val SMALL = ExecLimits(stdoutMax = 64 shl 10, stderrMax = 4096, deadline = 5.seconds)
    private val TINY = ExecLimits(stdoutMax = 1024, stderrMax = 1024, deadline = 5.seconds)

    suspend fun probe(session: SshSession, cli: HerdrCli, paneId: String): PtySize? {
        val argv = try { cli.paneProcessInfo(paneId) } catch (_: IllegalArgumentException) { return null }
        repeat(ATTEMPTS) {
            val size = attempt(session, argv)
            if (size != null) return size
        }
        return null
    }

    private suspend fun attempt(session: SshSession, processInfo: List<String>): PtySize? {
        return try {
            val info = CliResult.classify(session.exec(processInfo, limits = SMALL)) as? CliOutcome.Ok ?: return null
            val pid = shellPid(info.stdout) ?: return null
            val stat = session.exec(listOf("cat", "/proc/$pid/stat"), limits = TINY)
            if (stat.exit != 0) return null
            val pts = controllingPts(stat.stdout.toString(Charsets.UTF_8)) ?: return null
            val stty = session.exec(listOf("stty", "-F", "/dev/pts/$pts", "size"), limits = TINY)
            if (stty.exit != 0) null else parseStty(stty.stdout.toString(Charsets.UTF_8))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /**
     * The `/dev/pts/<n>` number of the controlling terminal in a `/proc/<pid>/stat` line, or null for a process without one
     * or on another kind of terminal. The line is `pid (comm) state ppid pgrp session tty_nr ...` and `comm` may hold spaces
     * and brackets, so the fields are counted from the last `)`. `tty_nr` packs a major (136 to 143 for pts) and a minor.
     */
    fun controllingPts(stat: String): Int? {
        val close = stat.lastIndexOf(')')
        if (close < 0) return null
        val fields = stat.substring(close + 1).trim().split(Regex("\\s+"))
        val ttyNr = fields.getOrNull(4)?.toLongOrNull() ?: return null
        if (ttyNr <= 0) return null
        val major = (ttyNr shr 8) and 0xfff
        val minor = (ttyNr and 0xff) or ((ttyNr shr 12) and 0xfff00)
        return if (major in 136..143) ((major - 136) * 256 + minor).toInt() else null
    }

    /** `result.process_info.shell_pid` of a `pane process-info` answer, or null when it is not there. */
    fun shellPid(stdout: String): Int? {
        val message = try { Envelope.parse(stdout.trim()) } catch (_: ProtocolError) { return null }
        val info = ((message as? Message.Success)?.result?.get("process_info") as? JsonObject) ?: return null
        return (info["shell_pid"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
    }

    private const val ATTEMPTS = 2

    /** `stty size` prints `rows cols`. */
    fun parseStty(text: String): PtySize? {
        val parts = text.trim().split(Regex("\\s+"))
        if (parts.size != 2) return null
        val rows = parts[0].toIntOrNull() ?: return null
        val cols = parts[1].toIntOrNull() ?: return null
        return if (TerminalLimits.validGeometry(cols, rows)) PtySize(cols, rows) else null
    }
}
