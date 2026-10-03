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
        if (savedInstanceState == null) offerAlert(intent)
        setContent { PaddockTheme { PaddockRoot(graph) } }
    }

    /** The activity is single-task: an alert's tap while the app exists arrives here, and the task comes forward. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        offerAlert(intent)
    }

    /** A VIEW intent is an alert's link or a pairing link; any app can fire either, so each inbox parses strictly and keeps nothing it refuses. */
    private fun offerAlert(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val graph = (application as PaddockApp).graph
        if (intent.data?.host == PairingLinks.HOST) graph.pairing.offer(intent.dataString) else graph.alerts.offer(intent.dataString)
    }
}
