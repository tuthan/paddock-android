package io.github.tuthan.paddock.billing

import io.github.tuthan.paddock.ports.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

enum class ProStatus {
    /** Never verified against the store (a fresh install, or the saved state was unreadable). Grants nothing. */
    UNKNOWN,

    /** The store was asked and the account does not own `pro`. */
    FREE,

    /** The store accepted an order that is not paid yet. Grants nothing until the store confirms. */
    PENDING,

    /** The account owns `pro`. [EntitlementState.acknowledged] says whether the store has the acknowledgement. */
    PRO,

    /** `pro` was owned and the store no longer lists it. Grants nothing; data made with Pro stays readable. */
    REVOKED,
}

enum class RevokeReason {
    REFUNDED,

    /** The purchase was never acknowledged (the phone was offline), so the store refunded it after three days. */
    UNCONFIRMED,
}

/**
 * What is saved between runs: the last verified answer and when it was verified, never a purchase token. A failed
 * verification leaves it as it is; only a successful query changes [status].
 */
@Serializable
data class EntitlementState(
    val status: ProStatus = ProStatus.UNKNOWN,
    val verifiedAtMillis: Long? = null,
    val acknowledged: Boolean = false,
    val purchaseTimeMillis: Long? = null,
    val revokeReason: RevokeReason? = null,
)

interface EntitlementStore {
    suspend fun load(): EntitlementState
    suspend fun save(state: EntitlementState)
}

class InMemoryEntitlementStore(initial: EntitlementState = EntitlementState()) : EntitlementStore {
    @Volatile var current = initial
    override suspend fun load() = current
    override suspend fun save(state: EntitlementState) { current = state }
}

/** One verification pass. [reachable] is false when the store could not be asked, so [state] is the last known state, unchanged. */
data class Verification(
    val state: EntitlementState,
    val reachable: Boolean,
    /** Tips the account still owns unconsumed (the app ended before the thank-you); the UI shows the thank-you and then calls [Entitlements.tipThanked]. */
    val unconsumedTips: List<StorePurchase> = emptyList(),
)

sealed interface BuyResult {
    /** `pro` is owned and verified (acknowledged or still being retried). */
    data class Done(val state: EntitlementState) : BuyResult
    data class Pending(val state: EntitlementState) : BuyResult
    data object Cancelled : BuyResult
    data class Unavailable(val kind: UnavailableKind) : BuyResult
    data class Failed(val message: String) : BuyResult
}

sealed interface TipResult {
    /** Bought; show the thank-you, then call [Entitlements.tipThanked] with [token] so the tip is consumed and can be bought again. */
    data class Thanks(val token: String) : TipResult
    data object Pending : TipResult
    data object Cancelled : TipResult
    data class Unavailable(val kind: UnavailableKind) : TipResult
    data class Failed(val message: String) : TipResult
}

/**
 * The entitlement state machine. The store account's purchase list is the only source of truth; this class asks, keeps
 * the last answer and its time in [store], and never downgrades because an ask failed. [unlockedBuild] is the one flag
 * the vault's M4 names: a build that carries every capability (the foss build) has Pro without a purchase and has no
 * store to ask.
 */
