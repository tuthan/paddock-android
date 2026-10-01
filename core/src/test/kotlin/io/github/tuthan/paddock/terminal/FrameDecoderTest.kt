package io.github.tuthan.paddock.terminal

import java.io.File
import java.util.Base64
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test

/** Slice 2: the Phase 00 frame records decode, the sequence rules hold, and a hostile record is refused before it is shown. */
class FrameDecoderTest {
    private val dir = File(System.getProperty("paddock.repoRoot"), "fixtures/herdr-0.9.1")
    private fun fixture(name: String) = File(dir, "frames-$name.jsonl").readLines().filter { it.isNotBlank() }
    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())
    private fun frame(seq: Long, full: Boolean = false, bytes: String = "x", w: Int = 80, h: Int = 24, encoding: String = "ansi") =
        """{"type":"terminal.frame","seq":$seq,"encoding":"$encoding","full":$full,"width":$w,"height":$h,"bytes":"${b64(bytes)}"}"""
    private fun decoded(line: String) = assertIs<TerminalRecord.Frame>(FrameDecoder.decode(line))

    @Test fun everyRecordOfTheFiveFixturesDecodesAsAFrameInOrder() {
        for (name in listOf("colours", "wide", "cursor", "scroll", "altscreen")) {
            val frames = fixture(name).map { decoded(it) }
            assertTrue(frames.size >= 4, name)
            assertTrue(frames[0].full, "$name: the stream starts with a full frame")
            assertTrue(frames.drop(1).none { it.full }, "$name: the rest are patches")
            assertEquals(frames.indices.map { it + 1L }, frames.map { it.seq }, name)
            assertTrue(frames.all { it.width == 80 && it.height == 24 && it.bytes.isNotEmpty() }, name)
            // The sequencer accepts exactly this stream.
            val sequencer = FrameSequencer()
            assertTrue(frames.all { sequencer.accept(it) == FrameSequencer.Verdict.Apply }, name)
        }
    }

    @Test fun theFullFrameStartsWithTheSynchronizedUpdateAndAClear() {
        val first = decoded(fixture("colours")[0]).bytes.toString(Charsets.ISO_8859_1)
        assertTrue(first.startsWith("\u001B[?2026h\u001B[?25l"), "synchronized update begins, cursor hidden")
        assertTrue("\u001B[2J" in first, "a full frame clears the screen")
    }

    @Test fun aClosedRecordCarriesHerdrsReason() {
        val r = FrameDecoder.decode("""{"type":"terminal.closed","reason":"terminal attach failed: terminal term_1 already has an attached client; retry with --takeover"}""")
        assertEquals("terminal attach failed: terminal term_1 already has an attached client; retry with --takeover", assertIs<TerminalRecord.Closed>(r).reason)
        assertEquals("detached", assertIs<TerminalRecord.Closed>(FrameDecoder.decode("""{"type":"terminal.closed","reason":"detached"}""")).reason)
        assertEquals("", assertIs<TerminalRecord.Closed>(FrameDecoder.decode("""{"type":"terminal.closed"}""")).reason)
    }

    @Test fun anUnknownTypeIsSkippedNotRefused() {
        assertEquals("terminal.graphics", assertIs<TerminalRecord.Unknown>(FrameDecoder.decode("""{"type":"terminal.graphics","x":1}""")).type)
        assertEquals("", assertIs<TerminalRecord.Unknown>(FrameDecoder.decode("""{"hello":1}""")).type)
    }

    @Test fun unknownFieldsOnAFrameAreIgnored() {
        val line = frame(1, full = true).replace("}", ""","future":{"a":[1]}}""")
        assertEquals(1L, decoded(line).seq)
    }

    @Test fun missingOrWrongFieldsAreProtocolErrors() {
        fun field(line: String) = assertFailsWith<TerminalProtocolError.MissingField> { FrameDecoder.decode(line) }.field
        assertEquals("seq", field("""{"type":"terminal.frame","encoding":"ansi","full":true,"width":8,"height":2,"bytes":""}"""))
        assertEquals("full", field("""{"type":"terminal.frame","seq":1,"encoding":"ansi","width":8,"height":2,"bytes":""}"""))
        assertEquals("width", field("""{"type":"terminal.frame","seq":1,"encoding":"ansi","full":true,"height":2,"bytes":""}"""))
        assertEquals("height", field("""{"type":"terminal.frame","seq":1,"encoding":"ansi","full":true,"width":8,"bytes":""}"""))
        assertEquals("bytes", field("""{"type":"terminal.frame","seq":1,"encoding":"ansi","full":true,"width":8,"height":2}"""))
        assertEquals("encoding", field("""{"type":"terminal.frame","seq":1,"full":true,"width":8,"height":2,"bytes":""}"""))
        // A number where a string belongs (and the reverse) is the same failure, not a crash.
        assertEquals("bytes", field("""{"type":"terminal.frame","seq":1,"encoding":"ansi","full":true,"width":8,"height":2,"bytes":7}"""))
        assertEquals("seq", field("""{"type":"terminal.frame","seq":"1","encoding":"ansi","full":true,"width":8,"height":2,"bytes":""}"""))
        assertEquals("width", field("""{"type":"terminal.frame","seq":1,"encoding":"ansi","full":true,"width":-3,"height":2,"bytes":""}"""))
    }

    @Test fun anEncodingThatIsNotAnsiIsRefused() {
        assertEquals("sixel", assertFailsWith<TerminalProtocolError.UnsupportedEncoding> { FrameDecoder.decode(frame(1, true, encoding = "sixel")) }.encoding)
    }

    @Test fun geometryOutOfRangeIsRefused() {
        for ((w, h) in listOf(0 to 24, 80 to 0, 1001 to 24, 80 to 501, 1000 to 500)) {
            assertFailsWith<TerminalProtocolError.BadGeometry>("$w x $h") { FrameDecoder.decode(frame(1, true, w = w, h = h)) }
        }
        decoded(frame(1, true, w = 1000, h = 250)) // exactly the cell budget
    }

    @Test fun notJsonAndNotAnObjectAreProtocolErrors() {
        assertFailsWith<TerminalProtocolError.NotJson> { FrameDecoder.decode("not json") }
        assertFailsWith<TerminalProtocolError.NotJson> { FrameDecoder.decode("[1,2]") }
        assertFailsWith<TerminalProtocolError.NotJson> { FrameDecoder.decode("") }
    }

    @Test fun badBase64IsRefused() {
        val line = """{"type":"terminal.frame","seq":1,"encoding":"ansi","full":true,"width":8,"height":2,"bytes":"@@@@"}"""
        assertFailsWith<TerminalProtocolError.BadBase64> { FrameDecoder.decode(line) }
    }

    @Test fun aFrameOverTheByteBudgetIsRefusedBeforeItIsDecoded() {
        val big = Base64.getEncoder().encodeToString(ByteArray(2000))
        val line = """{"type":"terminal.frame","seq":1,"encoding":"ansi","full":true,"width":8,"height":2,"bytes":"$big"}"""
        val e = assertFailsWith<TerminalProtocolError.TooLarge> { FrameDecoder.decode(line, maxFrameBytes = 1000) }
        assertEquals(1000, e.limit)
        // At the limit it passes, one byte over does not.
        val exact = Base64.getEncoder().encodeToString(ByteArray(1000))
        val atLimit = FrameDecoder.decode("""{"type":"terminal.frame","seq":1,"encoding":"ansi","full":true,"width":8,"height":2,"bytes":"$exact"}""", maxFrameBytes = 1000)
        assertEquals(1000, assertIs<TerminalRecord.Frame>(atLimit).bytes.size)
        assertFailsWith<TerminalProtocolError.TooLarge> {
            FrameDecoder.decode("""{"type":"terminal.frame","seq":1,"encoding":"ansi","full":true,"width":8,"height":2,"bytes":"${Base64.getEncoder().encodeToString(ByteArray(1001))}"}""", maxFrameBytes = 1000)
        }
    }

    @Test fun theLineBudgetFitsAFrameAtTheByteBudget() {
        val max = Base64.getEncoder().encodeToString(ByteArray(TerminalLimits.FRAME_BYTES))
        assertTrue(max.length + 200 < TerminalLimits.LINE_BYTES, "a maximal frame's line fits the line budget")
    }

    @Test fun framesKeepTheirBytesExactly() {
        val raw = byteArrayOf(0x1B, '['.code.toByte(), '2'.code.toByte(), 'J'.code.toByte(), 0x00, 0xFF.toByte())
        val line = """{"type":"terminal.frame","seq":9,"encoding":"ansi","full":false,"width":4,"height":2,"bytes":"${Base64.getEncoder().encodeToString(raw)}"}"""
        assertContentEquals(raw, decoded(line).bytes)
    }

    // --- sequence rules ---

    private fun f(seq: Long, full: Boolean = false) = TerminalRecord.Frame(seq, full, 80, 24, ByteArray(0))

    @Test fun theFirstFrameMustBeFull() {
        val s = FrameSequencer()
        assertIs<FrameSequencer.Verdict.Resync>(s.accept(f(1)))
        assertEquals(FrameSequencer.Verdict.Apply, s.accept(f(1, full = true)))
    }

    @Test fun framesMustFollowInOrderAndAGapAsksForAResync() {
        val s = FrameSequencer()
        assertEquals(FrameSequencer.Verdict.Apply, s.accept(f(7, full = true)))
        assertEquals(FrameSequencer.Verdict.Apply, s.accept(f(8)))
        val gap = assertIs<FrameSequencer.Verdict.Resync>(s.accept(f(10)))
        assertTrue("9" in gap.why && "missed" in gap.why, gap.why)
    }

    @Test fun aRepeatIsDroppedAndAReversalAsksForAResync() {
        val s = FrameSequencer()
        s.accept(f(1, full = true)); s.accept(f(2)); s.accept(f(3))
        assertEquals(FrameSequencer.Verdict.Drop, s.accept(f(3)))
        assertEquals(FrameSequencer.Verdict.Apply, s.accept(f(4)))
        assertIs<FrameSequencer.Verdict.Resync>(s.accept(f(2)))
    }

    @Test fun aFullFrameReplacesEverythingEvenAcrossAGap() {
        val s = FrameSequencer()
        s.accept(f(1, full = true))
        assertEquals(FrameSequencer.Verdict.Apply, s.accept(f(50, full = true)))
        assertEquals(FrameSequencer.Verdict.Apply, s.accept(f(51)))
    }

    @Test fun resetStartsANewStream() {
        val s = FrameSequencer()
        s.accept(f(5, full = true)); s.accept(f(6))
        s.reset()
        assertFalse(s.accept(f(7)) == FrameSequencer.Verdict.Apply, "a patch cannot start a stream")
        assertEquals(FrameSequencer.Verdict.Apply, s.accept(f(1, full = true)))
    }
}
