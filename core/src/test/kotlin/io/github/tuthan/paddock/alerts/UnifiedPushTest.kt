package io.github.tuthan.paddock.alerts

import io.github.tuthan.paddock.ports.Clock
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

class UnifiedPushTest {
    private val token = "0b6c5c3e-6a1c-4d0e-9a53-1f1b2f8f6a11"
    private fun extras(vararg kv: Pair<String, String?>, bytes: ByteArray? = null) = PushExtras(mapOf(*kv), bytes)
    private fun parse(action: String, e: PushExtras) = PushProtocol.parse(action, e)

    @Test fun anEndpointBroadcastParsesWithItsTokenAndId() {
        val r = parse(UnifiedPush.NEW_ENDPOINT, extras("token" to token, "endpoint" to "https://ntfy.example.org/upAbCdEf12345", "id" to "msg-1"))
        assertEquals(PushInbound.Endpoint(token, "https://ntfy.example.org/upAbCdEf12345", "msg-1"), r)
        assertEquals(PushInbound.Endpoint(token, "https://ntfy.example.org/up1", null), parse(UnifiedPush.NEW_ENDPOINT, extras("token" to token, "endpoint" to "https://ntfy.example.org/up1")))
    }

    @Test fun aRequestThatBreaksTheSpecificationIsIgnored() {
        val good = "https://ntfy.example.org/up1"
        for (bad in listOf(null, "", "ftp://x/y", "http://ntfy.example.org/up1", "https://user:pw@x.example/up", "https:///up", "https://x.example/up#frag", "https://x.example/a b", "https://x.example/\n", "not a url", "https://x.example/" + "a".repeat(1000)))
            assertNull(parse(UnifiedPush.NEW_ENDPOINT, extras("token" to token, "endpoint" to bad)), "$bad")
        for (badToken in listOf(null, "", "a b", "t".repeat(101), "tok\n", "é"))
            assertNull(parse(UnifiedPush.NEW_ENDPOINT, extras("token" to badToken, "endpoint" to good)), "$badToken")
        assertNull(parse(UnifiedPush.NEW_ENDPOINT, extras("token" to token, "endpoint" to good, "id" to "a b")))
        assertNull(parse(UnifiedPush.NEW_ENDPOINT, extras("token" to token)))
        assertNull(parse("org.unifiedpush.android.connector.SOMETHING_ELSE", extras("token" to token)))
        assertNull(PushProtocol.parse(null, extras("token" to token)))
    }

    @Test fun plainHttpIsAcceptedOnlyForALoopbackHost() {
        for (ok in listOf("http://127.0.0.1:8080/up1", "http://localhost/up1", "http://[::1]:2290/up1")) assertEquals(ok, PushEndpoint.accept(ok))
        for (no in listOf("http://10.0.2.2/up1", "http://example.org/up1", "http://127.0.0.1.example.org/up1")) assertNull(PushEndpoint.accept(no), no)
    }

    @Test fun aMessageNeedsBetweenOneAndFourThousandNinetySixBytes() {
        assertTrue(parse(UnifiedPush.MESSAGE, extras("token" to token, bytes = ByteArray(1) { 1 })) is PushInbound.Message)
        assertTrue(parse(UnifiedPush.MESSAGE, extras("token" to token, bytes = ByteArray(4096) { 1 })) is PushInbound.Message)
        assertNull(parse(UnifiedPush.MESSAGE, extras("token" to token, bytes = ByteArray(0))))
        assertNull(parse(UnifiedPush.MESSAGE, extras("token" to token, bytes = ByteArray(4097))))
        assertNull(parse(UnifiedPush.MESSAGE, extras("token" to token)))
    }

    @Test fun aFailureReasonIsOneOfFourAndAnythingElseIsAnInternalError() {
        fun reason(r: String?) = (parse(UnifiedPush.REGISTRATION_FAILED, extras("token" to token, "reason" to r)) as PushInbound.Failed).reason
        assertEquals(PushFailure.Network, reason("NETWORK"))
        assertEquals(PushFailure.ActionRequired, reason("ACTION_REQUIRED"))
        assertEquals(PushFailure.VapidRequired, reason("VAPID_REQUIRED"))
        assertEquals(PushFailure.InternalError, reason("INTERNAL_ERROR"))
        assertEquals(PushFailure.InternalError, reason(null))
        assertEquals(PushFailure.InternalError, reason("<script>"))
    }

