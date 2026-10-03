package io.github.tuthan.paddock.answers

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

const val ID_A = "11111111-1111-4111-8111-111111111111"
const val ID_B = "22222222-2222-4222-8222-222222222222"
const val ID_C = "33333333-3333-4333-8333-333333333333"

/** A request body as the hook writes it, with whatever the test wants to change. */
fun bodyJson(id: String = ID_A, session: String = "claude-1", input: String = """{"command":"ls -la","description":"list"}""", created: Long = 1_000, expires: Long = 61_000, truncated: Boolean = false, version: Int = 1) =
    """{"v":$version,"request_id":"$id","claude_session_id":"$session","herdr_session":"paddock-test","pane_id":"w1:p1","tool_name":"Bash","tool_input":$input,"permission_mode":"default","created_at":$created,"expires_at":$expires,"truncated":$truncated}"""

fun entryJson(id: String, state: String, created: Long? = 1_000, expires: Long? = 61_000, session: String? = "claude-1", decision: String? = null, size: Int = 400, complete: Boolean = true, alive: Boolean? = null) =
    """{"request_id":"$id","state":"$state","size":$size,"mtime_ms":0,"created_at":${created ?: "null"},"expires_at":${expires ?: "null"},"claude_session_id":${session?.let { "\"$it\"" } ?: "null"},"truncated":false,"decision":${decision?.let { "\"$it\"" } ?: "null"},"tool_name":"Bash","complete":$complete,"hook_alive":${alive ?: "null"}}"""

fun listingJson(now: Long = 2_000, entries: List<String> = listOf(entryJson(ID_A, "pending")), candidate: String? = """{"request_id":"$ID_A","complete":true,"body":${bodyJson()}}""") =
    """{"v":1,"now_ms":$now,"requests":[${entries.joinToString(",")}],"candidate":${candidate ?: "null"}}"""

class RequestsTest {
    private fun parse(text: String, received: Long = 5_000, rtt: Long = 40) = RequestListing.parse(text, received, rtt)

    @Test fun aListingIsReadWithTheHostsClockAndThePhonesOwnTimes() {
        val l = assertNotNull(parse(listingJson(now = 2_000)))
        assertEquals(2_000, l.hostNowMillis)
        assertEquals(5_000, l.receivedAtMillis)
        assertEquals(40, l.roundTripMillis)
        val c = assertNotNull(l.candidate)
        assertEquals(ID_A, c.requestId)
        assertTrue(c.complete)
        val r = assertNotNull(c.request)
        assertEquals(listOf("Bash", "claude-1", "w1:p1", "paddock-test", "default"), listOf(r.toolName, r.claudeSessionId, r.paneId, r.herdrSession, r.permissionMode))
        assertEquals(61_000, r.expiresAt)
        assertFalse(r.truncated)
        assertEquals(RequestState.Pending, l.entry(ID_A)?.state)
    }

    @Test fun everyStateAndADecisionAreRead() {
        val l = assertNotNull(parse(listingJson(entries = listOf(entryJson(ID_A, "pending"), entryJson(ID_B, "claimed", decision = "deny"), entryJson(ID_C, "consumed", decision = "allow"), entryJson("44444444-4444-4444-8444-444444444444", "expired")), candidate = null)))
        assertEquals(listOf(RequestState.Pending, RequestState.Claimed, RequestState.Consumed, RequestState.Expired), l.requests.map { it.state })
        assertEquals(listOf(null, Behavior.Deny, Behavior.Allow, null), l.requests.map { it.decision })
        assertNull(l.candidate)
    }

    @Test fun whetherTheHookIsAliveIsReadAsThreeValues() {
        val l = parse(listingJson(entries = listOf(entryJson(ID_A, "pending", alive = true), entryJson(ID_B, "pending", alive = false), entryJson(ID_C, "pending"))))!!
        assertEquals(listOf(true, false, null), l.requests.map { it.hookAlive })
    }

    @Test fun anIncompleteCandidateCarriesNoBodyAndIsSaidToBeIncomplete() {
        val l = assertNotNull(parse(listingJson(candidate = """{"request_id":"$ID_A","complete":false,"body":null}""")))
        assertEquals(Candidate(ID_A, false, null), l.candidate)
    }

    @Test fun aCandidateWhoseBodyNamesAnotherRequestIsNotTrusted() {
        val l = assertNotNull(parse(listingJson(candidate = """{"request_id":"$ID_A","complete":true,"body":${bodyJson(id = ID_B)}}""")))
        assertNull(l.candidate!!.request)
    }

