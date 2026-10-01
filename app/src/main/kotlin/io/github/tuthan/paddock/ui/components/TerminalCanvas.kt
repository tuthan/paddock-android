package io.github.tuthan.paddock.ui.components

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import io.github.tuthan.paddock.terminal.CellFlags
import io.github.tuthan.paddock.terminal.Cursor
import io.github.tuthan.paddock.terminal.Grid
import io.github.tuthan.paddock.ui.theme.PaddockColors
import io.github.tuthan.paddock.ui.theme.PaddockTokens
import io.github.tuthan.paddock.ui.theme.TermPalette
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** How many cells fit in a viewport of the given size at a text size, and the size of one cell. */
data class CellMetrics(val cellWidth: Float, val cellHeight: Float, val baseline: Float) {
    fun cols(viewWidth: Float) = max(1, floor(viewWidth / cellWidth).toInt())
    fun rows(viewHeight: Float) = max(1, floor(viewHeight / cellHeight).toInt())
}

/** Text sizes the pinch can reach, in sp. Below the smallest a cell is too small to read; above the largest it is a few characters wide. */
object TerminalText {
    const val MIN_SP = 6f
    const val MAX_SP = 28f
    /** The size a screen starts at: as large as lets the whole width fit, but never below what stays legible. */
    const val FIT_FLOOR_SP = 8f
    const val FIT_CEILING_SP = 14f
    const val STEP = 1.2f

    /** Width of one cell as a fraction of the text size, for the monospace face. */
    val widthPerSize: Float by lazy { Paint().apply { typeface = Typeface.MONOSPACE; textSize = 100f }.measureText("M") / 100f }

    fun fitSp(viewWidthPx: Float, cols: Int, pxPerSp: Float): Float =
        (viewWidthPx / (max(1, cols) * widthPerSize) / pxPerSp).coerceIn(FIT_FLOOR_SP, FIT_CEILING_SP)
}

/** What a pinch or pan in progress builds on. */
private class Gesture(var sp: Float) {
    var offset = Offset.Zero
    var cols = 0
    var rows = 0
    var cell = CellMetrics(1f, 1f, 1f)
    var view = IntSize.Zero
}

/** The faces and paints for one text size; made once per size, never per frame. */
private class Pens(private val textSizePx: Float) {
    private fun paint(style: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.MONOSPACE, style); textSize = textSizePx }
    val normal = paint(Typeface.NORMAL)
    val bold = paint(Typeface.BOLD)
    val italic = paint(Typeface.ITALIC)
    val boldItalic = paint(Typeface.BOLD_ITALIC)
    val line = Paint().apply { style = Paint.Style.FILL }
    val metrics = normal.fontMetrics.let { m ->
        CellMetrics(normal.measureText("M"), ceil(m.descent - m.ascent) + 1f, -m.ascent + 0.5f)
    }
    fun forFlags(flags: Int): Paint = when {
        flags and CellFlags.BOLD != 0 && flags and CellFlags.ITALIC != 0 -> boldItalic
        flags and CellFlags.BOLD != 0 -> bold
        flags and CellFlags.ITALIC != 0 -> italic
        else -> normal
    }
}

/**
 * The terminal's cells on the slab. Two fingers change the text size (cell size), one finger pans when the grid is larger
 * than the view, a double tap goes back to the starting size; none of it reaches the terminal. [textSizeSp] is the size in
 * use (the caller decides the starting one) and [onViewportCells] reports how many cells fit, for the cases where the
 * terminal's own size is unknown and the observer is drawn for the phone.
 */
