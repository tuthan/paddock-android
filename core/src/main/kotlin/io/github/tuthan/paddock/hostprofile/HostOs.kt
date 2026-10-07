package io.github.tuthan.paddock.hostprofile

import io.github.tuthan.paddock.ports.ExecLimits
import io.github.tuthan.paddock.ports.SshSession
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.Serializable

/**
 * The kind of machine a profile points at, for the icon beside its name. Only what the icon says: nothing else in Paddock depends on it, so a
 * wrong or missing answer costs a glyph and never a behaviour.
 */
@Serializable
enum class HostOs(val label: String) {
    Linux("Linux"), Mac("macOS"), Windows("Windows");

    companion object {
        /**
         * What `uname -s` says: `Linux`, `Darwin`, and on a Windows host whose SSH shell is a Unix layer `MINGW64_NT-10.0`, `MSYS_NT-10.0` or
         * `CYGWIN_NT-10.0`. Anything else (a BSD, an empty answer, a banner) is not guessed: null, and the user can pick the icon.
         */
        fun fromUname(output: String): HostOs? {
            val word = output.trim().lineSequence().lastOrNull()?.trim().orEmpty()
            return when {
                word == "Linux" -> Linux
                word == "Darwin" -> Mac
                word.startsWith("MINGW") || word.startsWith("MSYS") || word.startsWith("CYGWIN") -> Windows
                else -> null
            }
        }
    }
}

/**
 * Asks the machine which OS it is, over the SSH connection already open: one constant command, nothing interpolated, nothing written. Best
 * effort: a host that cannot answer (no `uname`, a Windows shell that is not a Unix one, a timeout) is null, never a failed connection.
 */
object HostOsProbe {
    const val COMMAND = "uname -s 2>/dev/null"

    suspend fun read(session: SshSession): HostOs? {
        val r = try {
            session.exec(listOf("sh", "-c", COMMAND), limits = ExecLimits(stdoutMax = 256, stderrMax = 256, deadline = 8.seconds))
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        return if (r.exit == 0) HostOs.fromUname(String(r.stdout, Charsets.UTF_8)) else null
    }
}
