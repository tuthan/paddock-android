package io.github.tuthan.paddock.wake

/** A command the user may run on the machine, with what it does. Shown and copied, never run by the app. */
data class WakeCommand(val label: String, val text: String)

object WakeCommands {
    /**
     * The commands that apply to what was read. A name or letter in them has passed the validation of [WakeTarget] and
     * [WakeReadiness] (`[A-Za-z0-9._-]`), so nothing here needs quoting. The connection name is the user's to fill in: Paddock
     * does not read it.
     */
    fun forTarget(target: WakeTarget): List<WakeCommand> {
        val iface = target.iface ?: return emptyList()
        val r = target.readiness ?: return emptyList()
        val out = ArrayList<WakeCommand>()
        // Each enable command follows its own reading: the wakeup node being off says nothing about the magic-packet setting.
        if (r.wakeup == "disabled") out += WakeCommand("Let the interface wake the machine, until the next reboot", "echo enabled | sudo tee /sys/class/net/$iface/device/power/wakeup")
        if (r.wifi && r.phy != null) {
            if (!r.magicPacketOn) out += WakeCommand("Wake on a magic packet, until the next reboot", "sudo iw phy ${r.phy} wowlan enable magic-packet")
            out += WakeCommand("Keep it after a reboot (name from `nmcli -t -f NAME,DEVICE connection show --active`)", "nmcli connection modify \"<connection name>\" 802-11-wireless.wake-on-wlan magic")
        } else if (!r.wifi) {
            if (!r.magicPacketOn) out += WakeCommand("Wake on a magic packet, until the next reboot", "sudo ethtool -s $iface wol g")
            out += WakeCommand("Keep it after a reboot (name from `nmcli -t -f NAME,DEVICE connection show --active`)", "nmcli connection modify \"<connection name>\" 802-3-ethernet.wake-on-lan magic")
        }
        return out
    }

    /** The Settings row's words: what was read, in a sentence, with when. */
    fun status(target: WakeTarget, capturedLabel: String): String {
        if (!target.available) return "Not available: ${target.reason ?: "no reason given"}"
        val r = target.readiness
        val verdict = when (r?.verdict) {
            WakeReadiness.Verdict.Ready -> "Ready"
            WakeReadiness.Verdict.NotReady -> "Not ready: ${notReadyWhy(r)}"
            else -> "Not known: ${unknownWhy(r)}"
        }
        return "$verdict · ${target.mac} on ${target.iface} · wakes from sleep, not from shutdown · as of $capturedLabel"
    }

    private fun notReadyWhy(r: WakeReadiness): String = when {
        r.wakeup == "disabled" -> "the interface is not allowed to wake the machine"
        r.wifi -> "Wi-Fi wake (WoWLAN) is ${r.wowlan ?: "off"}"
        else -> "Wake-on is ${r.ethtool ?: "off"}"
    }

    private fun unknownWhy(r: WakeReadiness?): String {
        val said = if (r?.wifi == true) r.wowlan else r?.ethtool
        return said?.removePrefix("unknown: ")?.takeIf { it.isNotEmpty() } ?: "the machine did not say"
    }
}
