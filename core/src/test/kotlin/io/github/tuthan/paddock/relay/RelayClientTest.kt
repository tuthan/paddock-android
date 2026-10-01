package io.github.tuthan.paddock.relay

import io.github.tuthan.paddock.herdr.AgentStatus
import io.github.tuthan.paddock.herdr.EventOutcome
import io.github.tuthan.paddock.herdr.ProtocolError
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RelayClientTest {
    private val fixtures = File(System.getProperty("paddock.repoRoot"), "fixtures/herdr-0.9.1")
    private val relay = "/home/u/.local/share/paddock/paddock-relay.py"
    private val socket = "/home/u/.config/herdr/sessions/paddock-test/herdr.sock"
    private fun client(s: FakeSession) = RelayClient(s, relay, socket, ids = { "id-1" })
    private fun respond(vararg lines: String): (FakeStream) -> Unit = { st -> lines.forEach { st.feed(it + "\n") } }

    // ---- LineReader -----------------------------------------------------------------------------------------

    @Test fun lineReaderJoinsSplitChunksAndMultiByteCharacters() {
        val r = LineReader(100)
        val bytes = "{\"a\":\"héllo\"}\n{\"b\":2}\n".toByteArray()
        val cut = bytes.indexOf('é'.code.toByte()) + 1                    // inside the two-byte character
        assertEquals(emptyList(), r.feed(bytes.copyOfRange(0, cut)))
        assertEquals(listOf("{\"a\":\"héllo\"}", "{\"b\":2}"), r.feed(bytes.copyOfRange(cut, bytes.size)))
    }

    @Test fun lineReaderRefusesALineOverTheBudgetBeforeItsNewline() {
        val r = LineReader(10)
        assertFailsWith<ProtocolError.LineTooLong> { r.feed("x".repeat(11).toByteArray()) }
        assertEquals(listOf("x".repeat(10)), LineReader(10).feed("${"x".repeat(10)}\n".toByteArray()))
    }

    // ---- call -------------------------------------------------------------------------------------------------

    @Test fun callReturnsTheSuccessAndClosesTheStream() = runBlocking<Unit> {
        val session = FakeSession(onStream = respond(File(fixtures, "socket-ping.jsonl").readText().trim().replace("fx-ping", "id-1")))
        val ok = client(session).call("ping")
        assertEquals("pong", ok.type)
        assertTrue(session.streams.single().closed)
        assertEquals("""{"id":"id-1","method":"ping","params":{}}""" + "\n", session.streams.single().writtenText())
    }

    @Test fun herdrErrorsSurfaceWithTheirCode() = runBlocking<Unit> {
        val session = FakeSession(onStream = respond("""{"id":"id-1","error":{"code":"agent_not_found","message":"agent target w9:p9 not found"}}"""))
        assertEquals("agent_not_found", assertFailsWith<HerdrError> { client(session).call("agent.get") }.code)
    }

    @Test fun aResponseForAnotherRequestIsAProtocolError() = runBlocking<Unit> {
        val session = FakeSession(onStream = respond("""{"id":"other","result":{"type":"pong"}}"""))
        assertFailsWith<ProtocolError.UnknownShape> { client(session).call("ping") }
    }

    @Test fun aStreamThatEndsWithoutALineIsRelayUnavailableWithItsExit() = runBlocking<Unit> {
        val session = FakeSession(onStream = { it.exit = 3; it.end() })
        assertEquals(3, assertFailsWith<RelayUnavailable> { client(session).call("ping") }.exit)
    }

    @Test fun anOversizeResponseIsRefusedAndTheStreamClosed() = runBlocking<Unit> {
        val session = FakeSession(onStream = { it.feed("x".repeat(2000)) })
        assertFailsWith<ProtocolError.LineTooLong> { client(session).call("ping", budget = 1000) }
        assertTrue(session.streams.single().closed)
    }

    @Test fun silenceTimesOutAndStillCloses() = runBlocking<Unit> {
        val session = FakeSession()
        // RelayTimeout is a plain exception, not a CancellationException: the caller's loop must survive a host that does not answer.
        val e = assertFailsWith<RelayTimeout> { client(session).call("ping", timeout = 100.milliseconds) }
        assertEquals("ping", e.method)
        assertTrue(session.streams.single().closed)
    }

    // ---- AC-03.7: free text only on stdin ---------------------------------------------------------------------

    @Test fun promptBodiesTravelInsideJsonOnStdinAndNeverInArgv() = runBlocking<Unit> {
        val prompt = "rm -rf / ; \$(reboot) 'quoted' \"double\" \nsecond line ✓"
        val session = FakeSession(onStream = respond("""{"id":"id-1","result":{"type":"ok"}}"""))
        client(session).call("pane.send_text", buildJsonObject { put("text", prompt) })
        val stream = session.streams.single()
        assertEquals(listOf("python3", relay, socket), stream.argv)
        assertFalse(stream.argv.any { "reboot" in it })
        val sent = stream.writtenText()
        assertEquals(1, sent.count { it == '\n' }, "newlines inside text must be JSON-escaped; one request, one line")
        assertTrue(sent.contains("\\n") && sent.contains("reboot"))
        assertTrue(session.execs.isEmpty())
    }

    // ---- subscribe --------------------------------------------------------------------------------------------

    private val life = listOf(buildJsonObject { put("type", "pane.created") })
    private val statusLines = File(fixtures, "events-status.jsonl").readLines().filter { it.isNotBlank() }

    @Test fun subscribeAcknowledgesThenEmitsMappedEventsAndEndsAsUnavailable() = runBlocking<Unit> {
        val session = FakeSession(onStream = { st -> statusLines.forEach { st.feed(it.replace("fx-stat", "id-1") + "\n") }; st.end() })
        val seen = mutableListOf<EventOutcome>()
        assertFailsWith<RelayUnavailable> { client(session).subscribe(life).collect { seen += it } }
        assertEquals(listOf(AgentStatus.Blocked, AgentStatus.Working, AgentStatus.Idle, AgentStatus.Unknown), seen.map { (it as EventOutcome.Status).event.agentStatus })
        assertTrue(session.streams.single().closed)
        assertTrue(session.streams.single().writtenText().contains(""""method":"events.subscribe""""))
    }

    @Test fun aRefusedSubscriptionFailsWithHerdrsCode() = runBlocking<Unit> {
        val refusal = File(fixtures, "subscribe-status-missing-pane.jsonl").readText().trim()
        val session = FakeSession(onStream = respond(refusal))
        val e = assertFailsWith<HerdrError> { client(session).subscribe(life).toList() }
        assertEquals("invalid_request", e.code); assertTrue(e.message!!.contains("pane_id"))
    }

    @Test fun eventsLostClosesTheFlowWithEventsLost() = runBlocking<Unit> {
        val session = FakeSession(onStream = respond("""{"id":"id-1","result":{"type":"subscription_started"}}""", """{"event":"events_lost","data":{}}"""))
        assertFailsWith<EventsLost> { client(session).subscribe(life).toList() }
        assertTrue(session.streams.single().closed)
    }

    @Test fun noAcknowledgementTimesOut() = runBlocking<Unit> {
        val session = FakeSession()
        assertFailsWith<SubscribeTimeout> { client(session).subscribe(life, ackTimeout = 100.milliseconds).toList() }
        assertTrue(session.streams.single().closed)
    }

    @Test fun anEventBeforeTheAcknowledgementIsAProtocolError() = runBlocking<Unit> {
        val session = FakeSession(onStream = respond("""{"event":"pane_created","data":{}}"""))
        assertFailsWith<ProtocolError.UnknownShape> { client(session).subscribe(life).toList() }
    }

    // ---- installer and AC-03.6 -----------------------------------------------------------------------------

    private val root = File(System.getProperty("paddock.repoRoot"))
    private val script = File(root, "host/paddock-relay.py").readBytes()
    private val pinned = Regex("\"host/paddock-relay.py\"\\s*:\\s*\"sha256:([0-9a-f]{64})\"").find(File(root, "host/SOURCE.json").readText())!!.groupValues[1]

    @Test fun theBundledScriptMatchesItsPinAndATamperedCopyIsRefused() {
        assertEquals(pinned, sha256Hex(script))
        RelayInstaller(FakeSession(), script, pinned)
        assertFailsWith<IllegalArgumentException> { RelayInstaller(FakeSession(), script + "#".toByteArray(), pinned) }
    }

    private fun sha256sumSession(hashOnHost: String?) = FakeSession(onExec = { argv, _ ->
        when (argv.first()) {
            "sha256sum" -> if (hashOnHost == null) FakeSession.result(1, err = "No such file") else FakeSession.result(0, "$hashOnHost  ${argv.last()}\n")
            "sh" -> if (argv[2].startsWith("printf")) FakeSession.result(0, "/home/u") else FakeSession.result(0)
            else -> FakeSession.result(127)
        }
    })

    @Test fun aHostCopyWithADifferentHashIsNeverRun() = runBlocking<Unit> {
        val inst = RelayInstaller(sha256sumSession("0".repeat(64)), script, pinned)
        val e = assertFailsWith<RelayRefused> { inst.verifiedPath("/home/u") }
        assertEquals(RelayState.Mismatch("0".repeat(64)), e.state)
    }

    @Test fun aMissingCopyIsRefusedUntilInstalled() = runBlocking<Unit> {
        assertFailsWith<RelayRefused> { RelayInstaller(sha256sumSession(null), script, pinned).verifiedPath("/home/u") }
    }

    @Test fun theCurrentCopyIsAccepted() = runBlocking<Unit> {
        val inst = RelayInstaller(sha256sumSession(pinned), script, pinned)
        assertEquals("/home/u/.local/share/paddock/paddock-relay.py", inst.verifiedPath(inst.homeDirectory()))
    }

    @Test fun installSendsTheScriptOnStdinAndReverifies() = runBlocking<Unit> {
        var installed = false
        val session = FakeSession(onExec = { argv, stdin ->
            when {
                argv.first() == "sh" && argv[2].startsWith("umask") -> { installed = true; assertContentEquals(script, stdin); FakeSession.result(0) }
                argv.first() == "sha256sum" -> if (installed) FakeSession.result(0, "$pinned  x\n") else FakeSession.result(1)
                else -> FakeSession.result(0, "/home/u")
            }
        })
        RelayInstaller(session, script, pinned).install("/home/u")
        assertTrue(installed)
        assertTrue(session.execs.none { (argv, _) -> argv.any { "print(" in it || "import socket" in it } }, "the script body must not be in argv")
    }

    @Test fun installThatLandsADifferentFileIsRefused() = runBlocking<Unit> {
        val session = FakeSession(onExec = { argv, _ -> if (argv.first() == "sha256sum") FakeSession.result(0, "${"1".repeat(64)}  x\n") else FakeSession.result(0) })
        assertFailsWith<RelayRefused> { RelayInstaller(session, script, pinned).install("/home/u") }
    }
}
