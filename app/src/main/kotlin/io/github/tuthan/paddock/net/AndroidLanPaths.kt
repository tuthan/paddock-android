package io.github.tuthan.paddock.net

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import io.github.tuthan.paddock.ports.LanPath
import io.github.tuthan.paddock.ports.LanPaths
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap

/**
 * The networks the phone is on, from `ConnectivityManager` (`ACCESS_NETWORK_STATE`, already held): every Wi-Fi, Ethernet and VPN network
 * with its IPv4 addresses and default gateways, never "the active network" alone, which may be the VPN. Cellular networks have no LAN
 * and are left out. The platform's [Network] for each path is remembered so [AndroidSockets] can bind a socket to it.
 */
class AndroidLanPaths(context: Context) : LanPaths {
    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val networks = ConcurrentHashMap<String, Network>()

    fun network(id: String): Network? = networks[id]

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    override fun paths(): List<LanPath> {
        val found = ArrayList<LanPath>()
        val seen = HashSet<String>()
        for (n in runCatching { cm.allNetworks }.getOrDefault(emptyArray())) {
            val caps = runCatching { cm.getNetworkCapabilities(n) }.getOrNull() ?: continue
            val tunnel = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            val lan = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!tunnel && !lan) continue
            val props = runCatching { cm.getLinkProperties(n) }.getOrNull() ?: continue
            val subnets = props.linkAddresses.mapNotNull { la ->
                val a = la.address as? Inet4Address ?: return@mapNotNull null
                if (a.isLoopbackAddress || a.isLinkLocalAddress) null else runCatching { io.github.tuthan.paddock.net.Ipv4Subnet(a, la.prefixLength) }.getOrNull()
            }
            val gateways = props.routes.filter { it.isDefaultRoute }.mapNotNull { it.gateway as? Inet4Address }.filter { !it.isAnyLocalAddress }
            val id = n.toString()
            if (!seen.add(id)) continue
            networks[id] = n
            found += LanPath(id, props.interfaceName ?: "", tunnel, subnets, gateways)
        }
        networks.keys.retainAll(seen)
        return found
    }
}
