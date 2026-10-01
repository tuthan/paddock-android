package io.github.tuthan.paddock.herdr

import java.io.File
import kotlin.test.assertTrue
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * Every field the models decode, checked against the pinned schema: the JSON type must be one the schema allows, and a
 * field the schema makes optional or nullable must be optional in the model. A model that types `agent_session` as a
 * string or `state_labels` as a list (which real agents broke in the field) fails here, not on a phone.
 */
/** Deliberately wrong against `AgentInfo`, to prove the check can fail. */
@kotlinx.serialization.Serializable
private class Wrong(@kotlinx.serialization.SerialName("agent_session") val s: String, val title: String)

@OptIn(ExperimentalSerializationApi::class)
class SchemaConformanceTest {
    private val root = Json.parseToJsonElement(File(System.getProperty("paddock.repoRoot"), "protocol/herdr-schema-22.json").readText()).jsonObject

    private fun def(path: String): JsonObject = path.removePrefix("#/").split('/').fold(root as JsonElement) { node, key -> node.jsonObject.getValue(key) }.jsonObject
    private fun response(name: String) = def("#/schemas/success_response/\$defs/$name")
    private fun event(name: String) = def("#/schemas/subscription_event/\$defs/$name")

    /** Fields the model reads that this schema does not declare, each with its reason. */
    private val modelOnly = mapOf("Agent" to setOf("message")) // Phase 08 capability probe; absent in 0.9.1 (Phase 00 finding 1)

    private val pairs: List<Triple<String, SerialDescriptor, JsonObject>> = listOf(
        Triple("Agent", Agent.serializer().descriptor, response("AgentInfo")),
        Triple("AgentSession", AgentSession.serializer().descriptor, response("AgentSessionInfo")),
        Triple("Pane", Pane.serializer().descriptor, response("PaneInfo")),
        Triple("Scroll", Scroll.serializer().descriptor, response("PaneScrollInfo")),
        Triple("Workspace", Workspace.serializer().descriptor, response("WorkspaceInfo")),
        Triple("Tab", Tab.serializer().descriptor, response("TabInfo")),
        Triple("Layout", Layout.serializer().descriptor, response("PaneLayoutSnapshot")),
        Triple("LayoutPane", LayoutPane.serializer().descriptor, response("PaneLayoutPane")),
        Triple("Rect", Rect.serializer().descriptor, response("PaneLayoutRect")),
        Triple("Snapshot", Snapshot.serializer().descriptor, response("SessionSnapshot")),
        Triple("StatusEvent", StatusEvent.serializer().descriptor, event("PaneAgentStatusChangedEvent")),
    )

    @Test fun everyModelFieldHasTheSchemaTypeAndOptionality() {
        val problems = pairs.flatMap { (model, descriptor, schema) -> check(model, descriptor, schema) }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test fun theCheckCatchesTheMistakesItExistsFor() {
        // A string where the schema has an object, and a required model field the schema leaves optional.
        val problems = check("Wrong", Wrong.serializer().descriptor, response("AgentInfo"))
        assertTrue(problems.any { "agent_session" in it && "string" in it }, problems.toString())
        assertTrue(problems.any { "title" in it && "optional" in it }, problems.toString())
    }

    private fun check(model: String, descriptor: SerialDescriptor, schema: JsonObject): List<String> {
        val properties = schema["properties"]?.jsonObject ?: JsonObject(emptyMap())
        val required = schema["required"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty()
        return (0 until descriptor.elementsCount).mapNotNull { i ->
            val name = descriptor.getElementName(i)
            val element = descriptor.getElementDescriptor(i)
            val property = properties[name]?.jsonObject
                ?: return@mapNotNull if (name in modelOnly[model].orEmpty()) null else "$model.$name is not in the schema"
            val allowed = types(property)
            val kind = jsonType(element)
            when {
                kind != null && kind !in allowed -> "$model.$name decodes as $kind but the schema allows $allowed"
                (name !in required || "null" in allowed) && !(descriptor.isElementOptional(i) || element.isNullable) ->
                    "$model.$name is required in the model but optional or nullable in the schema"
                else -> null
            }
        }
    }

    /** The JSON types a schema node admits, following `$ref`, `anyOf`, `oneOf` and `enum`. */
    private fun types(node: JsonObject): Set<String> {
        node["\$ref"]?.let { return types(def(it.jsonPrimitive.content)) }
        val union = listOf("anyOf", "oneOf").flatMap { key -> (node[key] as? JsonArray).orEmpty().map { types(it.jsonObject) } }
        if (union.isNotEmpty()) return union.flatten().toSet()
        return when (val t = node["type"]) {
            is JsonPrimitive -> setOf(t.content)
            is JsonArray -> t.map { it.jsonPrimitive.content }.toSet()
            else -> if ("enum" in node) setOf("string") else emptySet()
        }.let { if ("integer" in it) it + "number" else it }
    }

    /** What JSON a model element reads, or null when it accepts anything (a raw [JsonElement]). */
    private fun jsonType(d: SerialDescriptor): String? = when (val k: SerialKind = d.kind) {
        PrimitiveKind.STRING, SerialKind.ENUM -> "string"
        PrimitiveKind.INT, PrimitiveKind.LONG, PrimitiveKind.SHORT, PrimitiveKind.BYTE -> "integer"
        PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> "number"
        PrimitiveKind.BOOLEAN -> "boolean"
        StructureKind.LIST -> "array"
        StructureKind.MAP, StructureKind.CLASS, StructureKind.OBJECT -> "object"
        is PolymorphicKind, SerialKind.CONTEXTUAL -> null
        else -> error("unhandled kind $k")
    }
}
