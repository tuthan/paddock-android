package io.github.tuthan.paddock.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.tuthan.paddock.answers.DecisionEntryModel
import io.github.tuthan.paddock.answers.DecisionModel
import io.github.tuthan.paddock.ops.ResultTone
import io.github.tuthan.paddock.ui.components.Banner
import io.github.tuthan.paddock.ui.components.ButtonKind
import io.github.tuthan.paddock.ui.components.ButtonPair
import io.github.tuthan.paddock.ui.components.Chip
import io.github.tuthan.paddock.ui.components.Note
import io.github.tuthan.paddock.ui.components.PaddockButton
import io.github.tuthan.paddock.ui.components.ScreenHeader
import io.github.tuthan.paddock.ui.theme.PaddockIcons
import io.github.tuthan.paddock.ui.theme.PaddockTokens

/** What the sheet's buttons do. Each one is a no-op where the model does not offer it. */
data class DecisionActions(
    /** Yes and No say which request they were drawn for (the whole id), so a tap is only ever an answer to what the screen showed. */
    val onYes: (requestId: String) -> Unit = {},
    val onNo: (requestId: String) -> Unit = {},
    val onReviewNewer: () -> Unit = {},
    val onOpenTerminal: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onDismissResult: () -> Unit = {},
    /** Reads the host's files after an answer whose outcome is unknown. */
    val onSettle: () -> Unit = {},
    /** Opens the guarded-answers setup (the hook is not installed or not readable on this machine). */
    val onSetUp: (() -> Unit)? = null,
)

const val DECISION_FACT =
    "Yes or No answers this one request and nothing else. Paddock never sends \"always allow\". The dialog on the desktop stays open until somebody answers: whoever answers first wins."

/**
 * A Claude Code permission request that the host's hook published, shown whole. The tool input is the slab, scrolling inside its box,
 * exactly what Claude Code would run: it is never summarised into a button or shortened here. Yes and No are on only for a request
 * that was read completely, has time left and is still the newest ([DecisionModel.canAnswer]); otherwise both are off and one sentence
 * says which condition failed. An answer is a record of what was written and what the host's files show of it; the sheet never claims
 * that Claude Code applied it.
 *
 * [awaitsSettle]: an earlier answer from this phone ended unknown; the sheet offers a re-read of the host's files and nothing else.
 */
