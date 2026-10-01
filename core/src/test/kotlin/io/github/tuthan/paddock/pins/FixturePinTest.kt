package io.github.tuthan.paddock.pins

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class FixturePinTest {
    private val root = File(requireNotNull(System.getProperty("paddock.repoRoot")) { "paddock.repoRoot not set" })

    @Test
    fun everyPinnedFileMatchesItsHash() {
        assertEquals(emptyList(), PinVerifier.verify(root))
    }

    @Test
    fun manifestNamesTheTestedHerdrAndTheSchemaProtocol() {
        val manifest = PinVerifier.manifest(root)
        assertEquals("0.9.1", manifest.getValue("herdr").jsonPrimitive.content)
        val schema = kotlinx.serialization.json.Json.parseToJsonElement(File(root, "protocol/herdr-schema-22.json").readText())
        assertEquals(manifest.getValue("protocol").jsonPrimitive.int, schema.jsonObject.getValue("protocol").jsonPrimitive.int)
    }

    @Test
    fun theCorpusCoversTheStatesPhase08Needs() {
        val pinned = PinVerifier.hashes(root).keys
        listOf("agent-get-blocked.json", "events-status.jsonl", "events-lifecycle.jsonl", "message-capability.txt").forEach {
            assertTrue("fixtures/herdr-0.9.1/$it" in pinned, "expected $it to be pinned")
        }
    }

    @Test
    fun changingOneByteOfAFixtureFailsVerification() {
        val copy = copyOfRepoPins()
        val victim = File(copy, "fixtures/herdr-0.9.1/status.txt")
        victim.writeBytes(victim.readBytes().also { it[0] = (it[0].toInt() xor 1).toByte() })
        assertEquals(listOf("hash mismatch: fixtures/herdr-0.9.1/status.txt"), PinVerifier.verify(copy))
    }

    @Test
    fun anExtraFixtureAndAMissingFixtureBothFail() {
        val copy = copyOfRepoPins()
        File(copy, "fixtures/herdr-0.9.1/stray.json").writeText("{}")
        File(copy, "fixtures/herdr-0.9.1/pane-get.json").delete()
        assertEquals(
            setOf("not in SOURCE.json: fixtures/herdr-0.9.1/stray.json", "missing: fixtures/herdr-0.9.1/pane-get.json"),
            PinVerifier.verify(copy).toSet(),
        )
    }

    private fun copyOfRepoPins(): File {
        val dir = kotlin.io.path.createTempDirectory("paddock-pins").toFile().also { it.deleteOnExit() }
        File(root, "protocol").copyRecursively(File(dir, "protocol"))
        File(root, "fixtures").copyRecursively(File(dir, "fixtures"))
        return dir
    }
}
