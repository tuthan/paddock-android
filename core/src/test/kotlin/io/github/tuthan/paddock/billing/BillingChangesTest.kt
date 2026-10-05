package io.github.tuthan.paddock.billing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** The port's `changes` member has a default, so a store that has nothing to report (and every fake written before it) needs no change. */
class BillingChangesTest {
    /** A store written without knowing about `changes`: it overrides only what the port required before. */
    private object Plain : Billing {
        override val sellsPro = true
        override suspend fun purchases() = StoreResult.Ok(emptyList<StorePurchase>())
        override suspend fun products() = StoreResult.Ok(emptyList<ProductInfo>())
        override suspend fun purchase(productId: String) = StoreResult.Ok(PurchaseOutcome.Cancelled)
        override suspend fun acknowledge(token: String) = StoreResult.Ok(Unit)
        override suspend fun consume(token: String) = StoreResult.Ok(Unit)
    }

    @Test fun aStoreThatDoesNotOverrideChangesNeverReportsOneAndTheFlowEnds() = runBlocking {
        assertTrue(withTimeout(5_000) { Plain.changes.toList() }.isEmpty())
    }

    @Test fun theFossStubReportsNoChanges() = runBlocking {
        assertEquals(emptyList(), withTimeout(5_000) { NoBilling.changes.toList() })
    }
}
