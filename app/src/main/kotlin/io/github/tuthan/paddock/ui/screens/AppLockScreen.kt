package io.github.tuthan.paddock.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import io.github.tuthan.paddock.applock.AppLockCopy
import io.github.tuthan.paddock.ui.components.ButtonKind
import io.github.tuthan.paddock.ui.components.PaddockButton
import io.github.tuthan.paddock.ui.theme.PaddockIcons
import io.github.tuthan.paddock.ui.theme.PaddockTokens

/**
 * What covers Paddock while it is locked: one word, one button. It is a full-screen *dialog window*, not a layer inside the screens, for two reasons: it
 * sits above any other dialog window that was open when the app was covered (a confirmation, the Pro sheet), which a layer inside the activity would not,
 * and it is [SecureFlagPolicy.SecureOn], so it is blank in the recent-apps preview and a screenshot. Touches, focus and the keyboard all belong to it while
 * it is up, and Back leaves the app ([onLeave]) rather than reaching the screens underneath.
 *
 * [message] is what the phone said when asking failed ("That did not work"); [busy] is true while the phone's own prompt is showing.
 */
@Composable
fun AppLockScreen(message: String?, busy: Boolean, onUnlock: () -> Unit, onLeave: () -> Unit) {
    val c = PaddockTokens.colors
    Dialog(
        onDismissRequest = onLeave,
        properties = DialogProperties(
            dismissOnBackPress = true, dismissOnClickOutside = false, securePolicy = SecureFlagPolicy.SecureOn,
            usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            Modifier.fillMaxSize().background(c.ground).safeDrawingPadding().padding(horizontal = PaddockTokens.spacing.gutter)
                .semantics { paneTitle = AppLockCopy.LOCKED_TITLE },
            contentAlignment = Alignment.Center,
        ) {
            Column(Modifier.widthIn(max = 420.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(PaddockIcons.Lock, contentDescription = null, tint = c.accent, modifier = Modifier.size(48.dp))
                Text(AppLockCopy.LOCKED_TITLE, style = PaddockTokens.type.screenTitle, color = c.title, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
                Text(AppLockCopy.LOCKED_DETAIL, style = PaddockTokens.type.body, color = c.dim, textAlign = TextAlign.Center)
                if (message != null) {
                    Text(message, style = PaddockTokens.type.secondary, color = c.needsYou, textAlign = TextAlign.Center, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                PaddockButton(AppLockCopy.UNLOCK, onUnlock, enabled = !busy, kind = ButtonKind.Primary, icon = PaddockIcons.Lock)
            }
        }
    }
}
