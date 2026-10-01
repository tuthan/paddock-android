package io.github.tuthan.paddock.herdr

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull

/**
 * For fields that describe an agent but do not identify it (labels, session info, readiness hints, titles). A value
 * of the wrong shape becomes null instead of failing the whole read, so a herdr that changes one decorative field
 * disables what depends on that field and never the snapshot. Identity fields (`pane_id`, `terminal_id`, …) and
 * the snapshot's `version` and `protocol` stay strict.
 */
abstract class Lenient<T : Any>(private val inner: KSerializer<T>) : KSerializer<T?> {
    override val descriptor: SerialDescriptor = inner.descriptor.nullable

    override fun deserialize(decoder: Decoder): T? {
        val json = decoder as? JsonDecoder ?: return inner.deserialize(decoder)
        val element = json.decodeJsonElement()
        if (element is JsonNull) return null
        // kotlinx can also throw IndexOutOfBounds (an array where a string belongs), so any runtime failure counts.
        return try { json.json.decodeFromJsonElement(inner, element) } catch (_: RuntimeException) { null }
    }

    override fun serialize(encoder: Encoder, value: T?) {
        if (value == null) encoder.encodeNull() else encoder.encodeSerializableValue(inner, value)
    }
}

object LenientString : Lenient<String>(String.serializer())
object LenientBoolean : Lenient<Boolean>(Boolean.serializer())
object LenientLong : Lenient<Long>(Long.serializer())
object LenientLabels : Lenient<Map<String, String>>(MapSerializer(String.serializer(), String.serializer()))
object LenientAgentSession : Lenient<AgentSession>(AgentSession.serializer())
object LenientScroll : Lenient<Scroll>(Scroll.serializer())
