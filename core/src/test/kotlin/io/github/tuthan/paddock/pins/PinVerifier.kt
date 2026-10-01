package io.github.tuthan.paddock.pins

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Checks `protocol/SOURCE.json` against the files on disk, the same rule as tools/check-pins.sh: every file under
 * `protocol/` (except the manifest itself) and under `fixtures/` must be pinned, and every pin must match its file.
 */
object PinVerifier {
    const val MANIFEST = "protocol/SOURCE.json"
    private val tracked = listOf("protocol", "fixtures")

    fun manifest(root: File): JsonObject =
        Json.parseToJsonElement(File(root, MANIFEST).readText()).jsonObject

    fun hashes(root: File): Map<String, String> =
        manifest(root).getValue("files").jsonObject.mapValues { it.value.jsonPrimitive.content }

    fun sha256(file: File): String =
        "sha256:" + MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    /** Returns one message per problem; an empty list means every pin holds. */
    fun verify(root: File): List<String> {
        val pinned = hashes(root)
        val problems = mutableListOf<String>()
        for ((path, want) in pinned) {
            val file = File(root, path)
            when {
                !file.isFile -> problems += "missing: $path"
                sha256(file) != want -> problems += "hash mismatch: $path"
            }
        }
        val onDisk = tracked.flatMap { File(root, it).walkTopDown().filter { f -> !f.isDirectory }.toList() }
            .map { it.relativeTo(root).invariantSeparatorsPath } - MANIFEST
        (onDisk - pinned.keys).forEach { problems += "not in SOURCE.json: $it" }
        return problems
    }
}
