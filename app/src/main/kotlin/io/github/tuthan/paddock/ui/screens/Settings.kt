package io.github.tuthan.paddock.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.tuthan.paddock.alerts.AccessRecovery
import io.github.tuthan.paddock.alerts.AlertDelivery
import io.github.tuthan.paddock.ui.components.Banner
import io.github.tuthan.paddock.ui.components.ButtonKind
import io.github.tuthan.paddock.ui.components.Kicker
import io.github.tuthan.paddock.ui.components.PaddockButton
import io.github.tuthan.paddock.ui.components.ScreenHeader
import io.github.tuthan.paddock.ui.components.Toggle
import io.github.tuthan.paddock.ui.components.card
import io.github.tuthan.paddock.ui.theme.PaddockIcons
import io.github.tuthan.paddock.ui.theme.PaddockTokens

/** Whether the Android 17 local-network grant matters on this device and build, and if so whether it is given. */
enum class LocalAccess { NotRequired, Granted, Denied }

/** The watched machine as Settings shows it: its name, `user@host:port`, and the herdr session (null for the default). */
data class MachineSummary(val name: String, val endpoint: String, val session: String?)

data class SettingsState(
    val protectSensitiveScreens: Boolean,
    val localAccess: LocalAccess,
    val versionName: String,
    val machine: MachineSummary? = null,
    /** The herdr version the watched host reported in its last read, when there is one. */
    val herdrVersion: String? = null,
    val protocol: Int = 22,
    /** Whether the record of what was sent keeps the prompt text (off: a fingerprint only). */
    val keepPromptText: Boolean = false,
    val snippetCount: Int = 0,
    /** Why the saved record of sends cannot be read, or null. While it is set no prompt, key or focus is sent. */
    val journalUnreadable: String? = null,
    val alerts: AlertsState = AlertsState(),
    /** Agent icons: a glyph on the row's tile for the common agents, letters for the rest. */
    val agentGlyphs: Boolean = true,
)

/**
 * The Alerts section. [localAlerts] is the switch for notifications raised while Paddock is open but not in front; the
 * permission is asked for when it is turned on. [recovery] is set when notifications cannot reach the user, and says why.
 */
data class AlertsState(
    val localAlerts: Boolean = false,
    val hideOnLockScreen: Boolean = true,
    val recovery: AccessRecovery? = null,
)

const val RECONNECT_SHORT = "Fast retries, then up to every 2 minutes"

const val RECONNECT_POLICY =
    "If the connection drops, Paddock tries again after 1, 2 and 4 seconds, then waits longer between tries, up to two minutes. " +
        "Problems only you can fix, such as a refused key or a changed host key, wait for you instead of retrying."

