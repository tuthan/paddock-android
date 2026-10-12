package io.github.tuthan.paddock

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.tuthan.paddock.hostprofile.PairingLinks
import io.github.tuthan.paddock.ui.theme.PaddockTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge to edge on every API level, so the IME inset reaches `imePadding` below API 30 too (Add machine keeps Connect visible).
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val graph = (application as PaddockApp).graph
        // A recreation (rotation, process restore) carries the intent again; the link in it was handled when the activity was first created.
        if (savedInstanceState == null) { offerAlert(intent); offerShare(intent) }
        setContent { PaddockTheme { PaddockRoot(graph) } }
    }

    /** The activity is single-task: an alert's tap while the app exists arrives here, and the task comes forward. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        offerAlert(intent)
        offerShare(intent)
    }

    /**
     * An `ACTION_SEND` is a file or text another app shared (Phase 15, X13). The graph reads it once, in its own scope, with SEC-1 to SEC-3 at the door, and nothing in the
     * intent is forwarded or launched. The activity's intent is then replaced with an empty one, so a recreation or a later look at `intent` finds no share to take twice.
     */
    private fun offerShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        (application as PaddockApp).graph.receiveShare(intent)
        setIntent(Intent())
    }

    /** A VIEW intent is an alert's link or a pairing link; any app can fire either, so each inbox parses strictly and keeps nothing it refuses. */
    private fun offerAlert(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val graph = (application as PaddockApp).graph
        if (intent.data?.host == PairingLinks.HOST) graph.pairing.offer(intent.dataString) else graph.alerts.offer(intent.dataString)
    }
}
