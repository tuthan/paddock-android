package io.github.tuthan.paddock.terminal

/**
 * A colour as the terminal asked for it: the default, one of the 256 indexed colours, or 24-bit. Packed in an Int so a
 * grid is a few flat arrays; the UI maps indexed 0 to 15 to the theme (never to `faint`) and computes the rest.
 */
@JvmInline
value class TermColor(val packed: Int) {
    val isDefault: Boolean get() = packed == 0
    val isIndexed: Boolean get() = (packed ushr 24) == 1
    val isRgb: Boolean get() = (packed ushr 24) == 2
    /** 0 to 255 when [isIndexed]. */
    val index: Int get() = packed and 0xFF
    /** `0xRRGGBB` when [isRgb]. */
    val rgb: Int get() = packed and 0xFFFFFF

    companion object {
        val Default = TermColor(0)
        fun indexed(i: Int) = TermColor((1 shl 24) or (i and 0xFF))
        fun rgb(r: Int, g: Int, b: Int) = TermColor((2 shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF))
    }
}

/** Bits of a cell's flags. [WIDE] marks the first half of a double-width character and [CONTINUATION] its second half. */
object CellFlags {
    const val BOLD = 1
    const val DIM = 2
    const val ITALIC = 4
    const val UNDERLINE = 8
    const val BLINK = 16
    const val REVERSE = 32
    const val HIDDEN = 64
    const val STRIKE = 128
    const val WIDE = 256
    const val CONTINUATION = 512
    /** The bits a pen sets; the structural bits above them are the grid's. */
    const val STYLE_MASK = 255
}

data class Cursor(val row: Int, val col: Int, val visible: Boolean)

/**
 * An immutable snapshot of the visible cells. Rows and columns count from 0. A blank cell holds " "; the second half of
 * a double-width character holds "" and is flagged [CellFlags.CONTINUATION]. A cell's text is one character, with any
 * combining marks that followed it.
 */
class Grid internal constructor(
    val cols: Int,
    val rows: Int,
    private val text: Array<String>,
    private val fg: IntArray,
    private val bg: IntArray,
    private val flags: IntArray,
) {
    private fun at(row: Int, col: Int): Int {
        require(row in 0 until rows && col in 0 until cols) { "cell ($row, $col) outside ${cols}x$rows" }
        return row * cols + col
    }

    fun text(row: Int, col: Int): String = text[at(row, col)]
    fun fg(row: Int, col: Int): TermColor = TermColor(fg[at(row, col)])
    fun bg(row: Int, col: Int): TermColor = TermColor(bg[at(row, col)])
    fun flags(row: Int, col: Int): Int = flags[at(row, col)]
    fun isWide(row: Int, col: Int): Boolean = flags(row, col) and CellFlags.WIDE != 0
    fun isContinuation(row: Int, col: Int): Boolean = flags(row, col) and CellFlags.CONTINUATION != 0
    fun isBlank(row: Int, col: Int): Boolean = text(row, col) == " "

    /** The row's text without trailing blanks; a double-width character appears once. */
    fun rowText(row: Int): String {
        val sb = StringBuilder()
        for (c in 0 until cols) sb.append(text[at(row, c)])
        var end = sb.length
        while (end > 0 && sb[end - 1] == ' ') end--
        return sb.substring(0, end)
    }

    companion object {
        fun blank(cols: Int, rows: Int): Grid = Grid(cols, rows, Array(cols * rows) { " " }, IntArray(cols * rows), IntArray(cols * rows), IntArray(cols * rows))
    }
}
