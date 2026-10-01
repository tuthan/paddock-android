package io.github.tuthan.paddock.herdr

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Why a line from herdr cannot be used. Each one closes the connection it came from. */
sealed class ProtocolError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class LineTooLong(val size: Int, val limit: Int) : ProtocolError("line of $size bytes exceeds the $limit byte budget")
    class NotJson(cause: Throwable) : ProtocolError("line is not JSON", cause)
    class UnknownShape(val keys: Set<String>) : ProtocolError("line is neither a response nor an event (keys: ${keys.sorted()})")
    class WrongResultType(val expected: String, val actual: String?) : ProtocolError("expected result.type '$expected' but got '${actual ?: "<none>"}'")
    class Decode(val what: String, cause: Throwable) : ProtocolError("cannot decode $what: ${cause.message?.lineSequence()?.firstOrNull()}", cause)
}

/** The byte budgets from the Phase 03 note. A snapshot is one line and has its own, larger, budget. */
object Budgets {
    const val LINE = 1 shl 20
    const val SNAPSHOT_LINE = 8 shl 20
    /** Strings shown in the UI are cut to this many code points before they leave `core`. */
    const val UI_STRING = 512
}

/** One decoded line from the socket. */
sealed interface Message {
    /** `{"id":…,"result":{"type":…,…}}` */
    class Success(val id: String, val type: String, val result: JsonObject) : Message {
        /** Decodes the result as [T], first checking `result.type`. Wrong type or missing fields are [ProtocolError]s. */
        inline fun <reified T> decode(expectedType: String, json: Json = PaddockJson): T {
            if (type != expectedType) throw ProtocolError.WrongResultType(expectedType, type)
            return try { json.decodeFromJsonElement<T>(result) } catch (e: SerializationException) { throw ProtocolError.Decode(expectedType, e) }
                catch (e: IllegalArgumentException) { throw ProtocolError.Decode(expectedType, e) }
        }
    }

    /** `{"id":…,"error":{"code":…,"message":…}}`. The id is empty when herdr could not read the request. */
    data class Failure(val id: String, val code: String, val message: String) : Message

    /** `{"event":…,"data":{…}}` */
    class Event(val name: String, val data: JsonObject) : Message
}

object Envelope {
    /** Parses one line. [limit] is [Budgets.LINE] unless the caller asked for a snapshot. */
    fun parse(line: String, limit: Int = Budgets.LINE, json: Json = PaddockJson): Message {
        val size = line.length   // characters bound bytes from below; the transport enforces the byte budget before decoding
        if (size > limit) throw ProtocolError.LineTooLong(size, limit)
        val obj: JsonObject = try { json.parseToJsonElement(line).jsonObject } catch (e: SerializationException) { throw ProtocolError.NotJson(e) }
            catch (e: IllegalArgumentException) { throw ProtocolError.NotJson(e) }
        val keys = obj.keys
        return when {
            "error" in keys -> {
                val err = obj["error"] as? JsonObject ?: throw ProtocolError.UnknownShape(keys)
                Message.Failure(obj.idOrEmpty(), err.str("code") ?: "unknown", err.str("message") ?: "")
            }
            "result" in keys -> {
                val result = obj["result"] as? JsonObject ?: throw ProtocolError.UnknownShape(keys)
                Message.Success(obj.idOrEmpty(), result.str("type") ?: throw ProtocolError.WrongResultType("<any>", null), result)
            }
            "event" in keys && "data" in keys -> Message.Event(obj.str("event") ?: throw ProtocolError.UnknownShape(keys), obj["data"] as? JsonObject ?: throw ProtocolError.UnknownShape(keys))
            else -> throw ProtocolError.UnknownShape(keys)
        }
    }

    private fun JsonObject.str(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.idOrEmpty(): String = (get("id") as? JsonPrimitive)?.contentOrNull ?: ""
}

/** Cuts a string to [max] code points (so a surrogate pair is never split) and marks the cut with an ellipsis. */
fun String.boundedForUi(max: Int = Budgets.UI_STRING): String =
    if (codePointCount(0, length) <= max) this else substring(0, offsetByCodePoints(0, max)) + "…"
