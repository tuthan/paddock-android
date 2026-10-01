package io.github.tuthan.paddock.ui.screens

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import io.github.tuthan.paddock.attention.AgeText
import io.github.tuthan.paddock.attention.AgentRowModel
import io.github.tuthan.paddock.attention.HomeModel
import io.github.tuthan.paddock.attention.Section
import io.github.tuthan.paddock.attention.StateWord
import io.github.tuthan.paddock.live.BlockedPreview
import io.github.tuthan.paddock.ui.components.AgentRow
import io.github.tuthan.paddock.ui.components.Banner
import io.github.tuthan.paddock.ui.components.ExpandedAgentRow
import io.github.tuthan.paddock.ui.components.Hairline
import io.github.tuthan.paddock.ui.components.HerdSummary
import io.github.tuthan.paddock.ui.components.HostChip
import io.github.tuthan.paddock.ui.components.HostHealth
import io.github.tuthan.paddock.ui.components.HostKicker
import io.github.tuthan.paddock.ui.components.Note
import io.github.tuthan.paddock.ui.components.PaddockIconButton
import io.github.tuthan.paddock.ui.components.ReadySummaryRow
import io.github.tuthan.paddock.ui.components.ScreenHeader
import io.github.tuthan.paddock.ui.components.SectionKicker
import io.github.tuthan.paddock.ui.theme.PaddockIcons
import io.github.tuthan.paddock.ui.theme.PaddockTokens
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What the home screen is showing. Degraded keeps the last model so rows stay visible, dimmed, with their age. */
sealed interface HomeUiState {
    val hostName: String

    data class Loading(override val hostName: String) : HomeUiState

    /** Streams live and a read installed after them. [ageMillis] is the age of that read. */
    data class Live(override val hostName: String, val model: HomeModel, val ageMillis: Long) : HomeUiState

    /** The host cannot be trusted right now. [reason] is a host fact ("herdr not answering"), not an agent state. */
    data class Degraded(
        override val hostName: String,
        val model: HomeModel?,
        val reason: String,
        val ageMillis: Long?,
        val recoveryLabel: String? = null,
    ) : HomeUiState
}

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

/** "14:02" in the phone's zone. */
fun clockLabel(millis: Long): String = CLOCK.format(Instant.ofEpochMilli(millis))

