package io.github.tuthan.paddock.lifecycle

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Maps two platform signals onto [ConnectionOwner]: the app becoming visible (any activity started after none were)
 * and the default network changing. Leases, not this class, decide which connections exist; this only nudges them. [onNetworkChanged] is for
 * whoever else holds connections the owner does not know (the chips' probe).
 */
class AndroidTriggers(private val app: Application, private val owner: ConnectionOwner, private val onNetworkChanged: () -> Unit = {}) {
    private var started = 0
    private var current: Network? = null
    private var seen = false

    private val _foreground = MutableStateFlow(false)
    /** True while any activity of this app is started. The monitor's heartbeat and the connection leases follow it. */
    val foreground: StateFlow<Boolean> = _foreground.asStateFlow()

    private var resumed = 0
    private val _interactive = MutableStateFlow(false)
    /** True while an activity of this app is resumed: the herd is in front of the user. Differs from [foreground] when a window above it covers the app. */
    val interactive: StateFlow<Boolean> = _interactive.asStateFlow()

    private val activities = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) { if (started++ == 0) { _foreground.value = true; owner.refreshAll() } }
        override fun onActivityStopped(activity: Activity) { started = maxOf(0, started - 1); if (started == 0) _foreground.value = false }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) { resumed++; _interactive.value = true }
        override fun onActivityPaused(activity: Activity) { resumed = maxOf(0, resumed - 1); _interactive.value = resumed > 0 }
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    private val network = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // The first callback only reports the network that is already there. After that, a different network, or the
            // same one back after a loss, means sockets opened earlier may be dead.
            val changed = seen && network != current
            current = network; seen = true
            if (changed) { owner.onNetworkChanged(); onNetworkChanged() }
        }

        override fun onLost(network: Network) { if (network == current) current = null }
    }

    fun install() {
        app.registerActivityLifecycleCallbacks(activities)
        (app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).registerDefaultNetworkCallback(network)
    }

    fun uninstall() {
        app.unregisterActivityLifecycleCallbacks(activities)
        runCatching { (app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(network) }
    }
}
