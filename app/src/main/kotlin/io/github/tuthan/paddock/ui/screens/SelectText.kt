package io.github.tuthan.paddock.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.verticalScrollAxisRange
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import io.github.tuthan.paddock.output.Ansi
import io.github.tuthan.paddock.output.OutputCopy
import io.github.tuthan.paddock.output.OutputState
import io.github.tuthan.paddock.ui.SensitiveClipboard
import io.github.tuthan.paddock.ui.components.ButtonKind
import io.github.tuthan.paddock.ui.components.FlexColumn
import io.github.tuthan.paddock.ui.components.PaddockButton
import io.github.tuthan.paddock.ui.components.ScreenHeader
import io.github.tuthan.paddock.ui.components.flexible
import io.github.tuthan.paddock.ui.components.linesToText
import io.github.tuthan.paddock.ui.theme.PaddockIcons
import io.github.tuthan.paddock.ui.theme.PaddockTokens

/** Why a copy cannot be refreshed: the agent or its terminal has gone since the lines were read. */
enum class Since { AgentExited, TerminalClosed }

/**
 * What a Select text view is a copy of, held across a rotation and a reconnect by `Retained` (never in saved state: the lines are not saved anywhere). [snapshot]
 * is replaced in place by Re-read; [startLine] is the line of that copy the view opens at, when it was opened from a line's menu; [title] is the agent's as it was
 * then, so the view keeps saying whose copy it is while the machine is not connected.
 */
class SelectHolder(first: OutputState.Showing, val startLine: Int?, val title: String = "") {
    val snapshot = kotlinx.coroutines.flow.MutableStateFlow(first)
}

/** The words of the Select text view (Phase 15, C1). */
object SelectTextCopy {
    const val TITLE = "Select text"
    const val NOTE = "The agent's own line breaks, markers and borders come along."
    const val LAST_READ_FAILED = "The last read failed."
    const val EXITED = "This agent has exited since."
    const val CLOSED = "This terminal has closed since."

    private fun lines(n: Int) = if (n == 1) "1 line" else "$n lines"

    fun readAt(time: String, lines: Int) = "Text as read at $time · ${lines(lines)}"
    fun cut(n: Int) = (if (n == 1) "1 line is cut" else "$n lines are cut") + " at ${"%,d".format(Ansi.MAX_LINE_CHARS)} characters."
    fun leftOut(kept: Int, total: Int) = "The newest $kept of $total lines; the rest are too long to copy at once."

    /** Every sentence under the title for this copy, in the order they are shown. */
    fun facts(readAtTime: String, fit: OutputCopy.Fit, total: Int, stale: Boolean, since: Since?): List<String> = buildList {
        add(readAt(readAtTime, total))
        if (stale) add(LAST_READ_FAILED)
        if (fit.cut > 0) add(cut(fit.cut))
        if (fit.leftOut > 0) add(leftOut(fit.lines.size, total))
        when (since) { Since.AgentExited -> add(EXITED); Since.TerminalClosed -> add(CLOSED); null -> Unit }
    }
}

/**
 * The frozen copy of the lines the user was reading, as one read-only text with the platform's own selection, so what is copied is what was seen: the
 * feed reads nothing while this is open, and nothing here changes until [onReRead]. The widget is the state overload of `BasicTextField` (spike S1): it
 * scrolls in code, so the view opens at the newest line, or at [startLine]'s top, with nothing selected and no focus requested (a focused read-only field
 * raises the keyboard). Its toolbar's Copy goes through [SensitiveClipboard], so it is marked like [onCopyAll].
 */
