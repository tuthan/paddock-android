package io.github.tuthan.paddock.relay

import io.github.tuthan.paddock.herdr.PaddockJson
import io.github.tuthan.paddock.herdr.decodeResult
import io.github.tuthan.paddock.ports.ExecLimits
import io.github.tuthan.paddock.ports.SshSession
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Asks herdr where the Paddock plugin is installed: `herdr plugin list --plugin tuthan.paddock --json`, which needs no running server
 * and no tty (spike S3). It answers only with what herdr printed for the plugin id `paddock`; a directory that is not a plain
 * absolute path, a disabled plugin, an empty list or any failure is "no plugin", and the push install stays the way forward.
 * The path is a hint about where to look, never trust: the script found there is hashed against the app's pin before it runs.
 */
object PluginLocator {
    const val PLUGIN_ID = "tuthan.paddock"

    private val SEGMENT = Regex("[A-Za-z0-9._@+-]{1,100}")
    private val VERSION = Regex("[A-Za-z0-9._+-]{1,32}")
    private val LIMITS = ExecLimits(stdoutMax = 64 shl 10, stderrMax = 4096)

    /** An absolute path of plain segments, at most 400 characters: no spaces, quotes, `.` or `..`, so nothing in it needs quoting or can climb out. */
    fun isSafe(dir: String): Boolean {
        if (dir.length > 400 || !dir.startsWith("/")) return false
        val segments = dir.removePrefix("/").split('/')
        return segments.isNotEmpty() && segments.all { it != "." && it != ".." && SEGMENT.matches(it) }
    }

    suspend fun find(session: SshSession, herdr: String): PluginLocation? {
        val r = try {
            session.exec(listOf(herdr, "plugin", "list", "--plugin", PLUGIN_ID, "--json"), limits = LIMITS)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        if (r.exit != 0 || r.stdoutTruncated) return null
        return parse(r.stdout.toString(Charsets.UTF_8))
    }

    /** The plugin's directory and version out of the one JSON line herdr prints; null for anything else. */
    fun parse(text: String): PluginLocation? {
        val answer = PaddockJson.decodeResult<Answer>(text.trim()).getOrNull() ?: return null
        val entry = answer.result?.plugins?.firstOrNull { it.pluginId == PLUGIN_ID } ?: return null
        if (!entry.enabled) return null
        val root = entry.pluginRoot?.takeIf { isSafe(it) } ?: return null
        return PluginLocation(root, entry.version?.takeIf { VERSION.matches(it) })
    }

    @Serializable private data class Answer(val result: ListResult? = null)
    @Serializable private data class ListResult(val plugins: List<Entry> = emptyList())
    @Serializable private data class Entry(
        @SerialName("plugin_id") val pluginId: String? = null,
        @SerialName("plugin_root") val pluginRoot: String? = null,
        val version: String? = null,
        val enabled: Boolean = true,
    )
}

/** What the install screen says about a plugin whose script is not the pinned one. herdr has no plugin update command: the fix is a reinstall. */
object PluginNotes {
    fun mismatch(found: PluginMismatch): String {
        val version = found.version?.let { " (version $it)" } ?: ""
        return "The Paddock herdr plugin on this machine$version carries a relay that is not the one this app expects, so it was not used. " +
            "Reinstall the plugin from the release that matches this app (herdr plugin install, with --ref set to that release's tag), or install the relay here instead."
    }
}
