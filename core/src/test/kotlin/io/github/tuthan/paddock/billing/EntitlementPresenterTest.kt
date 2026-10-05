package io.github.tuthan.paddock.billing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class EntitlementPresenterTest {
    @Test
    fun theFreeVersionSaysWhereProComesFromAndOffersNothingToBuy() {
        val s = EntitlementPresenter.summary(EntitlementState(), 0, unlockedBuild = false, sellsPro = false)
        assertEquals("Free version", s.headline)
        assertTrue("Google Play build" in s.detail && "source code" in s.detail, s.detail)
        assertFalse(s.stale)
    }

    @Test
    fun aSourceBuildWithTheSwitchOnSaysEverythingIsUnlocked() {
        assertEquals("Everything is unlocked", EntitlementPresenter.summary(EntitlementState(), 0, unlockedBuild = true, sellsPro = false).headline)
    }

    @Test
    fun theFreeVersionHasNoProAndNoStoreToAsk() = runBlocking<Unit> {
        val e = Entitlements(NoBilling, InMemoryEntitlementStore(), { 5L }, unlockedBuild = false)
        assertFalse(e.hasPro(e.saved()))
        assertFalse(e.sellsPro)
        assertFalse(e.verify().reachable)
        // And with the source-build switch on, every capability is on without a purchase.
        assertTrue(Entitlements(NoBilling, InMemoryEntitlementStore(), { 5L }, unlockedBuild = true).let { it.hasPro(it.saved()) })
    }

    @Test
    fun gatedCapabilitiesAreLockedInTheFreeVersionAndOpenWhenBuiltUnlocked() {
        val gated = setOf("operations.stop")
        assertEquals(GateDecision.SHOW_GATE, ProGate.decide("operations.stop", hasPro = false, context = GateContext.IDLE, gated = gated))
        assertEquals(GateDecision.PROCEED, ProGate.decide("operations.stop", hasPro = true, context = GateContext.IDLE, gated = gated))
    }
}
