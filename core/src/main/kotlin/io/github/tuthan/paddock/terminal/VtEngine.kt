package io.github.tuthan.paddock.terminal

import io.github.tuthan.paddock.ports.TerminalEngine

/**
 * The terminal engine for herdr's frames, written for what they contain (see docs/terminal-engine-decision.md): cursor
 * addressing, SGR, erase, the private modes herdr wraps frames in, and text, plus the ordinary controls and scrolling
 * that make it correct for any byte stream a future herdr might send. Everything with a side effect or a reply is
 * parsed and dropped: no OSC (so no clipboard write, title or link), no DCS, APC, PM or SOS, no device report or query
 * answer. What it does not know is counted in [unhandled] and never shown.
 *
 * Single-threaded: the caller serializes [feed], [resize], [clear] and [grid].
 */
class VtEngine(cols: Int = 80, rows: Int = 24) : TerminalEngine {
    override var cols: Int = cols; private set
    override var rows: Int = rows; private set
    override var altScreen: Boolean = false; private set
    override val cursor: Cursor get() = Cursor(row, col, cursorVisible)

    /** Sequences the engine did not act on (unknown finals, unsupported escapes). Reset by [clear]. */
    var unhandled: Int = 0; private set
    /** The shape of the last few unhandled sequences, parameters only and capped, for diagnostics. Never text from the stream. */
    val unhandledSamples: List<String> get() = samples.toList()
    /** Strings (OSC, DCS, APC, PM, SOS) parsed and dropped. */
    var droppedStrings: Int = 0; private set

    init { require(TerminalLimits.validGeometry(cols, rows)) { "geometry ${cols}x$rows out of range" } }

    // --- screens ---

    private class Screen(val cols: Int, val rows: Int) {
        val text = Array(cols * rows) { " " }
        val fg = IntArray(cols * rows)
        val bg = IntArray(cols * rows)
        val flags = IntArray(cols * rows)
    }

    private var primary = Screen(cols, rows)
    private var alt = Screen(cols, rows)
    private var screen = primary

    // --- cursor, pen, modes ---

    private var row = 0
    private var col = 0
    private var pendingWrap = false
    private var cursorVisible = true
    private var autowrap = true
    private var penFg = 0
    private var penBg = 0
    private var penFlags = 0
    private var scrollTop = 0
    private var scrollBottom = rows - 1
    private var saved: Saved? = null
    private var altSaved: Saved? = null
    private val samples = ArrayDeque<String>()

    private data class Saved(val row: Int, val col: Int, val fg: Int, val bg: Int, val flags: Int, val wrap: Boolean, val autowrap: Boolean)

    // --- parser ---

    private enum class State { Ground, Escape, EscapeIntermediate, CsiEntry, CsiParam, CsiIntermediate, CsiIgnore, Str, StrEscape }

    private var state = State.Ground
    private var utf8Need = 0
    private var utf8Cp = 0
    private var utf8Min = 0
    private val params = IntArray(MAX_PARAMS)
    private val subparam = BooleanArray(MAX_PARAMS)
    private var paramCount = 0
    private var cur = 0
    private var curOpen = false
    private var curSub = false
    private var afterSeparator = false
    private var marker = 0.toChar()
    private var intermediates = StringBuilder()
    private var stringIsOsc = false