@Composable
fun TerminalCanvas(
    grid: Grid?,
    cursor: Cursor?,
    textSizeSp: Float,
    onTextSizeSp: (Float) -> Unit,
    onResetTextSize: () -> Unit,
    onViewportCells: (cols: Int, rows: Int) -> Unit,
    onViewSize: (widthPx: Float) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
) {
    val colors = PaddockTokens.colors
    val density = LocalDensity.current
    val textSizePx = with(density) { textSizeSp.sp.toPx() }
    val pens = remember(textSizePx) { Pens(textSizePx) }
    var view by remember { mutableStateOf(IntSize.Zero) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val m = pens.metrics

    fun clamp(o: Offset, cols: Int, rows: Int, cell: CellMetrics, size: IntSize): Offset {
        val minX = min(0f, size.width - cols * cell.cellWidth)
        val minY = min(0f, size.height - rows * cell.cellHeight)
        return Offset(o.x.coerceIn(minX, 0f), o.y.coerceIn(minY, 0f))
    }

    val cols = grid?.cols ?: 0
    val rows = grid?.rows ?: 0
    // A gesture delivers several events between two frames, so the size and the offset it builds on are kept here and not
    // read back from state that only changes on the next recomposition.
    val g = remember { Gesture(textSizeSp) }
    g.sp = textSizeSp
    g.cols = cols; g.rows = rows; g.cell = m; g.view = view
    val shown = clamp(offset, cols, rows, m, view)
    g.offset = shown

    androidx.compose.runtime.LaunchedEffect(view, m, grid == null) {
        if (view.width > 0 && view.height > 0) {
            onViewSize(view.width.toFloat())
            onViewportCells(m.cols(view.width.toFloat()), m.rows(view.height.toFloat()))
        }
    }

    fun zoomTo(newSp: Float, around: Offset) {
        val target = newSp.coerceIn(TerminalText.MIN_SP, TerminalText.MAX_SP)
        if (target == g.sp) return
        val ratio = target / g.sp
        g.sp = target
        g.cell = CellMetrics(g.cell.cellWidth * ratio, g.cell.cellHeight * ratio, g.cell.baseline * ratio)
        g.offset = clamp(around - (around - g.offset) * ratio, g.cols, g.rows, g.cell, g.view)
        offset = g.offset
        onTextSizeSp(target)
    }

    Canvas(
        modifier
            .fillMaxSize()
            .onSizeChanged { view = it }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    if (zoom != 1f) zoomTo(g.sp * zoom, centroid)
                    g.offset = clamp(g.offset + pan, g.cols, g.rows, g.cell, g.view)
                    offset = g.offset
                }
            }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { g.offset = Offset.Zero; offset = Offset.Zero; onResetTextSize() }) }
            .semantics {
                contentDescription = description
                stateDescription = "Text size ${textSizeSp.roundToInt()}"
                customActions = listOf(
                    CustomAccessibilityAction("Larger text") { zoomTo(g.sp * TerminalText.STEP, Offset.Zero); true },
                    CustomAccessibilityAction("Smaller text") { zoomTo(g.sp / TerminalText.STEP, Offset.Zero); true },
                    CustomAccessibilityAction("Fit text to the screen") { g.offset = Offset.Zero; offset = Offset.Zero; onResetTextSize(); true },
                )
            },
    ) {
        drawIntoCanvas { canvas ->
            if (grid == null) return@drawIntoCanvas
            val n = canvas.nativeCanvas
            n.save()
            n.clipRect(0f, 0f, size.width, size.height)
            n.translate(shown.x, shown.y)
            drawGrid(n, grid, cursor, pens, colors, size.width - shown.x, size.height - shown.y, -shown.x, -shown.y, dimmed)
            n.restore()
        }
    }
}

