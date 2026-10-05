package io.github.tuthan.paddock.billing

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The play flavor's constant, and what happens when the library's client cannot be built (review F2, F5). The real `PlayBilling` needs a phone with the
 * library; its `sellsPro` is held to the constant by `ProGraphTest` in androidTest.
 */
class DistributionTest {
    private val state = EntitlementState(status = ProStatus.PRO, verifiedAtMillis = 1L, acknowledged = true)

    @Test fun thePlayFlavorSellsPro() {
        assertTrue(Distribution.SELLS_PRO)
    }

    @Test fun aClientThatBuildsIsUsedAsIs() {
        assertSame(NoBilling, Distribution.billingOrUnavailable { NoBilling })
    }

    @Test fun aClientThatCannotBeBuiltFallsBackToAStoreThatSaysItIsUnavailableAndStillSellsPro() = runBlocking<Unit> {
        for (thrown in listOf<Throwable>(IllegalStateException("closed"), IllegalArgumentException("context"), NoClassDefFoundError("com/android/billingclient/api/Foo"))) {
            val billing = Distribution.billingOrUnavailable { throw thrown }
            assertEquals("the fallback must report what the flavor does, or the widget process and the app would disagree about Pro", Distribution.SELLS_PRO, billing.sellsPro)
            val gone = StoreResult.Unavailable(UnavailableKind.STORE_UNAVAILABLE)
            assertEquals(gone, billing.purchases()); assertEquals(gone, billing.products()); assertEquals(gone, billing.purchase(Products.PRO))
            assertEquals(gone, billing.acknowledge("t")); assertEquals(gone, billing.consume("t"))
            assertEquals(emptyList<Unit>(), billing.changes.toList())
        }
    }

    @Test fun aSavedPurchaseStaysHonouredWhenTheClientCannotBeBuiltAndTheStoreCannotBeAsked() = runBlocking<Unit> {
        val entitlements = Entitlements(Distribution.billingOrUnavailable { throw IllegalStateException("closed") }, InMemoryEntitlementStore(state), { 2L }, Distribution.UNLOCKED)
        assertTrue(entitlements.hasPro(entitlements.saved()))
        val v = entitlements.verify()
        assertEquals("a store that cannot be asked changes nothing", state, v.state)
        assertEquals(false, v.reachable)
    }

    @Test fun anOutOfMemoryIsNotMistakenForAMissingLibrary() {
        assertThrows(OutOfMemoryError::class.java) { Distribution.billingOrUnavailable { throw OutOfMemoryError() } }
    }
}
