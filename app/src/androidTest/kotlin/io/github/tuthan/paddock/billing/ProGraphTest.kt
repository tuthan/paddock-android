package io.github.tuthan.paddock.billing

import androidx.test.platform.app.InstrumentationRegistry
import io.github.tuthan.paddock.PaddockApp
import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.identity.TargetRef
import io.github.tuthan.paddock.identity.TerminalKey
import io.github.tuthan.paddock.ops.Begin
import io.github.tuthan.paddock.ops.OperationKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The gate through the app's real graph (the one `PaddockApp` built for this process), not a fake: what a tap on a Pro control gets back, and what
 * the gate sheet is asked for. The foss debug build is unlocked by default, so the locked half needs `-PpaddockUnlocked=false`; each build is held to
 * its own answer, so neither run skips them.
 */
class ProGraphTest {
    private val graph get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PaddockApp).graph

    @After fun clear() { graph.dismissGate(); graph.dismissGateNotice(); graph.manualInput.leave() }

    @Test fun anUnlockedBuildLetsEveryCapabilityRunAndNeverAsksForTheGate() {
        assumeTrue("the unlocked foss debug build", Distribution.UNLOCKED)
        for (c in ProCapabilities.ALL) {
            assertFalse("${c.id} is not locked", graph.locked(c))
            assertTrue("${c.id} runs", graph.requestCapability(c.id, pendingAnswerOnScreen = false))
        }
        assertNull(graph.gateRequest.value)
    }

    @Test fun aLockedBuildRefusesEveryGatedCapabilityAndAsksForTheGateOnlyWhenIdle() {
        assumeTrue("a build with -PpaddockUnlocked=false", !Distribution.UNLOCKED)
        // No "assume idle": a row an operation left Sent no longer holds the gate once it is older than ProGate.OPERATION_STUCK_AFTER_MILLIS, so the app is idle here
        // unless something started within the last two minutes is still running, which is a leak in another test and should fail this one loudly.
        assertEquals("what the gate sees as busy: ${graph.journal.records.value.filter { it.inFlight }}", GateContext.IDLE, graph.gateContext(false))
        for (c in ProCapabilities.ALL) {
            assertTrue("${c.id} is locked", graph.locked(c))
            assertFalse("${c.id} does not run", graph.requestCapability(c.id, pendingAnswerOnScreen = false))
            assertEquals("the sheet is asked for ${c.id}", c.id, graph.gateRequest.value)
            assertNull("an idle tap opens the sheet and says nothing else", graph.gateNotice.value)
            graph.dismissGate()
            assertNull(graph.gateRequest.value)
        }
    }

    @Test fun aLockedBuildDoesNotSpendABackgroundReadOnAWidgetThatDrawsNothing() {
        assumeTrue("a build with -PpaddockUnlocked=false", !Distribution.UNLOCKED)
        val outcome = kotlinx.coroutines.runBlocking { graph.refreshWidgetCache() }
        assertTrue(outcome, outcome.startsWith("skipped: widgets are Pro"))
    }

    @Test fun aProHoldersWidgetRefreshIsNeverSkippedForTheLock() {
        assumeTrue("a build with -PpaddockUnlocked=false that sells Pro (the play flavor)", !Distribution.UNLOCKED && Distribution.SELLS_PRO)
        withSavedEntitlement(EntitlementState(status = ProStatus.PRO, verifiedAtMillis = 1L, acknowledged = true)) {
            // The saved answer decides, even though this graph's view of Pro was loaded before the file changed.
            val outcome = kotlinx.coroutines.runBlocking { graph.refreshWidgetCache() }
            assertFalse(outcome, outcome.startsWith("skipped: widgets are Pro"))
        }
    }

    @Test fun aLeftoverProFileDoesNotSpareAFossBuildsWidgetRefresh() {
        // A play install of the same application id left PRO behind; the foss build cannot verify it, so it is not honoured (the widget and the refresh agree: PaddockWidgets.locked).
        assumeTrue("a locked foss build (-PpaddockUnlocked=false)", !Distribution.UNLOCKED && !Distribution.SELLS_PRO)
        withSavedEntitlement(EntitlementState(status = ProStatus.PRO, verifiedAtMillis = 1L, acknowledged = true)) {
            val outcome = kotlinx.coroutines.runBlocking { graph.refreshWidgetCache() }
            assertTrue(outcome, outcome.startsWith("skipped: widgets are Pro"))
        }
    }

    @Test fun aLeftoverProAnswerUnlocksNothingInABuildThatCannotVerifyIt() {
        assumeTrue("a locked foss build (-PpaddockUnlocked=false)", !Distribution.UNLOCKED && !Distribution.SELLS_PRO)
        val pro = ProView(state = EntitlementState(status = ProStatus.PRO, verifiedAtMillis = 1L, acknowledged = true))
        for (c in ProCapabilities.ALL) assertTrue("${c.id} stays locked", graph.locked(c, pro))
    }

    @Test fun theFlavorConstantIsWhatTheGraphsBillingReports() {
        // The widget process asks the constant and must not build a store; the app asks the store it built. They may never differ, in the fallback for a client that failed to build either.
        assertEquals(Distribution.SELLS_PRO, graph.entitlements.sellsPro)
    }

    @Test fun aBuildWithoutAStoreAsksNoPricesAndHasNone() {
        assumeTrue("a build that does not sell Pro (foss)", !Distribution.SELLS_PRO)
        graph.loadPrices()
        assertTrue(graph.pro.value.prices.isEmpty())
    }

    /** Runs [body] with `entitlement.json` holding [state] and puts back whatever was there. */
    private fun withSavedEntitlement(state: EntitlementState, body: () -> Unit) {
        val file = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, FileEntitlementStore.FILE_NAME)
        val before = file.takeIf { it.exists() }?.readBytes()
        try {
            kotlinx.coroutines.runBlocking { FileEntitlementStore(file).save(state) }
            body()
        } finally { if (before != null) file.writeBytes(before) else file.delete() }
    }

    @Test fun aPendingAnswerOnScreenDefersTheGateInsteadOfOpeningIt() {
        assumeTrue("a build with -PpaddockUnlocked=false", !Distribution.UNLOCKED)
        assertFalse(graph.requestCapability(ProCapabilities.GUARDED_ANSWERS.id, pendingAnswerOnScreen = true))
        assertNull("no sheet over a pending answer", graph.gateRequest.value)
        assertEquals(GateContext.PENDING_ANSWER, graph.gateContext(pendingAnswerOnScreen = true))
        assertEquals("the tap says why", "Pro is offered once the request on screen is answered.", graph.gateNotice.value)
    }

    @Test fun aTapDuringManualInputSaysWhyAndOpensNoSheet() {
        assumeTrue("a build with -PpaddockUnlocked=false", !Distribution.UNLOCKED)
        graph.manualInput.enter("term_gate_test", System.currentTimeMillis(), epoch = 1)
        assertFalse(graph.requestCapability(ProCapabilities.START_AGENT.id, pendingAnswerOnScreen = false))
        assertNull("no sheet during Manual input", graph.gateRequest.value)
        assertEquals("Pro is offered once Manual input is closed.", graph.gateNotice.value)
    }

    @Test fun aTapDuringAnOperationInFlightSaysWhyAndOpensNoSheetAndAfterItSettlesTheSheetOpens() {
        assumeTrue("a build with -PpaddockUnlocked=false", !Distribution.UNLOCKED)
        // A row of this test's own, settled in the finally: the journal is the app's real one, and a row left Requested would hold the gate for every later test for two minutes.
        val key = TerminalKey(TargetRef(HostProfileId("gate-test"), "gate-test", "term_gate_test"), epoch = 1)
        val row = (graph.journal.begin(key, OperationKind.Prompt, "x") as Begin.Started).record
        try {
            assertFalse(graph.requestCapability(ProCapabilities.MANAGE_SESSIONS.id, pendingAnswerOnScreen = false))
            assertNull("no sheet over a running operation", graph.gateRequest.value)
            assertEquals("Pro is offered once the operation in progress finishes.", graph.gateNotice.value)
        } finally { graph.journal.notSent(row.id, "test_cleanup") }
        // Once it is settled the same tap is idle: the sheet opens and the sentence is gone.
        assertFalse(graph.requestCapability(ProCapabilities.MANAGE_SESSIONS.id, pendingAnswerOnScreen = false))
        assertEquals(ProCapabilities.MANAGE_SESSIONS.id, graph.gateRequest.value)
        assertNull(graph.gateNotice.value)
    }

    @Test fun aDeferralNoticeGoesByItselfAfterItsWait() {
        assumeTrue("a build with -PpaddockUnlocked=false", !Distribution.UNLOCKED)
        graph.requestCapability(ProCapabilities.GUARDED_ANSWERS.id, pendingAnswerOnScreen = true)
        assertNotNull(graph.gateNotice.value)
        val deadline = System.currentTimeMillis() + GateNotice.SHOW_MILLIS + 3_000
        while (graph.gateNotice.value != null && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertNull("cleared after $GateNotice.SHOW_MILLIS ms", graph.gateNotice.value)
    }

    // ---- D5: Settings' "What Pro covers" row asks for the overview ------------------------------------------------------------------

    @Test fun theOverviewIsAskedForFromAnIdleAppAndSaysNothingElse() {
        // Either build: the graph asks for the sheet when idle; whether Pro is held is the host's to check when it draws (SettingsTest holds both).
        assertEquals("what the gate sees as busy: ${graph.journal.records.value.filter { it.inFlight }}", GateContext.IDLE, graph.gateContext(false))
        graph.openProOverview()
        assertEquals(ProCopy.OVERVIEW_ID, graph.gateRequest.value)
        assertNull(graph.gateNotice.value)
    }

    @Test fun theOverviewDuringManualInputSaysWhyAndOpensNoSheet() {
        graph.manualInput.enter("term_gate_test", System.currentTimeMillis(), epoch = 1)
        graph.openProOverview()
        assertNull("no sheet during Manual input", graph.gateRequest.value)
        assertEquals("Pro is offered once Manual input is closed.", graph.gateNotice.value)
    }

    @Test fun theOverviewDuringAnOperationInFlightSaysWhyAndOpensOnceItSettles() {
        val key = TerminalKey(TargetRef(HostProfileId("gate-test"), "gate-test", "term_gate_test"), epoch = 1)
        val row = (graph.journal.begin(key, OperationKind.Prompt, "x") as Begin.Started).record
        try {
            graph.openProOverview()
            assertNull("no sheet over a running operation", graph.gateRequest.value)
            assertEquals("Pro is offered once the operation in progress finishes.", graph.gateNotice.value)
        } finally { graph.journal.notSent(row.id, "test_cleanup") }
        graph.openProOverview()
        assertEquals(ProCopy.OVERVIEW_ID, graph.gateRequest.value)
        assertNull("the sheet replaces the sentence", graph.gateNotice.value)
    }

    @Test fun whatIsNotGatedAlwaysRunsInEitherBuild() {
        for (id in listOf("alerts", "snippets", "hosts.add", "input.manual")) assertTrue(id, graph.requestCapability(id, pendingAnswerOnScreen = true))
        assertNull(graph.gateRequest.value)
        assertNull("what is free is never deferred, so it never explains itself", graph.gateNotice.value)
    }
}
