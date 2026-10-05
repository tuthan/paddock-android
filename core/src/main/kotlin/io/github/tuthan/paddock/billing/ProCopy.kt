package io.github.tuthan.paddock.billing

/**
 * The words about what Pro is, in one place so a test can pin them. Pro names only what exists: a reserved capability ([ProCapability.built]
 * false) is never listed, and the twelve-month window and the compatibility policy (vault M7) are not promised here until they are written.
 */
object ProCopy {
    /** What Pro covers today, from the gated ids that something in the app asks about; what stays free is said right after it. */
    fun coverage(gated: Set<String>): String {
        val named = ProCapabilities.ALL.filter { it.id in gated && it.built }.map { it.label.replaceFirstChar { c -> c.lowercase() } }
        if (named.isEmpty()) return "No capability is Pro yet: everything in this version is free, and a purchase would unlock nothing."
        return "Pro covers ${join(named)}. $STAYS_FREE"
    }

    /** The sheet's title for the capability the user chose; a gate asked for an id nobody named still says the true thing. */
    fun gateTitle(capabilityId: String): String = ProCapabilities.byId(capabilityId)?.let { "${it.label} is a Pro capability" } ?: "This is a Pro capability"

    /** What stays free, said beside every offer so Free is a path and not a hidden option. */
    const val STAYS_FREE = "Everything else stays free: every view of your agents, readable output, typing into any terminal, snippets, every alert, and adding a machine."

    /** The free version's way to every capability: Pro is bought in the Google Play build, or the app is built from source code. */
    const val FREE_VERSION_ROUTE = "This is the free version. Pro capabilities are bought in the Google Play build, and building Paddock from its source code turns every capability on."

    const val REFUNDS = "Purchases, receipts and refunds are handled by Google Play; Paddock has no account and keeps no record of a purchase beyond the answer Google Play last gave."
    const val TIPS = "Tips are optional, separate from Pro, and unlock nothing."

    private fun join(items: List<String>) = when (items.size) {
        1 -> items[0]
        2 -> "${items[0]} and ${items[1]}"
        else -> items.dropLast(1).joinToString(", ") + ", and " + items.last()
    }
}