@Composable
fun DecisionSheet(
    header: AgentHeader,
    model: DecisionModel,
    awaitsSettle: Boolean,
    actions: DecisionActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** What the last re-read of the host's files found; kept until the sheet is left. */
    notice: String? = null,
) {
    val c = PaddockTokens.colors
    Column(modifier.fillMaxSize()) {
        ScreenHeader("Permission request", onBack = onBack, subtitle = header.title.ifEmpty { null }, compact = true, backDescription = "Back")
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(start = PaddockTokens.spacing.gutter, end = PaddockTokens.spacing.gutter, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            notice?.let { Note(it, icon = PaddockIcons.Eye, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            model.readError?.let { Banner("Could not read the request: $it", actionLabel = actions.onSetUp?.let { "Set up" } ?: "Try again", onAction = actions.onSetUp ?: actions.onRefresh) }
            when (model.kind) {
                DecisionModel.Kind.Loading -> Text("Reading the request…", style = PaddockTokens.type.body, color = c.dim)
                DecisionModel.Kind.NoRequest -> {
                    Text(model.whyNot.orEmpty(), style = PaddockTokens.type.body, color = c.title, modifier = Modifier.padding(top = 8.dp))
                    if (awaitsSettle) SettleBanner(actions)
                    PaddockButton("Check again", actions.onRefresh, kind = ButtonKind.Secondary)
                    PaddockButton("Open terminal", actions.onOpenTerminal, kind = ButtonKind.Ghost)
                }
                DecisionModel.Kind.Request -> RequestBody(model, awaitsSettle, actions)
            }
        }
    }
}

@Composable
private fun SettleBanner(actions: DecisionActions) =
    Banner(
        "An earlier answer from this phone ended with its outcome unknown. Read the host's files to see what became of it. Nothing is sent again.",
        actionLabel = "Read the files", onAction = actions.onSettle,
    )

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.RequestBody(model: DecisionModel, awaitsSettle: Boolean, actions: DecisionActions) {
    val c = PaddockTokens.colors
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        Chip(model.toolName.orEmpty(), tone = c.needsYou, description = "Tool: ${model.toolName}")
        model.permissionMode?.let { Chip("Mode $it", description = "Permission mode $it") }
        model.requestShort?.let { Chip("Request $it", description = "Request number $it") }
        model.timeLeft?.let { Chip(it, tone = if (it == "Expired") c.attention else null, description = it) }
    }
    val shape = RoundedCornerShape(PaddockTokens.radii.row)
    Box(Modifier.weight(1f).fillMaxWidth().clip(shape).background(c.slab).border(1.dp, c.line(), shape)) {
        Text(
            model.inputText.orEmpty(), style = PaddockTokens.type.monoFact, color = c.text,
            modifier = Modifier.testTag("tool-input").fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
        )
    }
    model.inputNote?.let { Banner(it) }
    // At most 40% of the window, scrolling inside it, so a long sentence or a large font cannot push the buttons off the screen.
    val cap = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height.let { with(androidx.compose.ui.platform.LocalDensity.current) { it.toDp() } } * 0.4f
    Column(
        Modifier.fillMaxWidth().heightIn(max = cap).verticalScroll(rememberScrollState()).semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        model.result?.let { line ->
            when (model.resultTone) {
                ResultTone.Ok -> Note(line, icon = PaddockIcons.Eye)
                ResultTone.Unknown -> Banner(line, actionLabel = "Read the files", onAction = actions.onSettle)
                else -> Banner(line, actionLabel = "Open terminal", onAction = actions.onOpenTerminal)
            }
            if (model.resultTone != ResultTone.Unknown && model.resultTone != ResultTone.Ok) PaddockButton("Dismiss", actions.onDismissResult, kind = ButtonKind.Ghost, small = true, fillWidth = false)
        }
        if (awaitsSettle && model.resultTone != ResultTone.Unknown) SettleBanner(actions)
        model.status?.let { s ->
            if (model.statusTone == ResultTone.Refused) Banner(s, actionLabel = "Open terminal", onAction = actions.onOpenTerminal) else Note(s, icon = PaddockIcons.Eye)
        }
        // While a read of the host's files is on offer it is the one thing to do, and the generic "re-read before sending" sentence would only repeat it.
        if (!awaitsSettle) model.whyNot?.let { Note(it, icon = PaddockIcons.Warning) }
        if (model.offersNewer) Banner("A newer request (${model.newerToolName}) is waiting.", actionLabel = "Review the new request", onAction = actions.onReviewNewer)
    }
    ButtonPair(
        { m -> PaddockButton(if (model.sending) "Sending…" else "Yes", { actions.onYes(model.requestId.orEmpty()) }, m, kind = ButtonKind.Primary, enabled = model.canAnswer) },
        { m -> PaddockButton("No", { actions.onNo(model.requestId.orEmpty()) }, m, kind = ButtonKind.Secondary, enabled = model.canAnswer) },
    )
    PaddockButton("Answer in the terminal instead", actions.onOpenTerminal, kind = ButtonKind.Ghost)
    Note(DECISION_FACT)
}

/**
 * The Output tab's way in: one line saying Claude Code is waiting for a Yes or No and for which tool, and a button to the sheet.
 * It carries no answer itself; the request is read in full on the sheet.
 */
@Composable
fun DecisionEntry(model: DecisionEntryModel, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val c = PaddockTokens.colors
    val shape = RoundedCornerShape(PaddockTokens.radii.row)
    val what = "Claude Code is waiting for a Yes or No · ${model.toolName ?: "too large to show here"}" + (model.timeLeft?.let { " · $it" }.orEmpty())
    Row(
        modifier.fillMaxWidth().clip(shape).background(c.needsYou.copy(alpha = 0.09f)).border(1.dp, c.needsYou.copy(alpha = 0.30f), shape)
            .clickable(role = Role.Button, onClickLabel = "Review the request and answer", onClick = onOpen)
            .semantics(mergeDescendants = true) { contentDescription = "$what. Review and answer." }
            .padding(horizontal = 12.dp, vertical = 12.dp).testTag("decision-entry"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(PaddockIcons.Warning, contentDescription = null, tint = c.needsYou, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(what, style = PaddockTokens.type.secondary, color = c.title)
            Text("Review and answer", style = PaddockTokens.type.secondary, color = c.needsYou)
        }
        Icon(PaddockIcons.Chevron, contentDescription = null, tint = c.faint, modifier = Modifier.size(20.dp))
    }
}
