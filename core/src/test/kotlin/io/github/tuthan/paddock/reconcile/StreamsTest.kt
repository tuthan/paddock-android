package io.github.tuthan.paddock.reconcile

import io.github.tuthan.paddock.herdr.EventOutcome
import io.github.tuthan.paddock.relay.FakeSession
import io.github.tuthan.paddock.relay.FakeStream
import io.github.tuthan.paddock.relay.HerdrError
import io.github.tuthan.paddock.relay.RelayClient
import io.github.tuthan.paddock.relay.RelayUnavailable
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StreamsTest {
    private val relay = "/r.py"; private val sock = "/s/herdr.sock"
    private val ack = """{"id":"x","result":{"type":"subscription_started"}}"""
    private val refusal = """{"id":"","error":{"code":"invalid_request","message":"unknown pane"}}"""
    private val scope = CoroutineScope(Dispatchers.Default)
    private var n = 0
    private fun client(session: FakeSession) = RelayClient(session, relay, sock, ids = { "id-${++n}" })
    private fun String.acked(id: String) = replace("\"x\"", "\"$id\"")

    private fun panesOf(stream: FakeStream) = Regex("\"pane_id\":\"([^\"]+)\"").findAll(stream.writtenText()).map { it.groupValues[1] }.toSet()

    @Test fun lifecycleSubscriptionsAreOneRequestWithNoParametersAndStatusNeedsAPaneEach() {
        assertEquals(24, Subscriptions.lifecycle().size)
        assertTrue(Subscriptions.lifecycle().all { it.keys == setOf("type") })
        val status = Subscriptions.status(setOf("w1:p2", "w1:p1"))
        assertEquals(listOf("w1:p1", "w1:p2"), status.map { it["pane_id"].toString().trim('"') })
        assertTrue(status.all { it["type"].toString() == "\"pane.agent_status_changed\"" })
    }

    @Test fun replacingTheSetStartsTheNewStreamBeforeClosingTheOldOne() = runBlocking<Unit> {
        val order = CopyOnWriteArrayList<String>()
        val session = FakeSession(onStream = { st -> st.feed(ack.acked("id-${session0.size + 1}") + "\n"); session0 += st })
        val streams = StatusStreams(client(session), scope, { emptySet() }, { }, { order += "ended:$it" })
        streams.update(setOf("w1:p1"))
        val first = session.streams[0]
        streams.update(setOf("w1:p1", "w1:p2"))
        val second = session.streams[1]
        assertEquals(setOf("w1:p1", "w1:p2"), panesOf(second))
        withTimeout(2_000) { while (!first.closed) delay(5) }
        assertTrue(!second.closed)
        assertEquals(setOf("w1:p1", "w1:p2"), streams.coveredPanes())
        streams.update(setOf("w1:p1", "w1:p2"))                  // unchanged: no third stream
        assertEquals(2, session.streams.size)
        streams.stop(); withTimeout(2_000) { while (!second.closed) delay(5) }
        assertEquals(emptyList(), order)                         // cancellation is not an "ended" report
    }
    private val session0 = CopyOnWriteArrayList<FakeStream>()

    @Test fun aRefusedSetIsRetriedOnceWithAFreshPaneSet() = runBlocking<Unit> {
        var call = 0
        val session = FakeSession(onStream = { st -> call++; st.feed((if (call == 1) refusal else ack.acked("id-$call")) + "\n") })
        val ended = CopyOnWriteArrayList<Throwable>()
        val streams = StatusStreams(client(session), scope, { setOf("w1:p1") }, { }, { ended += it })
        streams.update(setOf("w1:p1", "w1:p9"))                  // p9 closed meanwhile
        assertEquals(2, session.streams.size)
        assertEquals(setOf("w1:p1"), panesOf(session.streams[1]))
        assertEquals(setOf("w1:p1"), streams.coveredPanes())
        assertTrue(ended.isEmpty())
        streams.stop()
    }

    @Test fun aSecondRefusalIsReportedAndNothingIsCovered() = runBlocking<Unit> {
        val session = FakeSession(onStream = { it.feed(refusal + "\n") })
        val ended = CopyOnWriteArrayList<Throwable>()
        val streams = StatusStreams(client(session), scope, { setOf("w1:p1") }, { }, { ended += it })
        streams.update(setOf("w1:p1", "w1:p9"))
        assertEquals(1, ended.size); assertIs<HerdrError>(ended[0])
        assertEquals(emptySet(), streams.coveredPanes())
    }

    @Test fun anEmptyPaneSetClosesTheStreamWithoutOpeningAnother() = runBlocking<Unit> {
        val session = FakeSession(onStream = { it.feed(ack.acked("id-${n + 1}") + "\n") })
        val streams = StatusStreams(client(session), scope, { emptySet() }, { }, { })
        streams.update(setOf("w1:p1")); val first = session.streams[0]
        streams.update(emptySet())
        withTimeout(2_000) { while (!first.closed) delay(5) }
        assertEquals(1, session.streams.size)
    }

    @Test fun aPromotedStreamThatDropsIsReportedSoTheOwnerCanReconcile() = runBlocking<Unit> {
        val ended = CopyOnWriteArrayList<Throwable>()
        val session = FakeSession(onStream = { it.feed(ack.acked("id-${n + 1}") + "\n") })
        val streams = StatusStreams(client(session), scope, { emptySet() }, { }, { ended += it })
        streams.update(setOf("w1:p1"))
        session.streams[0].end()
        withTimeout(2_000) { while (ended.isEmpty()) delay(5) }
        assertIs<RelayUnavailable>(ended[0])
    }

    @Test fun eventsReachTheCallbackAsMappedOutcomes() = runBlocking<Unit> {
        val got = CopyOnWriteArrayList<EventOutcome>()
        val session = FakeSession(onStream = { it.feed(ack.acked("id-${n + 1}") + "\n" + """{"event":"pane.agent_status_changed","data":{"pane_id":"w1:p1","agent_status":"blocked"}}""" + "\n") })
        val streams = StatusStreams(client(session), scope, { emptySet() }, { got += it }, { })
        streams.update(setOf("w1:p1"))
        withTimeout(2_000) { while (got.isEmpty()) delay(5) }
        assertIs<EventOutcome.Status>(got[0]); streams.stop()
    }
}
