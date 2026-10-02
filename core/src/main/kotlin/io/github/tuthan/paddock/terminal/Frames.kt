package io.github.tuthan.paddock.terminal

import java.util.Base64
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import io.github.tuthan.paddock.herdr.PaddockJson

/** One record on the stdout of `herdr terminal session observe` or `control`. */
sealed interface TerminalRecord {
    /**
     * A repaint of the observer's viewport as ANSI bytes. [full] frames start from a cleared screen; the others patch the
     * cells that changed since the previous frame. [width] and [height] are the viewport the frame was drawn for.
     */
    class Frame(val seq: Long, val full: Boolean, val width: Int, val height: Int, val bytes: ByteArray) : TerminalRecord

    /** herdr closed the stream and said why (`detached`, a refused attach, a taken-over controller). Text from herdr, shown as data. */
    data class Closed(val reason: String) : TerminalRecord

    /** A record type this client does not know. Skipped, never shown. */
    data class Unknown(val type: String) : TerminalRecord
}

/** Why a record cannot be used. A stream that produces one is closed and reopened; nothing from the record is shown. */
sealed class TerminalProtocolError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NotJson(cause: Throwable) : TerminalProtocolError("terminal record is not JSON", cause)
    class MissingField(val field: String) : TerminalProtocolError("terminal record has no usable '$field'")
    class UnsupportedEncoding(val encoding: String) : TerminalProtocolError("terminal frame encoding '$encoding' is not supported")
    class TooLarge(val size: Int, val limit: Int) : TerminalProtocolError("terminal frame of $size bytes exceeds the $limit byte budget")
    class BadBase64(cause: Throwable) : TerminalProtocolError("terminal frame bytes are not base64", cause)
    class BadGeometry(val width: Int, val height: Int) : TerminalProtocolError("terminal frame geometry ${width}x$height is out of range")
}

/** Size limits for a terminal stream. A phone viewport is far below all of them. */
object TerminalLimits {
    /** Decoded bytes of one frame. */
    const val FRAME_BYTES = 1 shl 20
    /** The longest JSON line: base64 of a full frame plus the envelope. */
    const val LINE_BYTES = (FRAME_BYTES / 3 + 1) * 4 + 4096
    /**
     * Lines read from the host but not yet drawn. Each can be [LINE_BYTES] at the very most, usually a few kilobytes, so the
     * queue holds a few megabytes at its worst and the stream waits on the host beyond that.
     */
    const val STREAM_QUEUE_LINES = 16
    const val MAX_COLS = 1000
    const val MAX_ROWS = 500
    const val MAX_CELLS = 250_000
    /** Bytes in one `terminal.input` record; a longer paste is split by the caller. */
    const val INPUT_BYTES = 16 shl 10

    fun validGeometry(cols: Int, rows: Int) = cols in 1..MAX_COLS && rows in 1..MAX_ROWS && cols.toLong() * rows <= MAX_CELLS
}

/** Decodes one JSON line into a [TerminalRecord]. */
object FrameDecoder {
    fun decode(line: String, maxFrameBytes: Int = TerminalLimits.FRAME_BYTES): TerminalRecord {
        val obj: JsonObject = try { PaddockJson.parseToJsonElement(line).jsonObject }
            catch (e: SerializationException) { throw TerminalProtocolError.NotJson(e) }
            catch (e: IllegalArgumentException) { throw TerminalProtocolError.NotJson(e) }
        return when (val type = obj.str("type")) {
            "terminal.frame" -> frame(obj, maxFrameBytes)
            "terminal.closed" -> TerminalRecord.Closed(obj.str("reason") ?: "")
            else -> TerminalRecord.Unknown(type ?: "")
        }
    }

    private fun frame(o: JsonObject, maxFrameBytes: Int): TerminalRecord.Frame {
        val encoding = o.str("encoding") ?: throw TerminalProtocolError.MissingField("encoding")
        if (encoding != "ansi") throw TerminalProtocolError.UnsupportedEncoding(encoding.take(32))
        val seq = o.long("seq") ?: throw TerminalProtocolError.MissingField("seq")
        val full = (o["full"] as? JsonPrimitive)?.booleanOrNull ?: throw TerminalProtocolError.MissingField("full")
        val width = o.long("width")?.takeIf { it in 0..Int.MAX_VALUE } ?: throw TerminalProtocolError.MissingField("width")
        val height = o.long("height")?.takeIf { it in 0..Int.MAX_VALUE } ?: throw TerminalProtocolError.MissingField("height")
        if (!TerminalLimits.validGeometry(width.toInt(), height.toInt())) throw TerminalProtocolError.BadGeometry(width.toInt(), height.toInt())
        val text = o.str("bytes") ?: throw TerminalProtocolError.MissingField("bytes")
        // Checked before decoding so a hostile record never allocates its claimed size.
        val decodedUpperBound = text.length / 4 * 3
        if (decodedUpperBound > maxFrameBytes + 2) throw TerminalProtocolError.TooLarge(decodedUpperBound, maxFrameBytes)
        val bytes = try { Base64.getDecoder().decode(text) } catch (e: IllegalArgumentException) { throw TerminalProtocolError.BadBase64(e) }
        if (bytes.size > maxFrameBytes) throw TerminalProtocolError.TooLarge(bytes.size, maxFrameBytes)
        return TerminalRecord.Frame(seq, full, width.toInt(), height.toInt(), bytes)
    }

    private fun JsonObject.str(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.long(key: String): Long? = (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
}

/**
 * Enforces the stream's order. The first frame must be a full one (the observer's own start); after that each frame is
 * the next sequence number. A frame that skips ahead means a repaint was lost, so what is on screen may be wrong and the
 * only safe move is to reconnect for a fresh full frame ([Resync]); a repeat is dropped; a full frame replaces everything
 * and resets the expectation. Reset it with each new stream.
 */
class FrameSequencer {
    sealed interface Verdict {
        data object Apply : Verdict
        data object Drop : Verdict
        data class Resync(val why: String) : Verdict
    }

    private var last: Long? = null

    fun reset() { last = null }

    fun accept(frame: TerminalRecord.Frame): Verdict {
        val previous = last
        if (previous == null) {
            if (!frame.full) return Verdict.Resync("the first frame was not a full frame")
            last = frame.seq
            return Verdict.Apply
        }
        if (frame.seq == previous) return Verdict.Drop
        if (frame.seq < previous) return Verdict.Resync("the frame sequence went backwards ($previous then ${frame.seq})")
        if (frame.full || frame.seq == previous + 1) { last = frame.seq; return Verdict.Apply }
        return Verdict.Resync("frames ${previous + 1} to ${frame.seq - 1} were missed")
    }
}
