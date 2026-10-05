package io.github.tuthan.paddock.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import io.github.tuthan.paddock.discovery.NsdService
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * Looks for machines that announce an SSH-like service on the LAN (mDNS through `NsdManager`), for as long as the flow is collected.
 * It runs only when the finder is opened and started, never in the background, and sends nothing to a machine: it listens for what they
 * announce and resolves the name to an address. Resolving is one at a time (the platform refuses a second while one is active), and at most
 * [MAX_PENDING] announcements wait their turn: a network can announce faster than they resolve, and the rest are dropped.
 *
 * [log] receives one short line per platform callback worth knowing (started, failed with a code), so a device run can say what the
 * platform did; it is never shown to the user. A [SecurityException] from the platform ends the flow with that exception.
 */
class NsdBrowser(context: Context, private val log: (String) -> Unit = {}) {
    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)

    @Suppress("DEPRECATION")
    fun browse(types: List<String> = TYPES): Flow<NsdService> = callbackFlow {
        // Each announcement waits with whether its type only names an address: `_workstation._tcp.` says what a machine is called, not where SSH is.
        val pending = Channel<Pair<NsdServiceInfo, Boolean>>(MAX_PENDING)
        val listeners = ArrayList<NsdManager.DiscoveryListener>()
        launch {
            for ((info, nameOnly) in pending) {
                val resolved = withTimeoutOrNull(RESOLVE_MILLIS) { resolve(info, nameOnly) } ?: continue
                trySend(resolved)
            }
        }
        for (type in types) {
            val nameOnly = type.startsWith(WORKSTATION_TYPE)
            val l = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) { log("started $serviceType") }
                // A full queue refuses the announcement (trySend fails and nothing is thrown): that is the cap.
                override fun onServiceFound(info: NsdServiceInfo) { pending.trySend(info to nameOnly) }
                override fun onServiceLost(info: NsdServiceInfo) {}
                override fun onDiscoveryStopped(serviceType: String) { log("stopped $serviceType") }
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { log("start failed $serviceType code $errorCode") }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) { log("stop failed $serviceType code $errorCode") }
            }
            try {
                nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l)
                listeners += l
            } catch (e: SecurityException) {
                log("security exception for $type: ${e.message}")
                close(e)
                break
            } catch (e: IllegalArgumentException) {
                log("rejected $type: ${e.message}")
            }
        }
        awaitClose {
            pending.close()
            for (l in listeners) try { nsd.stopServiceDiscovery(l) } catch (_: IllegalArgumentException) { }
        }
    }

    /** The first IPv4 address and the port of [info], or null when it cannot be resolved. Nothing stays registered with the platform afterwards. */
    @Suppress("DEPRECATION")
    private suspend fun resolve(info: NsdServiceInfo, nameOnly: Boolean): NsdService? {
        fun service(host: Inet4Address?, port: Int, name: String): NsdService? =
            if (host != null && port in 1..65535) NsdService(name, host, port, nameOnly) else null
        if (Build.VERSION.SDK_INT >= 34) {
            return awaitServiceInfo<NsdServiceInfo, NsdService>(
                register = { reports ->
                    val callback = object : NsdManager.ServiceInfoCallback {
                        override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) { log("resolve failed code $errorCode"); reports.onFailed(errorCode) }
                        override fun onServiceUpdated(updated: NsdServiceInfo) = reports.onUpdated(updated)
                        override fun onServiceLost() = reports.onLost()
                        override fun onServiceInfoCallbackUnregistered() {}
                    }
                    nsd.registerServiceInfoCallback(info, Executor { it.run() }, callback)
                    val unregister: () -> Unit = { try { nsd.unregisterServiceInfoCallback(callback) } catch (_: IllegalArgumentException) { } }
                    unregister
                },
                // An update with no IPv4 address is not the answer yet (a host's AAAA can arrive before its A): the timeout decides.
                pick = { updated -> service(updated.hostAddresses.filterIsInstance<Inet4Address>().firstOrNull(), updated.port, updated.serviceName) },
            )
        }
        return suspendCancellableCoroutine { cont ->
            fun done(result: NsdService?) { if (cont.isActive) cont.resume(result) }
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) { log("resolve failed code $errorCode"); done(null) }
                override fun onServiceResolved(resolved: NsdServiceInfo) { done(service(resolved.host as? Inet4Address, resolved.port, resolved.serviceName)) }
            })
        }
    }

    companion object {
        const val WORKSTATION_TYPE = "_workstation._tcp"
        val TYPES = listOf("_ssh._tcp.", "_sftp-ssh._tcp.", "$WORKSTATION_TYPE.")
        private const val RESOLVE_MILLIS = 4_000L
        private const val MAX_PENDING = 64
    }
}
