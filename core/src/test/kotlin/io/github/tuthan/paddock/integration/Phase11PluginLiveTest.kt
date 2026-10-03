package io.github.tuthan.paddock.integration

import io.github.tuthan.paddock.relay.PluginLocator
import io.github.tuthan.paddock.relay.PluginMismatch
import io.github.tuthan.paddock.relay.RelayInstaller
import io.github.tuthan.paddock.relay.RelayRefused
import io.github.tuthan.paddock.relay.RelayState
import io.github.tuthan.paddock.relay.sha256Hex
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The app's plugin discovery against the real herdr and the real plugin checkout (AC-11.7 and AC-11.8, the host half of what a phone does).
 * Runs only when `PADDOCK_TEST_PLUGIN_DIR` names a checkout of the Paddock herdr plugin. Every herdr command goes through a wrapper that
 * runs it under `env -i` with an isolated HOME, so the user's own herdr configuration and plugins are never read or written, and no
 * server is needed: `plugin link` and `plugin list` work without one (spike S3).
 */
class Phase11PluginLiveTest {
    private val pluginDir = System.getenv("PADDOCK_TEST_PLUGIN_DIR")?.let(::File)
    private val realHerdr = System.getenv("PADDOCK_HERDR") ?: "/usr/bin/herdr"
    private val script = File(System.getProperty("paddock.repoRoot"), "host/paddock-relay.py")

    /** An isolated HOME holding a `bin/herdr` that runs the real herdr with nothing else from this environment. */
    private class Isolated(realHerdr: String) {
        val home: File = Files.createTempDirectory("paddock-plugin-live").toFile()
        val herdr: String
        init {
            File(home, "bin").mkdirs()
            val wrapper = File(home, "bin/herdr")
            wrapper.writeText("#!/bin/sh\nexec env -i HOME='${home.absolutePath}' PATH=/usr/bin:/bin '$realHerdr' \"\$@\" < /dev/null\n")
            wrapper.setExecutable(true)
            herdr = wrapper.absolutePath
        }
        fun link(dir: File) {
            val p = ProcessBuilder(herdr, "plugin", "link", dir.absolutePath).redirectErrorStream(true).start()
            val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
            assertEquals(0, p.waitFor(), "plugin link failed: $out")
        }
        /** A session whose commands, including the installer's `sh -c ... $HOME`, see only the isolated HOME. */
        fun session() = LocalProcessSession(env = mapOf("HOME" to home.absolutePath))
        fun cleanup() { home.deleteRecursively() }
    }

    private fun live(block: suspend (Isolated, File) -> Unit) {
        assumeTrue("PADDOCK_TEST_PLUGIN_DIR is not set", pluginDir != null && File(pluginDir, "herdr-plugin.toml").isFile)
        assumeTrue("no herdr at $realHerdr", File(realHerdr).canExecute())
        val iso = Isolated(realHerdr)
        try { runBlocking { block(iso, pluginDir!!) } } finally { iso.cleanup() }
    }

    @Test fun theAppFindsThePluginAndRunsItsRelayOnlyBecauseTheHashMatchesThePin() = live { iso, dir ->
        iso.link(dir)
        val session = iso.session()
        val found = assertNotNull(PluginLocator.find(session, iso.herdr))
        assertEquals(dir.canonicalPath, File(found.dir).canonicalPath)
        assertNotNull(found.version)
        val installer = RelayInstaller(session, script.readBytes(), sha256Hex(script.readBytes()), plugin = found)
        assertEquals(RelayState.Current, installer.state(iso.home.absolutePath))
        assertEquals("${found.dir}/host/paddock-relay.py", installer.verifiedPath(iso.home.absolutePath))
        assertNull(installer.pluginMismatch)
        assertTrue(session.stdinLog.isEmpty(), "nothing was sent to the host")
    }

    @Test fun aPluginThatCarriesAnotherScriptIsFoundButNeverRunAndThePushInstallStillWorks() = live { iso, dir ->
        val copy = Files.createTempDirectory("paddock-plugin-tampered").toFile()
        try {
            dir.copyRecursively(copy, overwrite = true) { _, _ -> kotlin.io.OnErrorAction.SKIP }
            File(copy, ".git").deleteRecursively()
            File(copy, "host/paddock-relay.py").appendText("# edited on the host\n")
            iso.link(copy)
            val session = iso.session()
            val found = assertNotNull(PluginLocator.find(session, iso.herdr))
            val bytes = script.readBytes()
            val installer = RelayInstaller(session, bytes, sha256Hex(bytes), plugin = found)
            val home = iso.home.absolutePath
            assertEquals(RelayState.Missing, installer.state(home), "nothing at the push destination yet")
            val mismatch = assertNotNull(installer.pluginMismatch)
            assertEquals(found.version, mismatch.version)
            assertTrue(mismatch != PluginMismatch(sha256Hex(bytes), found.version), "the plugin's copy is not the pin")
            assertFailsWith<RelayRefused> { installer.verifiedPath(home) }
            installer.install(home)
            assertEquals("$home/.local/share/paddock/paddock-relay.py", installer.verifiedPath(home))
            assertEquals(sha256Hex(bytes), sha256Hex(File(home, ".local/share/paddock/paddock-relay.py").readBytes()))
        } finally { copy.deleteRecursively() }
    }

    @Test fun aHostWithoutThePluginIsNoPluginNotAnError() = live { iso, _ ->
        val session = iso.session()
        assertNull(PluginLocator.find(session, iso.herdr), "an isolated herdr with nothing linked lists no plugin")
    }
}
