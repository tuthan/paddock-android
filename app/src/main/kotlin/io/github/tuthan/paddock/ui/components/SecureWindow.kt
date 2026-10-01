package io.github.tuthan.paddock.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * While this is in the composition and [enabled], the window is `FLAG_SECURE`: no screenshots, and a blank thumbnail in
 * recents. It restores the flag it found on leaving, so two screens that both ask for it do not clear each other's
 * protection when one of them goes away first.
 */
@Composable
fun SecureWindow(enabled: Boolean) {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity, enabled) {
        val window = activity?.window
        if (window == null || !enabled) return@DisposableEffect onDispose { }
        val had = window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (!had) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}
