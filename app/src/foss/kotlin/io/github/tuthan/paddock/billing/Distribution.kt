package io.github.tuthan.paddock.billing

import android.content.Context
import io.github.tuthan.paddock.BuildConfig

/**
 * The foss build (GitHub, F-Droid, IzzyOnDroid): no billing library, nothing sold, and the free version: Pro capabilities are locked and
 * there is nothing to buy here (vault decision M4, 2026-10-04). [UNLOCKED] is false in every published build; a source build with
 * `-PpaddockUnlocked=true` turns it on, which is how anyone who wants every capability gets it.
 */
object Distribution {
    val UNLOCKED: Boolean = BuildConfig.UNLOCKED

    /**
     * Whether this build sells Pro, known without building the store: the home-screen widget process asks it and must not construct a billing
     * client. It is what [billing]'s `sellsPro` reports (`DistributionTest` and `ProGraphTest` hold the two together). False here: a saved Pro answer
     * is never honoured, because nothing in this build can verify or correct it.
     */
    const val SELLS_PRO = false

    @Suppress("UNUSED_PARAMETER") fun billing(context: Context): Billing = NoBilling
}
