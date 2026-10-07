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

/** Which of the pasted texts was last copied, for the button's label. */
enum class GuardedCopied { None, Config, Registration }

/** The agents whose permission prompts the phone can answer, in the order the screen offers them. */
enum class GuardedAgent(val label: String) { Claude("Claude Code"), Codex("Codex"), Opencode("opencode") }

data class GuardedAnswersUi(
    val machine: String,
    val decideSha256: String,
    val hookSha256: String,
    val host: GuardedHostState,
    val installing: Boolean = false,
    val installError: String? = null,
    val windowSeconds: Int,
    /** The texts to paste on the host, shown in full before they can be copied; null while the host's paths are not known. [settingsSnippet] is Claude Code's. */
    val configCommand: String? = null,
    val settingsSnippet: String? = null,
    val copied: GuardedCopied = GuardedCopied.None,
    /** The opencode plugin's hash, or null on a build that ships none (the opencode part of the screen is then absent). */
    val opencodeSha256: String? = null,
    /** Codex's own window, in seconds: it shows no prompt on the desktop while the hook waits, so it is a delay and is chosen apart. */
    val codexWindowSeconds: Int = 20,
    val codexSnippet: String? = null,
    val opencodeCommand: String? = null,
)

/** The windows the screen offers, in seconds. The hook's own limit is [io.github.tuthan.paddock.answers.AnswerSetupText.MAX_WINDOW_SECONDS]. */
val GUARDED_WINDOWS = listOf(30, 60, 120, 300)

/** Codex's windows. Short on purpose: while the hook waits Codex shows nothing on the desktop, so each one is a delay of its prompt. */
val GUARDED_CODEX_WINDOWS = listOf(10, 20, 30, 60)

/** The machine page's one line. */
const val GUARDED_ROW = "Yes or No from the phone for Claude Code, Codex and opencode permission prompts, for the one request shown. Off until set up on the machine."

const val GUARDED_HOW =
    "When Claude Code, Codex or opencode asks for permission to run a tool, a hook on the machine writes the request to a file and waits for a Yes or No from this phone, for the window below. Claude Code and opencode keep their own dialog on the desktop the whole time, and whoever answers first wins. Codex shows nothing on the desktop until its hooks have returned, so it has a window of its own, and the desktop prompt waits for it."

const val GUARDED_SCOPE =
    "Only those agents' permission prompts, and only the one request an answer was read for. Every other question an agent asks stays manual, in the terminal. Paddock sends no keys for this: it writes one decision file, and the hook hands it to the agent."

const val GUARDED_LIMITS =
    "If the hook is not registered, is not running or runs out its window, the agent asks on the desktop as it always did. If you answer on the desktop in the same moment as the phone (Claude Code, opencode), the agent keeps the desktop's answer; Paddock then says the answer was written, never that it was applied."

const val GUARDED_CODEX_WINDOW =
    "Codex shows no prompt on the desktop while its hook waits, so this window is a delay of that prompt every time nobody answers from the phone. herdr also keeps the agent \"working\" until that prompt appears, so there is no alert for a Codex request: open the agent in Paddock to see it."

