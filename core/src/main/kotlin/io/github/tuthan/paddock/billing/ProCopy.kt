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

    /**
     * What Pro covers as a list, for the gate sheet (decision D6): the built, gated labels in [ProCapabilities.ALL] order, except that the
     * capability with id [first] (the one the user just chose) leads, so the sheet starts with what the tap was about and the rest reads as what
     * else the purchase brings. Labels keep their capital, because each is a line of its own. Empty when nothing built is gated; [coverage]
     * stays the one-sentence form Settings uses while Pro is held, and [coverageShort] the row Settings shows while it is not.
     */
    fun coverageLines(gated: Set<String>, first: String? = null): List<String> =
        ProCapabilities.ALL.filter { it.id in gated && it.built }.sortedBy { it.id != first }.map { it.label }

    /**
     * What Pro covers as one short line, for the "What Pro covers" row on Settings' Pro card (decision D5): the built, gated labels in
     * [ProCapabilities.ALL] order, joined with ", ", the first keeping its capital and the rest lower-cased as [coverage] does. No "and" and no
     * full stop, because it is a row's secondary text, not a sentence; the sheet the row opens says the rest. Empty when nothing built is gated.
     */
    fun coverageShort(gated: Set<String>): String =
        ProCapabilities.ALL.filter { it.id in gated && it.built }
            .mapIndexed { i, c -> if (i == 0) c.label else c.label.replaceFirstChar { ch -> ch.lowercase() } }
            .joinToString(", ")

    /**
     * The gate request that opens the sheet as an overview of Pro (Settings' "What Pro covers" row, decision D5), not for a capability. It is never
     * in [ProGate.GATED] and [ProCapabilities.byId] does not know it, so [ProGate.decide] says PROCEED for it: whoever shows the sheet asks for it by
     * this id and decides by idle and Pro held itself (the app's gate host does).
     */
    const val OVERVIEW_ID = "pro.overview"

    /** The title of the Settings row that opens the overview, and the overview sheet's title ([gateTitle] of [OVERVIEW_ID]). */
    const val OVERVIEW_ROW = "What Pro covers"

    /** The words in front of [coverageLines] on the gate sheet, and the start of what TalkBack reads for the list as one block. */
    const val COVERS = "Pro covers:"

    /**
     * The sheet's title for the capability the user chose; for [OVERVIEW_ID] the overview's title, since nothing was chosen; a gate asked for an id
     * nobody named still says the true thing.
     */
    fun gateTitle(capabilityId: String): String =
        if (capabilityId == OVERVIEW_ID) OVERVIEW_ROW
        else ProCapabilities.byId(capabilityId)?.let { "${it.label} is a Pro capability" } ?: "This is a Pro capability"

    /** What stays free, said beside every offer so Free is a path and not a hidden option. */
    const val STAYS_FREE = "Everything else stays free: every view of your agents, readable output, typing into any terminal, snippets, every alert, and adding or removing a machine."

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
