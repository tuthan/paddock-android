package io.github.tuthan.paddock.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.tuthan.paddock.ui.theme.PaddockIcons
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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.tuthan.paddock.output.AnsiLine
import io.github.tuthan.paddock.terminal.KeyEncoder
import io.github.tuthan.paddock.terminal.NamedKey
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
 * stops itself. When [following] turns back on the list returns to the newest line. While paused, a pill on the slab
 * says so.
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
    val shape = RoundedCornerShape(PaddockTokens.radii.slab)
    Box(modifier.fillMaxSize().clip(shape).background(c.slab).border(1.dp, c.line(), shape).nestedScroll(connection)) {
        LazyColumn(
            Modifier.fillMaxSize().semantics { contentDescription = "Terminal output" },
            state = listState,
            reverseLayout = true,
            // Packed at the top when the text is shorter than the slab, so a short log reads from the top like a log.
            verticalArrangement = Arrangement.Top,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        ) {
            itemsIndexed(newestFirst) { _, line ->
                Text(ansiLineToText(line, c), style = PaddockTokens.type.slab, color = c.text)
            }
        }
        if (!following) {
            val pill = RoundedCornerShape(50)
            Row(
                Modifier.align(Alignment.TopEnd).padding(10.dp).heightIn(min = 28.dp).clip(pill).background(c.surface.copy(alpha = 0.92f))
                    .border(1.dp, c.line(focused = true), pill).padding(horizontal = 10.dp).semantics { contentDescription = "Paused" },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(PaddockIcons.Pause, contentDescription = null, tint = c.text, modifier = Modifier.size(14.dp))
                Text("PAUSED", style = PaddockTokens.type.stateWord, color = c.text)
            }
        }
    }
}

/**
 * A status chip, 34 dp to the eye. With [onClick] it is a real button with a 48 dp target. [tone] tints it the way the
 * design tints a state chip (Working in the accent, Blocked in red); null is the plain chip.
 */
@Composable
fun Chip(
    text: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, description: String = text,
    tone: Color? = null, icon: ImageVector? = null, leading: (@Composable () -> Unit)? = null,
) {
    val c = PaddockTokens.colors
    val shape = RoundedCornerShape(50)
    // The 48 dp target is the outer box; the press ripple is drawn on the visible pill only.
    val press = remember { MutableInteractionSource() }
    Box(
        modifier
            .then(if (onClick != null) Modifier.clickable(interactionSource = press, indication = null, role = Role.Button, onClickLabel = description, onClick = onClick).minimumInteractiveComponentSize() else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.heightIn(min = 34.dp).clip(shape).then(if (onClick != null) Modifier.indication(press, ripple()) else Modifier)
                .background(if (tone != null) tone.copy(alpha = 0.10f) else c.surface)
                .border(1.dp, if (tone != null) tone.copy(alpha = 0.5f) else c.control(), shape)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            leading?.invoke()
            if (icon != null) Icon(icon, contentDescription = null, tint = tone ?: c.text, modifier = Modifier.size(14.dp))
            Text(text, style = PaddockTokens.type.chip, color = tone ?: c.text, maxLines = 1)
        }
    }
}

/** Two or three mutually exclusive tabs, each at least 48 dp tall and announced as selected or not. The selected one is raised onto the field colour. */
@Composable
fun SegmentedTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = PaddockTokens.colors
    val shape = RoundedCornerShape(PaddockTokens.radii.button)
    val inner = RoundedCornerShape(PaddockTokens.radii.button - 2.dp)
    Row(
        modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.text.copy(alpha = 0.10f), shape).padding(3.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = PaddockTokens.spacing.touchTarget)
                    .clip(inner)
                    .background(if (on) c.field else Color.Transparent)
                    .clickable(role = Role.Tab, onClick = { onSelect(i) })
                    .semantics { this.selected = on },
                contentAlignment = Alignment.Center,
            ) { Text(label, style = PaddockTokens.type.chip.copy(fontSize = PaddockTokens.type.summary.fontSize, fontWeight = FontWeight.Medium), color = if (on) c.title else c.dim) }
        }
    }
}

/** One key of the strip: what it says, what TalkBack says, and the bytes it sends. */
private class StripKey(val label: String, val description: String, val bytes: ByteArray)

