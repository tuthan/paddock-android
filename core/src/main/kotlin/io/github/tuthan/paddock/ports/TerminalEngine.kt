package io.github.tuthan.paddock.ports

import io.github.tuthan.paddock.terminal.Cursor
import io.github.tuthan.paddock.terminal.Grid

/**
 * Turns the bytes of herdr's `terminal.frame` records into cells. The engine only reads: it never writes to the host,
 * answers no query, opens no link, touches no clipboard and sets no title, whatever the bytes ask for. Anything it does
 * not understand is dropped, never shown as text.
 *
 * [feed] takes one self-contained chunk (a frame): a sequence left open at the end of it is discarded, and only the
 * screen, the pen and the modes carry into the next call. Not thread-safe; the caller serializes.
 */
interface TerminalEngine {
    val cols: Int
    val rows: Int
    /** Whether the engine was told to switch to the alternate screen. herdr repaints instead of sending the switch, so this stays false for its frames. */
    val altScreen: Boolean
    val cursor: Cursor

    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset)

    /** Changes the viewport. Cells that still fit keep their content; the cursor is clamped. */
    fun resize(cols: Int, rows: Int)

    /** Back to a blank screen with the cursor at the top, the default pen and modes. Frames and grids are never kept. */
    fun clear()

    fun grid(): Grid
}