/**
 * Home: the summary and the host, then the herd in attention order. Blocked and done rows carry the colour; working,
 * ready and unknown stay quiet. While something needs the user, several ready agents fold into one row. A degraded
 * host keeps its last rows, dimmed and dated, with no actions. The order never changes under a finger: while the list
 * is touched or scrolling it holds the order it had, and catches up when let go.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HerdHome(
    state: HomeUiState,
    nowMillis: Long,
    modifier: Modifier = Modifier,
    onOpenAgent: (AgentRowModel) -> Unit = {},
    onRecovery: () -> Unit = {},
    /** The captured prompt of the first blocked agent, when one was read. Only a live host expands that row. */
    preview: BlockedPreview? = null,
    onReview: (AgentRowModel) -> Unit = {},
    onSettings: () -> Unit = {},
    /** Pull down to read the herd again; null hides the gesture. */
    onRefresh: (() -> Unit)? = null,
    refreshing: Boolean = false,
    clock: (Long) -> String = ::clockLabel,
) {
    val live = when (state) { is HomeUiState.Live -> state.model; is HomeUiState.Degraded -> state.model; is HomeUiState.Loading -> null }
    val enabled = state is HomeUiState.Live
    val listState = rememberLazyListState()
    var touching by remember { mutableStateOf(false) }
    val held = remember { object { var model: HomeModel? = null } }
    val frozen = touching || listState.isScrollInProgress
    val model = if (frozen) held.model ?: live else live
    SideEffect { if (!frozen) held.model = live }
    var readyOpen by rememberSaveable { mutableStateOf(false) }

    val firstBlockedId = model?.rows?.firstOrNull { it.state == StateWord.Blocked }?.key?.target?.terminalId
    val (health, chipStatus) = when (state) {
        is HomeUiState.Loading -> HostHealth.Connecting to "connecting"
        is HomeUiState.Live -> HostHealth.Live to "live · " + AgeText.span(state.ageMillis).removeSuffix(" ago")
        is HomeUiState.Degraded -> HostHealth.Degraded to (state.ageMillis?.let { "as of " + clock(nowMillis - it) } ?: "no data yet")
    }

    Column(modifier.fillMaxSize()) {
        ScreenHeader("Paddock") { PaddockIconButton(PaddockIcons.Settings, "Settings", onSettings) }
        val list: @Composable () -> Unit = {
            LazyColumn(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        touching = true
                        do { val e = awaitPointerEvent(PointerEventPass.Initial) } while (e.changes.any { it.pressed })
                        touching = false
                    }
                },
                state = listState,
                contentPadding = PaddingValues(start = PaddockTokens.spacing.gutter, end = PaddockTokens.spacing.gutter, top = 0.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "summary") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        val summary = live?.summary ?: if (state is HomeUiState.Loading) "Connecting…" else "No data yet"
                        HerdSummary(summary, flag = if (state is HomeUiState.Degraded) "not live" else null, dimmed = state !is HomeUiState.Live)
                        HostChip(state.hostName, chipStatus, health = health)
                    }
                }
                if (state is HomeUiState.Degraded) {
                    item(key = "banner") { Banner(state.reason, Modifier.padding(top = 4.dp), actionLabel = state.recoveryLabel, onAction = onRecovery) }
                }
                if (model != null && model.rows.isEmpty() && state is HomeUiState.Live) {
                    item(key = "quiet") { Text("Start an agent in herdr and it appears here.", style = PaddockTokens.type.body, color = PaddockTokens.colors.dim, modifier = Modifier.padding(top = 8.dp)) }
                }
                if (model != null && state is HomeUiState.Degraded && model.rows.isNotEmpty()) {
                    // Every row is the stale host's: one dated label, then the rows as they were, without actions.
                    item(key = "stale-h") { HostKicker(state.hostName + (state.ageMillis?.let { " · as of " + clock(nowMillis - it) } ?: "")) }
                    items(model.rows, key = { it.key.target.terminalId }) { row ->
                        AgentRow(row, nowMillis, enabled = false, modifier = Modifier.animateItem())
                    }
                } else if (model != null && state is HomeUiState.Live) {
                    model.sections.forEach { (section, rows) ->
                        item(key = "h-${section.name}") { SectionKicker(section, rows.size, Modifier.animateItem()) }
                        val fold = section == Section.Ready && !model.quiet && rows.size >= 2 && !readyOpen
                        if (fold) {
                            item(key = "ready-fold") { ReadySummaryRow(rows, onExpand = { readyOpen = true }, modifier = Modifier.animateItem()) }
                        } else items(rows, key = { it.key.target.terminalId }) { row ->
                            val expanded = preview != null && row.state == StateWord.Blocked && row.key.target.terminalId == preview.terminalId && row.key.target.terminalId == firstBlockedId
                            if (expanded) ExpandedAgentRow(row, nowMillis, preview!!.state, onOpen = { onOpenAgent(row) }, onReview = { onReview(row) }, modifier = Modifier.animateItem())
                            else AgentRow(row, nowMillis, enabled = enabled, modifier = Modifier.animateItem(), onClick = { onOpenAgent(row) })
                        }
                    }
                    if (model.quiet && model.rows.isNotEmpty() && onRefresh != null) {
                        item(key = "quiet-note") {
                            Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Hairline()
                                Note("Nothing needs you. Paddock stays quiet until an agent blocks or finishes. Pull down to read the herd again.")
                            }
                        }
                    }
                }
                item(key = "end") { Spacer(Modifier.height(8.dp)) }
            }
        }
        Box(Modifier.weight(1f)) {
            if (onRefresh != null) PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) { list() }
            else list()
        }
    }
}
