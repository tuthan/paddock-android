package io.github.tuthan.paddock.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.tuthan.paddock.answers.AnswerSetup
import io.github.tuthan.paddock.relay.RelayState
import io.github.tuthan.paddock.ui.components.Banner
import io.github.tuthan.paddock.ui.components.ButtonKind
import io.github.tuthan.paddock.ui.components.Chip
import io.github.tuthan.paddock.ui.components.Fact
import io.github.tuthan.paddock.ui.components.Kicker
import io.github.tuthan.paddock.ui.components.Note
import io.github.tuthan.paddock.ui.components.PaddockButton
import io.github.tuthan.paddock.ui.components.ScreenHeader
import io.github.tuthan.paddock.ui.theme.PaddockIcons
import io.github.tuthan.paddock.ui.theme.PaddockTokens

/** What the screen knows about the two guarded-answers scripts on the host right now. */
sealed interface GuardedHostState {
    /** No live connection to the machine, so nothing can be read or installed from here. */
    data object NotConnected : GuardedHostState
    data object Reading : GuardedHostState
    data class Failed(val message: String) : GuardedHostState
    data class Known(val setup: AnswerSetup) : GuardedHostState
}

/** Which of the two pasted texts was last copied, for the button's label. */
enum class GuardedCopied { None, Config, Settings }

data class GuardedAnswersUi(
    val machine: String,
    val decideSha256: String,
    val hookSha256: String,
    val host: GuardedHostState,
    val installing: Boolean = false,
    val installError: String? = null,
    val windowSeconds: Int,
    /** The two texts to paste on the host, shown in full before they can be copied; null while the host's paths are not known. */
    val configCommand: String? = null,
    val settingsSnippet: String? = null,
    val copied: GuardedCopied = GuardedCopied.None,
)

/** The windows the screen offers, in seconds. The hook's own limit is [io.github.tuthan.paddock.answers.AnswerSetupText.MAX_WINDOW_SECONDS]. */
val GUARDED_WINDOWS = listOf(30, 60, 120, 300)

/** The Settings row's one line. */
const val GUARDED_ROW = "Yes or No for Claude Code's permission prompts, for the one request shown. Off until set up on the machine."

const val GUARDED_HOW =
    "When Claude Code asks for permission to run a tool, a hook on the machine writes the request to a file and waits for a Yes or No from this phone, for the window below. The desktop's own dialog is on screen the whole time, and whoever answers first wins."

const val GUARDED_SCOPE =
    "Only Claude Code's permission prompts, and only the one request an answer was read for. Every other question an agent asks stays manual, in the terminal. Paddock sends no keys for this: it writes one decision file, and the hook hands it to Claude Code."

const val GUARDED_LIMITS =
    "If the hook is not registered, is not running or runs out its window, Claude Code asks on the desktop as it always did. If you answer on the desktop in the same moment as the phone, Claude Code keeps the desktop's answer; Paddock then says the answer was written, never that it was applied."

