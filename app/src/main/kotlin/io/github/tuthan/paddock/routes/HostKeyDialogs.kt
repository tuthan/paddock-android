package io.github.tuthan.paddock.routes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.github.tuthan.paddock.AppGraph
import io.github.tuthan.paddock.hostkey.HostKeyPrompts
import io.github.tuthan.paddock.hostkey.HostKeyState
import io.github.tuthan.paddock.ui.components.FingerprintDialog
import kotlinx.coroutines.launch

@Composable
internal fun HostKeyDialogs(graph: AppGraph, reviewKey: Boolean, dismissReview: () -> Unit) {
    val request by graph.broker.firstTrust.collectAsState()
    request?.let { r ->
        // Answers name the request they answer: a tap on a dialog whose connect was cancelled answers nothing else.
        FingerprintDialog(HostKeyPrompts.firstTrust(r.endpoint, r.presented, r.linkFingerprints), onTrust = { graph.broker.answerFirstTrust(r.id, true) }, onCancel = { graph.broker.answerFirstTrust(r.id, false) })
    }
    // A key the pairing link does not name was refused before any question: this only says so, with both fingerprints, and offers no way to trust it.
    val refusal by graph.broker.pairingRefused.collectAsState()
    refusal?.let { r -> FingerprintDialog(HostKeyPrompts.pairingMismatch(r), onTrust = {}, onCancel = { graph.broker.clearPairingRefusal() }) }
    val profile by graph.machineBook.profile.collectAsState()
    val changed by graph.broker.changed.collectAsState()
    val c = profile?.let { changed[it.id] }
    val scope = rememberCoroutineScope()
    if (reviewKey && c != null) {
        FingerprintDialog(
            HostKeyPrompts.changed(c.endpoint, HostKeyState.Changed(c.pin, c.presented)),
            // The tap approves the key drawn here: `c` names its attempt, and replaceKey refuses it once a later attempt replaced it.
            onTrust = { scope.launch { graph.replaceKey(c); dismissReview() } },
            onCancel = dismissReview,
        )
    }
}
