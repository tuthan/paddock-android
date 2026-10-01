package io.github.tuthan.paddock.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.tuthan.paddock.output.AnsiLine
import io.github.tuthan.paddock.ui.theme.AnsiPalette
import io.github.tuthan.paddock.ui.theme.PaddockColors
import io.github.tuthan.paddock.ui.theme.PaddockTokens

internal fun ansiLineToText(line: AnsiLine, colors: PaddockColors): AnnotatedString = buildAnnotatedString {
    if (line.spans.isEmpty()) append(" ") // an empty line keeps its height
    for (span in line.spans) {
        val s = span.style
        // SGR 2 on its own reads as the dim token; a colour that was asked for keeps its contrast.
        val color = if (s.fg == null && s.dim) colors.dim else AnsiPalette.color(colors, s.fg)
        withStyle(
            SpanStyle(
                color = color,
                fontWeight = if (s.bold) FontWeight.Bold else null,
                textDecoration = if (s.underline) TextDecoration.Underline else null,
            ),
        ) { append(span.text) }
    }
}

/**
 * The last lines of a terminal on the slab. The list is reversed (newest line at index 0, laid out from the bottom), so
 * staying at the newest line needs no scroll code: new text arrives at the bottom and the view stays there while
 * [following]. A drag that reveals older lines calls [onUserScrolledUp]; only a user drag does, so following never
 * stops itself. When [following] turns back on the list returns to the newest line.
 */
@Composable
fun OutputSlab(lines: List<AnsiLine>, following: Boolean, onUserScrolledUp: () -> Unit, modifier: Modifier = Modifier) {
    val c = PaddockTokens.colors
    val listState = rememberLazyListState()
    val scrolledUp = rememberUpdatedState(onUserScrolledUp)
    val newestFirst = remember(lines) { lines.asReversed() }
    val connection = remember(listState) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // A finger moving down reveals older lines, which are the later indices of the reversed list.
                if (source == NestedScrollSource.UserInput && available.y > 0f && listState.canScrollForward) scrolledUp.value()
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(following) { if (following) listState.scrollToItem(0) }
    Box(modifier.fillMaxSize().background(c.slab, RoundedCornerShape(PaddockTokens.radii.row)).nestedScroll(connection)) {
        LazyColumn(
            Modifier.fillMaxSize().semantics { contentDescription = "Terminal output" },
            state = listState,
            reverseLayout = true,
            // Packed at the top when the text is shorter than the slab, so a short log reads from the top like a log.
            verticalArrangement = Arrangement.Top,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        ) {
            itemsIndexed(newestFirst) { _, line ->
                Text(ansiLineToText(line, c), style = PaddockTokens.type.monoFact, color = c.text)
            }
        }
    }
}

/** A small status chip. With [onClick] it is a real button, at least 48 dp tall. */
@Composable
fun Chip(text: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, description: String = text) {
    val c = PaddockTokens.colors
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .background(c.field, shape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = description, onClick = onClick).minimumInteractiveComponentSize() else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) { Text(text, style = PaddockTokens.type.secondary, color = c.title) }
}

/** Two or three mutually exclusive tabs, each at least 48 dp tall and announced as selected or not. */
@Composable
fun SegmentedTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = PaddockTokens.colors
    val shape = RoundedCornerShape(PaddockTokens.radii.button)
    Row(modifier.fillMaxWidth().background(c.field, shape).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = PaddockTokens.spacing.touchTarget)
                    .background(if (on) c.surface else c.field, RoundedCornerShape(PaddockTokens.radii.button - 2.dp))
                    .then(if (on) Modifier.border(1.dp, c.line(focused = true), RoundedCornerShape(PaddockTokens.radii.button - 2.dp)) else Modifier)
                    .clickable(role = Role.Tab, onClick = { onSelect(i) })
                    .semantics { this.selected = on },
                contentAlignment = Alignment.Center,
            ) { Text(label, style = PaddockTokens.type.rowTitle, color = if (on) c.title else c.dim) }
        }
    }
}

/** The manual keys. Rendered disabled in Phase 04: nothing here sends input, and TalkBack says so. */
@Composable
fun KeyStrip(modifier: Modifier = Modifier, note: String = "Keys arrive in Phase 06") {
    val c = PaddockTokens.colors
    Row(
        modifier.fillMaxWidth().alpha(0.5f).semantics(mergeDescendants = true) { disabled(); contentDescription = "Keys, unavailable. $note" },
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf("Esc", "Tab", "↑", "↓", "⏎", "^C").forEach { key ->
            Box(Modifier.heightIn(min = PaddockTokens.spacing.touchTarget).weight(1f).background(c.field, RoundedCornerShape(PaddockTokens.radii.key)), contentAlignment = Alignment.Center) {
                Text(key, style = PaddockTokens.type.monoFact, color = c.dim)
            }
        }
    }
}
