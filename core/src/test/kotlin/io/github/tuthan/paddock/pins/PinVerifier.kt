package io.github.tuthan.paddock.pins

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Checks `protocol/SOURCE.json` against the files on disk, the same rule as tools/check-pins.sh. */
object PinVerifier {
    private val tracked = listOf("protocol/herdr-schema-22.json", "fixtures")

    fun manifest(root: File): JsonObject =
        Json.parseToJsonElement(File(root, "protocol/SOURCE.json").readText()).jsonObject

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
        val onDisk = tracked.flatMap { File(root, it).walkTopDown().filter(File::isFile).toList() }
            .map { it.relativeTo(root).invariantSeparatorsPath }
        (onDisk - pinned.keys).forEach { problems += "not in SOURCE.json: $it" }
        return problems
    }
}
