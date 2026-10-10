package io.github.tuthan.paddock.hostprofile

import io.github.tuthan.paddock.ports.ExecLimits
import io.github.tuthan.paddock.ports.SshSession
import kotlin.time.Duration.Companion.seconds

/** The machine's hostname as Paddock keeps it: one DNS label, so it can be asked for as `<label>.local` or as a bare name a router knows. */
object MachineHostName {
    /** A DNS label (RFC 1123): letters, digits and inner hyphens, at most 63 characters. */
    val LABEL = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")

    /**
     * What `uname -n` printed, cut at the first dot (`labbox`, `Jdoes-MacBook-Pro` from `Jdoes-MacBook-Pro.local`, `box` from `box.example.org`).
     * Null for anything that is not a label: an empty answer, `localhost` (every machine is called that), a banner, spaces.
     */
    fun label(raw: String): String? {
        val line = raw.trim().lineSequence().lastOrNull()?.trim().orEmpty()
        val first = line.substringBefore('.')
        return first.takeIf { LABEL.matches(it) && !it.equals("localhost", ignoreCase = true) }
    }
}

/**
 * Asks the machine its hostname over the SSH connection already open: one constant command, nothing interpolated, nothing written. Best effort, as
 * [HostOsProbe] is: a machine that cannot answer is null, never a failed connection.
 */
object HostNameProbe {
    const val COMMAND = "uname -n 2>/dev/null"

    suspend fun read(session: SshSession): String? {
        val r = try {
            session.exec(listOf("sh", "-c", COMMAND), limits = ExecLimits(stdoutMax = 512, stderrMax = 256, deadline = 8.seconds))
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        return if (r.exit == 0) MachineHostName.label(String(r.stdout, Charsets.UTF_8)) else null
    }
}
