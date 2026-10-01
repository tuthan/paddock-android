package io.github.tuthan.paddock.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.tuthan.paddock.attention.AgeText
import io.github.tuthan.paddock.attention.AgentRowModel
import io.github.tuthan.paddock.attention.StateWord
import io.github.tuthan.paddock.live.PreviewState
import io.github.tuthan.paddock.ui.theme.PaddockColors
import io.github.tuthan.paddock.ui.theme.PaddockTokens

/** Small caps section label. Marked as a heading so TalkBack can jump between sections. */
@Composable
fun Kicker(text: String, modifier: Modifier = Modifier, color: Color = PaddockTokens.colors.dim) {
    Text(
        text = text.uppercase(), style = PaddockTokens.type.kicker, color = color,
        modifier = modifier.semantics { heading() },
    )
}

internal fun PaddockColors.stateColor(state: StateWord): Color = when (state) {
    StateWord.Blocked -> needsYou
    StateWord.Done -> done
    StateWord.Working -> attention
    StateWord.Ready, StateWord.IdleNotReady -> faint
    StateWord.Unknown -> dim
}

/**
 * The state as a word beside a dot. The dot's shape differs (filled for known states, a ring for unknown) so the
 * state is never carried by colour alone; the word is the primary signal.
 */
@Composable
fun StateWord(state: StateWord, modifier: Modifier = Modifier) {
    val c = PaddockTokens.colors
    val color = c.stateColor(state)
    Row(modifier.clearAndSetSemantics { }, verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(10.dp)) {
            if (state == StateWord.Unknown) drawCircle(color, radius = size.minDimension / 2 - 1.5.dp.toPx(), center = Offset(size.width / 2, size.height / 2), style = Stroke(width = 1.5.dp.toPx()))
            else drawCircle(color)
        }
        Spacer(Modifier.width(6.dp))
        Text(state.word, style = PaddockTokens.type.secondary, color = if (state == StateWord.Ready || state == StateWord.IdleNotReady) c.dim else color)
    }
}

/**
 * One agent. Reads state word, then title, then context and age, which is also the TalkBack order. When [enabled] is
 * false (the host is degraded) the row is dimmed, shows its age and offers no action.
 */
@Composable
fun AgentRow(model: AgentRowModel, nowMillis: Long, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    val c = PaddockTokens.colors
    val observed = AgeText.observed(nowMillis - model.observedAtMillis)
    val description = listOf(model.state.word, model.title, model.context.ifEmpty { null }, observed).filterNotNull().joinToString(", ")
    val shape = RoundedCornerShape(PaddockTokens.radii.row)
    Column(
        modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.55f)
            .background(c.surface, shape)
            .border(1.dp, if (model.state == StateWord.Blocked && enabled) c.washBorder(c.needsYou) else c.line(), shape)
            .then(if (enabled) Modifier.clickable(role = Role.Button, onClickLabel = "Open ${model.title}", onClick = onClick).minimumInteractiveComponentSize() else Modifier)
            .heightIn(min = PaddockTokens.spacing.touchTarget)
            .padding(PaddockTokens.spacing.cardPadding)
            .semantics(mergeDescendants = true) { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        StateWord(model.state)
        Text(model.title, style = PaddockTokens.type.rowTitle, color = c.title, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Text(
            listOf(model.context.ifEmpty { null }, observed).filterNotNull().joinToString(" · "),
            style = PaddockTokens.type.secondary, color = c.dim, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The first blocked agent, expanded: who it is, the captured prompt in a slab, and a Review prompt action. The prompt is
 * the last lines of herdr's detection text for that pane, shown as plain text through the same stripping as Output; when
 * it cannot be read the row says so and the action still opens the output, which reads again on open. Only an enabled
 * (live) row is ever expanded: a degraded host shows its rows compact, dimmed and without actions.
 */
@Composable
fun ExpandedAgentRow(model: AgentRowModel, nowMillis: Long, preview: PreviewState, onOpen: () -> Unit, onReview: () -> Unit, modifier: Modifier = Modifier) {
    val c = PaddockTokens.colors
    val observed = AgeText.observed(nowMillis - model.observedAtMillis)
    val description = listOf(model.state.word, model.title, model.context.ifEmpty { null }, observed).filterNotNull().joinToString(", ")
    val shape = RoundedCornerShape(PaddockTokens.radii.row)
    Column(
        modifier.fillMaxWidth().background(c.surface, shape).border(1.dp, c.washBorder(c.needsYou), shape).padding(PaddockTokens.spacing.cardPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(
            Modifier.fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = "Open ${model.title}", onClick = onOpen).minimumInteractiveComponentSize()
                .semantics(mergeDescendants = true) { contentDescription = description },
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            StateWord(model.state)
            Text(model.title, style = PaddockTokens.type.rowTitle, color = c.title, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(model.context.ifEmpty { null }, observed).filterNotNull().joinToString(" · "),
                style = PaddockTokens.type.secondary, color = c.dim, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Kicker("What it is asking")
        Column(
            Modifier.fillMaxWidth().background(c.slab, RoundedCornerShape(PaddockTokens.radii.key)).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            when (preview) {
                PreviewState.Loading -> Text("Reading the prompt…", style = PaddockTokens.type.secondary, color = c.dim)
                PreviewState.Unavailable -> Text("The prompt could not be read. Review prompt opens the output.", style = PaddockTokens.type.secondary, color = c.dim)
                is PreviewState.Showing ->
                    if (preview.lines.isEmpty()) Text("Nothing was captured. Review prompt opens the output.", style = PaddockTokens.type.secondary, color = c.dim)
                    else Column(
                        Modifier.semantics(mergeDescendants = true) { contentDescription = "Captured prompt: " + preview.lines.joinToString(". ") { it.text.trim() }.trim() },
                    ) {
                        preview.lines.forEach { line -> Text(ansiLineToText(line, c), style = PaddockTokens.type.monoFact, color = c.text) }
                    }
            }
        }
        PaddockButton("Review prompt", onReview, kind = ButtonKind.Quiet)
    }
}

/** The one-sentence herd summary at the top of Home. */
@Composable
fun HerdSummary(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.fillMaxWidth().semantics { heading() }, style = PaddockTokens.type.screenTitle, color = PaddockTokens.colors.title)
}

/** The host and how old what the screen shows is. Tapping it is Phase 04's host sheet, so it is only a label here. */
@Composable
fun HostChip(name: String, status: String, modifier: Modifier = Modifier) {
    val c = PaddockTokens.colors
    Row(
        modifier.background(c.field, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp).semantics(mergeDescendants = true) { contentDescription = "$name, $status" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, style = PaddockTokens.type.secondary, color = c.title)
        Text("  ·  $status", style = PaddockTokens.type.secondary, color = c.dim)
    }
}

/** A message the user cannot miss: an unreachable host, a missing grant. [action] is optional and is a real button. */
@Composable
fun Banner(text: String, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: () -> Unit = {}) {
    val c = PaddockTokens.colors
    val shape = RoundedCornerShape(PaddockTokens.radii.row)
    Column(
        modifier.fillMaxWidth().background(c.wash(c.attention), shape).border(1.dp, c.washBorder(c.attention), shape).padding(PaddockTokens.spacing.cardPadding),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text, style = PaddockTokens.type.body, color = c.title)
        if (actionLabel != null) {
            Text(
                actionLabel, style = PaddockTokens.type.rowTitle, color = c.accent,
                modifier = Modifier.clickable(role = Role.Button, onClick = onAction).minimumInteractiveComponentSize(),
            )
        }
    }
}