/** Settings, as grouped cards. Everything not yet built is shown disabled and says so, never hidden. */
@Composable
fun Settings(
    state: SettingsState,
    onProtectSensitive: (Boolean) -> Unit,
    onOpenSystemSettings: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onAddMachine: () -> Unit = {},
    onKeepPromptText: (Boolean) -> Unit = {},
    onEditSnippets: () -> Unit = {},
    onRetryJournal: () -> Unit = {},
    onResetJournal: () -> Unit = {},
    onLocalAlerts: (Boolean) -> Unit = {},
    onHideOnLockScreen: (Boolean) -> Unit = {},
    onAlertRecovery: (AccessRecovery.Action) -> Unit = {},
    onAlertRelay: () -> Unit = {},
    onGuardedAnswers: () -> Unit = {},
    onAgentGlyphs: (Boolean) -> Unit = {},
) {
    val c = PaddockTokens.colors
    var reconnectOpen by rememberSaveable { mutableStateOf(false) }
    var resetOpen by rememberSaveable { mutableStateOf(false) }
    var fontsOpen by rememberSaveable { mutableStateOf(false) }
    if (fontsOpen) FontLicenceDialog(onClose = { fontsOpen = false })
    if (resetOpen) {
        ConfirmDialog(
            "Reset the record of sends?",
            "Paddock keeps the unreadable file aside and starts an empty record. Any send whose outcome was unknown is forgotten, so look at the agent before you send again: an earlier prompt may already have arrived.",
            confirm = "Reset the record", onConfirm = { resetOpen = false; onResetJournal() }, onCancel = { resetOpen = false }, danger = true,
        )
    }
    Column(modifier.fillMaxSize()) {
        ScreenHeader("Settings", onBack = onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = PaddockTokens.spacing.gutter, end = PaddockTokens.spacing.gutter, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.journalUnreadable != null) {
                Section("Send record")
                Banner(
                    "Sending is off. The record of what this phone sent cannot be read, so a duplicate prompt cannot be ruled out. ${state.journalUnreadable}",
                    actionLabel = "Retry", onAction = onRetryJournal,
                )
                PaddockButton("Reset the record…", { resetOpen = true }, kind = ButtonKind.Danger)
            }

            Section("Machine")
            if (state.machine != null) {
                Fixed(state.machine.name, null, listOfNotNull(state.machine.endpoint, state.machine.session?.let { "session $it" } ?: "default session").joinToString(" · "), mono = true)
            }
            PaddockButton(if (state.machine == null) "Add a machine" else "Add another machine", onAddMachine, kind = ButtonKind.Ghost, icon = PaddockIcons.Plus)
            if (state.machine != null) Note2("Paddock watches one machine at a time; adding another makes it the watched one.")

            Section("Connection")
            Fixed("Monitor while the app is open", "On", "Paddock checks your agents while this app is open and stops a few seconds after you leave it.")
            Row(
                Modifier.card(c, padded = false)
                    .clickable(role = Role.Button, onClickLabel = if (reconnectOpen) "Hide the reconnect policy" else "Show the reconnect policy") { reconnectOpen = !reconnectOpen }
                    .heightIn(min = PaddockTokens.spacing.touchTarget).padding(horizontal = 14.dp, vertical = 10.dp)
                    .semantics(mergeDescendants = true) { stateDescription = if (reconnectOpen) "Expanded" else "Collapsed" },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Reconnect", style = PaddockTokens.type.rowTitle, color = c.title)
                    Text(if (reconnectOpen) RECONNECT_POLICY else RECONNECT_SHORT, style = PaddockTokens.type.secondary, color = c.dim)
                }
                Icon(PaddockIcons.Chevron, contentDescription = null, tint = c.faint, modifier = Modifier.size(20.dp).rotate(if (reconnectOpen) 90f else 0f))
            }

            if (state.localAccess != LocalAccess.NotRequired) {
                Section("Local network")
                when (state.localAccess) {
                    LocalAccess.Granted -> Fixed("Local-network access", "Allowed", "Needed to reach machines by a LAN address.")
                    LocalAccess.Denied -> Banner(
                        "Local-network access is off, so Paddock cannot reach machines by a LAN address. Turn it on in the app's settings, or use the machine's VPN address.",
                        actionLabel = "Open settings", onAction = onOpenSystemSettings,
                    )
                    LocalAccess.NotRequired -> Unit
                }
            }

            Section("Alerts")
            Toggle(
                "Alerts from this app", state.alerts.localAlerts, onLocalAlerts, Modifier.card(c, padded = false),
                inset = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                detail = "While Paddock is open but not in front, an agent that becomes blocked or done raises a notification. Paddock does not watch in the background in this version.",
            )
            if (state.alerts.recovery != null && state.alerts.localAlerts) {
                Banner(state.alerts.recovery.message, actionLabel = state.alerts.recovery.actionLabel, onAction = { onAlertRecovery(state.alerts.recovery.action) })
            }
            Toggle(
                "Hide prompt text on the lock screen", state.alerts.hideOnLockScreen, onHideOnLockScreen, Modifier.card(c, padded = false),
                inset = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                detail = "On: the lock screen shows only \"Paddock: attention on <machine>\". The agent's title appears once the phone is unlocked. Android's own lock-screen setting can hide more, never less.",
            )
            Row(
                Modifier.card(c, padded = false)
                    .clickable(role = Role.Button, onClickLabel = "Set up the alert relay", onClick = onAlertRelay)
                    .heightIn(min = PaddockTokens.spacing.touchTarget).padding(horizontal = 14.dp, vertical = 10.dp)
                    .semantics(mergeDescendants = true) { contentDescription = "Locked-phone alerts, ${AlertDelivery.MODE}. ${AlertDelivery.BEST_EFFORT} ${AlertDelivery.MEASURED}" },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Locked-phone alerts", style = PaddockTokens.type.rowTitle, color = c.title)
                    Text(AlertDelivery.MODE, style = PaddockTokens.type.secondary, color = c.text)
                    Text(AlertDelivery.BEST_EFFORT + " " + AlertDelivery.MEASURED, style = PaddockTokens.type.secondary, color = c.dim)
                }
                Icon(PaddockIcons.Chevron, contentDescription = null, tint = c.faint, modifier = Modifier.size(20.dp))
            }
            Note2("Every notification has two buttons, Open and Review. Both only open Paddock; nothing is ever sent to an agent from a notification.")

            Section("Answers")
            Row(
                Modifier.card(c, padded = false)
                    .clickable(role = Role.Button, onClickLabel = "Set up guarded answers", onClick = onGuardedAnswers)
                    .heightIn(min = PaddockTokens.spacing.touchTarget).padding(horizontal = 14.dp, vertical = 10.dp)
                    .semantics(mergeDescendants = true) { contentDescription = "Guarded answers. $GUARDED_ROW" },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Guarded answers", style = PaddockTokens.type.rowTitle, color = c.title)
                    Text(GUARDED_ROW, style = PaddockTokens.type.secondary, color = c.dim)
                }
                Icon(PaddockIcons.Chevron, contentDescription = null, tint = c.faint, modifier = Modifier.size(20.dp))
            }

            Section("Appearance")
            Fixed("Theme", "Paddock palette", "Follows the system light or dark setting.")
            Toggle(
                "Agent icons", state.agentGlyphs, onAgentGlyphs, Modifier.card(c, padded = false),
                inset = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                detail = if (state.agentGlyphs) "Pictures for the common agents, letters for the rest." else "Two letters for every agent.",
            )

            Section("Prompts")
            Row(
                Modifier.card(c, padded = false)
                    .clickable(role = Role.Button, onClickLabel = "Edit snippets", onClick = onEditSnippets)
                    .heightIn(min = PaddockTokens.spacing.touchTarget).padding(horizontal = 14.dp, vertical = 10.dp)
                    .semantics(mergeDescendants = true) { contentDescription = "Snippets, ${state.snippetCount} saved, kept on this phone and synced nowhere" },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Snippets", style = PaddockTokens.type.rowTitle, color = c.title)
                    Text("${state.snippetCount} saved · kept on this phone, synced nowhere", style = PaddockTokens.type.secondary, color = c.dim)
                }
                Icon(PaddockIcons.Chevron, contentDescription = null, tint = c.faint, modifier = Modifier.size(20.dp))
            }

            Section("Privacy")
            Toggle(
                "Protect sensitive screens", state.protectSensitiveScreens, onProtectSensitive, Modifier.card(c, padded = false),
                inset = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                detail = "Blocks screenshots and blanks the recent-apps preview on screens that can show agent output.",
            )
            Toggle(
                "Keep prompt text", state.keepPromptText, onKeepPromptText, Modifier.card(c, padded = false),
                inset = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                detail = "Off: the record of what you sent keeps only a fingerprint of each prompt. On: it keeps the text too, on this phone only.",
            )

            Section("About")
            Fixed(
                "Paddock ${state.versionName}", null,
                listOfNotNull("herdr protocol ${state.protocol}", state.herdrVersion?.let { "host on $it" }, "no analytics, no crash uploads").joinToString(" · "),
                description = "Version, ${state.versionName}",
            )
            Row(
                Modifier.card(c, padded = false)
                    .clickable(role = Role.Button, onClickLabel = "Show the font licences", onClick = { fontsOpen = true })
                    .heightIn(min = PaddockTokens.spacing.touchTarget).padding(horizontal = 14.dp, vertical = 10.dp)
                    .semantics(mergeDescendants = true) { contentDescription = "Fonts, IBM Plex Sans and JetBrains Mono, SIL Open Font License" },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Fonts", style = PaddockTokens.type.rowTitle, color = c.title)
                    Text("IBM Plex Sans and JetBrains Mono · SIL Open Font License 1.1", style = PaddockTokens.type.secondary, color = c.dim)
                }
                Icon(PaddockIcons.Chevron, contentDescription = null, tint = c.faint, modifier = Modifier.size(20.dp))
            }
            Note2(
                "Paddock connects to your machine over SSH only. Agent output is shown and not kept: the Activity log holds only state changes the phone saw and what you did here. " +
                    "An independent project, not affiliated with herdr.",
            )
        }
    }
}