/**
 * Guarded native answers (Phase 8, for three agents): what they do and do not do, the hash of each script before anything is installed or
 * copied, the install of those files on the user's say-so, and the texts the user pastes on the machine (the hook's configuration and, for
 * the agent chosen, how to register the hook with it). Paddock edits no agent's settings itself.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GuardedAnswers(
    ui: GuardedAnswersUi, onBack: () -> Unit, onInstall: () -> Unit, onCheck: () -> Unit, onWindow: (Int) -> Unit,
    onCopyConfig: () -> Unit, onCopyRegistration: (GuardedAgent) -> Unit, modifier: Modifier = Modifier, onCodexWindow: (Int) -> Unit = {},
) {
    val c = PaddockTokens.colors
    var confirm by rememberSaveable { mutableStateOf(false) }
    var agentIndex by rememberSaveable { mutableStateOf(0) }
    val agent = GuardedAgent.entries[agentIndex.coerceIn(0, GuardedAgent.entries.size - 1)]
    val known = (ui.host as? GuardedHostState.Known)?.setup
    val replacing = known != null && (known.decide is RelayState.Mismatch || known.hook is RelayState.Mismatch || known.opencode is RelayState.Mismatch)
    if (confirm) {
        ConfirmDialog(
            if (replacing) "Replace the answer scripts on ${ui.machine}?" else "Install the answer scripts on ${ui.machine}?",
            "Paddock writes ${if (ui.opencodeSha256 != null) "these three files" else "these two files"}, with owner-only permissions, and checks each hash after. It does not touch any agent's settings, does not register the hook and starts nothing.",
            confirm = if (replacing) "Replace" else "Install",
            onConfirm = { confirm = false; onInstall() }, onCancel = { confirm = false },
            facts = listOfNotNull(
                known?.hookDestination?.let { "Hook" to it }, "SHA-256 of the hook" to ui.hookSha256,
                known?.decideDestination?.let { "Writer" to it }, "SHA-256 of the writer" to ui.decideSha256,
                known?.opencodeDestination?.let { "Plugin" to it }, ui.opencodeSha256?.let { "SHA-256 of the plugin" to it },
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
            ui.opencodeSha256?.let { Fact("SHA-256 of paddock-opencode-permission.js", it) }
            when (val h = ui.host) {
                GuardedHostState.NotConnected -> Banner("Paddock is not connected to ${ui.machine}, so it cannot see or install the scripts. Connect from the herd, then come back.")
                GuardedHostState.Reading -> Note("Reading the machine…")
                is GuardedHostState.Failed -> Banner("Could not read the machine: ${h.message}", actionLabel = "Try again", onAction = onCheck)
                is GuardedHostState.Known -> {
                    Fact("Hook on the host", h.setup.hookDestination)
                    Text(state(h.setup.hook, ui.machine), style = PaddockTokens.type.body, color = c.text)
                    Fact("Writer on the host", h.setup.decideDestination)
                    Text(state(h.setup.decide, ui.machine), style = PaddockTokens.type.body, color = c.text)
                    val plugin = h.setup.opencode
                    val pluginAt = h.setup.opencodeDestination
                    if (plugin != null && pluginAt != null) {
                        Fact("opencode plugin on the host", pluginAt)
                        Text(state(plugin, ui.machine), style = PaddockTokens.type.body, color = c.text)
                    }
                }
            }
            ui.installError?.let { Banner(it) }
            val allCurrent = known != null && known.decide == RelayState.Current && known.hook == RelayState.Current && (known.opencode ?: RelayState.Current) == RelayState.Current
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
                "The hook holds an agent's permission request this long for an answer from the phone. After it, only the desktop's dialog is left. The number goes into the texts below; the hook itself reads it from its configuration on the machine, which is the file that counts.",
                style = PaddockTokens.type.body, color = c.text,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                for (w in GUARDED_WINDOWS) {
                    val on = w == ui.windowSeconds
                    Chip("$w s", tone = if (on) c.accent else null, onClick = { onWindow(w) }, description = if (on) "$w seconds, chosen" else "$w seconds")
                }
            }
            Note("0 turns the hook off: edit window_seconds in the hook's configuration on the machine.")
            Text("Codex", style = PaddockTokens.type.rowTitle, color = c.title, modifier = Modifier.padding(top = 4.dp))
            Text(GUARDED_CODEX_WINDOW, style = PaddockTokens.type.body, color = c.text)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                for (w in GUARDED_CODEX_WINDOWS) {
                    val on = w == ui.codexWindowSeconds
                    Chip("$w s", tone = if (on) c.accent else null, onClick = { onCodexWindow(w) }, description = if (on) "Codex $w seconds, chosen" else "Codex $w seconds")
                }
            }

            Kicker("Paste on ${ui.machine}", Modifier.padding(top = 8.dp))
            Text("1. The hook's configuration, for every agent. It is written only if none exists, so nothing you set is overwritten.", style = PaddockTokens.type.body, color = c.text)
            Pasteable(ui.configCommand)
            if (ui.configCommand != null) PaddockButton(if (ui.copied == GuardedCopied.Config) "Copied" else "Copy the configuration command", onCopyConfig, icon = PaddockIcons.Copy, kind = ButtonKind.Secondary)

            Text("2. Register it for the agent you use. Each one is separate; do the ones you want.", style = PaddockTokens.type.body, color = c.text, modifier = Modifier.padding(top = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                for (a in GuardedAgent.entries) {
                    if (a == GuardedAgent.Opencode && ui.opencodeSha256 == null) continue
                    val on = a == agent
                    Chip(a.label, tone = if (on) c.accent else null, onClick = { agentIndex = a.ordinal }, description = if (on) "${a.label}, chosen" else a.label)
                }
            }
            val registration = when (agent) { GuardedAgent.Claude -> ui.settingsSnippet; GuardedAgent.Codex -> ui.codexSnippet; GuardedAgent.Opencode -> ui.opencodeCommand }
            Text(registerHow(agent), style = PaddockTokens.type.body, color = c.text)
            Pasteable(registration)
            if (registration != null) PaddockButton(if (ui.copied == GuardedCopied.Registration) "Copied" else "Copy the registration", { onCopyRegistration(agent) }, icon = PaddockIcons.Copy, kind = ButtonKind.Secondary)
            Note(registerAfter(agent))
        }
    }
}

private fun registerHow(agent: GuardedAgent) = when (agent) {
    GuardedAgent.Claude ->
        "Add this under hooks.PermissionRequest in ~/.claude/settings.json, merging it with what is there. Paddock never edits that file. Claude Code's timeout is the window plus five seconds."
    GuardedAgent.Codex ->
        "Add this under hooks.PermissionRequest in ~/.codex/hooks.json, merging it with what is there. Paddock never edits that file. Codex's timeout is its window plus ten seconds, and `--agent codex` tells the hook which agent it serves."
    GuardedAgent.Opencode ->
        "Run this on the machine. It links the plugin Paddock installed into opencode's plugin directory; the file stays the pinned one, and Paddock never writes into opencode's folders."
}

private fun registerAfter(agent: GuardedAgent) = when (agent) {
    GuardedAgent.Claude -> "Claude Code reads its hook settings when a session starts, so start a new Claude Code session after registering."
    GuardedAgent.Codex ->
        "Codex asks you to trust a new hook: start Codex and choose Trust, or open /hooks and press t. Changing its window changes the timeout above, and Codex then asks again. Paddock finds the right pane even when Codex's shared daemon runs the hook (Linux); on a Mac start Codex with codex --no-daemon."
    GuardedAgent.Opencode -> "opencode loads plugins when it starts, so restart opencode after registering. It keeps its own prompt on the desktop, so nothing there waits for the phone."
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