/**
 * Guarded native answers (Phase 8): what they do and do not do, the hash of each script before anything is installed or copied, the
 * install of those two files on the user's say-so, and the two texts the user pastes on the machine (the hook's configuration and the
 * registration in Claude Code's settings). Paddock edits neither of those itself.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GuardedAnswers(
    ui: GuardedAnswersUi, onBack: () -> Unit, onInstall: () -> Unit, onCheck: () -> Unit, onWindow: (Int) -> Unit,
    onCopyConfig: () -> Unit, onCopySettings: () -> Unit, modifier: Modifier = Modifier,
) {
    val c = PaddockTokens.colors
    var confirm by rememberSaveable { mutableStateOf(false) }
    val known = (ui.host as? GuardedHostState.Known)?.setup
    val replacing = known != null && (known.decide is RelayState.Mismatch || known.hook is RelayState.Mismatch)
    if (confirm) {
        ConfirmDialog(
            if (replacing) "Replace the answer scripts on ${ui.machine}?" else "Install the answer scripts on ${ui.machine}?",
            "Paddock writes these two files, with owner-only permissions, and checks each hash after. It does not touch Claude Code's settings, does not register the hook and starts nothing.",
            confirm = if (replacing) "Replace" else "Install",
            onConfirm = { confirm = false; onInstall() }, onCancel = { confirm = false },
            facts = listOfNotNull(
                known?.hookDestination?.let { "Hook" to it }, "SHA-256 of the hook" to ui.hookSha256,
                known?.decideDestination?.let { "Writer" to it }, "SHA-256 of the writer" to ui.decideSha256,
            ),
        )
    }
    Column(modifier.fillMaxSize()) {
        ScreenHeader("Guarded answers", onBack = onBack, compact = true)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = PaddockTokens.spacing.gutter, end = PaddockTokens.spacing.gutter, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Yes or No from the phone", style = PaddockTokens.type.screenTitle, color = c.title)
            Text(GUARDED_HOW, style = PaddockTokens.type.body, color = c.text)
            Note(GUARDED_SCOPE)
            Banner(GUARDED_LIMITS, tint = c.accent, icon = PaddockIcons.Eye)
            Note("Needs Python 3.11 or newer on ${ui.machine}.")

            Kicker("The scripts", Modifier.padding(top = 8.dp))
            Fact("SHA-256 of paddock-claude-permission-hook.py", ui.hookSha256)
            Fact("SHA-256 of paddock-decide.py", ui.decideSha256)
            when (val h = ui.host) {
                GuardedHostState.NotConnected -> Banner("Paddock is not connected to ${ui.machine}, so it cannot see or install the scripts. Connect from the herd, then come back.")
                GuardedHostState.Reading -> Note("Reading the machine…")
                is GuardedHostState.Failed -> Banner("Could not read the machine: ${h.message}", actionLabel = "Try again", onAction = onCheck)
                is GuardedHostState.Known -> {
                    Fact("Hook on the host", h.setup.hookDestination)
                    Text(state(h.setup.hook, ui.machine), style = PaddockTokens.type.body, color = c.text)
                    Fact("Writer on the host", h.setup.decideDestination)
                    Text(state(h.setup.decide, ui.machine), style = PaddockTokens.type.body, color = c.text)
                }
            }
            ui.installError?.let { Banner(it) }
            val allCurrent = known != null && known.decide == RelayState.Current && known.hook == RelayState.Current
            val installLabel = when {
                ui.installing -> "Installing…"
                replacing -> "Replace the scripts…"
                allCurrent -> "Reinstall the scripts…"
                else -> "Install the scripts…"
            }
            PaddockButton(installLabel, { confirm = true }, enabled = known != null && !ui.installing, kind = if (allCurrent) ButtonKind.Ghost else ButtonKind.Primary, modifier = Modifier.testTag("guarded-install"))
            PaddockButton("Check the setup", onCheck, enabled = ui.host !is GuardedHostState.NotConnected && ui.host !is GuardedHostState.Reading, kind = ButtonKind.Ghost)

            Kicker("How long to wait", Modifier.padding(top = 8.dp))
            Text(
                "The hook holds Claude Code's permission request this long for an answer from the phone. After it, only the desktop's dialog is left. The number goes into the two texts below; the hook itself reads it from its configuration file on the machine.",
                style = PaddockTokens.type.body, color = c.text,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                for (w in GUARDED_WINDOWS) {
                    val on = w == ui.windowSeconds
                    Chip("$w s", tone = if (on) c.accent else null, onClick = { onWindow(w) }, description = if (on) "$w seconds, chosen" else "$w seconds")
                }
            }
            Note("0 turns the hook off: edit window_seconds in the hook's configuration on the machine.")

            Kicker("Paste on ${ui.machine}", Modifier.padding(top = 8.dp))
            Text("1. The hook's configuration. It is written only if none exists, so nothing you set is overwritten.", style = PaddockTokens.type.body, color = c.text)
            Pasteable(ui.configCommand)
            if (ui.configCommand != null) PaddockButton(if (ui.copied == GuardedCopied.Config) "Copied" else "Copy the configuration command", onCopyConfig, icon = PaddockIcons.Copy, kind = ButtonKind.Secondary)
            Text(
                "2. Register the hook. Add this under hooks.PermissionRequest in ~/.claude/settings.json, merging it with what is there. Paddock never edits that file. Claude Code's timeout is the window plus five seconds.",
                style = PaddockTokens.type.body, color = c.text,
            )
            Pasteable(ui.settingsSnippet)
            if (ui.settingsSnippet != null) PaddockButton(if (ui.copied == GuardedCopied.Settings) "Copied" else "Copy the registration", onCopySettings, icon = PaddockIcons.Copy, kind = ButtonKind.Secondary)
            Note("Claude Code reads its hook settings when a session starts, so start a new Claude Code session after registering.")
        }
    }
}

private fun state(s: RelayState, machine: String) = when (s) {
    RelayState.Current -> "Installed, and it is the pinned script."
    RelayState.Missing -> "Not installed on $machine yet."
    is RelayState.Mismatch -> "A different file is there. It is never run; installing replaces it."
}

@Composable
private fun Pasteable(text: String?) {
    if (text == null) return
    val shape = RoundedCornerShape(PaddockTokens.radii.row)
    val c = PaddockTokens.colors
    Text(
        text, style = PaddockTokens.type.monoFact, color = c.text,
        modifier = Modifier.fillMaxWidth().clip(shape).background(c.slab).border(1.dp, c.line(), shape).horizontalScroll(rememberScrollState()).padding(12.dp),
    )
}