/** The licence text that has to travel with the bundled fonts (assets/licenses/fonts.txt), read off the main thread. */
@Composable
private fun FontLicenceDialog(onClose: () -> Unit) {
    val c = PaddockTokens.colors
    val ctx = LocalContext.current
    val text by produceState<String?>(null) {
        value = withContext(Dispatchers.IO) {
            runCatching { ctx.assets.open(FONT_LICENCES_ASSET).use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
                ?: "The licence text could not be read from this build."
        }
    }
    val shape = RoundedCornerShape(PaddockTokens.radii.dialog)
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 24.dp).fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.fieldLine(), shape).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Font licences", style = PaddockTokens.type.headerTitle, color = c.title)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                Text(text ?: "", style = PaddockTokens.type.monoFact, color = c.text)
            }
            PaddockButton("Close", onClose, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary, autoFocus = true)
        }
    }
}

const val FONT_LICENCES_ASSET = "licenses/fonts.txt"

@Composable
private fun Section(title: String) = Kicker(title, Modifier.padding(top = 12.dp))

@Composable
private fun Note2(text: String) = io.github.tuthan.paddock.ui.components.Note(text, Modifier.padding(horizontal = 2.dp, vertical = 2.dp))

/** A card the user cannot change. Read as "label, value, detail"; a disabled one is announced as unavailable. */
@Composable
private fun Fixed(label: String, value: String?, detail: String?, enabled: Boolean = true, mono: Boolean = false, description: String? = null) {
    val c = PaddockTokens.colors
    val said = description ?: (listOfNotNull(label, value, detail).joinToString(", ") + if (enabled) "" else ", unavailable")
    Row(
        Modifier.card(c).alpha(if (enabled) 1f else 0.55f)
            .semantics(mergeDescendants = true) { contentDescription = said; if (!enabled) disabled() },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, style = PaddockTokens.type.rowTitle, color = c.title)
            if (detail != null) Text(detail, style = if (mono) PaddockTokens.type.monoFact else PaddockTokens.type.secondary, color = c.dim)
        }
        if (value != null) Text(value, style = PaddockTokens.type.secondary, color = if (enabled) c.text else c.dim)
    }
}
