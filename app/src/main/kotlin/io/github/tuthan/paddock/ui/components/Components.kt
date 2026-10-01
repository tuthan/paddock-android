package io.github.tuthan.paddock.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.tuthan.paddock.attention.AgeText
import io.github.tuthan.paddock.attention.AgentRowModel
import io.github.tuthan.paddock.attention.Monogram
import io.github.tuthan.paddock.attention.Section
import io.github.tuthan.paddock.attention.StateWord
import io.github.tuthan.paddock.live.PreviewState
import io.github.tuthan.paddock.ui.theme.PaddockColors
import io.github.tuthan.paddock.ui.theme.PaddockIcons
import io.github.tuthan.paddock.ui.theme.PaddockTokens

/**
 * A small-caps label. Only for Paddock's own words: it upper-cases what it is given, so a path, an algorithm or a
 * host name never goes in here (those sit on their own line, in mono). Marked as a heading for TalkBack.
 */
@Composable
fun Kicker(text: String, modifier: Modifier = Modifier, color: Color = PaddockTokens.colors.dim) {
    Text(text = text.uppercase(), style = PaddockTokens.type.kicker, color = color, modifier = modifier.semantics { heading() })
}

/** A home section's label with its dot and count, "● NEEDS YOU · 2", coloured only where the section needs attention. */
@Composable
fun SectionKicker(section: Section, count: Int, modifier: Modifier = Modifier) {
    val c = PaddockTokens.colors
    val (dotState, textColor) = when (section) {
        Section.NeedsYou -> StateWord.Blocked to c.needsYou
        Section.Done -> StateWord.Done to c.done
        Section.Working -> StateWord.Working to c.dim
        Section.Ready -> StateWord.Ready to c.dim
        Section.Unknown -> StateWord.Unknown to c.dim
    }
    Row(
        modifier.padding(top = 6.dp).semantics(mergeDescendants = true) { heading(); contentDescription = "${section.heading}, $count" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StateDot(dotState)
        Text("${section.heading.uppercase()} · $count", style = PaddockTokens.type.kicker, color = textColor)
    }
}

/** A host-level label: "● LAPTOP · AS OF 14:02", in the attention colour. */
@Composable
fun HostKicker(text: String, modifier: Modifier = Modifier) {
    val c = PaddockTokens.colors
    Row(
        modifier.padding(top = 6.dp).semantics(mergeDescendants = true) { heading(); contentDescription = text },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Dot(c.attention)
        Text(text.uppercase(), style = PaddockTokens.type.kicker, color = c.attention)
    }
}

/** The colour a state is written in. Working is the accent; ready and unknown are quiet. */
internal fun PaddockColors.stateColor(state: StateWord): Color = when (state) {
    StateWord.Blocked -> needsYou
    StateWord.Done -> done
    StateWord.Working -> accent
    StateWord.Ready, StateWord.IdleNotReady, StateWord.Unknown -> dim
}

/** A plain 8 dp dot. */
@Composable
fun Dot(color: Color, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    Canvas(modifier.size(size).clearAndSetSemantics { }) { drawCircle(color) }
}

/**
 * A state's dot. The shape differs as well as the colour, so the state never rests on colour alone: filled for a known
 * state, a solid ring for an agent still starting, a dashed ring for unknown.
 */
@Composable
fun StateDot(state: StateWord, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    val c = PaddockTokens.colors
    Canvas(modifier.size(size).clearAndSetSemantics { }) {
        val w = 1.5.dp.toPx()
        when (state) {
            StateWord.Blocked -> drawCircle(c.needsYou)
            StateWord.Done -> drawCircle(c.done)
            StateWord.Working -> drawCircle(c.accent)
            StateWord.Ready -> drawCircle(c.faint)
            StateWord.IdleNotReady -> drawCircle(c.faint, radius = this.size.minDimension / 2 - w / 2, style = Stroke(width = w))
            StateWord.Unknown -> drawCircle(
                c.faint, radius = this.size.minDimension / 2 - w / 2,
                style = Stroke(width = w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 1.6.dp.toPx()))),
            )
        }
    }
}

/** The state as a dot and a small upper-case word: "● WORKING". */
@Composable
fun StateWord(state: StateWord, modifier: Modifier = Modifier) {
    val c = PaddockTokens.colors
    Row(modifier.clearAndSetSemantics { }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StateDot(state)
        Text(state.word.uppercase(), style = PaddockTokens.type.stateWord, color = c.stateColor(state))
    }
}

