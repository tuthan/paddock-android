package io.github.tuthan.paddock.answers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** What a Claude Code permission request is doing, read from the name of its file on the host (see `host/paddock-decide.py`). */
enum class RequestState(val wire: String) {
    Pending("pending"), Claimed("claimed"), Consumed("consumed"), Expired("expired");
    companion object { fun of(wire: String?) = entries.firstOrNull { it.wire == wire } }
}

enum class Behavior(val wire: String) {
    Allow("allow"), Deny("deny");
    companion object { fun of(wire: String?) = entries.firstOrNull { it.wire == wire } }
}

/** One request file as `paddock-decide.py list` reports it. The fields past [state] are null when the file could not be read whole. */
data class RequestEntry(
    val requestId: String,
    val state: RequestState,
    val sizeBytes: Long,
    val createdAt: Long?,
    val expiresAt: Long?,
    val claudeSessionId: String?,
    val truncated: Boolean?,
    /** What the decision file beside a claimed request says, if the phone's decision was written. */
    val decision: Behavior?,
    val toolName: String?,
    val complete: Boolean,
    /** Whether the hook process that published a pending or claimed request still runs on the host; null when that cannot be told. */
    val hookAlive: Boolean? = null,
)

/**
 * The request as the hook wrote it. [toolInputText] is what the sheet shows, rendered from the input in full by [ToolInputText]; when
 * [truncated] it is the cut text the hook kept, and the request is never answerable from the phone.
 */
data class PendingRequest(
    val requestId: String,
    val claudeSessionId: String,
    val herdrSession: String?,
    val paneId: String,
    val toolName: String,
    val toolInputText: String,
    val permissionMode: String,
    val createdAt: Long,
    val expiresAt: Long,
    val truncated: Boolean,
)

/** The newest pending request. [request] is null when the file was too big to read whole ([complete] false) or did not parse. */
data class Candidate(val requestId: String, val complete: Boolean, val request: PendingRequest?)

/**
 * One answer of `paddock-decide.py list`. [hostNowMillis] is the host's clock when the script ran: every deadline is judged against
 * it, never against the phone's clock. [receivedAtMillis] and [roundTripMillis] are the phone's own: when the answer arrived, and how
 * long the call took, which is what the answerability rule has to allow for.
 */
data class RequestListing(
    val hostNowMillis: Long,
    val requests: List<RequestEntry>,
    val candidate: Candidate?,
    val receivedAtMillis: Long,
    val roundTripMillis: Long,
) {
    fun entry(requestId: String) = requests.firstOrNull { it.requestId == requestId }

    companion object {
        const val MAX_REQUESTS = 60
        private val json = Json { ignoreUnknownKeys = true }
        private val uuid4 = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")

        /** The listing in [text], or null when it is not an answer this build understands (another version, not JSON, wrong shapes). */
        fun parse(text: String, receivedAtMillis: Long, roundTripMillis: Long): RequestListing? {
            val root = try { json.parseToJsonElement(text) as? JsonObject } catch (e: RuntimeException) { null } ?: return null
            if (root.long("v") != 1L) return null
            val now = root.long("now_ms") ?: return null
            val entries = (root["requests"] as? JsonArray ?: return null).take(MAX_REQUESTS).map { entry(it as? JsonObject ?: return null) ?: return null }
            val candidate = when (val c = root["candidate"]) {
                null, JsonNull -> null
                is JsonObject -> candidate(c) ?: return null
                else -> return null
            }
            return RequestListing(now, entries, candidate, receivedAtMillis, roundTripMillis)
        }

        private fun entry(o: JsonObject): RequestEntry? {
            val id = o.string("request_id")?.takeIf(uuid4::matches) ?: return null
            val state = RequestState.of(o.string("state")) ?: return null
            return RequestEntry(
                id, state, o.long("size") ?: return null, o.long("created_at"), o.long("expires_at"), o.string("claude_session_id"),
                o.bool("truncated"), Behavior.of(o.string("decision")), o.string("tool_name"), o.bool("complete") ?: false, o.bool("hook_alive"),
            )
        }

        private fun candidate(o: JsonObject): Candidate? {
            val id = o.string("request_id")?.takeIf(uuid4::matches) ?: return null
            val complete = o.bool("complete") ?: return null
            val body = o["body"]
            val request = if (body is JsonObject) request(body) else null
            return Candidate(id, complete, request?.takeIf { it.requestId == id })
        }

        private fun request(o: JsonObject): PendingRequest? {
            if (o.long("v") != 1L) return null
            val id = o.string("request_id")?.takeIf(uuid4::matches) ?: return null
            val truncated = o.bool("truncated") ?: return null
            val input = o["tool_input"] ?: JsonNull
            return PendingRequest(
                id, o.string("claude_session_id") ?: return null, o.string("herdr_session"), o.string("pane_id") ?: return null,
                o.string("tool_name") ?: return null, ToolInputText.render(input), o.string("permission_mode").orEmpty(),
                o.long("created_at") ?: return null, o.long("expires_at") ?: return null, truncated,
            )
        }

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
        private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
    }
}

/**
 * The tool input as the sheet shows it, whole and unsummarised: each field of an input object on its own, the text of a string
 * field as it is (line breaks and all), anything else as compact JSON. What Claude Code would run is therefore readable where the
 * JSON text would hide it behind `\n` escapes. Characters that could make the text lie are shown, not obeyed: control characters,
 * the bidirectional overrides and isolates, and zero-width characters appear as `\u{...}` escapes, so a command cannot reorder or
 * hide what it says.
 */
object ToolInputText {
    private val compact = Json { prettyPrint = false }

    fun render(input: JsonElement): String = when (input) {
        is JsonObject -> if (input.isEmpty()) "(no input)" else input.entries.joinToString("\n\n") { (k, v) -> visible(k) + ":\n" + indent(value(v)) }
        else -> value(input)
    }

    private fun value(v: JsonElement): String = when {
        v is JsonPrimitive && v.isString -> visible(v.content)
        v is JsonNull -> "null"
        else -> visible(compact.encodeToString(JsonElement.serializer(), v))
    }

    private fun indent(text: String) = text.lines().joinToString("\n") { "  $it" }

    /** [s] with every character that could disguise text replaced by its code point. Tab and line feed stay. */
    fun visible(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            if (hides(cp)) out.append("\\u{").append(Integer.toHexString(cp)).append('}') else out.appendCodePoint(cp)
        }
        return out.toString()
    }

    private fun hides(cp: Int): Boolean = when {
        cp == '\n'.code || cp == '\t'.code -> false
        cp < 0x20 || cp == 0x7f || cp in 0x80..0x9f -> true
        cp in 0x200b..0x200f || cp in 0x202a..0x202e || cp in 0x2060..0x2064 || cp in 0x2066..0x206f || cp == 0xfeff || cp == 0x061c || cp == 0x180e -> true
        cp in 0xfff9..0xfffb || cp in 0xe0000..0xe007f -> true
        Character.getType(cp) == Character.UNASSIGNED.toInt() || Character.getType(cp) == Character.SURROGATE.toInt() -> true
        else -> false
    }
}
