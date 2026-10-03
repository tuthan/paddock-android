package io.github.tuthan.paddock.relay

import io.github.tuthan.paddock.relay.FakeSession.Companion.result
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginLocatorTest {
    private fun listing(root: String? = "/home/u/.local/share/herdr/plugins/paddock", version: String? = "0.1.0", enabled: Boolean = true, id: String = "paddock") =
        buildString {
            append("""{"id":"cli:plugin","result":{"plugins":[{"plugin_id":"$id",""")
            if (root != null) append(""""plugin_root":"$root",""")
            if (version != null) append(""""version":"$version",""")
            append(""""min_herdr_version":"0.9.1","enabled":$enabled,"source":{"kind":"github"},"actions":[],"panes":[]}],"type":"plugin_list"}}""")
        }

    @Test fun theDirectoryAndVersionComeFromTheOneLineHerdrPrints() {
        assertEquals(PluginLocation("/home/u/.local/share/herdr/plugins/paddock", "0.1.0"), PluginLocator.parse(listing() + "\n"))
        assertEquals(PluginLocation("/opt/p", null), PluginLocator.parse(listing(root = "/opt/p", version = null)), "the version is only for showing")
    }

    @Test fun herdrsOwnListingOfALinkedPluginIsUnderstood() {
        // Captured from herdr 0.9.1 with the plugin linked in an isolated HOME; the path is replaced, the shape is herdr's.
        val real = File(System.getProperty("paddock.repoRoot"), "fixtures/herdr-0.9.1/plugin-list-paddock.json").readText()
        assertEquals(PluginLocation("/srv/herdr/plugins/paddock", "0.1.0"), PluginLocator.parse(real))
    }

    @Test fun nothingInstalledOrAnythingOddIsNoPlugin() {
        assertNull(PluginLocator.parse("""{"id":"cli:plugin","result":{"plugins":[],"type":"plugin_list"}}"""), "absence is an empty list")
        assertNull(PluginLocator.parse(""))
        assertNull(PluginLocator.parse("No plugins installed."))
        assertNull(PluginLocator.parse("{"))
        assertNull(PluginLocator.parse("""{"result":{"plugins":"x"}}"""))
        assertNull(PluginLocator.parse("""{"error":{"code":"nope"}}"""))
        assertNull(PluginLocator.parse(listing(root = null)), "an entry without a directory")
        assertNull(PluginLocator.parse(listing(enabled = false)), "a plugin the user switched off is left alone")
        assertNull(PluginLocator.parse(listing(id = "neyham.paddock")), "only the plugin id paddock counts")
    }

    @Test fun aDirectoryThatIsNotAPlainAbsolutePathIsNeverUsed() {
        val hostile = listOf(
            "relative/dir", "", "/", "/a/../etc", "/a/./b", "/a//b", "/a b", "/a'b", "/a\\\\b", "/a\$HOME", "/a;rm", "/a`id`", "/a\\nb",
            "/${"x".repeat(101)}", "/" + "a/".repeat(250) + "b", "~/plugins/paddock", "/a/*", "/é",
        )
        for (dir in hostile) {
            assertFalse(PluginLocator.isSafe(dir), "accepted $dir")
            assertNull(PluginLocator.parse(listing(root = dir)), "used $dir")
        }
        for (dir in listOf("/home/u/.local/share/herdr/plugins/paddock", "/opt/herdr-plugins/paddock@0.1.0", "/srv/p+q/x_y")) assertTrue(PluginLocator.isSafe(dir), dir)
        assertFailsWith<IllegalArgumentException> { PluginLocation("/a/../b", null) }
    }

    @Test fun aVersionThatIsNotPlainTextIsDroppedNotShown() {
        assertNull(PluginLocator.parse(listing(version = "1.0 <b>x</b>"))?.version)
        assertEquals("1.2.3-rc.1", PluginLocator.parse(listing(version = "1.2.3-rc.1"))?.version)
    }

    @Test fun findAsksHerdrWithNoShellAndFailsToNoPlugin() = runBlocking<Unit> {
        val ok = FakeSession(onExec = { _, _ -> result(0, listing()) })
        assertEquals("/home/u/.local/share/herdr/plugins/paddock", PluginLocator.find(ok, "/usr/bin/herdr")?.dir)
        assertEquals(listOf("/usr/bin/herdr", "plugin", "list", "--plugin", "paddock", "--json"), ok.execs.single().first)
        assertNull(PluginLocator.find(FakeSession(onExec = { _, _ -> result(1, "", "unknown command") }), "/usr/bin/herdr"), "an older herdr without plugins")
        assertNull(PluginLocator.find(FakeSession(onExec = { _, _ -> result(0, listing().replace("}", "")) }), "/usr/bin/herdr"))
        assertNull(PluginLocator.find(FakeSession(onExec = { _, _ -> error("link dropped") }), "/usr/bin/herdr"))
        assertNull(PluginLocator.find(FakeSession(onExec = { _, _ -> result(0, listing()) }), "/opt/it's/herdr"), "an argv no login shell reads the same way is not sent")
    }
}