    override fun feed(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size) { "range outside the array" }
        for (i in offset until offset + length) step(bytes[i].toInt() and 0xFF)
        // A frame is self-contained: whatever is still open at its end is dropped with it.
        state = State.Ground; utf8Need = 0
    }

    override fun resize(cols: Int, rows: Int) {
        require(TerminalLimits.validGeometry(cols, rows)) { "geometry ${cols}x$rows out of range" }
        if (cols == this.cols && rows == this.rows) return
        primary = copyInto(primary, cols, rows); alt = copyInto(alt, cols, rows)
        screen = if (altScreen) alt else primary
        this.cols = cols; this.rows = rows
        row = row.coerceIn(0, rows - 1); col = col.coerceIn(0, cols - 1); pendingWrap = false
        scrollTop = 0; scrollBottom = rows - 1
    }

    override fun clear() {
        primary = Screen(cols, rows); alt = Screen(cols, rows); screen = primary; altScreen = false
        row = 0; col = 0; pendingWrap = false; cursorVisible = true; autowrap = true
        penFg = 0; penBg = 0; penFlags = 0; scrollTop = 0; scrollBottom = rows - 1; saved = null; altSaved = null
        state = State.Ground; utf8Need = 0; unhandled = 0; droppedStrings = 0; samples.clear()
    }

    override fun grid(): Grid = Grid(cols, rows, screen.text.copyOf(), screen.fg.copyOf(), screen.bg.copyOf(), screen.flags.copyOf())

    private fun copyInto(from: Screen, cols: Int, rows: Int): Screen {
        val to = Screen(cols, rows)
        for (r in 0 until minOf(from.rows, rows)) for (c in 0 until minOf(from.cols, cols)) {
            val a = r * from.cols + c; val b = r * cols + c
            to.text[b] = from.text[a]; to.fg[b] = from.fg[a]; to.bg[b] = from.bg[a]; to.flags[b] = from.flags[a]
        }
        // A double-width character cut in half by the new right edge becomes a blank.
        if (cols < from.cols) for (r in 0 until minOf(from.rows, rows)) {
            val last = r * cols + cols - 1
            if (to.flags[last] and CellFlags.WIDE != 0) blankCell(to, last, 0)
        }
        return to
    }

    // --- byte stepping ---

    private fun step(b: Int) {
        when (state) {
            State.Ground -> ground(b)
            State.Escape -> escape(b)
            State.EscapeIntermediate -> escapeIntermediate(b)
            State.CsiEntry, State.CsiParam, State.CsiIntermediate -> csi(b)
            State.CsiIgnore -> when {
                b == ESC -> { state = State.Escape }
                b == CAN || b == SUB -> state = State.Ground
                b in 0x40..0x7E -> { note("CSI ignored"); state = State.Ground }
                b < 0x20 -> control(b)
            }
            State.Str -> when {
                b == ESC -> state = State.StrEscape
                b == BEL && stringIsOsc -> { droppedStrings++; state = State.Ground }
                b == CAN || b == SUB -> state = State.Ground
            }
            State.StrEscape -> if (b == '\\'.code) { droppedStrings++; state = State.Ground } else { droppedStrings++; state = State.Escape; escape(b) }
        }
    }

    private fun ground(b: Int) {
        if (utf8Need > 0) {
            if (b and 0xC0 == 0x80) {
                utf8Cp = (utf8Cp shl 6) or (b and 0x3F)
                if (--utf8Need == 0) {
                    val cp = utf8Cp
                    if (cp < utf8Min || cp > 0x10FFFF || cp in 0xD800..0xDFFF) put(0xFFFD) else put(cp)
                }
                return
            }
            // A lead byte without its continuation: one replacement, then this byte is read afresh.
            utf8Need = 0; put(0xFFFD)
        }
        when {
            b < 0x20 -> if (b == ESC) state = State.Escape else control(b)
            b < 0x7F -> put(b)
            b == 0x7F -> Unit
            b in 0xC2..0xDF -> { utf8Need = 1; utf8Cp = b and 0x1F; utf8Min = 0x80 }
            b in 0xE0..0xEF -> { utf8Need = 2; utf8Cp = b and 0x0F; utf8Min = 0x800 }
            b in 0xF0..0xF4 -> { utf8Need = 3; utf8Cp = b and 0x07; utf8Min = 0x10000 }
            else -> put(0xFFFD)
        }
    }

    /** C0 controls that act in any state. */
    private fun control(b: Int) {
        when (b) {
            0x08 -> { if (col > 0) col--; pendingWrap = false }                       // BS
            0x09 -> { col = nextTab(col); pendingWrap = false }                         // HT
            0x0A, 0x0B, 0x0C -> { index(); pendingWrap = false }                       // LF, VT, FF
            0x0D -> { col = 0; pendingWrap = false }                                    // CR
            CAN, SUB -> state = State.Ground
            else -> Unit                                                                // NUL, BEL, ENQ, SO, SI and the rest do nothing
        }
    }

    private fun escape(b: Int) {
        when {
            b == ESC -> Unit
            b == CAN || b == SUB -> state = State.Ground
            b < 0x20 -> control(b)
            b == '['.code -> { beginCsi(); state = State.CsiEntry }
            b == ']'.code -> { stringIsOsc = true; state = State.Str }
            b == 'P'.code || b == 'X'.code || b == '^'.code || b == '_'.code -> { stringIsOsc = false; state = State.Str }
            b in 0x20..0x2F -> { intermediates.setLength(0); intermediates.append(b.toChar()); state = State.EscapeIntermediate }
            else -> { escFinal(b.toChar()); if (state == State.Escape) state = State.Ground }
        }
    }

    private fun escapeIntermediate(b: Int) {
        when {
            b == ESC -> state = State.Escape
            b == CAN || b == SUB -> state = State.Ground
            b < 0x20 -> control(b)
            b in 0x20..0x2F -> if (intermediates.length < MAX_INTERMEDIATES) intermediates.append(b.toChar())
            else -> {
                // Charset designation (ESC ( B and friends), DECALN, 7-bit/8-bit controls: no cell changes, nothing to show.
                val first = intermediates.firstOrNull()
                if (first == '(' || first == ')' || first == '*' || first == '+' || first == ' ' || first == '#') Unit else note("ESC ${intermediates}${b.toChar()}")
                state = State.Ground
            }
        }
    }

    private fun escFinal(c: Char) {
        when (c) {
            '7' -> saveCursor()
            '8' -> restoreCursor()
            'c' -> fullReset()
            'D' -> { index(); pendingWrap = false }
            'E' -> { col = 0; index(); pendingWrap = false }
            'M' -> { reverseIndex(); pendingWrap = false }
            'H', '=', '>', '\\', 'N', 'O', 'n', 'o', '|', '}', '~' -> Unit
            else -> note("ESC $c")
        }
    }

    private fun fullReset() {
        val c = cols; val r = rows
        clear(); this.cols = c; this.rows = r
    }

    // --- CSI ---

    private fun beginCsi() {
        paramCount = 0; cur = 0; curOpen = false; curSub = false; afterSeparator = false
        marker = 0.toChar(); intermediates.setLength(0)
    }

    private fun pushParam() {
        if (paramCount < MAX_PARAMS) { params[paramCount] = cur; subparam[paramCount] = curSub }
        paramCount++; cur = 0; curOpen = false; curSub = false
    }

    /** A parameter is pending when digits were read, or a separator was the last thing read (`1;;3`, a trailing `;`). */
    private fun finishParams() { if (curOpen || afterSeparator) pushParam(); afterSeparator = false }

    private fun csi(b: Int) {
        when {
            b == ESC -> { state = State.Escape; return }
            b == CAN || b == SUB -> { state = State.Ground; return }
            b < 0x20 -> { control(b); return }
        }
        when (state) {
            State.CsiEntry, State.CsiParam -> when {
                b in '0'.code..'9'.code -> { state = State.CsiParam; curOpen = true; afterSeparator = false; cur = minOf(cur * 10 + (b - '0'.code), 65_535) }
                b == ';'.code || b == ':'.code -> { state = State.CsiParam; pushParam(); curSub = b == ':'.code; afterSeparator = true }
                b in '<'.code..'?'.code -> if (state == State.CsiEntry) { marker = b.toChar(); state = State.CsiParam } else state = State.CsiIgnore
                b in 0x20..0x2F -> { finishParams(); intermediates.append(b.toChar()); state = State.CsiIntermediate }
                b in 0x40..0x7E -> { finishParams(); dispatch(b.toChar()) }
                else -> state = State.CsiIgnore
            }
            State.CsiIntermediate -> when {
                b in 0x20..0x2F -> if (intermediates.length < MAX_INTERMEDIATES) intermediates.append(b.toChar())
                b in 0x40..0x7E -> dispatch(b.toChar())
                else -> state = State.CsiIgnore
            }
            else -> Unit
        }
    }

    private val storedParams: Int get() = minOf(paramCount, MAX_PARAMS)

    private fun param(i: Int, default: Int): Int {
        if (i >= storedParams) return default
        val v = params[i]
        return if (v == 0) default else v
    }

    /** Parameter [i] as written, 0 when absent or empty. */
    private fun raw(i: Int): Int = if (i < storedParams) params[i] else 0

    private fun dispatch(f: Char) {
        val priv = marker != 0.toChar()
        val ims = intermediates.toString()
        state = State.Ground
        if (ims.isNotEmpty()) {
            // DECSCUSR (CSI Ps SP q, a cursor shape), DECSTR (CSI ! p, a soft reset) and DECRQM (CSI ? Ps $ p, a mode query)
            // draw nothing and are never answered.
            if (!(f == 'q' && ims == " ") && !(f == 'p' && (ims == "!" || ims == "$"))) note("CSI $ims$f")
            return
        }
        if (priv) {
            when (f) {
                'h' -> for (i in 0 until maxOf(storedParams, 1)) privateMode(raw(i), true)
                'l' -> for (i in 0 until maxOf(storedParams, 1)) privateMode(raw(i), false)
                // Queries and reports (DECRQM, mouse and keyboard protocol negotiation): no reply is ever sent.
                'p', 'u', 'c', 'n', 'm', 'S', 'J', 'K' -> Unit
                else -> note("CSI $marker$f")
            }
            return
        }
        when (f) {
            'A' -> moveRow(-param(0, 1))
            'B', 'e' -> moveRow(param(0, 1))
            'C', 'a' -> moveCol(param(0, 1))
            'D' -> moveCol(-param(0, 1))
            'E' -> { moveRow(param(0, 1)); col = 0 }
            'F' -> { moveRow(-param(0, 1)); col = 0 }
            'G', '`' -> { col = (param(0, 1) - 1).coerceIn(0, cols - 1); pendingWrap = false }
            'H', 'f' -> { row = (param(0, 1) - 1).coerceIn(0, rows - 1); col = (param(1, 1) - 1).coerceIn(0, cols - 1); pendingWrap = false }
            'd' -> { row = (param(0, 1) - 1).coerceIn(0, rows - 1); pendingWrap = false }
            'J' -> eraseDisplay(raw(0))
            'K' -> eraseLine(raw(0))
            'L' -> insertLines(param(0, 1))
            'M' -> deleteLines(param(0, 1))
            'P' -> deleteChars(param(0, 1))
            '@' -> insertChars(param(0, 1))
            'X' -> eraseChars(param(0, 1))
            'S' -> repeat(minOf(param(0, 1), rows)) { scrollUp(scrollTop, scrollBottom) }
            'T' -> repeat(minOf(param(0, 1), rows)) { scrollDown(scrollTop, scrollBottom) }
            'm' -> sgr()
            'r' -> {
                val top = (param(0, 1) - 1).coerceIn(0, rows - 1)
                val bottom = (param(1, rows) - 1).coerceIn(0, rows - 1)
                if (top < bottom) { scrollTop = top; scrollBottom = bottom } else { scrollTop = 0; scrollBottom = rows - 1 }
                row = 0; col = 0; pendingWrap = false
            }
            's' -> saveCursor()
            'u' -> restoreCursor()
            'g' -> Unit                                       // tab clear: tab stops are fixed every eight
            'h', 'l' -> Unit                                  // ANSI modes (insert, newline): not set
            'n', 'c', 't', 'x', 'q' -> Unit                   // reports and queries; never answered
            else -> note("CSI $f")
        }
    }

    private fun privateMode(mode: Int, on: Boolean) {
        when (mode) {
            25 -> cursorVisible = on
            7 -> autowrap = on
            47, 1047 -> switchScreen(on, save = false)
            1049 -> switchScreen(on, save = true)
            // Synchronized update, application cursor keys, mouse tracking, bracketed paste, focus reporting, blink, origin:
            // the frame is atomic so 2026 needs no handling, and none of the others change a cell.
            else -> Unit
        }
    }

    private fun switchScreen(toAlt: Boolean, save: Boolean) {
        if (toAlt == altScreen) return
        if (toAlt) {
            if (save) altSaved = Saved(row, col, penFg, penBg, penFlags, pendingWrap, autowrap)
            alt = Screen(cols, rows); screen = alt; altScreen = true
        } else {
            screen = primary; altScreen = false
            if (save) altSaved?.let { restore(it) }
        }
    }

    // --- SGR ---

    private fun sgr() {
        val n = storedParams
        if (n == 0) { resetPen(); return }
        var i = 0
        while (i < n) {
            // A parameter and the sub-parameters written after it with ':' are one group.
            var len = 1
            while (i + len < n && subparam[i + len]) len++
            val code = params[i]
            var consumed = len
            when (code) {
                0 -> resetPen()
                1 -> penFlags = penFlags or CellFlags.BOLD
                2 -> penFlags = penFlags or CellFlags.DIM
                3 -> penFlags = penFlags or CellFlags.ITALIC
                4 -> penFlags = if (len > 1 && params[i + 1] == 0) penFlags and CellFlags.UNDERLINE.inv() else penFlags or CellFlags.UNDERLINE
                5, 6 -> penFlags = penFlags or CellFlags.BLINK
                7 -> penFlags = penFlags or CellFlags.REVERSE
                8 -> penFlags = penFlags or CellFlags.HIDDEN
                9 -> penFlags = penFlags or CellFlags.STRIKE
                21 -> penFlags = penFlags or CellFlags.UNDERLINE
                22 -> penFlags = penFlags and (CellFlags.BOLD or CellFlags.DIM).inv()
                23 -> penFlags = penFlags and CellFlags.ITALIC.inv()
                24 -> penFlags = penFlags and CellFlags.UNDERLINE.inv()
                25 -> penFlags = penFlags and CellFlags.BLINK.inv()
                27 -> penFlags = penFlags and CellFlags.REVERSE.inv()
                28 -> penFlags = penFlags and CellFlags.HIDDEN.inv()
                29 -> penFlags = penFlags and CellFlags.STRIKE.inv()
                in 30..37 -> penFg = TermColor.indexed(code - 30).packed
                39 -> penFg = 0
                in 40..47 -> penBg = TermColor.indexed(code - 40).packed
                49 -> penBg = 0
                in 90..97 -> penFg = TermColor.indexed(8 + code - 90).packed
                in 100..107 -> penBg = TermColor.indexed(8 + code - 100).packed
                38, 48, 58 -> {
                    // 38/48/58 select a colour either with ';' (38;5;n, 38;2;r;g;b) or with ':' (38:5:n, 38:2:r:g:b, and
                    // 38:2:cs:r:g:b with a colour-space id before the channels). Underline colour (58) is read so its
                    // arguments are not mistaken for codes, and not drawn.
                    var color: TermColor? = null
                    if (len > 1) {
                        val kind = params[i + 1]
                        if (kind == 5 && len >= 3 && params[i + 2] in 0..255) color = TermColor.indexed(params[i + 2])
                        else if (kind == 2 && len >= 5) color = rgbOf(params[i + len - 3], params[i + len - 2], params[i + len - 1])
                    } else {
                        when (raw(i + 1)) {
                            5 -> { consumed = 3; if (i + 2 < n && params[i + 2] in 0..255) color = TermColor.indexed(params[i + 2]) }
                            2 -> { consumed = 5; if (i + 4 < n) color = rgbOf(params[i + 2], params[i + 3], params[i + 4]) }
                        }
                    }
                    if (color != null) { if (code == 38) penFg = color.packed else if (code == 48) penBg = color.packed }
                }
                else -> Unit
            }
            i += consumed
        }
    }

    private fun rgbOf(r: Int, g: Int, b: Int): TermColor? = if (r in 0..255 && g in 0..255 && b in 0..255) TermColor.rgb(r, g, b) else null

    private fun resetPen() { penFg = 0; penBg = 0; penFlags = 0 }

    // --- text ---

    private fun put(cp: Int) {
        val w = CellWidth.of(cp)
        if (w == 0) { combine(cp); return }
        if (pendingWrap) { if (autowrap) { col = 0; index() }; pendingWrap = false }
        if (w == 2 && col == cols - 1) {
            if (!autowrap) return
            blankCell(screen, row * cols + col, penBg)
            col = 0; index()
        }
        val at = row * cols + col
        clearWidePair(at)
        screen.text[at] = String(Character.toChars(cp)); screen.fg[at] = penFg; screen.bg[at] = penBg
        if (w == 2) {
            val next = at + 1
            clearWidePair(next)
            screen.flags[at] = penFlags or CellFlags.WIDE
            screen.text[next] = ""; screen.fg[next] = penFg; screen.bg[next] = penBg; screen.flags[next] = penFlags or CellFlags.CONTINUATION
        } else screen.flags[at] = penFlags
        col += w
        if (col >= cols) { col = cols - 1; pendingWrap = autowrap }
    }

    /** A combining mark or joiner joins the character before the cursor, if there is one. */
    private fun combine(cp: Int) {
        var c = if (pendingWrap) col else col - 1
        if (c < 0) return
        var at = row * cols + c
        if (screen.flags[at] and CellFlags.CONTINUATION != 0 && c > 0) { c--; at-- }
        if (screen.text[at] == " " || screen.text[at].length > MAX_CLUSTER) return
        screen.text[at] = screen.text[at] + String(Character.toChars(cp))
    }

    /** About to overwrite [at]: if it is half of a double-width character, the other half becomes a blank. */
    private fun clearWidePair(at: Int) {
        val f = screen.flags[at]
        val rowStart = (at / cols) * cols
        if (f and CellFlags.WIDE != 0 && at + 1 < rowStart + cols) blankCell(screen, at + 1, 0)
        if (f and CellFlags.CONTINUATION != 0 && at - 1 >= rowStart) blankCell(screen, at - 1, 0)
    }

    private fun blankCell(s: Screen, at: Int, bg: Int) { s.text[at] = " "; s.fg[at] = 0; s.bg[at] = bg; s.flags[at] = 0 }

    // --- cursor movement and scrolling ---

    private fun moveRow(delta: Int) {
        val inRegion = row in scrollTop..scrollBottom
        row = if (delta < 0) (row + delta).coerceAtLeast(if (inRegion) scrollTop else 0) else (row + delta).coerceAtMost(if (inRegion) scrollBottom else rows - 1)
        pendingWrap = false
    }

    private fun moveCol(delta: Int) { col = (col + delta).coerceIn(0, cols - 1); pendingWrap = false }

    private fun nextTab(from: Int): Int = minOf(((from / 8) + 1) * 8, cols - 1)

    /** LF, IND: down one row, scrolling the region when the cursor is on its last row. */
    private fun index() { if (row == scrollBottom) scrollUp(scrollTop, scrollBottom) else if (row < rows - 1) row++ }

    private fun reverseIndex() { if (row == scrollTop) scrollDown(scrollTop, scrollBottom) else if (row > 0) row-- }

    private fun scrollUp(top: Int, bottom: Int) {
        val s = screen
        for (r in top until bottom) copyRow(s, r + 1, r)
        blankRow(s, bottom)
    }

    private fun scrollDown(top: Int, bottom: Int) {
        val s = screen
        for (r in bottom downTo top + 1) copyRow(s, r - 1, r)
        blankRow(s, top)
    }

    private fun copyRow(s: Screen, from: Int, to: Int) {
        System.arraycopy(s.text, from * cols, s.text, to * cols, cols); System.arraycopy(s.fg, from * cols, s.fg, to * cols, cols)
        System.arraycopy(s.bg, from * cols, s.bg, to * cols, cols); System.arraycopy(s.flags, from * cols, s.flags, to * cols, cols)
    }

    private fun blankRow(s: Screen, r: Int) { for (c in 0 until cols) blankCell(s, r * cols + c, penBg) }

    private fun insertLines(n: Int) {
        if (row !in scrollTop..scrollBottom) return
        repeat(minOf(n, scrollBottom - row + 1)) { scrollDown(row, scrollBottom) }
        col = 0; pendingWrap = false
    }

    private fun deleteLines(n: Int) {
        if (row !in scrollTop..scrollBottom) return
        repeat(minOf(n, scrollBottom - row + 1)) { scrollUp(row, scrollBottom) }
        col = 0; pendingWrap = false
    }

    private fun insertChars(n: Int) {
        val s = screen; val count = minOf(n, cols - col); val base = row * cols
        for (c in cols - 1 downTo col + count) moveCell(s, base + c - count, base + c)
        for (c in col until col + count) blankCell(s, base + c, penBg)
        fixPairsInRow(row); pendingWrap = false
    }

    private fun deleteChars(n: Int) {
        val s = screen; val count = minOf(n, cols - col); val base = row * cols
        for (c in col until cols - count) moveCell(s, base + c + count, base + c)
        for (c in cols - count until cols) blankCell(s, base + c, penBg)
        fixPairsInRow(row); pendingWrap = false
    }

    private fun eraseChars(n: Int) {
        val base = row * cols
        for (c in col until minOf(col + n, cols)) { clearWidePair(base + c); blankCell(screen, base + c, penBg) }
        pendingWrap = false
    }

    private fun moveCell(s: Screen, from: Int, to: Int) { s.text[to] = s.text[from]; s.fg[to] = s.fg[from]; s.bg[to] = s.bg[from]; s.flags[to] = s.flags[from] }

    /** After cells shifted within a row, a double-width character with a missing half becomes a blank. */
    private fun fixPairsInRow(r: Int) {
        val base = r * cols
        for (c in 0 until cols) {
            val f = screen.flags[base + c]
            if (f and CellFlags.WIDE != 0 && (c + 1 >= cols || screen.flags[base + c + 1] and CellFlags.CONTINUATION == 0)) blankCell(screen, base + c, 0)
            if (f and CellFlags.CONTINUATION != 0 && (c == 0 || screen.flags[base + c - 1] and CellFlags.WIDE == 0)) blankCell(screen, base + c, 0)
        }
    }

    private fun eraseLine(mode: Int) {
        val base = row * cols
        val range = when (mode) { 1 -> 0..col; 2 -> 0 until cols; else -> col until cols }
        for (c in range) { clearWidePair(base + c); blankCell(screen, base + c, penBg) }
        pendingWrap = false
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> { eraseLine(0); for (r in row + 1 until rows) blankRow(screen, r) }
            1 -> { for (r in 0 until row) blankRow(screen, r); eraseLine(1) }
            2, 3 -> for (r in 0 until rows) blankRow(screen, r)
        }
        pendingWrap = false
    }

    private fun saveCursor() { saved = Saved(row, col, penFg, penBg, penFlags, pendingWrap, autowrap) }
    private fun restoreCursor() { restore(saved ?: Saved(0, 0, 0, 0, 0, false, true)) }
    private fun restore(s: Saved) {
        row = s.row.coerceIn(0, rows - 1); col = s.col.coerceIn(0, cols - 1)
        penFg = s.fg; penBg = s.bg; penFlags = s.flags; pendingWrap = s.wrap && col == cols - 1; autowrap = s.autowrap
    }

    private fun note(what: String) {
        unhandled++
        if (samples.size >= MAX_SAMPLES) samples.removeFirst()
        samples.addLast(what.take(24))
    }

    private companion object {
        const val ESC = 0x1B
        const val CAN = 0x18
        const val SUB = 0x1A
        const val BEL = 0x07
        const val MAX_PARAMS = 32
        const val MAX_INTERMEDIATES = 4
        const val MAX_CLUSTER = 12
        const val MAX_SAMPLES = 8
    }
}
