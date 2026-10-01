package io.github.tuthan.paddock.relay

import io.github.tuthan.paddock.herdr.Budgets
import io.github.tuthan.paddock.herdr.Envelope
import io.github.tuthan.paddock.herdr.EventMapper
import io.github.tuthan.paddock.herdr.EventOutcome
import io.github.tuthan.paddock.herdr.Message
import io.github.tuthan.paddock.herdr.ProtocolError
import io.github.tuthan.paddock.ports.SshSession
import java.io.ByteArrayOutputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** herdr answered with an error. [code] is herdr's (`agent_not_found`, `invalid_request`, …). */
class HerdrError(val code: String, message: String) : Exception("herdr error $code: $message")

/** The relay produced no line: the script is missing, the socket is gone, or the host dropped the stream. */
class RelayUnavailable(val exit: Int?) : Exception("relay ended without a response (exit ${exit ?: "unknown"})")

/** The host never acknowledged a subscription within the deadline. */
class SubscribeTimeout : Exception("subscription not acknowledged in time")

/** The stream of a subscription was closed by herdr's `events_lost`: the caller must mark itself stale and reconcile. */
class EventsLost : Exception("events lost")

/** Splits a byte stream into UTF-8 lines. A line that grows past [limit] bytes before its newline is a protocol error. */
class LineReader(private val limit: Int) {
    private val pending = ByteArrayOutputStream()

    fun feed(chunk: ByteArray): List<String> {
        val out = ArrayList<String>()
        var start = 0
        for (i in chunk.indices) {
            if (chunk[i] == NL) {
                pending.write(chunk, start, i - start)
                out += takeLine()
                start = i + 1
            }
        }
        pending.write(chunk, start, chunk.size - start)
        if (pending.size() > limit) throw ProtocolError.LineTooLong(pending.size(), limit)
        return out
    }

    private fun takeLine(): String {
        if (pending.size() > limit) throw ProtocolError.LineTooLong(pending.size(), limit)
        return pending.toByteArray().toString(Charsets.UTF_8).also { pending.reset() }
    }

    private companion object { const val NL = '\n'.code.toByte() }
}

fun Flow<ByteArray>.lines(limit: Int): Flow<String> = flow {
    val reader = LineReader(limit)
    collect { chunk -> reader.feed(chunk).forEach { emit(it) } }
}

/**
 * herdr's socket API through [paddock-relay.py](../../../../../../../../host/paddock-relay.py) over one SSH exec
 * channel per stream. Requests, including any free text in their params, travel on stdin as JSON; argv holds only
 * the interpreter, the relay path and the socket path.
 */
class RelayClient(
    private val session: SshSession,
    private val relayPath: String,
    private val socketPath: String,
    private val python: String = "python3",
    private val ids: () -> String = Counter()::next,
) {
    private val argv get() = listOf(python, relayPath, socketPath)

    /** One request on a fresh stream, as observed in 0.9.1: one request per connection, then EOF. */
    suspend fun call(method: String, params: JsonObject = JsonObject(emptyMap()), budget: Int = Budgets.LINE, timeout: Duration = 10.seconds): Message.Success {
        val id = ids()
        val channel = session.openStream(argv)
        try {
            return withTimeout(timeout) {
                channel.write(request(id, method, params))
                val line = try { channel.stdout.lines(budget).first() } catch (_: NoSuchElementException) { throw RelayUnavailable(runCatching { channel.awaitExit() }.getOrNull()) }
                when (val m = Envelope.parse(line, budget)) {
                    is Message.Success -> m.also { if (it.id != id) throw ProtocolError.UnknownShape(setOf("id:${it.id}")) }
                    is Message.Failure -> throw HerdrError(m.code, m.message)
                    is Message.Event -> throw ProtocolError.UnknownShape(setOf("event"))
                }
            }
        } finally { runCatching { channel.close() } }
    }

    /**
     * Opens a dedicated stream, waits for `subscription_started`, then emits mapped events until the stream ends.
     * The flow fails with [EventsLost] on `events_lost`, with [HerdrError] when the subscription is refused (for
     * example a status subscription without `pane_id`), with [SubscribeTimeout] when no acknowledgement arrives, and
     * with [RelayUnavailable] when the host stream drops. Cancelling the collector closes the channel.
     */
    fun subscribe(subscriptions: List<JsonObject>, ackTimeout: Duration = 10.seconds): Flow<EventOutcome> = flow {
        val id = ids()
        val channel = session.openStream(argv)
        try {
            channel.write(request(id, "events.subscribe", buildJsonObject { put("subscriptions", JsonArray(subscriptions)) }))
            var acknowledged = false
            coroutineScope {
                val watchdog = launch { delay(ackTimeout); if (!acknowledged) throw SubscribeTimeout() }
                // One collection of the stream: the first line is the acknowledgement, everything after is events.
                channel.stdout.lines(Budgets.LINE).collect { line ->
                    val m = Envelope.parse(line)
                    if (!acknowledged) {
                        when (m) {
                            is Message.Success -> if (m.type == "subscription_started") { acknowledged = true; watchdog.cancel() } else throw ProtocolError.WrongResultType("subscription_started", m.type)
                            is Message.Failure -> throw HerdrError(m.code, m.message)
                            is Message.Event -> throw ProtocolError.UnknownShape(setOf("event"))
                        }
                    } else when (m) {
                        is Message.Event -> when (val o = EventMapper.map(m)) { EventOutcome.EventsLost -> throw EventsLost(); else -> emit(o) }
                        is Message.Failure -> if (EventMapper.isEventsLost(m)) throw EventsLost() else throw HerdrError(m.code, m.message)
                        is Message.Success -> Unit
                    }
                }
                watchdog.cancel()
            }
            throw RelayUnavailable(runCatching { channel.awaitExit() }.getOrNull())
        } finally { runCatching { channel.close() } }
    }

    private fun request(id: String, method: String, params: JsonObject): ByteArray =
        (buildJsonObject { put("id", id); put("method", method); put("params", params) }.toString() + "\n").toByteArray(Charsets.UTF_8)

    private class Counter { private var n = 0; fun next() = "paddock-${++n}" }
}
