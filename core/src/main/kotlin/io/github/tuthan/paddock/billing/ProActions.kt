package io.github.tuthan.paddock.billing

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One store action at a time (review F9). Buy, Tip, Restore and every verification are all store calls that set and clear the same "busy" the
 * buttons are disabled by; run side by side, whichever finished first cleared it while another was still in flight, and a second tap could start a
 * second purchase. So there is one lock: an action the user starts is refused up front when another is running ([tryRun], which is also what the
 * disabled buttons promise), and one the app starts by itself (the start verification, a store change) waits its turn ([run]) instead of being
 * dropped, so a change that arrived mid-action is still looked at afterwards.
 */
class StoreActions {
    private val lock = Mutex()

    /** Whether an action holds the lock now. */
    val inFlight: Boolean get() = lock.isLocked

    /** Runs [block] unless another action is in flight, and says whether it ran. A refused action does nothing at all. */
    suspend fun tryRun(block: suspend () -> Unit): Boolean {
        if (!lock.tryLock()) return false
        try { block() } finally { lock.unlock() }
        return true
    }

    /** Runs [block] once no other action is in flight. */
    suspend fun run(block: suspend () -> Unit) = lock.withLock { block() }
}

/**
 * Runs [block] and keeps whatever it throws from reaching the process: the graph launches store work in a scope with no exception handler, so an
 * uncaught throw there ends the app. The Billing port promises not to throw, but the caller does not depend on that. A throw that is not the
 * caller's own cancellation is handed to [onFailure] as the sentence the screen shows ([ProMessages.storeThrew]).
 */
suspend fun <T> storeGuarded(onFailure: (String) -> T, block: suspend () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    onFailure(ProMessages.storeThrew(e))
}

/** The sentences Settings and the gate sheet say about a store action, chosen here so a test can pin each one. */
object ProMessages {
    const val TIP_EARLIER = "Thank you for your earlier tip."
    const val NOT_REACHED = "Google Play could not be reached, so nothing changed."
    const val RESTORED = "Pro restored from this Google account."
    const val NOT_FINISHED = "Google Play has not finished the payment."
    const val RESTORE_NONE = "Google Play lists no Pro purchase for this account."
    const val STORE_UNAVAILABLE = "Google Play is not available on this phone or install."
    const val BOUGHT = "Pro is on. Thank you."
    const val BOUGHT_PENDING = "Google Play has not finished the payment. Pro turns on when it does."
    const val TIP_THANKS = "Thank you. A tip unlocks nothing and is not Pro."

    /**
     * What a verification says. The outcome of a Restore is always said, because it is what the user asked; the thank-you for an earlier tip that
     * was still unconsumed is added after it and never replaces it (review F10). Only a reachable store lists tips, so [tipThanked] implies it.
     */
    fun verification(restore: Boolean, reachable: Boolean, status: ProStatus, tipThanked: Boolean): String? {
        val outcome = when {
            !reachable -> if (restore) NOT_REACHED else null
            !restore -> null
            status == ProStatus.PRO -> RESTORED
            status == ProStatus.PENDING -> NOT_FINISHED
            else -> RESTORE_NONE
        }
        return listOfNotNull(outcome, TIP_EARLIER.takeIf { tipThanked }).joinToString(" ").ifEmpty { null }
    }

    fun tip(result: TipResult): String? = when (result) {
        is TipResult.Thanks -> TIP_THANKS
        TipResult.Pending -> NOT_FINISHED
        TipResult.Cancelled -> null
        is TipResult.Unavailable -> STORE_UNAVAILABLE
        is TipResult.Failed -> "The tip did not go through: ${result.message}."
    }

    fun purchase(result: BuyResult): String? = when (result) {
        is BuyResult.Done -> BOUGHT
        is BuyResult.Pending -> BOUGHT_PENDING
        BuyResult.Cancelled -> null
        is BuyResult.Unavailable -> STORE_UNAVAILABLE
        is BuyResult.Failed -> "The purchase did not go through: ${result.message}."
    }

    /** A store action ended in a throw nobody expected: say what kind, never the message (a library's text can carry anything). */
    fun storeThrew(e: Throwable): String = "Google Play could not be used: ${e.javaClass.simpleName.ifEmpty { e.javaClass.name }}"
}
