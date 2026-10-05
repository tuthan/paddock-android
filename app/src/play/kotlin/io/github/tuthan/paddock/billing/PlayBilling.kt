package io.github.tuthan.paddock.billing

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The Play Billing library behind the [Billing] port, in the play flavor only. Nothing here talks to a server of Paddock's: the
 * library speaks to the Play Store app on the phone. Every call goes to the library on the main thread, as it expects.
 * [Entitlements] serializes purchases, so at most one flow waits on [purchaseUpdates] at a time. No call throws and none waits without
 * a bound: see [guarded] and [awaitCallback].
 */
class PlayBilling(context: Context) : Billing {
    override val sellsPro = true

    private val app = context.applicationContext as Application
    private val main = Handler(Looper.getMainLooper())
    private val connectLock = Mutex()
    @Volatile private var details: Map<String, ProductDetails> = emptyMap()
    private val purchaseUpdates = PurchaseUpdates()
    override val changes: Flow<Unit> get() = purchaseUpdates.changes
    private val front = FrontActivity().also { app.registerActivityLifecycleCallbacks(it) }

    private val client: BillingClient = BillingClient.newBuilder(app)
        .setListener { result, purchases -> purchaseUpdates.onUpdate(result, purchases) }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    override suspend fun purchases(): StoreResult<List<StorePurchase>> = withClient {
        val result = onMain<Pair<BillingResult, List<Purchase>>> { done ->
            client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()) { r, list -> done(r to list) }
        }
        result.first.toStoreResult { result.second.flatMap { it.toStorePurchases() } }
    }

    override suspend fun products(): StoreResult<List<ProductInfo>> = withClient {
        val (result, list) = queryDetails()
        result.toStoreResult { list.mapNotNull { d -> d.oneTimePurchaseOfferDetails?.formattedPrice?.let { ProductInfo(d.productId, it) } } }
    }

    override suspend fun purchase(productId: String): StoreResult<PurchaseOutcome> = withClient {
        val activity = front.get() ?: return@withClient StoreResult.Failed("Paddock has to be in front to start a purchase")
        var product = details[productId]
        if (product == null) {
            val (result, _) = queryDetails()
            if (result.responseCode != BillingResponseCode.OK) return@withClient result.failure()
            product = details[productId] ?: return@withClient StoreResult.Failed("the store does not list $productId for this account")
        }
        val flow = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(product).build()))
            .build()
        val waiting = purchaseUpdates.expect()
        try {
            val launched = onMain<BillingResult> { done -> done(client.launchBillingFlow(activity, flow)) }
            if (launched.responseCode != BillingResponseCode.OK) return@withClient launched.failure()
            val (result, purchases) = withTimeoutOrNull(FLOW_TIMEOUT_MILLIS) { waiting.await() } ?: return@withClient StoreResult.Failed("the purchase did not finish")
            when (result.responseCode) {
                BillingResponseCode.OK -> {
                    val bought = purchases.orEmpty().flatMap { it.toStorePurchases() }.firstOrNull { it.productId == productId }
                        ?: return@withClient StoreResult.Failed("the store confirmed a purchase but did not list $productId")
                    StoreResult.Ok(if (bought.state == PurchaseState.PENDING) PurchaseOutcome.Pending(bought) else PurchaseOutcome.Purchased(bought))
                }
                BillingResponseCode.USER_CANCELED -> StoreResult.Ok(PurchaseOutcome.Cancelled)
                BillingResponseCode.ITEM_ALREADY_OWNED -> StoreResult.Ok(PurchaseOutcome.AlreadyOwned)
                else -> result.failure()
            }
        } finally {
            purchaseUpdates.release(waiting)
        }
    }

    override suspend fun acknowledge(token: String): StoreResult<Unit> = withClient {
        val result = onMain<BillingResult> { done -> client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(token).build()) { done(it) } }
        result.toStoreResult { }
    }

    override suspend fun consume(token: String): StoreResult<Unit> = withClient {
        val result = onMain<BillingResult> { done -> client.consumeAsync(ConsumeParams.newBuilder().setPurchaseToken(token).build()) { r, _ -> done(r) } }
        result.toStoreResult { }
    }

    private suspend fun queryDetails(): Pair<BillingResult, List<ProductDetails>> {
        val products = (listOf(Products.PRO) + Products.TIPS).map {
            QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(BillingClient.ProductType.INAPP).build()
        }
        val (result, list) = onMain<Pair<BillingResult, List<ProductDetails>>> { done ->
            client.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(products).build()) { r, q -> done(r to q.productDetailsList) }
        }
        if (result.responseCode == BillingResponseCode.OK) details = list.associateBy { it.productId }
        return result to list
    }

    /** Connects if needed, then runs [block]. A connection that cannot be made is the store being unavailable or failing, never a crash. */
    private suspend fun <T> withClient(block: suspend () -> StoreResult<T>): StoreResult<T> =
        guarded { connectedThen(connectLock, { client.isReady }, { connect() }, block) }

    /** Null once the client is connected, else why it is not. */
    private suspend fun connect(): StoreResult<Nothing>? {
        val setup = onMain<BillingResult> { done ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) = done(result)
                override fun onBillingServiceDisconnected() = Unit
            })
        }
        return if (setup.responseCode == BillingResponseCode.OK) null else setup.failure()
    }

    /** Runs [call] on the main thread and waits at most [CALL_TIMEOUT_MILLIS] for the answer it hands to `done`. */
    private suspend fun <T : Any> onMain(call: (done: (T) -> Unit) -> Unit): T = awaitCallback(CALL_TIMEOUT_MILLIS, { run -> main.post { run() } }, call)

    private fun Purchase.toStorePurchases(): List<StorePurchase> {
        val state = when (purchaseState) {
            Purchase.PurchaseState.PURCHASED -> PurchaseState.PURCHASED
            Purchase.PurchaseState.PENDING -> PurchaseState.PENDING
            else -> return emptyList()
        }
        return products.filter { it == Products.PRO || Products.isTip(it) }.map { StorePurchase(it, purchaseToken, state, isAcknowledged, purchaseTime) }
    }

    private inline fun <T> BillingResult.toStoreResult(ok: () -> T): StoreResult<T> = if (responseCode == BillingResponseCode.OK) StoreResult.Ok(ok()) else failure()

    private fun <T> BillingResult.failure(): StoreResult<T> = when (responseCode) {
        // No Play Store, an install the store does not recognise, billing off for the account or the country, or an old Play Store.
        BillingResponseCode.BILLING_UNAVAILABLE, BillingResponseCode.FEATURE_NOT_SUPPORTED -> StoreResult.Unavailable(UnavailableKind.STORE_UNAVAILABLE)
        else -> StoreResult.Failed("Google Play answered $responseCode${debugMessage.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}")
    }

    /** The activity in front, held weakly: a purchase flow needs one to launch from, and nothing else holds it. */
    private class FrontActivity : Application.ActivityLifecycleCallbacks {
        private var current = WeakReference<Activity>(null)
        fun get(): Activity? = current.get()
        override fun onActivityResumed(activity: Activity) { current = WeakReference(activity) }
        override fun onActivityPaused(activity: Activity) { if (current.get() === activity) current = WeakReference(null) }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    private companion object {
        /** A purchase sheet the user is looking at can stay open this long. */
        const val FLOW_TIMEOUT_MILLIS = 15L * 60 * 1000
    }
}

