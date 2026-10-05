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
 * announce and resolves the name to an address. Resolving is one at a time (the platform refuses a second while one is active).
 *
 * [log] receives one short line per platform callback worth knowing (started, failed with a code), so a device run can say what the
 * platform did; it is never shown to the user. A [SecurityException] from the platform ends the flow with that exception.
 */
class NsdBrowser(context: Context, private val log: (String) -> Unit = {}) {
    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)

    @Suppress("DEPRECATION")
    fun browse(types: List<String> = TYPES): Flow<NsdService> = callbackFlow {
        val pending = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val listeners = ArrayList<NsdManager.DiscoveryListener>()
        launch {
            for (info in pending) {
                val resolved = withTimeoutOrNull(RESOLVE_MILLIS) { resolve(info) } ?: continue
                trySend(resolved)
            }
        }
        for (type in types) {
            val l = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) { log("started $serviceType") }
                override fun onServiceFound(info: NsdServiceInfo) { pending.trySend(info) }
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

    /** The first IPv4 address and the port of [info], or null when it cannot be resolved. */
    @Suppress("DEPRECATION")
    private suspend fun resolve(info: NsdServiceInfo): NsdService? = suspendCancellableCoroutine { cont ->
        fun done(host: Inet4Address?, port: Int, name: String) { if (cont.isActive) cont.resume(if (host != null && port in 1..65535) NsdService(name, host, port) else null) }
        if (Build.VERSION.SDK_INT >= 34) {
            val callback = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) { log("resolve failed code $errorCode"); done(null, 0, "") }
                override fun onServiceUpdated(updated: NsdServiceInfo) { done(updated.hostAddresses.filterIsInstance<Inet4Address>().firstOrNull(), updated.port, updated.serviceName) }
                override fun onServiceLost() { done(null, 0, "") }
                override fun onServiceInfoCallbackUnregistered() {}
            }
            val executor = Executor { it.run() }
            try { nsd.registerServiceInfoCallback(info, executor, callback) } catch (e: IllegalArgumentException) { done(null, 0, ""); return@suspendCancellableCoroutine }
            cont.invokeOnCancellation { try { nsd.unregisterServiceInfoCallback(callback) } catch (_: IllegalArgumentException) { } }
        } else {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) { log("resolve failed code $errorCode"); done(null, 0, "") }
                override fun onServiceResolved(resolved: NsdServiceInfo) { done(resolved.host as? Inet4Address, resolved.port, resolved.serviceName) }
            })
        }
    }

    companion object {
        val TYPES = listOf("_ssh._tcp.", "_sftp-ssh._tcp.", "_workstation._tcp.")
        private const val RESOLVE_MILLIS = 4_000L
    }
}
