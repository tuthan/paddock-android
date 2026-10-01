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
        val protocol = manifest.getValue("protocol").jsonPrimitive.int
        val schemaPath = "protocol/herdr-schema-$protocol.json"
        assertTrue(schemaPath in PinVerifier.hashes(root), "expected $schemaPath to be pinned")
        val schema = kotlinx.serialization.json.Json.parseToJsonElement(File(root, schemaPath).readText())
        assertEquals(protocol, schema.jsonObject.getValue("protocol").jsonPrimitive.int)
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

    @Test
    fun anUnlistedFileAnywhereUnderProtocolFails() {
        val copy = copyOfRepoPins()
        // A new schema beside the pinned one (a herdr bump half done) and any other stray file both fail.
        File(copy, "protocol/herdr-schema-23.json").writeText("{\"protocol\":23}")
        File(copy, "protocol/notes/extra.txt").apply { parentFile.mkdirs() }.writeText("x")
        assertEquals(
            setOf("not in SOURCE.json: protocol/herdr-schema-23.json", "not in SOURCE.json: protocol/notes/extra.txt"),
            PinVerifier.verify(copy).toSet(),
        )
    }

    @Test
    fun theManifestItselfIsNotReportedAsUnlisted() {
        assertTrue(PinVerifier.verify(copyOfRepoPins()).none { PinVerifier.MANIFEST in it })
    }

    private fun copyOfRepoPins(): File {
        val dir = kotlin.io.path.createTempDirectory("paddock-pins").toFile().also { it.deleteOnExit() }
        File(root, "protocol").copyRecursively(File(dir, "protocol"))
        File(root, "fixtures").copyRecursively(File(dir, "fixtures"))
        return dir
    }
}
