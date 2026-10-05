package io.github.tuthan.paddock.billing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** The store's product ids (Phase 13): one one-time unlock, and three tips that are consumed so the same tip can be bought again. */
object Products {
    const val PRO = "pro"
    val TIPS = listOf("tip_3", "tip_5", "tip_10")
    fun isTip(productId: String) = productId in TIPS
}

enum class PurchaseState { PURCHASED, PENDING }

/**
 * One purchase the store account owns. [token] identifies it to the store for acknowledge and consume and is never
 * written to disk or sent anywhere by Paddock. [acknowledged] is the store's own flag: Play refunds and revokes a
 * one-time purchase that is not acknowledged within [Entitlements.ACKNOWLEDGE_WINDOW_MILLIS].
 */
data class StorePurchase(
    val productId: String,
    val token: String,
    val state: PurchaseState,
    val acknowledged: Boolean,
    val purchaseTimeMillis: Long,
)

/** A product as the store prices it for this account; [price] is the store's localized string, never computed here. */
data class ProductInfo(val productId: String, val price: String)

enum class UnavailableKind {
    /** This build carries no billing library (the `foss` build): there is nothing to ask. */
    BUILD_WITHOUT_BILLING,

    /** Billing is part of the build but this device or install cannot use it: no Play Store, installed outside Play, signed out. */
    STORE_UNAVAILABLE,
}

sealed interface StoreResult<out T> {
    data class Ok<T>(val value: T) : StoreResult<T>

    /** The store cannot be used; nothing changed. */
    data class Unavailable(val kind: UnavailableKind) : StoreResult<Nothing>

    /** One call failed (network, service disconnected, a transient store error); the caller keeps what it had and may retry. */
    data class Failed(val message: String) : StoreResult<Nothing>
}

sealed interface PurchaseOutcome {
    data class Purchased(val purchase: StorePurchase) : PurchaseOutcome

    /** The store accepted the order but payment is not complete; no capability is granted until it confirms. */
    data class Pending(val purchase: StorePurchase) : PurchaseOutcome
    data object Cancelled : PurchaseOutcome

    /** The account already owns the product, so the purchases query is the way to learn the state. */
    data object AlreadyOwned : PurchaseOutcome
}

/**
 * The store, as `core` sees it. The Play Billing library lives only behind this port in the `play` build; the `foss`
 * build has [NoBilling], so `core` and the foss artefacts never see the library.
 */
interface Billing {
    /** Whether this build sells Pro. False for the foss stub, which also hides every purchase control. */
    val sellsPro: Boolean

    /** Everything the store account owns of this app's products and has not consumed. The only source of truth for a purchase. */
    suspend fun purchases(): StoreResult<List<StorePurchase>>

    suspend fun products(): StoreResult<List<ProductInfo>>

    /** Starts the store's purchase flow for [productId] and suspends until it ends. */
    suspend fun purchase(productId: String): StoreResult<PurchaseOutcome>

    suspend fun acknowledge(token: String): StoreResult<Unit>

    suspend fun consume(token: String): StoreResult<Unit>

    /**
     * The store reported a purchase change that no call of ours was waiting for, such as a pending purchase it finished while the app
     * was open; the owner should verify. It carries no data, because [purchases] is the only source of truth, and it coalesces: a
     * change that arrives while the owner is still verifying is delivered once more, not once per change. A change that arrives while
     * nobody collects is dropped, which is safe because the owner verifies at start. The default never emits, which is right for a
     * store that is not there.
     */
    val changes: Flow<Unit> get() = emptyFlow()
}

/** The `foss` build's billing: a store that is not there. Every call answers [UnavailableKind.BUILD_WITHOUT_BILLING]. */
object NoBilling : Billing {
    override val sellsPro = false
    private val none = StoreResult.Unavailable(UnavailableKind.BUILD_WITHOUT_BILLING)
    override suspend fun purchases() = none
    override suspend fun products() = none
    override suspend fun purchase(productId: String) = none
    override suspend fun acknowledge(token: String) = none
    override suspend fun consume(token: String) = none
}
