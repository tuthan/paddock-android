package io.github.tuthan.paddock.billing

/** What Settings shows about Pro: a headline, a plain sentence under it, and whether the state is older than a week. */
data class EntitlementSummary(val headline: String, val detail: String, val stale: Boolean)

object EntitlementPresenter {
    fun summary(state: EntitlementState, nowMillis: Long, unlockedBuild: Boolean, storeReachable: Boolean = true, sellsPro: Boolean = true): EntitlementSummary {
        if (unlockedBuild) return EntitlementSummary("Everything is unlocked", "This build carries every feature. Nothing is sold in it.", stale = false)
        // The free version (the foss build as published): no store to ask and nothing to buy here.
        if (!sellsPro) return EntitlementSummary("Free version", ProCopy.FREE_VERSION_ROUTE, stale = false)
        val age = state.verifiedAtMillis?.let { age(nowMillis - it) }
        val stale = state.verifiedAtMillis?.let { nowMillis - it > Entitlements.STALE_AFTER_MILLIS } == true
        val offline = if (!storeReachable && age != null) " Google Play could not be reached just now, so this is what it said $age." else ""
        return when (state.status) {
            ProStatus.UNKNOWN -> EntitlementSummary("Not confirmed yet", "Paddock has not asked Google Play about Pro on this phone yet. Use Restore if you bought it before.", stale = false)
            ProStatus.FREE -> EntitlementSummary("Free", "Pro is not on this Google account.$offline", stale)
            ProStatus.PENDING -> EntitlementSummary("Purchase pending", "Google Play has not finished the payment. Pro turns on when it confirms; nothing is unlocked meanwhile.", stale)
            ProStatus.PRO -> EntitlementSummary(
                "Pro",
                if (state.acknowledged) "Confirmed with Google Play ${age ?: "just now"}.$offline"
                else "Bought, but Google Play has not confirmed the receipt yet. Open Paddock with the phone online within three days of buying, or Google Play refunds it.$offline",
                stale,
            )
            ProStatus.REVOKED -> EntitlementSummary(
                "Pro was withdrawn",
                when (state.revokeReason) {
                    RevokeReason.UNCONFIRMED -> "The purchase could not be confirmed with Google Play in time, so Google Play refunded it. Everything you made with Pro stays readable."
                    else -> "Google Play no longer lists the purchase (a refund or a chargeback). Everything you made with Pro stays readable."
                },
                stale,
            )
        }
    }

    fun age(millis: Long): String {
        val minutes = millis.coerceAtLeast(0) / 60_000
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes ${if (minutes == 1L) "minute" else "minutes"} ago"
            minutes < 48 * 60 -> (minutes / 60).let { "$it ${if (it == 1L) "hour" else "hours"} ago" }
            else -> (minutes / (24 * 60)).let { "$it days ago" }
        }
    }
}