    @Test fun theRelaysPayloadYieldsItsNonceAndNothingElseIsTrusted() {
        assertEquals(PushHint("workstation", "a1b2c3d4e5f60718"), PushHint.parse("""{"v":1,"h":"workstation","n":"a1b2c3d4e5f60718"}""".toByteArray()))
        assertEquals(PushHint(null, "42"), PushHint.parse("""{"n":42}""".toByteArray()))
        assertEquals(PushHint(null, null), PushHint.parse("""{"h":"Bad Profile!","n":"x y"}""".toByteArray()))
        assertEquals(PushHint(null, null), PushHint.parse("""{"h":["a"],"n":{"x":1}}""".toByteArray()))
        for (junk in listOf("", "not json", "[1,2]", "\"s\"", "null", "{")) assertEquals(PushHint(null, null), PushHint.parse(junk.toByteArray()), junk)
        assertEquals(PushHint(null, null), PushHint.parse(byteArrayOf(0x1f, 0x8b.toByte(), 0, 0xff.toByte())))   // bytes that are not text at all
    }

    // ---- the registry ----

    private var n = 0
    private var now = 1_000L
    private fun registry(store: PushStore = MemoryPushStore()) = PushRegistry(store, { "token-${++n}" }, Clock { now })
    private fun message(token: String, id: String? = "m1", body: String = """{"v":1,"h":"workstation","n":"abc123"}""") = PushInbound.Message(token, body.toByteArray(), id)

    @Test fun beginningMakesAFreshTokenEachTimeAndReturnsTheOneItReplaced() = runBlocking<Unit> {
        val r = registry()
        val (first, none) = r.begin("workstation", "io.example.distributor")
        assertNull(none)
        val (second, replaced) = r.begin("workstation", "io.example.other")
        assertEquals(first, replaced)
        assertNotEquals(first.token, second.token)
        assertEquals(listOf(second), r.all())
    }

    @Test fun aTokenThisPhoneDoesNotHoldIsIgnored() = runBlocking<Unit> {
        val r = registry()
        r.begin("workstation", "d")
        assertEquals(PushEffect.Ignore, r.onInbound(PushInbound.Endpoint("someone-elses", "https://x.example/up", null)))
        assertEquals(PushEffect.Ignore, r.onInbound(message("someone-elses")))
        assertEquals(PushEffect.Ignore, r.onInbound(PushInbound.Unregistered("someone-elses")))
        assertEquals(1, r.all().size)
    }

    @Test fun anEndpointIsStoredAndAcknowledgedAndOnlyThenDoesAMessageAlert() = runBlocking<Unit> {
        val r = registry()
        val (reg, _) = r.begin("workstation", "dist")
        assertEquals(PushEffect.Ignore, r.onInbound(message(reg.token)), "a message before any endpoint is not routable to this registration")
        val e = r.onInbound(PushInbound.Endpoint(reg.token, "https://ntfy.example.org/up1", "e1"))
        assertEquals(PushEffect.EndpointKnown("workstation", "https://ntfy.example.org/up1", false, PushAck("dist", reg.token, "e1")), e)
        assertEquals("https://ntfy.example.org/up1", r.forProfile("workstation")!!.endpoint)
        assertEquals(PushEffect.Alert("workstation", "abc123", PushAck("dist", reg.token, "m1")), r.onInbound(message(reg.token)))
        assertEquals(PushEffect.Alert("workstation", "abc123", null), r.onInbound(message(reg.token, id = null)))
    }

    @Test fun theMachineComesFromTheTokenNeverFromTheMessage() = runBlocking<Unit> {
        val r = registry()
        val (reg, _) = r.begin("workstation", "dist")
        r.onInbound(PushInbound.Endpoint(reg.token, "https://ntfy.example.org/up1", null))
        val e = r.onInbound(message(reg.token, body = """{"v":1,"h":"laptop","n":"zz"}""")) as PushEffect.Alert
        assertEquals("workstation", e.profile)
        assertEquals("workstation", (r.onInbound(message(reg.token, body = "garbage")) as PushEffect.Alert).profile)
    }

