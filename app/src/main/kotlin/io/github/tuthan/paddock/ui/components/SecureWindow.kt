package io.github.tuthan.paddock.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import java.util.WeakHashMap

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Who asked for `FLAG_SECURE` on each window. The flag goes on with the first request and comes off only when the last
 * one leaves, and never if the window already had it before Paddock asked, so screens that overlap during a navigation
 * cannot clear each other's protection in either order. Main thread only, like the composition that drives it.
 */
private object SecureRequests {
    private class Entry(var count: Int, val hadBefore: Boolean)
    private val entries = WeakHashMap<Window, Entry>()

    fun acquire(window: Window) {
        val e = entries.getOrPut(window) { Entry(0, window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) }
        if (e.count++ == 0) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun release(window: Window) {
        val e = entries[window] ?: return
        if (--e.count > 0) return
        entries.remove(window)
        if (!e.hadBefore) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}

/**
 * While this is in the composition and [enabled], the window is `FLAG_SECURE`: no screenshots, and a blank thumbnail in
 * recents. Requests are counted per window, so two screens that both ask for it never clear each other's protection.
 */
@Composable
fun SecureWindow(enabled: Boolean) {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity, enabled) {
        val window = activity?.window
        if (window == null || !enabled) return@DisposableEffect onDispose { }
        SecureRequests.acquire(window)
        onDispose { SecureRequests.release(window) }
    }
}