@Composable
fun SelectText(
    title: String, snapshot: OutputState.Showing, readAtTime: String, since: Since?, protect: Boolean, startLine: Int?, reReading: Boolean,
    onCopyAll: (OutputCopy.Fit) -> Unit, onShare: (OutputCopy.Fit) -> Unit, onReRead: () -> Unit, onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** False while the machine is not connected: the copy stays, and Re-read waits for the connection. */
    connected: Boolean = true,
) {
    val c = PaddockTokens.colors
    val fit = remember(snapshot) { OutputCopy.fit(snapshot.lines) }
    Column(modifier.fillMaxSize()) {
        ScreenHeader(SelectTextCopy.TITLE, onBack = onClose, subtitle = title.ifEmpty { null }, compact = true, backDescription = "Back")
        FlexColumn(
            Modifier.weight(1f).fillMaxWidth().padding(start = PaddockTokens.spacing.gutter, end = PaddockTokens.spacing.gutter, bottom = 12.dp),
            flexMin = 140.dp, spacing = 10.dp,
        ) {
            Column(Modifier.testTag("SELECT_FACTS"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for ((i, fact) in SelectTextCopy.facts(readAtTime, fit, snapshot.lines.size, snapshot.stale, since).withIndex()) {
                    Text(fact, style = PaddockTokens.type.secondary, color = if (i == 0) c.dim else c.title)
                }
            }
            Box(Modifier.flexible().fillMaxWidth()) {
                val base = LocalClipboard.current
                val protectNow = rememberUpdatedState(protect)
                val clipboard = remember(base) { SensitiveClipboard(base) { protectNow.value } }
                CompositionLocalProvider(LocalClipboard provides clipboard) {
                    // The line this view opens at, in the copy's own numbering: lines left out of the copy come off the front.
                    val offset = startLine?.let { (it - fit.leftOut).coerceAtLeast(0) }
                    androidx.compose.runtime.key(fit.text) { SelectableText(fit, offset) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PaddockButton("Copy all", { onCopyAll(fit) }, Modifier.weight(1f), kind = ButtonKind.Secondary, icon = PaddockIcons.Copy)
                PaddockButton("Share", { onShare(fit) }, Modifier.weight(1f), kind = ButtonKind.Secondary)
                PaddockButton(if (reReading) "Reading…" else "Re-read", onReRead, Modifier.weight(1f), kind = ButtonKind.Ghost, enabled = connected && since == null && !reReading)
            }
            Text(SelectTextCopy.NOTE, style = PaddockTokens.type.note, color = c.dim)
        }
    }
}

/** The text of [fit] on the slab, scrolled to the newest line or to the top of line [startOffsetLine] of the copy. */
@Composable
private fun SelectableText(fit: OutputCopy.Fit, startOffsetLine: Int?) {
    val c = PaddockTokens.colors
    val styled = remember(fit, c) { linesToText(fit.lines, c) }
    // Not `rememberTextFieldState`: that one is saveable, and would put the whole copy (up to 300 KB of output) in the activity's saved state.
    // A rotation gets the copy back from the holder; the selection is not kept.
    val state = remember { TextFieldState(styled.text) }
    val scroll = rememberScrollState()
    val transform = remember(styled) {
        OutputTransformation { for (s in styled.spanStyles) addStyle(s.item, s.start, s.end) }
    }
    val startChar = remember(fit, startOffsetLine) { startOffsetLine?.let { line -> fit.lines.take(line).sumOf { OutputCopy.trimmed(it).text.length + 1 } } }
    var layout by remember { mutableStateOf<(() -> TextLayoutResult?)?>(null) }
    var positioned by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(scroll.maxValue, layout, positioned) {
        if (positioned) return@LaunchedEffect
        val max = scroll.maxValue
        if (max == Int.MAX_VALUE) return@LaunchedEffect // not measured yet
        val target = if (startChar == null) max else {
            val l = layout?.invoke() ?: return@LaunchedEffect
            l.getLineTop(l.getLineForOffset(startChar.coerceAtMost(styled.text.length))).toInt().coerceIn(0, max)
        }
        scroll.scrollTo(target)
        positioned = true
    }
    val shape = RoundedCornerShape(PaddockTokens.radii.slab)
    BasicTextField(
        state = state, readOnly = true, textStyle = PaddockTokens.type.slab.copy(color = c.text), scrollState = scroll, outputTransformation = transform,
        cursorBrush = SolidColor(c.accent), onTextLayout = { layout = it },
        modifier = Modifier.fillMaxSize().clip(shape).background(c.slab).border(1.dp, c.line(), shape).padding(12.dp)
            // The text field does not report its own scroll range: say it, so a screen reader can tell where in the copy it is, and a test can see where it opened.
            .semantics { verticalScrollAxisRange = ScrollAxisRange(value = { scroll.value.toFloat() }, maxValue = { scroll.maxValue.toFloat() }) }
            .testTag("SELECT_TEXT"),
    )
}
