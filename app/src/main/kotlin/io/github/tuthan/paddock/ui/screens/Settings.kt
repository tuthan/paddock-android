package io.github.tuthan.paddock.ui.screens

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.tuthan.paddock.ui.components.Banner
import io.github.tuthan.paddock.ui.components.Kicker
import io.github.tuthan.paddock.ui.components.Toggle
import io.github.tuthan.paddock.ui.theme.PaddockTokens

/** Whether the Android 17 local-network grant matters on this device and build, and if so whether it is given. */
enum class LocalAccess { NotRequired, Granted, Denied }

data class SettingsState(val protectSensitiveScreens: Boolean, val localAccess: LocalAccess, val versionName: String)

const val RECONNECT_POLICY =
    "If the connection drops, Paddock tries again after 1, 2 and 4 seconds, then waits longer between tries, up to two minutes. " +
        "Problems only you can fix, such as a refused key or a changed host key, wait for you instead of retrying."

/** Settings. Everything not yet built is shown disabled with the phase that delivers it, never hidden. */
@Composable
fun Settings(
    state: SettingsState,
    onProtectSensitive: (Boolean) -> Unit,
    onOpenSystemSettings: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = PaddockTokens.colors
    Column(modifier.fillMaxSize().padding(horizontal = PaddockTokens.spacing.gutter)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(PaddockTokens.spacing.touchTarget).minimumInteractiveComponentSize()
                    .clickable(role = Role.Button, onClickLabel = "Back", onClick = onBack).semantics { contentDescription = "Back" },
                contentAlignment = Alignment.Center,
            ) { Text("←", style = PaddockTokens.type.screenTitle, color = c.title) }
            Text("Settings", style = PaddockTokens.type.screenTitle, color = c.title, modifier = Modifier.semantics { heading() })
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Section("Monitoring") {
                Fixed("Monitor while open", "On", "Paddock checks your agents while this app is open and stops a few seconds after you leave it.")
                Text(RECONNECT_POLICY, style = PaddockTokens.type.secondary, color = c.dim)
            }
            if (state.localAccess != LocalAccess.NotRequired) Section("Local network") {
                when (state.localAccess) {
                    LocalAccess.Granted -> Fixed("Local-network access", "Allowed", "Needed to reach machines by a LAN address.")
                    LocalAccess.Denied -> Banner(
                        "Local-network access is off, so Paddock cannot reach machines by a LAN address. Turn it on in the app's settings, or use the machine's VPN address.",
                        actionLabel = "Open settings", onAction = onOpenSystemSettings,
                    )
                    LocalAccess.NotRequired -> Unit
                }
            }
            Section("Notifications") {
                Fixed("Needs you", "Phase 07", "Alerts when an agent is blocked.", enabled = false)
                Fixed("Done", "Phase 07", "Alerts when an agent finishes.", enabled = false)
            }
            Section("Appearance") {
                Fixed("Theme", "Paddock palette", "Follows the system light or dark setting. Other themes are not offered.")
            }
            Section("Privacy") {
                Toggle(
                    "Protect sensitive screens", state.protectSensitiveScreens, onProtectSensitive,
                    detail = "Blocks screenshots and shows a blank preview in recent apps on screens that can show agent output.",
                )
            }
            Section("About") {
                Fixed("Version", state.versionName, null)
                Text(
                    "Paddock connects to your machine over SSH only. Agent output is shown and not kept: the Activity log holds only state changes the phone saw and what you did here.",
                    style = PaddockTokens.type.secondary, color = c.dim,
                )
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Kicker(title, Modifier.padding(top = 8.dp))
        content()
    }
}

/** A row the user cannot change. Read as "label, value, detail"; a disabled one is announced as unavailable. */
@Composable
private fun Fixed(label: String, value: String, detail: String?, enabled: Boolean = true) {
    val c = PaddockTokens.colors
    val description = listOfNotNull(label, value, detail).joinToString(", ") + if (enabled) "" else ", unavailable"
    Row(
        Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.55f).heightIn(min = PaddockTokens.spacing.touchTarget).padding(vertical = 6.dp)
            .semantics(mergeDescendants = true) { contentDescription = description; if (!enabled) disabled() },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = PaddockTokens.type.rowTitle, color = c.title)
            if (detail != null) Text(detail, style = PaddockTokens.type.secondary, color = c.dim)
        }
        Text(value, style = PaddockTokens.type.secondary, color = c.dim)
    }
}
