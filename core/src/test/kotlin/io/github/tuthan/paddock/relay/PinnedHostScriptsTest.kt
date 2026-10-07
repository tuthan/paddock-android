package io.github.tuthan.paddock.relay

import java.io.File
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The app's bundled assets are the files in host/ plus a pin beside each (app/build.gradle.kts, GenerateRelayAssets); this builds the same set in memory. */
class PinnedHostScriptsTest {
    private val host = File(System.getProperty("paddock.repoRoot"), "host")
    private val source = host.resolve("SOURCE.json").readText()

    private fun bundled(): MutableMap<String, ByteArray> {
        val assets = mutableMapOf<String, ByteArray>()
        for (file in host.listFiles { f -> f.isFile && f.name != "SOURCE.json" }!!) {
            val pin = Regex("\"host/${Regex.escape(file.name)}\"\\s*:\\s*\"sha256:([0-9a-f]{64})\"").find(source)!!.groupValues[1]
            assets[file.name] = file.readBytes()
            val pinName = if (file.name == "paddock-relay.py" || file.name == "paddock-control.py") file.name.removeSuffix(".py") + ".sha256" else "${file.name}.sha256"
            assets[pinName] = "$pin\n".toByteArray()
        }
        return assets
    }

    private fun scripts(assets: Map<String, ByteArray>) = PinnedHostScripts { assets.getValue(it) }

    @Test fun everyBundledFileIsReadAndItsPinIsTheHashOfWhatWasRead() {
        val s = scripts(bundled())
        assertContentEquals(host.resolve("paddock-relay.py").readBytes(), s.relay)
        assertEquals(sha256Hex(s.relay), s.relaySha256)
        assertEquals(sha256Hex(s.control), s.controlSha256)
        assertEquals(sha256Hex(s.alert), s.alertSha256)
        assertEquals(sha256Hex(s.decide), s.decideSha256)
        assertEquals(sha256Hex(s.hook), s.hookSha256)
        assertEquals(sha256Hex(s.opencode), s.opencodeSha256)
        assertEquals(host.resolve("paddock-alert-relay.service").readText(), s.alertUnit)
        assertEquals(host.resolve("alert-relay.example.toml").readText(), s.alertConfigExample)
        assertTrue(s.alertUnit.isNotBlank() && s.alertConfigExample.isNotBlank())
    }

    @Test fun aScriptThatDoesNotMatchItsPinStopsTheAppWithTheNameOfTheFile() {
        val expected = mapOf(
            "paddock-relay.py" to "the bundled relay does not match its pin",
            "paddock-control.py" to "the bundled control helper does not match its pin",
            "paddock-alert-relay.py" to "the bundled paddock-alert-relay.py does not match its pin",
            "paddock-alert-relay.service" to "the bundled paddock-alert-relay.service does not match its pin",
            "alert-relay.example.toml" to "the bundled alert-relay.example.toml does not match its pin",
            "paddock-decide.py" to "the bundled paddock-decide.py does not match its pin",
            "paddock-claude-permission-hook.py" to "the bundled paddock-claude-permission-hook.py does not match its pin",
            "paddock-opencode-permission.js" to "the bundled paddock-opencode-permission.js does not match its pin",
        )
        for ((name, message) in expected) {
            val assets = bundled()
            assets[name] = assets.getValue(name) + "\n# tampered".toByteArray()
            assertEquals(message, assertFailsWith<IllegalStateException>(name) { scripts(assets) }.message)
        }
    }

    @Test fun aPinWithTrailingWhitespaceIsStillThePin() {
        val assets = bundled()
        assets["paddock-relay.sha256"] = "  ${assets.getValue("paddock-relay.sha256").toString(Charsets.UTF_8).trim()}\r\n\n".toByteArray()
        assertEquals(sha256Hex(assets.getValue("paddock-relay.py")), scripts(assets).relaySha256)
    }

    @Test fun aWrongPinIsRefusedEvenWhenTheScriptIsUntouched() {
        val assets = bundled()
        assets["paddock-decide.py.sha256"] = ("0".repeat(64)).toByteArray()
        assertFailsWith<IllegalStateException> { scripts(assets) }
    }
}
