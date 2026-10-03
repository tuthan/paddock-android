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

/**
 * Where herdr installed the Paddock plugin on the host ([PluginLocator]): its directory, and the version its manifest declares
 * when that could be read. The plugin ships pinned copies of the host scripts under `host/`; the installer looks there first.
 */
data class PluginLocation(val dir: String, val version: String?) {
    init { require(PluginLocator.isSafe(dir)) { "unexpected plugin directory" } }
}

/** The plugin carries a copy of a script that is not the pinned one. It is never run; [version] is what the plugin's manifest says. */
data class PluginMismatch(val actualSha256: String, val version: String?)

fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/**
 * Installs and verifies one pinned host script (`paddock-relay.py` by default, `paddock-control.py` for terminal control, `paddock-alert-relay.py` for alerts). Installation is explicit: the caller shows the user
 * [expectedSha256] and [destination] and calls [install] only after they agree. [verifiedPath] re-hashes the file
 * on the host with `sha256sum` before every first use and refuses anything but the pinned script. This guards
 * against a stale or edited copy, not against someone who already controls the host account.
 */
class RelayInstaller(
    private val session: SshSession,
    private val script: ByteArray,
    val expectedSha256: String,
    /** The file name under `~/.local/share/paddock/`. */
    val fileName: String = "paddock-relay.py",
    /**
     * The Paddock herdr plugin's directory on the host, when it is installed. A copy of the script under `<dir>/host/` whose hash is
     * the pin is used where it is and nothing is written to the host; one that differs is never run ([pluginMismatch]), and the push
     * install stays the way to get the pinned script there. Same hash check either way.
     */
    private val plugin: PluginLocation? = null,
) {
    init {
        require(sha256Hex(script) == expectedSha256) { "bundled $fileName does not match its pinned hash" }
        require(Regex("paddock-[a-z]+(-[a-z]+)*\\.py").matches(fileName)) { "unexpected script name" }
    }

    @Volatile private var viaPlugin = false

    /** Set by [state] when the plugin has a copy of this script that is not the pinned one. Null otherwise, including with no plugin. */
    @Volatile var pluginMismatch: PluginMismatch? = null; private set

    /** The account's home directory, because `~` would be quoted and never expanded in an argv. */
    suspend fun homeDirectory(): String {
        val r = session.exec(listOf("sh", "-c", "printf %s \"\$HOME\""))
        check(r.exit == 0) { "cannot read HOME on the host" }
        val home = r.stdout.toString(Charsets.UTF_8)
        check(home.startsWith("/") && '\n' !in home) { "unexpected HOME on the host" }
        return home
    }

    private fun pushDestination(home: String) = "$home/.local/share/paddock/$fileName"

    /** Where the script runs from: the plugin's pinned copy once [state] found it, otherwise where [install] writes it. */
    fun destination(home: String) = if (viaPlugin && plugin != null) "${plugin.dir}/host/$fileName" else pushDestination(home)

    private suspend fun hashAt(path: String): String? {
        val r = session.exec(listOf("sha256sum", "--", path), limits = SMALL)
        return if (r.exit != 0) null else r.stdout.toString(Charsets.UTF_8).trim().substringBefore(' ')
    }

    suspend fun state(home: String): RelayState {
        viaPlugin = false
        if (plugin != null) {
            val actual = hashAt("${plugin.dir}/host/$fileName")
            pluginMismatch = if (actual != null && actual != expectedSha256) PluginMismatch(actual, plugin.version) else null
            if (actual == expectedSha256) { viaPlugin = true; return RelayState.Current }
        }
        val actual = hashAt(pushDestination(home)) ?: return RelayState.Missing
        return if (actual == expectedSha256) RelayState.Current else RelayState.Mismatch(actual)
    }

    /** Writes the pinned script (on stdin, never in argv) atomically with mode 600 and re-verifies it. */
    suspend fun install(home: String) {
        val dir = "\$HOME/.local/share/paddock"
        val r = session.exec(
            listOf("sh", "-c", "umask 077 && mkdir -p \"$dir\" && cat > \"$dir/$fileName.tmp\" && mv -f \"$dir/$fileName.tmp\" \"$dir/$fileName\""),
            stdin = script, limits = SMALL,
        )
        check(r.exit == 0) { "$fileName install failed (exit ${r.exit})" }
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
