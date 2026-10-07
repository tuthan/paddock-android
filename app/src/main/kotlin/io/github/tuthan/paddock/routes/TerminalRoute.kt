package io.github.tuthan.paddock.routes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import io.github.tuthan.paddock.AppGraph
import io.github.tuthan.paddock.ui.screens.TerminalActions
import io.github.tuthan.paddock.ui.screens.TerminalTab

/**
 * The Terminal tab of one agent. The session lives while the tab is on screen: leaving the tab or the screen closes it
 * (which releases control), going to the background suspends it (also a release), and a rotation keeps it, because turning the
 * phone must not hand the terminal back.
 */
@Composable
internal fun TerminalRoute(graph: AppGraph, host: io.github.tuthan.paddock.live.MonitoredHost, terminalId: String) {
    var attempt by rememberSaveable { mutableIntStateOf(0) }
    val session = remember(host, terminalId, attempt) { graph.terminals.acquire(Triple(host, terminalId, attempt)) { host.terminalSession(terminalId) } }
    val view by session.view.collectAsState()
    val ctx = LocalContext.current
    val activity = remember(ctx) { generateSequence(ctx) { (it as? android.content.ContextWrapper)?.baseContext }.firstOrNull { it is android.app.Activity } as? android.app.Activity }
    DisposableEffect(session) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                // A rotation stops and restarts the activity too; only a real trip to the background releases the terminal.
                Lifecycle.Event.ON_STOP -> if (activity?.isChangingConfigurations != true) session.suspend()
                Lifecycle.Event.ON_START -> session.resume()
                else -> Unit
            }
        }
        val owner = ctx as? LifecycleOwner
        owner?.lifecycle?.addObserver(observer)
        onDispose {
            owner?.lifecycle?.removeObserver(observer)
            graph.terminals.release(session, recreating = activity?.isChangingConfigurations == true)
        }
    }
    val actions = remember(session) {
        TerminalActions(
            onViewport = { cols, rows -> if (graph.terminals.firstUse(session)) session.open(cols, rows) else session.setViewport(cols, rows) },
            onRequestControl = session::requestControl, onTakeOver = session::takeOver, onInstallHelper = session::installHelper,
            onDismissNotice = session::dismissNotice, onRelease = session::release,
            onResizeToFit = { cols, rows -> session.resizeToFit(cols, rows) }, onKey = { session.send(it) },
            onRetry = { attempt++ },
        )
    }
    TerminalTab(view, actions)
}
