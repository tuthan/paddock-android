package io.github.tuthan.paddock.relay

import io.github.tuthan.paddock.relay.FakeSession.Companion.result
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pinned script is looked for in the herdr plugin's directory first; the consented push install stays the fallback (AC-11.8). */
class RelayInstallerPluginTest {
    private val script = "print('relay')\n".toByteArray()
    private val sha = sha256Hex(script)
    private val other = "0".repeat(64)
    private val home = "/home/u"
    private val plugin = PluginLocation("/home/u/.local/share/herdr/plugins/paddock", "0.1.0")
    private val inPlugin = "${plugin.dir}/host/paddock-relay.py"
    private val pushed = "$home/.local/share/paddock/paddock-relay.py"

    /** A host whose files are [files] (path to hash); every other path is missing. */
    private fun host(files: MutableMap<String, String>) = FakeSession(onExec = { argv, stdin ->
        when {
            argv.firstOrNull() == "sha256sum" -> files[argv.last()]?.let { result(0, "$it  ${argv.last()}\n") } ?: result(1, "", "No such file")
            argv.firstOrNull() == "sh" && stdin != null -> { files[pushed] = sha256Hex(stdin); result(0) }
            else -> result(0)
        }
    })

    private fun installer(session: FakeSession, plugin: PluginLocation? = this.plugin) = RelayInstaller(session, script, sha, plugin = plugin)

    @Test fun thePluginsPinnedCopyIsUsedWhereItIsAndNothingIsWritten() = runBlocking<Unit> {
        val session = host(mutableMapOf(inPlugin to sha))
        val i = installer(session)
        assertEquals(RelayState.Current, i.state(home))
        assertEquals(inPlugin, i.destination(home))
        assertEquals(inPlugin, i.verifiedPath(home))
        assertNull(i.pluginMismatch)
        assertTrue(session.execs.none { (_, stdin) -> stdin != null })
    }

    @Test fun aPluginCopyThatIsNotThePinIsNeverRunAndTheHostIsAskedBeforeAnythingIsWritten() = runBlocking<Unit> {
        val session = host(mutableMapOf(inPlugin to other))
        val i = installer(session)
        assertEquals(RelayState.Missing, i.state(home), "nothing at the push destination")
        assertEquals(PluginMismatch(other, "0.1.0"), i.pluginMismatch)
        assertEquals(pushed, i.destination(home), "the plugin's copy is not where it would run from")
        assertFailsWith<RelayRefused> { i.verifiedPath(home) }
        assertTrue(session.execs.none { (_, stdin) -> stdin != null }, "state and verifiedPath write nothing")
    }

    @Test fun aMismatchedPluginDoesNotStopThePushInstallFromWorking() = runBlocking<Unit> {
        val files = mutableMapOf(inPlugin to other)
        val session = host(files)
        val i = installer(session)
        assertEquals(RelayState.Missing, i.state(home))
        i.install(home)
        assertEquals(pushed, i.verifiedPath(home))
        assertEquals(sha, files[pushed])
        assertEquals(other, files[inPlugin], "the plugin's files are never touched")
    }

    @Test fun anAlreadyPushedPinnedCopyStillRunsWhenThePluginCarriesAnotherOne() = runBlocking<Unit> {
        val i = installer(host(mutableMapOf(inPlugin to other, pushed to sha)))
        assertEquals(RelayState.Current, i.state(home))
        assertEquals(pushed, i.destination(home))
        assertEquals(PluginMismatch(other, "0.1.0"), i.pluginMismatch, "still reported, for the screen that asks")
    }

    @Test fun theLocationFollowsTheLatestCheckSoALaterPluginUpdateIsSeenAndALaterBreakIsRefused() = runBlocking<Unit> {
        val files = mutableMapOf(inPlugin to sha)
        val i = installer(host(files))
        assertEquals(inPlugin, i.verifiedPath(home))
        files[inPlugin] = other // the user reinstalled an older plugin while the phone was connected
        assertFailsWith<RelayRefused> { i.verifiedPath(home) }
        assertEquals(pushed, i.destination(home))
        files[inPlugin] = sha
        assertEquals(inPlugin, i.verifiedPath(home))
    }

    @Test fun withNoPluginItIsTheOldBehaviourExactly() = runBlocking<Unit> {
        val files = mutableMapOf<String, String>()
        val session = host(files)
        val i = installer(session, plugin = null)
        assertEquals(RelayState.Missing, i.state(home))
        assertEquals(pushed, i.destination(home))
        assertTrue(session.execs.none { (argv, _) -> argv.last().contains("herdr") }, "no plugin path is ever probed")
        files[pushed] = other
        assertEquals(RelayState.Mismatch(other), i.state(home))
        assertNull(i.pluginMismatch)
    }

    @Test fun theNoteNamesTheVersionFoundAndTheWayToFixItWithoutGuessingARepository() {
        val note = PluginNotes.mismatch(PluginMismatch(other, "0.0.9"))
        assertTrue("0.0.9" in note && "--ref" in note && "not used" in note, note)
        assertTrue("/" !in note, "no owner/repo in $note")
        assertTrue("version" !in PluginNotes.mismatch(PluginMismatch(other, null)))
    }
}
