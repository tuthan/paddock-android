package io.github.tuthan.paddock.discovery

/** The port the user typed on the finder: blank means only SSH's own, a number in range is added, anything else is an error and nothing starts. */
data class TypedPort(val port: Int?, val error: String?)

/** What the finder says once a scan has ended, and how it reads the typed port. */
object FinderText {
    const val PORT_ERROR = "Enter a port from 1 to 65535, or leave it empty to look at port 22 only."

    fun port(text: String): TypedPort {
        val t = text.trim()
        if (t.isEmpty()) return TypedPort(null, null)
        val n = if (t.length <= 5 && t.all { it in '0'..'9' }) t.toInt() else null
        return if (n != null && n in 1..65535) TypedPort(n, null) else TypedPort(null, PORT_ERROR)
    }

    /** Null while nothing has ended: the sentence from before the scan is what the page shows then. */
    fun result(state: FinderState): String? {
        val n = state.rows.size
        val found = when (n) { 0 -> "none"; 1 -> "1 machine"; else -> "$n machines" }
        return when (state.phase) {
            FinderPhase.Done ->
                if (n == 0) "No SSH server answered. Check that sshd is running on the machine and that it is on this network, or type its address instead."
                else "Found $found. Tap one to fill in its address and port. Nothing has been connected to."
            FinderPhase.Cancelled -> "Stopped. Found so far: $found."
            else -> null
        }
    }

    fun progress(state: FinderState): String? =
        if (state.phase == FinderPhase.Scanning) "Tried ${state.done} of ${state.total} addresses" else null
}
