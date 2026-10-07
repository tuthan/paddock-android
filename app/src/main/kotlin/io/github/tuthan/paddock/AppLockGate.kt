package io.github.tuthan.paddock

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import io.github.tuthan.paddock.applock.AppLockCopy
import io.github.tuthan.paddock.applock.AuthResult
import io.github.tuthan.paddock.applock.DeviceLock
import io.github.tuthan.paddock.applock.findActivity
import io.github.tuthan.paddock.applock.rememberDeviceAuth
import io.github.tuthan.paddock.ui.components.SecureWindow
import io.github.tuthan.paddock.ui.screens.AppLockScreen
import kotlinx.coroutines.launch

/**
 * Covers the app with the lock screen while [AppGraph.appLock] says so, and asks the phone for the user's credential. The screens stay composed underneath
 * (the route, the draft and the open terminal are where the user left them) but nothing reaches them: the lock is a dialog window above every other window,
 * it takes focus and the keyboard, and the connection is released while it is up ([AppGraph]), so nothing underneath keeps working or asking.
 *
 * It asks once each time the app comes to the front covered, not on every resume: a user who backs out of the prompt is left on the lock screen with its
 * button, and the credential screen of Android 8 to 10, which pauses and resumes this activity, cannot loop. A phone whose screen lock was removed while
 * the lock was on has nothing to ask, so the app opens and the lock is turned off, with one line saying so.
 */
@Composable
internal fun AppLockGate(graph: AppGraph, content: @Composable () -> Unit) {
    val locked by graph.appLock.locked.collectAsState()
    val settings by graph.settings.collectAsState()
    val ctx = LocalContext.current
    val auth = rememberDeviceAuth()
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val resumed = rememberResumed()
    var asking by remember { mutableStateOf(false) }
    var attempted by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    // No screenshot and no recent-apps preview while the lock is on or the app is covered; the lock screen itself is secure in its own window.
    SecureWindow(settings.appLock || locked)
    content()

    fun ask() {
        asking = true; message = null
        graph.appLock.authenticating()
        auth.authenticate(AppLockCopy.UNLOCK_TITLE, AppLockCopy.CONFIRM_SUBTITLE) { result ->
            asking = false
            when (result) {
                AuthResult.Success -> graph.appLock.unlocked()
                AuthResult.Cancelled -> graph.appLock.cancelled()
                AuthResult.NoLock -> { graph.appLock.cancelled(); dropLock(graph, ctx) }
                is AuthResult.Failed -> { graph.appLock.cancelled(); message = AppLockCopy.failed(result.message) }
            }
        }
    }

    LaunchedEffect(locked) {
        if (!locked) { attempted = false; asking = false; message = null } else { clear(focus); keyboard?.hide() }
    }
    // Each time the app leaves the front, the next return may ask again on its own. Not while the phone's prompt is what took it to the back: on Android 8 to 10
    // that prompt is a full-screen activity that stops this one, and a user who cancels it would be asked again at once, for ever, instead of reaching Unlock.
    LaunchedEffect(graph) { graph.triggers.foreground.collect { visible -> if (!visible && !asking) attempted = false } }
    LaunchedEffect(locked, resumed) {
        if (!locked || !resumed || attempted || asking) return@LaunchedEffect
        attempted = true
        if (auth.lock() == DeviceLock.NotSet) dropLock(graph, ctx) else ask()
    }

    if (locked) {
        AppLockScreen(
            message = message, busy = asking, onUnlock = { if (!asking) { attempted = true; if (auth.lock() == DeviceLock.NotSet) dropLock(graph, ctx) else ask() } },
            onLeave = { ctx.findActivity()?.moveTaskToBack(true) },
        )
    }
}

private fun clear(focus: FocusManager) = focus.clearFocus(force = true)

/** The phone's screen lock is gone, so nothing can be asked: open the app and turn the lock off rather than leave the user outside their own app. */
private fun dropLock(graph: AppGraph, ctx: android.content.Context) {
    graph.scope.launch { graph.setAppLock(false); graph.appLock.unlocked() }
    Toast.makeText(ctx, AppLockCopy.DROPPED, Toast.LENGTH_LONG).show()
}

/** True while the activity is resumed: the system's prompt is only shown to an app in front. */
@Composable
private fun rememberResumed(): Boolean {
    val owner = LocalContext.current as? LifecycleOwner
    var resumed by remember { mutableStateOf(owner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) resumed = true else if (e == Lifecycle.Event.ON_PAUSE) resumed = false
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }
    return resumed
}