/** How long one library call (connect, query, acknowledge, consume, launch) may take to answer. The purchase sheet has its own, longer wait. */
internal const val CALL_TIMEOUT_MILLIS = 30_000L

/** The library did not answer within the bound. [guarded] makes it a [StoreResult.Failed]; it is deliberately not a [CancellationException], which would be mistaken for the caller's own. */
internal class StoreNoAnswer : RuntimeException("Google Play did not answer in time")

/**
 * Runs [call] where [post] puts it (the main thread) and waits at most [timeoutMillis] for the one answer it hands to `done`. A throw
 * from [call] reaches the caller; no answer in time throws [StoreNoAnswer]. A deferred takes the answer, so an answer that comes after
 * the wait is over, or a second one, completes nothing and cannot resume a caller that has gone.
 */
internal suspend fun <T : Any> awaitCallback(timeoutMillis: Long, post: (() -> Unit) -> Unit, call: (done: (T) -> Unit) -> Unit): T {
    val answer = CompletableDeferred<T>()
    post { try { call { answer.complete(it) } } catch (e: Exception) { answer.completeExceptionally(e) } }
    return withTimeoutOrNull(timeoutMillis) { answer.await() } ?: throw StoreNoAnswer()
}

/**
 * Holds [block] to the [Billing] port's contract: it never throws, because its callers launch it with no handler and a throw would end
 * the process. A library that throws (a client that is closed or still connecting, a dead Play Store service) is one failed call, and
 * the caller keeps what it had and may retry. Only the caller's own cancellation passes through. An [Error] is not handled here.
 */
internal suspend fun <T> guarded(block: suspend () -> StoreResult<T>): StoreResult<T> = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: StoreNoAnswer) {
    StoreResult.Failed(e.message.orEmpty())
} catch (e: Exception) {
    StoreResult.Failed("Google Play threw ${e.javaClass.simpleName}${e.message?.let { ": $it" }.orEmpty()}")
}

/**
 * Connects under [lock] if [isReady] says there is no connection, then runs [block] outside it, so a second caller waits for the
 * connection and not for the first caller's work. [connect] answers null once connected, else why not. The lock is released however
 * [connect] ends, including by its bound running out, so one stuck connection cannot hold every later call.
 */
internal suspend fun <T> connectedThen(lock: Mutex, isReady: () -> Boolean, connect: suspend () -> StoreResult<Nothing>?, block: suspend () -> StoreResult<T>): StoreResult<T> {
    lock.withLock { if (!isReady()) connect()?.let { return it } }
    return block()
}

/**
 * What the library's purchases listener reports. The update of a purchase flow we started goes to the call waiting for it ([expect]);
 * one nobody waits for (a pending purchase the store finished while the app was open, a purchase that finishes after its flow gave up)
 * would otherwise be dropped, and an unacknowledged purchase is refunded by Play after three days, so it is announced on [changes] for
 * the owner to verify. One slot of buffer keeps the latest change for an owner that is busy verifying.
 */
internal class PurchaseUpdates {
    private val waiting = AtomicReference<CompletableDeferred<Pair<BillingResult, List<Purchase>?>>?>(null)
    private val changed = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val changes: Flow<Unit> = changed.asSharedFlow()

    fun expect(): CompletableDeferred<Pair<BillingResult, List<Purchase>?>> = CompletableDeferred<Pair<BillingResult, List<Purchase>?>>().also { waiting.set(it) }
    fun release(flow: CompletableDeferred<Pair<BillingResult, List<Purchase>?>>) { waiting.compareAndSet(flow, null) }

    fun onUpdate(result: BillingResult, purchases: List<Purchase>?) {
        // Only the first update completes the flow's deferred; a second one for the same flow is nobody's and is announced.
        if (waiting.get()?.complete(result to purchases) == true) return
        if (result.responseCode == BillingResponseCode.OK || result.responseCode == BillingResponseCode.ITEM_ALREADY_OWNED) changed.tryEmit(Unit)
    }
}