    @Test fun aListingThatIsNotUnderstoodIsNullNeverHalfRead() {
        for (text in listOf(
            "", "nope", "[]", "{}", listingJson().replace("\"v\":1", "\"v\":2"), listingJson().replace("\"now_ms\":2000", "\"now_ms\":\"2000\""),
            listingJson(entries = listOf(entryJson("not-a-uuid", "pending"))), listingJson(entries = listOf(entryJson(ID_A, "weird"))),
            listingJson(entries = listOf("[]")), listingJson(candidate = "[]"), listingJson(candidate = """{"request_id":"x","complete":true}"""),
            """{"v":1,"now_ms":1,"requests":{},"candidate":null}""",
        )) assertNull(parse(text), text.take(60))
    }

    @Test fun aRequestBodyWithMissingOrWrongFieldsHasNoRequest() {
        for (body in listOf(
            bodyJson(version = 2), bodyJson().replace("\"truncated\":false", "\"truncated\":\"no\""), bodyJson().replace("\"tool_name\":\"Bash\",", ""),
            bodyJson().replace("\"expires_at\":61000", "\"expires_at\":\"soon\""), bodyJson().replace("\"claude_session_id\":\"claude-1\",", ""),
        )) assertNull(parse(listingJson(candidate = """{"request_id":"$ID_A","complete":true,"body":$body}"""))!!.candidate!!.request, body.take(80))
    }

    @Test fun unknownFieldsAreIgnoredSoALaterScriptDoesNotBreakAnEarlierApp() {
        val l = parse(listingJson().replace("\"v\":1,\"now_ms\"", "\"v\":1,\"extra\":{\"a\":1},\"now_ms\""))
        assertNotNull(l)
    }

    @Test fun nothingBeyondTheCapIsKept() {
        val many = (1..100).map { entryJson("%08d-0000-4000-8000-000000000000".format(it), "expired") }
        assertEquals(RequestListing.MAX_REQUESTS, parse(listingJson(entries = many, candidate = null))!!.requests.size)
    }

    @Test fun aTruncatedRequestIsSaidToBeTruncated() {
        val l = parse(listingJson(candidate = """{"request_id":"$ID_A","complete":true,"body":${bodyJson(input = "\"{\\\"content\\\":\\\"xxxx\"", truncated = true)}}"""))!!
        assertTrue(l.candidate!!.request!!.truncated)
        assertEquals("{\"content\":\"xxxx", l.candidate!!.request!!.toolInputText)
    }
}

class ToolInputTextTest {
    private fun render(json: String) = ToolInputText.render(Json.parseToJsonElement(json))

    @Test fun eachFieldIsOnItsOwnAndAStringIsShownAsItIsWithItsLineBreaks() {
        assertEquals("command:\n  echo one\n  echo two\n\ndescription:\n  say it twice", render("""{"command":"echo one\necho two","description":"say it twice"}"""))
    }

    @Test fun otherValuesAreCompactJsonAndNothingIsLeftOut() {
        assertEquals("timeout:\n  120000\n\nflags:\n  [1,2,{\"a\":true}]\n\nnothing:\n  null", render("""{"timeout":120000,"flags":[1,2,{"a":true}],"nothing":null}"""))
        assertEquals("(no input)", render("{}"))
        assertEquals("7", render("7"))
    }

    @Test fun aLongInputIsKeptWholeNeverShortened() {
        val long = "x".repeat(250_000)
        assertEquals(250_000 + "content:\n  ".length, render("""{"content":"$long"}""").length)
    }

    @Test fun charactersThatCouldDisguiseTextAreShownAsTheirCodePoints() {
        val rtl = "rm -rf /tmp/\u202Efdp.txt"
        assertEquals("command:\n  rm -rf /tmp/\\u{202e}fdp.txt", ToolInputText.render(buildJsonObject { put("command", rtl) }))
        assertEquals("a\\u{1b}[31mb", ToolInputText.visible("a\u001b[31mb"))
        assertEquals("zero\\u{200b}width\\u{feff}", ToolInputText.visible("zero\u200bwidth\ufeff"))
        assertEquals("nul\\u{0}", ToolInputText.visible("nul\u0000"))
        assertEquals("tab\there\nnewline", ToolInputText.visible("tab\there\nnewline"))
        assertEquals("c1\\u{85}", ToolInputText.visible("c1\u0085"))
        assertEquals("tag\\u{e0041}", ToolInputText.visible("tag\uDB40\uDC41"))
        assertEquals("lone\\u{d800}", ToolInputText.visible("lone\uD800"))
        assertEquals("héllo \u2603 \uD83D\uDE00", ToolInputText.visible("héllo \u2603 \uD83D\uDE00"))
    }

    @Test fun aFieldNameCannotDisguiseItselfEither() {
        assertEquals("a\\u{202e}b:\n  x", ToolInputText.render(buildJsonObject { put("a\u202eb", "x") }))
    }

    @Test fun nullInputIsShownAsNull() {
        assertEquals("null", ToolInputText.render(JsonNull))
        assertEquals("(no input)", ToolInputText.render(JsonObject(emptyMap())))
        assertEquals("plain", ToolInputText.render(JsonPrimitive("plain")))
    }
}