/** Two letters for the agent kind on a 32 dp tile, in mono, as the design's monograms. */
@Composable
fun MonogramTile(kind: String?, modifier: Modifier = Modifier) {
    val c = PaddockTokens.colors
    val shape = RoundedCornerShape(8.dp)
    // Decorative (the row says the same in words), so it grows with the font only a little and always fits its tile.
    val density = LocalDensity.current
    val scale = density.fontScale.coerceAtMost(1.3f)
    val tile = 32.dp * scale
    val size = with(density) { (12.dp * scale).toSp() }
    Box(modifier.size(tile).clearAndSetSemantics { }.background(c.field, shape), contentAlignment = Alignment.Center) {
        Text(Monogram.of(kind), style = PaddockTokens.type.monoFact.copy(fontWeight = FontWeight.Medium, fontSize = size, lineHeight = size), color = c.title, maxLines = 1)
    }
}

/** What TalkBack reads for a row: state word, title, context, then how long ago the phone saw it. */
internal fun rowDescription(model: AgentRowModel, nowMillis: Long, stale: Boolean = false): String {
    val observed = AgeText.observed(nowMillis - model.observedAtMillis)
    val state = if (stale) "Last seen ${model.state.word.lowercase()}" else model.state.word
    return listOfNotNull(state, model.title, model.context.ifEmpty { null }, observed).joinToString(", ")
}

/** The row's second line: where it is, then what the phone saw and when. */
private fun rowDetail(model: AgentRowModel, nowMillis: Long, stale: Boolean): String {
    val observed = AgeText.observed(nowMillis - model.observedAtMillis)
    val state = when {
        stale -> "was ${model.state.word.lowercase()}"
        model.state == StateWord.IdleNotReady -> "starting"
        else -> null
    }
    val tail = if (!stale && model.state == StateWord.Done) "tap marks seen" else null
    return listOfNotNull(model.context.ifEmpty { null }, state, observed, tail).joinToString(" · ")
}

/**
 * One agent: monogram, a one-line title, then where it is and when the phone saw it. Colour is spent only where
 * attention is: blocked and done rows are washed in their colour and lead to the agent with a chevron; working, ready
 * and unknown rows are plain with the state's dot. A [stale] row (its host is not live) is dimmed, says what it *was*,
 * and offers no action.
 */
