package io.github.tuthan.paddock.discovery

import java.net.Inet4Address

/**
 * A service a machine announced on the LAN (mDNS): its instance name, its address and the port it said. Nothing was connected to. [nameOnly] is
 * set for `_workstation._tcp.`, which Avahi announces on port 9: it says what the machine at the address is called, not where SSH listens.
 */
data class NsdService(val name: String, val address: Inet4Address, val port: Int, val nameOnly: Boolean = false)

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
 *
 * At most [maxRows] rows are made: a network can announce as many services as it likes. A name announced for an address alone
 * ([NsdService.nameOnly]) is attached to the rows at that address, now and when one appears later, and is never a row of its own.
 *
 * Safe to call from several threads (the probe and the mDNS listener add while the page reads). Every call holds this object's lock, so a
 * caller that must publish the list exactly as its own add left it can hold the same lock around both.
 */
class FoundHosts(private val maxRows: Int = MAX_ROWS) {
    private val rows = LinkedHashMap<Pair<String, Int>, FoundHost>()
    private val addressNames = HashMap<String, String>()

    val list: List<FoundHost> get() = synchronized(this) { rows.values.toList() }

    fun add(hit: ProbeHit): Boolean = synchronized(this) {
        val key = hit.address.hostAddress.orEmpty() to hit.port
        val old = rows[key]
        if (old == null && rows.size >= maxRows) return false
        val next = (old ?: FoundHost(key.first, key.second, addressNames[key.first], null)).copy(software = hit.software)
        rows[key] = next
        old != next
    }

    fun add(service: NsdService): Boolean = synchronized(this) {
        val name = cleanName(service.name) ?: return false
        val address = service.address.hostAddress.orEmpty()
        if (service.nameOnly) return nameAddress(address, name)
        val key = address to service.port
        val old = rows[key]
        if (old == null && rows.size >= maxRows) return false
        val next = (old ?: FoundHost(address, service.port, null, null)).copy(name = old?.name ?: name)
        rows[key] = next
        old != next
    }

    /** True when a row took the name. The first name an address was given stays, as a row's does. */
    private fun nameAddress(address: String, name: String): Boolean {
        if (address !in addressNames && addressNames.size >= maxRows) return false
        val known = addressNames.getOrPut(address) { name }
        var changed = false
        for ((key, row) in rows.entries.toList()) {
            if (key.first == address && row.name == null) { rows[key] = row.copy(name = known); changed = true }
        }
        return changed
    }

    companion object {
        const val MAX_ROWS = 200
        const val MAX_NAME_CHARACTERS = 64

        /**
         * A name from the network is shown, and stored as the profile's name, as plain text only: control, format and line or paragraph
         * separator characters (the marks and overrides that reorder or hide text) and lone surrogate halves are dropped, and what is left is
         * cut to [MAX_NAME_CHARACTERS] characters, never inside a surrogate pair. Null when nothing visible is left.
         */
        fun cleanName(raw: String): String? {
            val kept = StringBuilder()
            var i = 0
            while (i < raw.length) {
                val cp = raw.codePointAt(i)
                i += Character.charCount(cp)
                if (cp in 0xD800..0xDFFF || drops(cp)) continue
                kept.appendCodePoint(cp)
            }
            val text = kept.toString().trim()
            val cut = if (text.codePointCount(0, text.length) > MAX_NAME_CHARACTERS) text.substring(0, text.offsetByCodePoints(0, MAX_NAME_CHARACTERS)).trimEnd() else text
            return cut.takeIf { it.isNotEmpty() }
        }

        private fun drops(cp: Int): Boolean = when (Character.getType(cp)) {
            Character.CONTROL.toInt(), Character.FORMAT.toInt(), Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt() -> true
            else -> false
        }
    }
}
