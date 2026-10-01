package io.github.tuthan.paddock.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.tuthan.paddock.attention.AgeText
import io.github.tuthan.paddock.attention.HomeModel
import io.github.tuthan.paddock.attention.StateWord
import io.github.tuthan.paddock.live.BlockedPreview
import io.github.tuthan.paddock.ui.components.ExpandedAgentRow
import io.github.tuthan.paddock.ui.components.AgentRow
import io.github.tuthan.paddock.ui.components.Banner
import io.github.tuthan.paddock.ui.components.HerdSummary
import io.github.tuthan.paddock.ui.components.HostChip
import io.github.tuthan.paddock.ui.components.Kicker
import io.github.tuthan.paddock.ui.theme.PaddockTokens

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

/**
 * Home: the summary, the host chip with its age, then sections in attention order. Data refresh never moves the scroll
 * position: rows are keyed by terminal, so an insert above does not shift what the user is reading unless they are
 * at the top.
 */
@Composable
fun HerdHome(
    state: HomeUiState,
    nowMillis: Long,
    modifier: Modifier = Modifier,
    onOpenAgent: (paneId: String) -> Unit = {},
    onRecovery: () -> Unit = {},
    /** The captured prompt of the first blocked agent, when one was read. Only a live host expands that row. */
    preview: BlockedPreview? = null,
    onReview: (paneId: String) -> Unit = {},
) {
    val model = when (state) { is HomeUiState.Live -> state.model; is HomeUiState.Degraded -> state.model; is HomeUiState.Loading -> null }
    val enabled = state is HomeUiState.Live
    val firstBlockedId = model?.rows?.firstOrNull { it.state == StateWord.Blocked }?.key?.target?.terminalId
    val chipStatus = when (state) {
        is HomeUiState.Loading -> "connecting"
        is HomeUiState.Live -> "live · " + AgeText.span(state.ageMillis).replace(" ago", "")
        is HomeUiState.Degraded -> "stale" + (state.ageMillis?.let { " · " + AgeText.observed(it) } ?: "")
    }
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = PaddockTokens.spacing.gutter, vertical = PaddockTokens.spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(PaddockTokens.spacing.rowGap),
    ) {
        item(key = "summary") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HerdSummary(model?.summary ?: if (state is HomeUiState.Loading) "Connecting…" else "No data yet")
                HostChip(state.hostName, chipStatus)
            }
        }
        if (state is HomeUiState.Degraded) {
            item(key = "banner") {
                Banner(state.reason, actionLabel = state.recoveryLabel, onAction = onRecovery)
            }
        }
        if (model != null && model.rows.isEmpty() && state is HomeUiState.Live) {
            item(key = "quiet") { Text("Start an agent in herdr and it appears here.", style = PaddockTokens.type.body, color = PaddockTokens.colors.dim) }
        }
        model?.sections?.forEach { (section, rows) ->
            item(key = "h-${section.name}") { Kicker(section.heading, Modifier.padding(top = 6.dp)) }
            items(rows, key = { it.key.target.terminalId }) { row ->
                if (enabled && preview != null && row.state == StateWord.Blocked && row.key.target.terminalId == preview.terminalId && row.key.target.terminalId == firstBlockedId) {
                    ExpandedAgentRow(row, nowMillis, preview.state, onOpen = { onOpenAgent(row.paneId) }, onReview = { onReview(row.paneId) })
                } else AgentRow(row, nowMillis, enabled = enabled, onClick = { onOpenAgent(row.paneId) })
            }
        }
        item(key = "end") { Spacer(Modifier.height(24.dp)) }
    }
}