private val STRIP_KEYS = listOf(
    StripKey("Esc", "Escape", KeyEncoder.named(NamedKey.Escape)),
    StripKey("Enter", "Enter", KeyEncoder.named(NamedKey.Enter)),
    StripKey("↑", "Up arrow", KeyEncoder.named(NamedKey.Up)),
    StripKey("↓", "Down arrow", KeyEncoder.named(NamedKey.Down)),
    StripKey("←", "Left arrow", KeyEncoder.named(NamedKey.Left)),
    StripKey("→", "Right arrow", KeyEncoder.named(NamedKey.Right)),
    StripKey("Tab", "Tab", KeyEncoder.named(NamedKey.Tab)),
    StripKey("Ctrl+C", "Control C", KeyEncoder.interrupt),
)

/** Keys only the live strip adds after the design's eight: the ones a phone keyboard has no key for. */
private val MORE_KEYS = listOf(
    StripKey("Home", "Home", KeyEncoder.named(NamedKey.Home)),
    StripKey("End", "End", KeyEncoder.named(NamedKey.End)),
    StripKey("PgUp", "Page up", KeyEncoder.named(NamedKey.PageUp)),
    StripKey("PgDn", "Page down", KeyEncoder.named(NamedKey.PageDown)),
)

/**
 * The manual keys, in the design's order with Esc first and in red. Without [onKey] (the Output tab, which only reads) the
 * strip is drawn but inert: nothing here sends input, TalkBack says so, and a caption under the strip says why. With
 * [onKey] and [enabled] (the Terminal tab while this phone controls the terminal) each key is a 48 dp button that hands
 * its bytes to [onKey]; when [enabled] is false the same strip is inert, so observing never sends a key. A live strip also
 * has Home, End, PgUp and PgDn, and, when [ctrlArmed] is given, a sticky Ctrl: tapping it arms it for the next character
 * typed on the Android keyboard (which has no Ctrl of its own), and it is spent on that one character.
 */
@Composable
fun KeyStrip(
    modifier: Modifier = Modifier, note: String = KEYS_NOTE, onKey: ((ByteArray) -> Unit)? = null, enabled: Boolean = onKey != null,
    ctrlArmed: Boolean? = null, onToggleCtrl: () -> Unit = {},
) {
    val c = PaddockTokens.colors
    val shape = RoundedCornerShape(PaddockTokens.radii.key)
    val live = onKey != null && enabled
    val group = if (live) Modifier.semantics { contentDescription = "Keys" } else Modifier.semantics(mergeDescendants = true) { disabled(); contentDescription = "Keys, unavailable. $note" }
    Column(modifier.fillMaxWidth().then(group), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().then(if (live) Modifier else Modifier.alpha(0.5f)).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            (if (live) STRIP_KEYS + MORE_KEYS else STRIP_KEYS).forEachIndexed { index, key ->
                if (live && ctrlArmed != null && index == STRIP_KEYS.size) CtrlToggleKey(ctrlArmed, onToggleCtrl, shape)
                val esc = key.label == "Esc"
                Box(
                    Modifier.heightIn(min = PaddockTokens.spacing.touchTarget).widthIn(min = PaddockTokens.spacing.touchTarget).clip(shape).background(c.field)
                        .border(1.dp, if (esc) c.needsYou.copy(alpha = 0.4f) else c.control(), shape)
                        .then(if (live) Modifier.clickable(role = Role.Button, onClickLabel = key.description) { onKey!!(key.bytes) }.semantics { contentDescription = key.description } else Modifier)
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(key.label, style = PaddockTokens.type.monoFact.copy(fontSize = PaddockTokens.type.chip.fontSize), color = if (esc) c.needsYou else c.title) }
            }
        }
        Text(note, style = PaddockTokens.type.note, color = c.dim)
    }
}

/** The sticky Ctrl of the live strip: on or off, announced as a switch, spent by the next character typed on the keyboard. */
@Composable
private fun CtrlToggleKey(armed: Boolean, onToggle: () -> Unit, shape: RoundedCornerShape) {
    val c = PaddockTokens.colors
    Box(
        Modifier.heightIn(min = PaddockTokens.spacing.touchTarget).widthIn(min = PaddockTokens.spacing.touchTarget).clip(shape)
            .background(if (armed) c.accent.copy(alpha = 0.18f) else c.field)
            .border(if (armed) 2.dp else 1.dp, if (armed) c.accent else c.control(), shape)
            .toggleable(value = armed, role = Role.Switch, onValueChange = { onToggle() })
            .semantics { contentDescription = "Control, for the next character typed"; stateDescription = if (armed) "armed" else "off" }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text("Ctrl", style = PaddockTokens.type.monoFact.copy(fontSize = PaddockTokens.type.chip.fontSize), color = if (armed) c.accent else c.title) }
}

const val KEYS_NOTE = "Read-only for now: Paddock does not send keys or replies to agents yet."
