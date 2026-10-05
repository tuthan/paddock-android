package io.github.tuthan.paddock.discovery

import java.net.Inet4Address

/** A service a machine announced on the LAN (mDNS): its instance name, its address and the port it said. Nothing was connected to. */
data class NsdService(val name: String, val address: Inet4Address, val port: Int)

/**
 * One row of the finder. [software] is read from the SSH identification line, so it is only set for an address that was probed and
 * answered; [name] only for one that announced itself. A row with neither is not made.
 */
data class FoundHost(val address: String, val port: Int, val name: String?, val software: String?) {
    /** `192.168.42.86 · devbox.local · OpenSSH 9.9`: what the row shows. */
    val label: String get() = listOfNotNull(address, name, software).joinToString(" · ")

    /** The port is part of the label only when it is not the SSH default, so the usual row stays short. */
    val labelWithPort: String get() = if (port == 22) label else "$label · port $port"
}

/**
 * The rows the finder shows, merged from the probe and from mDNS by address and port. Rows keep the order they first appeared in, so a row
 * never jumps under a finger when a second source fills in its name. A name and a banner never replace one another.
 */
class FoundHosts {
    private val rows = LinkedHashMap<Pair<String, Int>, FoundHost>()

    val list: List<FoundHost> get() = rows.values.toList()

    fun add(hit: ProbeHit): Boolean {
        val key = hit.address.hostAddress.orEmpty() to hit.port
        val old = rows[key]
        val next = (old ?: FoundHost(key.first, key.second, null, null)).copy(software = hit.software)
        rows[key] = next
        return old != next
    }

    fun add(service: NsdService): Boolean {
        val key = service.address.hostAddress.orEmpty() to service.port
        val old = rows[key]
        val name = cleanName(service.name) ?: return false
        val next = (old ?: FoundHost(key.first, key.second, null, null)).copy(name = old?.name ?: name)
        rows[key] = next
        return old != next
    }

    companion object {
        /** A name from the network is shown as text only: control characters and anything past a screenful are dropped. */
        fun cleanName(raw: String): String? = raw.filter { it.code >= 0x20 && it.code != 0x7f }.trim().take(64).takeIf { it.isNotEmpty() }
    }
}