class Entitlements(
    private val billing: Billing,
    private val store: EntitlementStore,
    private val clock: Clock,
    val unlockedBuild: Boolean = false,
) {
    /** Serializes the calls that ask the store. They can hang (a Play callback that never fires, a purchase sheet left open), so nothing that only reads waits on this. */
    private val storeCalls = Mutex()

    /** Guards [store] and is held only for one load or one compare-and-save, never across a store call, so [saved] answers while a store call is in flight. */
    private val file = Mutex()

    val sellsPro: Boolean get() = billing.sellsPro

    /** The store reported a purchase change that no call of ours was waiting for ([Billing.changes]); the owner verifies on each. Never emits in a build without a store. */
    val storeChanges: Flow<Unit> get() = billing.changes

    /** The store's localized prices for `pro` and the tips; never computed here. */
    suspend fun products(): StoreResult<List<ProductInfo>> = billing.products()

    /** The saved state, without asking the store and without waiting for a store call that is in flight: the last state that was committed. */
    suspend fun saved(): EntitlementState = file.withLock { store.load() }

    /** True when a Pro capability may run now: an unlocked build, or a verified purchase in a build that sells Pro. Pending, revoked and unknown grant nothing. */
    fun hasPro(state: EntitlementState): Boolean = hasPro(state, unlockedBuild, billing.sellsPro)

    /**
     * Asks the store what the account owns and records the answer. Run at app start, after a purchase and on Restore.
     * An unacknowledged `pro` is acknowledged here, so a failed acknowledgement is retried on every start until the
     * store reports it.
     */
    suspend fun verify(): Verification = storeCalls.withLock { verifyLocked() }

    /** Restore is verification: the store account's purchase history is the only source, and no Paddock account exists. */
    suspend fun restore(): Verification = verify()

    suspend fun buyPro(): BuyResult = storeCalls.withLock {
        when (val r = billing.purchase(Products.PRO)) {
            is StoreResult.Unavailable -> BuyResult.Unavailable(r.kind)
            is StoreResult.Failed -> BuyResult.Failed(r.message)
            is StoreResult.Ok -> when (val o = r.value) {
                PurchaseOutcome.Cancelled -> BuyResult.Cancelled
                // The order the store just reported is itself the store's answer: record it (and acknowledge it) without a second round trip.
                is PurchaseOutcome.Purchased -> settle(o.purchase).let { if (it.status == ProStatus.PENDING) BuyResult.Pending(it) else BuyResult.Done(it) }
                is PurchaseOutcome.Pending -> BuyResult.Pending(settle(o.purchase))
                // Owned already (a second device, a reinstall): only the purchase list can say what the account holds.
                // Only a PRO the list actually holds is Done: a pending order is still pending, and an empty list (or a refunded order) unlocks nothing, so nothing is thanked.
                PurchaseOutcome.AlreadyOwned -> verifyLocked().let {
                    when {
                        !it.reachable -> BuyResult.Failed("the store reports Pro as owned but could not be asked for it; try Restore")
                        it.state.status == ProStatus.PRO -> BuyResult.Done(it.state)
                        it.state.status == ProStatus.PENDING -> BuyResult.Pending(it.state)
                        else -> BuyResult.Failed("the store reports Pro as owned but lists no Pro purchase for this account; try Restore")
                    }
                }
            }
        }
    }

    suspend fun buyTip(productId: String): TipResult = storeCalls.withLock {
        require(Products.isTip(productId)) { "not a tip: $productId" }
        when (val r = billing.purchase(productId)) {
            is StoreResult.Unavailable -> TipResult.Unavailable(r.kind)
            is StoreResult.Failed -> TipResult.Failed(r.message)
            is StoreResult.Ok -> when (val o = r.value) {
                PurchaseOutcome.Cancelled -> TipResult.Cancelled
                is PurchaseOutcome.Pending -> TipResult.Pending
                is PurchaseOutcome.Purchased -> TipResult.Thanks(o.purchase.token)
                // An unconsumed earlier tip blocks buying it again; verify() lists it and the thank-you consumes it.
                PurchaseOutcome.AlreadyOwned -> verifyLocked().unconsumedTips.firstOrNull { it.productId == productId }
                    ?.let { TipResult.Thanks(it.token) } ?: TipResult.Failed("the store reports the tip as owned but does not list it")
            }
        }
    }

    /** The thank-you has been shown: consume the tip so the store lets it be bought again. Returns false when the store could not be reached (the next verification lists it again). */
    suspend fun tipThanked(token: String): Boolean = storeCalls.withLock { billing.consume(token) is StoreResult.Ok }

    /** Records one `pro` order from the store: pending grants nothing; a paid one is acknowledged now, and retried on every start if that fails. */
    private suspend fun settle(pro: StorePurchase): EntitlementState {
        val now = clock.nowMillis()
        val next = if (pro.state == PurchaseState.PENDING) {
            EntitlementState(ProStatus.PENDING, now, purchaseTimeMillis = pro.purchaseTimeMillis)
        } else {
            EntitlementState(ProStatus.PRO, now, pro.acknowledged || billing.acknowledge(pro.token) is StoreResult.Ok, pro.purchaseTimeMillis)
        }
        commit(next)
        return next
    }

    /** Writes [next] unless it is what is saved already. Every write goes through here, under [file], and only a caller holding [storeCalls] gets here, so the file never has two writers. */
    private suspend fun commit(next: EntitlementState) = file.withLock { if (next != store.load()) store.save(next) }

    private suspend fun verifyLocked(): Verification {
        val before = saved()
        val purchases = when (val r = billing.purchases()) {
            is StoreResult.Ok -> r.value
            // Could not ask: the last known state stands, with its age. Never a silent downgrade.
            is StoreResult.Unavailable, is StoreResult.Failed -> return Verification(before, reachable = false)
        }
        val now = clock.nowMillis()
        val pro = purchases.firstOrNull { it.productId == Products.PRO }
        val after = when {
            pro == null -> when (before.status) {
                ProStatus.PRO -> EntitlementState(
                    ProStatus.REVOKED, now, revokeReason = if (before.acknowledged) RevokeReason.REFUNDED else RevokeReason.UNCONFIRMED,
                )
                ProStatus.REVOKED -> before.copy(verifiedAtMillis = now)
                else -> EntitlementState(ProStatus.FREE, now)
            }
            else -> return Verification(settle(pro), reachable = true, unconsumedTips = unconsumed(purchases))
        }
        commit(after)
        return Verification(after, reachable = true, unconsumedTips = unconsumed(purchases))
    }

    private fun unconsumed(purchases: List<StorePurchase>) = purchases.filter { Products.isTip(it.productId) && it.state == PurchaseState.PURCHASED }

    companion object {
        /** Play refunds and revokes a one-time purchase that is not acknowledged within three days. */
        const val ACKNOWLEDGE_WINDOW_MILLIS = 3L * 24 * 60 * 60 * 1000

        /** After this long without a successful verification the saved state is shown as stale. It is still honoured. */
        const val STALE_AFTER_MILLIS = 7L * 24 * 60 * 60 * 1000

        /**
         * Whether [state] grants Pro in a build that is [unlockedBuild] and does or does not [sellsPro]. One rule for the
         * app and for the home-screen widget process, which reads the saved file without an instance. A build that cannot
         * verify a purchase honours none: the foss build never asks the store, so a PRO state in an `entitlement.json` left
         * by a play install of the same application id could never be corrected, and a refund would go unseen forever.
         */
        fun hasPro(state: EntitlementState, unlockedBuild: Boolean, sellsPro: Boolean): Boolean =
            unlockedBuild || (sellsPro && state.status == ProStatus.PRO)

        /**
         * A return to the foreground verifies again once the last attempt is this old: a pending purchase that completed while the app sat in the
         * background is then seen and acknowledged well inside [ACKNOWLEDGE_WINDOW_MILLIS], and switching away and back does not cost a store round
         * trip each time.
         */
        const val REVERIFY_AFTER_MILLIS = 15L * 60 * 1000

        /** Whether a verification is due on a return to the foreground. [lastAttemptMillis] is this process's last attempt (null: none yet); a clock that went backwards counts as due. */
        fun reverifyDue(lastAttemptMillis: Long?, nowMillis: Long): Boolean =
            lastAttemptMillis == null || nowMillis < lastAttemptMillis || nowMillis - lastAttemptMillis >= REVERIFY_AFTER_MILLIS
    }
}
