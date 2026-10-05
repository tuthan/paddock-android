package io.github.tuthan.paddock.billing

import android.content.Context
import io.github.tuthan.paddock.BuildConfig

/** The play build: Pro is a purchase, so nothing is unlocked by the build itself. */
object Distribution {
    val UNLOCKED: Boolean = BuildConfig.UNLOCKED

    /**
     * Whether this build sells Pro, known without building the store: the home-screen widget process asks it and must not construct a billing
     * client. It is what [billing]'s `sellsPro` reports, in the fallback too (`DistributionTest` and `ProGraphTest` hold them together).
     */
    const val SELLS_PRO = true

    /** The library's client, or, when building it throws, a store that answers [UnavailableKind.STORE_UNAVAILABLE] to everything, so the app still starts. */
    fun billing(context: Context): Billing = billingOrUnavailable { PlayBilling(context) }

    /** [build]'s store; a library that cannot even construct its client (a missing class, a closed context) must not end the process at graph creation. */
    internal fun billingOrUnavailable(build: () -> Billing): Billing = try {
        build()
    } catch (e: Exception) {
        UnavailableBilling
    } catch (e: LinkageError) {
        UnavailableBilling
    }
}

/**
 * The store when the library could not be started. It still sells Pro ([Distribution.SELLS_PRO]): a purchase saved by an earlier run stays honoured, as
 * it is whenever the store cannot be reached, and the next start that can build the client verifies it. Every call says the store is unavailable.
 */
internal object UnavailableBilling : Billing {
    override val sellsPro = Distribution.SELLS_PRO
    private val none = StoreResult.Unavailable(UnavailableKind.STORE_UNAVAILABLE)
    override suspend fun purchases() = none
    override suspend fun products() = none
    override suspend fun purchase(productId: String) = none
    override suspend fun acknowledge(token: String) = none
    override suspend fun consume(token: String) = none
}