/** Draws the cells that intersect the region from ([left], [top]) to ([right], [bottom]) in grid pixels. */
private fun drawGrid(
    n: android.graphics.Canvas, grid: Grid, cursor: Cursor?, pens: Pens, colors: PaddockColors,
    right: Float, bottom: Float, left: Float, top: Float, dimmed: Boolean,
) {
    val cw = pens.metrics.cellWidth
    val ch = pens.metrics.cellHeight
    val firstRow = max(0, floor(top / ch).toInt())
    val lastRow = min(grid.rows - 1, floor(bottom / ch).toInt())
    val firstCol = max(0, floor(left / cw).toInt())
    val lastCol = min(grid.cols - 1, floor(right / cw).toInt())
    val fade = if (dimmed) 0.55f else 1f
    val ground = colors.slab
    for (r in firstRow..lastRow) {
        val y = r * ch
        var c = firstCol
        // A wide character that starts left of the view still draws its right half inside it.
        if (c > 0 && grid.isContinuation(r, c)) c--
        while (c <= lastCol) {
            if (grid.isContinuation(r, c)) { c++; continue }
            val flags = grid.flags(r, c)
            val fg = grid.fg(r, c)
            val bg = grid.bg(r, c)
            val style = flags and CellFlags.STYLE_MASK
            val ascii = isAscii(grid.text(r, c)) && flags and CellFlags.WIDE == 0
            var end = c + 1
            if (ascii) {
                while (end <= lastCol && !grid.isContinuation(r, end) && grid.fg(r, end) == fg && grid.bg(r, end) == bg &&
                    (grid.flags(r, end) and CellFlags.STYLE_MASK) == style && grid.flags(r, end) and CellFlags.WIDE == 0 && isAscii(grid.text(r, end))
                ) end++
            } else {
                end = c + if (flags and CellFlags.WIDE != 0) 2 else 1
            }
            val span = end - c
            val reverse = style and CellFlags.REVERSE != 0
            var fgColor = if (reverse) (if (bg.isDefault) ground else TermPalette.color(colors, bg, ground)) else TermPalette.color(colors, fg, colors.text)
            val bgColor: Color? = if (reverse) TermPalette.color(colors, fg, colors.text) else if (bg.isDefault) null else TermPalette.color(colors, bg, ground)
            if (style and CellFlags.DIM != 0) fgColor = if (fg.isDefault && !reverse) colors.dim else fgColor.copy(alpha = fgColor.alpha * 0.6f)
            val x = c * cw
            if (bgColor != null) {
                pens.line.color = bgColor.copy(alpha = bgColor.alpha * fade).toArgb()
                n.drawRect(x, y, x + span * cw, y + ch, pens.line)
            }
            if (style and CellFlags.HIDDEN == 0) {
                val text = runText(grid, r, c, end, ascii)
                if (text.isNotBlank()) {
                    val paint = pens.forFlags(style)
                    paint.color = fgColor.copy(alpha = fgColor.alpha * fade).toArgb()
                    n.drawText(text, x, y + pens.metrics.baseline, paint)
                }
            }
            if (style and CellFlags.UNDERLINE != 0) { pens.line.color = fgColor.copy(alpha = fgColor.alpha * fade).toArgb(); n.drawRect(x, y + ch - 2f, x + span * cw, y + ch - 1f, pens.line) }
            if (style and CellFlags.STRIKE != 0) { pens.line.color = fgColor.copy(alpha = fgColor.alpha * fade).toArgb(); n.drawRect(x, y + ch / 2f, x + span * cw, y + ch / 2f + 1f, pens.line) }
            c = end
        }
    }
    if (cursor != null && cursor.visible && cursor.row in 0 until grid.rows && cursor.col in 0 until grid.cols && !dimmed) {
        val wide = grid.isWide(cursor.row, cursor.col)
        val x = cursor.col * cw
        val y = cursor.row * ch
        pens.line.color = colors.text.copy(alpha = 0.85f).toArgb()
        n.drawRect(x, y, x + (if (wide) 2 else 1) * cw, y + ch, pens.line)
        val under = grid.text(cursor.row, cursor.col)
        if (under.isNotBlank()) {
            val paint = pens.forFlags(grid.flags(cursor.row, cursor.col) and CellFlags.STYLE_MASK)
            paint.color = ground.toArgb()
            n.drawText(under, x, y + pens.metrics.baseline, paint)
        }
    }
}

private fun isAscii(s: String) = s.length == 1 && s[0].code in 0x20..0x7E

private fun runText(grid: Grid, row: Int, from: Int, to: Int, ascii: Boolean): String {
    if (!ascii) return grid.text(row, from)
    val sb = StringBuilder(to - from)
    for (i in from until to) sb.append(grid.text(row, i))
    return sb.toString()
}

/** What TalkBack reads for the screen: its size, who may type, and the text on it. */
fun terminalDescription(grid: Grid?, status: String): String {
    if (grid == null) return "Terminal. $status"
    val text = buildString {
        for (r in 0 until grid.rows) { val t = grid.rowText(r); if (t.isNotEmpty()) { if (isNotEmpty()) append('\n'); append(t) } }
    }.take(4000)
    return "Terminal, ${grid.cols} columns by ${grid.rows} rows. $status." + if (text.isEmpty()) " The screen is empty." else " Screen text:\n$text"
}