    @Test fun aChangedAddressAfterItWasSentToTheHostIsSaidToBeNewlyUnsent() = runBlocking<Unit> {
        val r = registry()
        val (reg, _) = r.begin("workstation", "dist")
        r.onInbound(PushInbound.Endpoint(reg.token, "https://ntfy.example.org/up1", null))
        r.markShared("workstation")
        assertEquals(1_000L, r.forProfile("workstation")!!.sharedAtMillis)
        val same = r.onInbound(PushInbound.Endpoint(reg.token, "https://ntfy.example.org/up1", null)) as PushEffect.EndpointKnown
        assertEquals(false, same.changedAfterShared)
        assertEquals(1_000L, r.forProfile("workstation")!!.sharedAtMillis)
        val changed = r.onInbound(PushInbound.Endpoint(reg.token, "https://ntfy.example.org/up2", null)) as PushEffect.EndpointKnown
        assertEquals(true, changed.changedAfterShared)
        assertNull(r.forProfile("workstation")!!.sharedAtMillis)
    }

    @Test fun aFailureBeforeAnEndpointChangesTheTokenAndOneAfterItIsStale() = runBlocking<Unit> {
        val r = registry()
        val (reg, _) = r.begin("workstation", "dist")
        assertEquals(PushEffect.Failed("workstation", PushFailure.Network), r.onInbound(PushInbound.Failed(reg.token, PushFailure.Network)))
        val after = r.forProfile("workstation")!!
        assertNotEquals(reg.token, after.token)
        assertEquals(PushFailure.Network, after.failure)
        assertEquals(PushEffect.Ignore, r.onInbound(PushInbound.Failed(reg.token, PushFailure.Network)), "the old token is gone")
        r.onInbound(PushInbound.Endpoint(after.token, "https://ntfy.example.org/up1", null))
        assertEquals(PushEffect.Ignore, r.onInbound(PushInbound.Failed(after.token, PushFailure.InternalError)))
        assertEquals("https://ntfy.example.org/up1", r.forProfile("workstation")!!.endpoint)
        assertNull(r.forProfile("workstation")!!.failure)
    }

    @Test fun unregisteredAndEndRemoveTheRegistrationAndItsTokenStopsWorking() = runBlocking<Unit> {
        val r = registry()
        val (reg, _) = r.begin("workstation", "dist")
        r.onInbound(PushInbound.Endpoint(reg.token, "https://ntfy.example.org/up1", null))
        assertEquals(PushEffect.Unregistered("workstation"), r.onInbound(PushInbound.Unregistered(reg.token)))
        assertEquals(emptyList(), r.all())
        assertEquals(PushEffect.Ignore, r.onInbound(message(reg.token)))
        val (again, _) = r.begin("workstation", "dist")
        assertNotEquals(reg.token, again.token)
        assertEquals(again, r.end("workstation"))
        assertNull(r.end("workstation"))
        assertEquals(PushEffect.Ignore, r.onInbound(message(again.token)))
    }

    @Test fun twoMachinesHaveTwoTokensAndDoNotSeeEachOthersMessages() = runBlocking<Unit> {
        val r = registry()
        val (a, _) = r.begin("workstation", "dist")
        val (b, _) = r.begin("laptop", "dist")
        r.onInbound(PushInbound.Endpoint(a.token, "https://ntfy.example.org/a", null)); r.onInbound(PushInbound.Endpoint(b.token, "https://ntfy.example.org/b", null))
        assertEquals("laptop", (r.onInbound(message(b.token)) as PushEffect.Alert).profile)
        assertEquals("workstation", (r.onInbound(message(a.token)) as PushEffect.Alert).profile)
    }

    @Test fun theFileStoreKeepsRegistrationsAcrossProcessesAndReadsACorruptFileAsNone() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("push-store").toFile()
        val file = dir.resolve("push.json")
        val r = registry(FilePushStore(file))
        val (reg, _) = r.begin("workstation", "dist")
        r.onInbound(PushInbound.Endpoint(reg.token, "https://ntfy.example.org/up1", null))
        val reopened = registry(FilePushStore(file))
        assertEquals("https://ntfy.example.org/up1", reopened.forProfile("workstation")!!.endpoint)
        file.writeText("{ not json")
        assertEquals(emptyList(), registry(FilePushStore(file)).all())
        dir.deleteRecursively()
    }

    @Test fun theDescriptionFitsTheSpecsHundredBytes() {
        assertEquals("Paddock: workstation", UnifiedPush.description("workstation"))
        assertTrue(UnifiedPush.description("é".repeat(80)).encodeToByteArray().size <= UnifiedPush.MAX_DESCRIPTION_BYTES)
    }
}