@Composable
fun AgentRow(model: AgentRowModel, nowMillis: Long, enabled: Boolean, modifier: Modifier = Modifier, stale: Boolean = !enabled, onClick: () -> Unit = {}) {
    val c = PaddockTokens.colors
    val shape = RoundedCornerShape(PaddockTokens.radii.row)
    val loud = !stale && (model.state == StateWord.Blocked || model.state == StateWord.Done)
    val tone = when (model.state) { StateWord.Blocked -> c.needsYou; StateWord.Done -> c.done; else -> null }
    Row(
        modifier
            .fillMaxWidth()
            .alpha(if (stale) 0.55f else 1f)
            .clip(shape)
            .background(if (loud && tone != null) c.wash(tone) else c.surface)
            .border(1.dp, if (loud && tone != null) c.washBorder(tone) else c.line(), shape)
            .then(if (enabled) Modifier.clickable(role = Role.Button, onClickLabel = "Open ${model.title}", onClick = onClick) else Modifier)
            .heightIn(min = PaddockTokens.spacing.touchTarget)
            .padding(horizontal = 14.dp, vertical = if (loud) 12.dp else 10.dp)
            .semantics(mergeDescendants = true) { contentDescription = rowDescription(model, nowMillis, stale) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MonogramTile(model.agentKind)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            // One line as designed; two once the font is large, so a title is still recognisable.
            val lines = if (LocalDensity.current.fontScale >= 1.3f) 2 else 1
            Text(model.title, style = PaddockTokens.type.rowTitle, color = c.title, maxLines = lines, overflow = TextOverflow.Ellipsis)
            Text(rowDetail(model, nowMillis, stale), style = PaddockTokens.type.secondary, color = c.dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (loud && enabled) Icon(PaddockIcons.Chevron, contentDescription = null, tint = c.faint, modifier = Modifier.size(20.dp))
        else StateDot(model.state)
    }
}

/**
 * The first blocked agent, expanded into a card: who it is, the captured prompt on a slab, and Review prompt as the
 * primary action. The prompt is the last lines of herdr's detection text for that pane, shown as plain text through the
 * same stripping as Output; when it cannot be read the card says so and the action still opens the output. Only a live
 * row is ever expanded.
 */
@Composable
fun ExpandedAgentRow(model: AgentRowModel, nowMillis: Long, preview: PreviewState, onOpen: () -> Unit, onReview: () -> Unit, modifier: Modifier = Modifier) {
    val c = PaddockTokens.colors
    val shape = RoundedCornerShape(PaddockTokens.radii.card)
    Column(
        modifier.fillMaxWidth().clip(shape).background(c.wash(c.needsYou)).border(1.dp, c.washBorder(c.needsYou), shape).padding(PaddockTokens.spacing.cardPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button, onClickLabel = "Open ${model.title}", onClick = onOpen)
                .heightIn(min = PaddockTokens.spacing.touchTarget)
                .semantics(mergeDescendants = true) { contentDescription = rowDescription(model, nowMillis) },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MonogramTile(model.agentKind)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(model.title, style = PaddockTokens.type.rowTitle, color = c.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(rowDetail(model, nowMillis, stale = false), style = PaddockTokens.type.secondary, color = c.dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(PaddockTokens.radii.slab)).background(c.slab)
                .border(1.dp, c.line(), RoundedCornerShape(PaddockTokens.radii.slab)).padding(12.dp),
        ) {
            when (preview) {
                PreviewState.Loading -> Text("Reading the prompt…", style = PaddockTokens.type.secondary, color = c.dim)
                PreviewState.Unavailable -> Text("The prompt could not be read. Review prompt opens the output.", style = PaddockTokens.type.secondary, color = c.dim)
                is PreviewState.Showing ->
                    if (preview.lines.isEmpty()) Text("Nothing was captured. Review prompt opens the output.", style = PaddockTokens.type.secondary, color = c.dim)
                    else Column(Modifier.semantics(mergeDescendants = true) { contentDescription = "Captured prompt: " + preview.lines.joinToString(". ") { it.text.trim() }.trim() }) {
                        preview.lines.forEach { line -> Text(ansiLineToText(line, c), style = PaddockTokens.type.slab, color = c.text) }
                    }
            }
        }
        PaddockButton("Review prompt", onReview, kind = ButtonKind.Primary, small = true)
    }
}

/** Ready agents folded into one row while something else needs the user: "2 agents ready — api, docs". */
@Composable
fun ReadySummaryRow(rows: List<AgentRowModel>, onExpand: () -> Unit, modifier: Modifier = Modifier) {
    val c = PaddockTokens.colors
    val shape = RoundedCornerShape(PaddockTokens.radii.row)
    val names = rows.map { r -> r.context.substringBefore(" › ").ifEmpty { r.title } }.distinct()
    val shown = names.take(3).joinToString(", ") + if (names.size > 3) " +${names.size - 3}" else ""
    val text = "${rows.size} ${if (rows.size == 1) "agent" else "agents"} ready — $shown"
    Row(
        modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.line(), shape)
            .clickable(role = Role.Button, onClickLabel = "Show the ready agents", onClick = onExpand)
            .heightIn(min = PaddockTokens.spacing.touchTarget).padding(horizontal = 14.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) { contentDescription = text },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text, style = PaddockTokens.type.secondary, color = c.text, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Icon(PaddockIcons.Chevron, contentDescription = null, tint = c.faint, modifier = Modifier.size(20.dp))
    }
}

/**
 * The herd summary under the title. The "need you" part is the only coloured word; each part keeps its words together
 * (no break inside "2 need you") and the line breaks between parts. [flag] is a host fact in the attention colour.
 */
@Composable
fun HerdSummary(text: String, modifier: Modifier = Modifier, flag: String? = null, dimmed: Boolean = false) {
    val c = PaddockTokens.colors
    val parts = text.split(" · ")
    val styled = buildAnnotatedString {
        parts.forEachIndexed { i, part ->
            // The dot stays with the part before it, so a wrapped line never starts with "·".
            if (i > 0) append("\u00A0· ")
            val words = part.replace(' ', ' ')
            if (!dimmed && ("need you" in part || "needs you" in part) && !part.startsWith("Nothing")) withStyle(SpanStyle(color = c.needsYou, fontWeight = FontWeight.SemiBold)) { append(words) }
            else append(words)
        }
        if (flag != null) { append("\u00A0· "); withStyle(SpanStyle(color = c.attention)) { append(flag.replace(' ', ' ')) } }
    }
    Text(
        styled, modifier.fillMaxWidth().semantics { contentDescription = listOfNotNull(text, flag).joinToString(", ") },
        style = PaddockTokens.type.summary, color = c.dim,
    )
}

/** The host chip's health. */
enum class HostHealth { Live, Degraded, Connecting }

/**
 * The watched machine as a chip with its reachability dot, then how fresh the screen is ("live · 3 s", "as of 14:02")
 * on a dashed chip. Read together by TalkBack.
 */
@Composable
fun HostChip(name: String, status: String, modifier: Modifier = Modifier, health: HostHealth = HostHealth.Live) {
    val c = PaddockTokens.colors
    val pill = RoundedCornerShape(50)
    val healthWord = when (health) { HostHealth.Live -> "live"; HostHealth.Degraded -> "not live"; HostHealth.Connecting -> "connecting" }
    Row(
        modifier.semantics(mergeDescendants = true) { contentDescription = "$name, $healthWord, $status" },
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        val (bg, border, fg, dot) = when (health) {
            HostHealth.Live -> listOf(c.accent.copy(alpha = 0.14f), c.accent.copy(alpha = 0.5f), c.title, c.done)
            HostHealth.Degraded -> listOf(c.surface, c.attention.copy(alpha = 0.5f), c.attention, c.attention)
            HostHealth.Connecting -> listOf(c.surface, c.control(), c.text, c.faint)
        }
        Row(
            Modifier.heightIn(min = 34.dp).clip(pill).background(bg).border(1.dp, border, pill).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Dot(dot)
            Text(name, style = PaddockTokens.type.chip, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val dash = with(LocalDensity.current) { floatArrayOf(4.dp.toPx(), 3.dp.toPx()) }
        val line = c.control()
        Box(
            Modifier.heightIn(min = 34.dp)
                .drawBehind {
                    drawRoundRect(line, cornerRadius = CornerRadius(size.height / 2), style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(dash)))
                }
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) { Text(status, style = PaddockTokens.type.chip, color = c.dim, maxLines = 1) }
    }
}

/**
 * A host fact the user cannot miss: unreachable, stale, a changed key, a missing grant. A warning icon, the sentence,
 * and when there is one, the action as an outlined button in the same colour (below the sentence at large font sizes).
 */
@Composable
fun Banner(text: String, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: () -> Unit = {}) {
    val c = PaddockTokens.colors
    val shape = RoundedCornerShape(PaddockTokens.radii.row)
    val stacked = LocalDensity.current.fontScale >= 1.3f
    val body: @Composable (Modifier) -> Unit = { m ->
        Row(m, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(PaddockIcons.Warning, contentDescription = null, tint = c.attention, modifier = Modifier.size(20.dp))
            Text(text, style = PaddockTokens.type.secondary, color = c.attention, modifier = Modifier.weight(1f))
        }
    }
    val action: @Composable () -> Unit = {
        if (actionLabel != null) PaddockButton(actionLabel, onAction, kind = ButtonKind.Ghost, small = true, fillWidth = stacked, tint = c.attention)
    }
    val frame = modifier.fillMaxWidth().clip(shape).background(c.bannerWash(c.attention)).border(1.dp, c.bannerBorder(c.attention), shape)
        .padding(horizontal = 12.dp, vertical = 10.dp)
    if (stacked || actionLabel == null) {
        Column(frame, verticalArrangement = Arrangement.spacedBy(10.dp)) { body(Modifier.fillMaxWidth()); action() }
    } else {
        Row(frame, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            body(Modifier.weight(1f))
            action()
        }
    }
}

/** A short explanatory note under a control, 12 sp dim, with an optional leading icon. */
@Composable
fun Note(text: androidx.compose.ui.text.AnnotatedString, modifier: Modifier = Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    val c = PaddockTokens.colors
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        if (icon != null) Icon(icon, contentDescription = null, tint = c.dim, modifier = Modifier.size(16.dp).padding(top = 1.dp))
        Text(text, style = PaddockTokens.type.note, color = c.dim, modifier = Modifier.weight(1f))
    }
}

@Composable
fun Note(text: String, modifier: Modifier = Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) =
    Note(androidx.compose.ui.text.AnnotatedString(text), modifier, icon)

/** Prose with the given [mono] fragments in the mono face: "Append it to `~/.ssh/authorized_keys` …". */
@Composable
fun withMono(text: String, vararg mono: String): androidx.compose.ui.text.AnnotatedString = buildAnnotatedString {
    var rest = text
    while (rest.isNotEmpty()) {
        val hit = mono.mapNotNull { m -> rest.indexOf(m).takeIf { it >= 0 }?.let { it to m } }.minByOrNull { it.first }
        if (hit == null) { append(rest); break }
        append(rest.substring(0, hit.first))
        withStyle(SpanStyle(fontFamily = PaddockTokens.type.monoFact.fontFamily, fontFeatureSettings = "tnum")) { append(hit.second) }
        rest = rest.substring(hit.first + hit.second.length)
    }
}

/** A thin divider at the line colour. */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().heightIn(min = 1.dp, max = 1.dp).background(PaddockTokens.colors.line()))
}
