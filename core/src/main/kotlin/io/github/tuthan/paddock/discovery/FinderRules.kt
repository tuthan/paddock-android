package io.github.tuthan.paddock.discovery

import io.github.tuthan.paddock.net.Ipv4Subnet
import io.github.tuthan.paddock.ports.LanPath

/** What Find on this network may do right now, from where the phone is. */
sealed interface FinderDecision {
    /** Not on Wi-Fi or Ethernet (cellular, a VPN alone, or offline). */
    data object NoLan : FinderDecision
    data class TooWide(val prefix: Int) : FinderDecision
    /** A LAN with no IPv4 address: names can still be found, addresses cannot be enumerated. */
    data object Ipv6Only : FinderDecision
    /** Android 17 and later: the local-network access has to be granted first. */
    data object NeedsGrant : FinderDecision
    data class Ready(val subnet: Ipv4Subnet, val path: LanPath) : FinderDecision
}

object FinderRules {
    /** The ports looked at besides the SSH default: the one the user typed, when there is one. */
    fun ports(typed: Int?): List<Int> = listOfNotNull(22, typed?.takeIf { it != 22 && it in 1..65535 })

    fun decide(paths: List<LanPath>, grantMissing: Boolean): FinderDecision {
        val lans = paths.filter { !it.tunnel }
        if (lans.isEmpty()) return FinderDecision.NoLan
        val lan = lans.firstOrNull { it.hasIpv4 } ?: return FinderDecision.Ipv6Only
        val subnet = lan.subnets.first()
        if (subnet.prefix < Ipv4Subnet.MIN_PROBE_PREFIX) return FinderDecision.TooWide(subnet.prefix)
        if (grantMissing) return FinderDecision.NeedsGrant
        return FinderDecision.Ready(subnet, lan)
    }

    /** Said before anything runs: what the probe does, to which addresses, and what it never does. */
    fun beforeScan(subnet: Ipv4Subnet, ports: List<Int>): String {
        val portText = if (ports.size == 1) "port ${ports[0]}" else "ports " + ports.dropLast(1).joinToString(", ") + " and " + ports.last()
        return "Paddock asks every address on ${subnet.network.hostAddress}/${subnet.prefix} whether an SSH server answers on $portText. " +
            "It connects to none of them beyond reading the server's first line, and trusts and stores nothing. " +
            "On a managed network this can show up in an intrusion-detection system."
    }

    fun sentence(decision: FinderDecision): String = when (decision) {
        FinderDecision.NoLan -> "Find works on Wi-Fi or Ethernet, on the machine's own network. This phone is not on one."
        is FinderDecision.TooWide -> "This network is larger than Paddock will probe (a /${decision.prefix}). Type the machine's address instead."
        FinderDecision.Ipv6Only -> "This network has no IPv4 address, so Paddock cannot list its addresses. Type the machine's address instead."
        FinderDecision.NeedsGrant -> "Android has to let Paddock reach devices on your network first. It is used only to look for machines."
        is FinderDecision.Ready -> beforeScan(decision.subnet, listOf(22))
    }
}
