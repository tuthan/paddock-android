package io.github.tuthan.paddock.terminal

import java.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class ScrollDirection(val wire: String) { Up("up"), Down("down") }
/** `wheel` scrolls herdr's scrollback; `page_key` sends Page Up or Page Down to the application. */
enum class ScrollSource(val wire: String) { Wheel("wheel"), PageKey("page_key") }
enum class MouseAction(val wire: String) { Down("down"), Up("up"), Drag("drag"), Move("move") }
enum class MouseButton(val wire: String) { Left("left"), Right("right"), Middle("middle") }

/**
 * The commands `herdr terminal session control` reads on stdin, one JSON object per line. Shapes are herdr's
 * (`terminal_sessions.rs` in 0.9.3, checked against the installed 0.9.1 in the Phase 05 integration run). Input always
 * travels as base64 bytes, never as `text`, so a key press and a paste are the same record and nothing is interpreted
 * on the way. Out-of-range arguments are caller bugs and throw [IllegalArgumentException] before anything is written.
 */
object ControlEncoder {
    fun input(bytes: ByteArray): ByteArray {
        require(bytes.isNotEmpty()) { "empty input" }
        require(bytes.size <= TerminalLimits.INPUT_BYTES) { "input of ${bytes.size} bytes exceeds ${TerminalLimits.INPUT_BYTES}" }
        return line(buildJsonObject { put("type", "terminal.input"); put("bytes", Base64.getEncoder().encodeToString(bytes)) })
    }

    fun resize(cols: Int, rows: Int): ByteArray {
        require(TerminalLimits.validGeometry(cols, rows)) { "geometry ${cols}x$rows out of range" }
        return line(buildJsonObject { put("type", "terminal.resize"); put("cols", cols); put("rows", rows) })
    }

    fun scroll(direction: ScrollDirection, lines: Int, source: ScrollSource = ScrollSource.Wheel): ByteArray {
        require(lines in 1..MAX_SCROLL_LINES) { "scroll of $lines lines out of range" }
        return line(buildJsonObject { put("type", "terminal.scroll"); put("direction", direction.wire); put("lines", lines); put("source", source.wire) })
    }

    /**
     * [column] and [row] address a cell of the controller's viewport. Whether herdr counts from 0 or 1 is not verified.
     * The installed herdr 0.9.1 rejects `terminal.mouse` (it arrived in 0.9.3), so nothing in the app sends it yet.
     */
    fun mouse(action: MouseAction, button: MouseButton, column: Int, row: Int, modifiers: Int = 0): ByteArray {
        require(column in 0..MAX_CELL && row in 0..MAX_CELL && modifiers in 0..255) { "mouse arguments out of range" }
        return line(buildJsonObject {
            put("type", "terminal.mouse"); put("action", action.wire); put("button", button.wire)
            put("column", column); put("row", row); put("modifiers", modifiers)
        })
    }

    /** Ends the control session. herdr answers with `terminal.closed` (reason `detached`) and the process exits. */
    fun release(): ByteArray = line(buildJsonObject { put("type", "terminal.release") })

    private fun line(o: JsonObject): ByteArray = (o.toString() + "\n").toByteArray(Charsets.UTF_8)

    private const val MAX_SCROLL_LINES = 1000
    private const val MAX_CELL = 65_535
}
