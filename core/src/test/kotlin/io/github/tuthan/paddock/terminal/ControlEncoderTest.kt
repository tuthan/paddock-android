package io.github.tuthan.paddock.terminal

import java.util.Base64
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class ControlEncoderTest {
    private fun obj(bytes: ByteArray): JsonObject {
        val text = bytes.toString(Charsets.UTF_8)
        assertTrue(text.endsWith("\n") && text.count { it == '\n' } == 1, "exactly one newline-terminated line: $text")
        return Json.parseToJsonElement(text.trimEnd()).jsonObject
    }

    @Test fun inputIsBase64BytesNeverText() {
        val raw = byteArrayOf(0x1B, 0x5B, 0x41, 0x0D, 0x03, 0xC3.toByte(), 0xA9.toByte(), 0x00)
        val o = obj(ControlEncoder.input(raw))
        assertEquals("terminal.input", o["type"]!!.jsonPrimitive.content)
        assertContentEquals(raw, Base64.getDecoder().decode(o["bytes"]!!.jsonPrimitive.content))
        assertTrue("text" !in o, "herdr refuses text and bytes together; Paddock only ever sends bytes")
    }

    @Test fun inputWithNewlinesAndQuotesStaysOneLine() {
        val o = obj(ControlEncoder.input("line1\nline2 \"quoted\"\r".toByteArray()))
        assertEquals("line1\nline2 \"quoted\"\r", Base64.getDecoder().decode(o["bytes"]!!.jsonPrimitive.content).toString(Charsets.UTF_8))
    }

    @Test fun inputIsBounded() {
        assertFailsWith<IllegalArgumentException> { ControlEncoder.input(ByteArray(0)) }
        ControlEncoder.input(ByteArray(TerminalLimits.INPUT_BYTES))
        assertFailsWith<IllegalArgumentException> { ControlEncoder.input(ByteArray(TerminalLimits.INPUT_BYTES + 1)) }
    }

    @Test fun resizeCarriesColsAndRows() {
        val o = obj(ControlEncoder.resize(97, 31))
        assertEquals("terminal.resize", o["type"]!!.jsonPrimitive.content)
        assertEquals(97, o["cols"]!!.jsonPrimitive.int); assertEquals(31, o["rows"]!!.jsonPrimitive.int)
        for ((c, r) in listOf(0 to 10, 10 to 0, -1 to 5, 1001 to 10, 10 to 501)) assertFailsWith<IllegalArgumentException>("$c x $r") { ControlEncoder.resize(c, r) }
    }

    @Test fun scrollNamesDirectionSourceAndLines() {
        val o = obj(ControlEncoder.scroll(ScrollDirection.Up, 5))
        assertEquals("terminal.scroll", o["type"]!!.jsonPrimitive.content)
        assertEquals("up", o["direction"]!!.jsonPrimitive.content); assertEquals(5, o["lines"]!!.jsonPrimitive.int)
        assertEquals("wheel", o["source"]!!.jsonPrimitive.content)
        assertEquals("page_key", obj(ControlEncoder.scroll(ScrollDirection.Down, 1, ScrollSource.PageKey))["source"]!!.jsonPrimitive.content)
        assertFailsWith<IllegalArgumentException> { ControlEncoder.scroll(ScrollDirection.Up, 0) }
        assertFailsWith<IllegalArgumentException> { ControlEncoder.scroll(ScrollDirection.Up, 1001) }
    }

    @Test fun mouseNamesActionButtonAndCell() {
        val o = obj(ControlEncoder.mouse(MouseAction.Down, MouseButton.Right, 12, 3, 4))
        assertEquals("terminal.mouse", o["type"]!!.jsonPrimitive.content)
        assertEquals("down", o["action"]!!.jsonPrimitive.content); assertEquals("right", o["button"]!!.jsonPrimitive.content)
        assertEquals(12, o["column"]!!.jsonPrimitive.int); assertEquals(3, o["row"]!!.jsonPrimitive.int); assertEquals(4, o["modifiers"]!!.jsonPrimitive.int)
        assertFailsWith<IllegalArgumentException> { ControlEncoder.mouse(MouseAction.Move, MouseButton.Left, -1, 0) }
        assertFailsWith<IllegalArgumentException> { ControlEncoder.mouse(MouseAction.Move, MouseButton.Left, 0, 0, 256) }
    }

    @Test fun releaseIsJustItsType() {
        assertEquals("""{"type":"terminal.release"}""" + "\n", ControlEncoder.release().toString(Charsets.UTF_8))
    }
}
