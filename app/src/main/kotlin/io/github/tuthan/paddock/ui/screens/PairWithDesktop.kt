package io.github.tuthan.paddock.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.tuthan.paddock.pairing.PairingText
import io.github.tuthan.paddock.pairing.PairingView
import io.github.tuthan.paddock.ui.components.ButtonKind
import io.github.tuthan.paddock.ui.components.Fact
import io.github.tuthan.paddock.ui.components.PaddockButton
import io.github.tuthan.paddock.ui.components.ScreenHeader
import io.github.tuthan.paddock.ui.theme.PaddockTokens

/** The pairing page's inputs that never change during a request: where the key goes and what it is. */
data class PairingTarget(val endpoint: String, val fingerprint: String)

/**
 * Sending this phone's key to a desktop and waiting for its owner. The fingerprint is shown whole with its first eight characters
 * apart, the same way the desktop sets them off, because comparing the two screens is the whole check. While the request is active
 * the only action is Cancel; a refusal or a lost window offers Back (the command on Add machine always works instead), and a window
 * that ended unheard also offers Connect, since the desktop may have written the key.
 */
@Composable
fun PairWithDesktop(
    view: PairingView,
    target: PairingTarget,
    onCancel: () -> Unit,
    onBack: () -> Unit,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = PaddockTokens.colors
    Column(modifier.fillMaxSize()) {
        ScreenHeader("Pair with the desktop", onBack = if (view.active) onCancel else onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = PaddockTokens.spacing.gutter, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Read aloud when it changes: the state moves while the user is looking at the desktop.
            Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(view.headline, style = PaddockTokens.type.screenTitle, color = c.title)
                Text(view.detail, style = PaddockTokens.type.body, color = c.dim)
            }
            view.secondsLeft?.let { Fact("Expires in", PairingText.clock(it)) }
            Fact("Sending to", target.endpoint)
            Fact("Fingerprint of this phone's key", target.fingerprint)
            Fact("First 8 characters", PairingText.fingerprintHead(target.fingerprint))
        }
        Column(Modifier.padding(horizontal = PaddockTokens.spacing.gutter, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (view.offerConnect) PaddockButton("Connect", onConnect)
            if (view.active) PaddockButton("Cancel", onCancel, kind = ButtonKind.Secondary)
            else PaddockButton("Back", onBack, kind = ButtonKind.Secondary)
        }
    }
}
