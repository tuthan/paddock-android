package io.github.tuthan.paddock.relay

import io.github.tuthan.paddock.ports.ExecLimits
import io.github.tuthan.paddock.ports.SshSession
import java.security.MessageDigest

sealed interface RelayState {
    data object Missing : RelayState
    data object Current : RelayState
    /** Something is at the destination but it is not the pinned script. It is never run. */
    data class Mismatch(val actualSha256: String) : RelayState
}

class RelayRefused(val state: RelayState) : Exception("the relay on the host is not the pinned script ($state)")

fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/**
 * Installs and verifies `paddock-relay.py` on the host. Installation is explicit: the caller shows the user
 * [expectedSha256] and [destination] and calls [install] only after they agree. [verifiedPath] re-hashes the file
 * on the host with `sha256sum` before every first use and refuses anything but the pinned script. This guards
 * against a stale or edited copy, not against someone who already controls the host account.
 */
class RelayInstaller(private val session: SshSession, private val script: ByteArray, val expectedSha256: String) {
    init { require(sha256Hex(script) == expectedSha256) { "bundled relay does not match its pinned hash" } }

    /** The account's home directory, because `~` would be quoted and never expanded in an argv. */
    suspend fun homeDirectory(): String {
        val r = session.exec(listOf("sh", "-c", "printf %s \"\$HOME\""))
        check(r.exit == 0) { "cannot read HOME on the host" }
        val home = r.stdout.toString(Charsets.UTF_8)
        check(home.startsWith("/") && '\n' !in home) { "unexpected HOME on the host" }
        return home
    }

    fun destination(home: String) = "$home/.local/share/paddock/paddock-relay.py"

    suspend fun state(home: String): RelayState {
        val r = session.exec(listOf("sha256sum", "--", destination(home)), limits = SMALL)
        if (r.exit != 0) return RelayState.Missing
        val actual = r.stdout.toString(Charsets.UTF_8).trim().substringBefore(' ')
        return if (actual == expectedSha256) RelayState.Current else RelayState.Mismatch(actual)
    }

    /** Writes the pinned script (on stdin, never in argv) atomically with mode 600 and re-verifies it. */
    suspend fun install(home: String) {
        val dir = "\$HOME/.local/share/paddock"
        val r = session.exec(
            listOf("sh", "-c", "umask 077 && mkdir -p \"$dir\" && cat > \"$dir/paddock-relay.py.tmp\" && mv -f \"$dir/paddock-relay.py.tmp\" \"$dir/paddock-relay.py\""),
            stdin = script, limits = SMALL,
        )
        check(r.exit == 0) { "relay install failed (exit ${r.exit})" }
        val after = state(home)
        if (after != RelayState.Current) throw RelayRefused(after)
    }

    /** The path to run, only when the host copy is the pinned script. */
    suspend fun verifiedPath(home: String): String {
        val s = state(home)
        if (s != RelayState.Current) throw RelayRefused(s)
        return destination(home)
    }

    private companion object { val SMALL = ExecLimits(stdoutMax = 4096, stderrMax = 4096) }
}
