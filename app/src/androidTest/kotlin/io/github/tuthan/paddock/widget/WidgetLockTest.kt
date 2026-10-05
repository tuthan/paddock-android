package io.github.tuthan.paddock.widget

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tuthan.paddock.billing.Distribution
import io.github.tuthan.paddock.billing.EntitlementState
import io.github.tuthan.paddock.billing.FileEntitlementStore
import io.github.tuthan.paddock.billing.ProStatus
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Widgets are a Pro capability (vault M8). What decides it is the entitlement file the app saved, read by the widget callback itself: these tests
 * write that file and watch the platform draw. The foss debug build is unlocked by default, so the locked half needs `-PpaddockUnlocked=false`
 * and is skipped otherwise (the default flows keep every capability on).
 */
class WidgetLockTest {
    private val target: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val entitlement get() = File(target.filesDir, FileEntitlementStore.FILE_NAME)
    private val aside get() = File(target.filesDir, FileEntitlementStore.FILE_NAME + ".corrupt")

    private fun texts(root: View): List<String> = buildList {
        fun walk(v: View) { if (v is TextView && v.visibility == View.VISIBLE && v.text.isNotEmpty()) add(v.text.toString()); if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i)) }
        walk(root)
    }

    /** Runs [body] with the entitlement file as [state] (absent when null) and puts back whatever was there. */
    private fun withEntitlement(state: EntitlementState?, raw: String? = null, body: () -> Unit) {
        val before = entitlement.takeIf { it.exists() }?.readBytes()
        val corruptBefore = aside.takeIf { it.exists() }?.readBytes()
        try {
            entitlement.delete()
            when {
                raw != null -> entitlement.writeText(raw)
                state != null -> runBlocking { FileEntitlementStore(entitlement).save(state) }
            }
            body()
        } finally {
            if (before != null) entitlement.writeBytes(before) else entitlement.delete()
            if (corruptBefore != null) aside.writeBytes(corruptBefore) else aside.delete()
        }
    }

    @Test fun onALockedBuildThatSellsProOnlyAProEntitlementOpensTheWidgets() {
        assumeFalse("an unlocked build never locks widgets", Distribution.UNLOCKED)
        assumeTrue("a build that sells Pro (the play flavor)", Distribution.SELLS_PRO)
        withEntitlement(null) { assertTrue("no entitlement file is Free", PaddockWidgets.locked(target)) }
        for (status in listOf(ProStatus.UNKNOWN, ProStatus.FREE, ProStatus.PENDING, ProStatus.REVOKED)) {
            withEntitlement(EntitlementState(status = status, verifiedAtMillis = 1L)) { assertTrue("$status is locked", PaddockWidgets.locked(target)) }
        }
        withEntitlement(EntitlementState(status = ProStatus.PRO, verifiedAtMillis = 1L, acknowledged = true)) { assertFalse(PaddockWidgets.locked(target)) }
        withEntitlement(EntitlementState(status = ProStatus.PRO, verifiedAtMillis = 1L, acknowledged = false)) { assertFalse("bought and not yet acknowledged still holds Pro", PaddockWidgets.locked(target)) }
        withEntitlement(null, raw = "{not json") { assertTrue("an unreadable file is unknown, which is locked", PaddockWidgets.locked(target)) }
    }

    @Test fun aLeftoverProFileDoesNotUnlockTheWidgetsOfABuildThatCannotVerifyIt() {
        // Review F5: a play install of the same application id left PRO in the file; the foss build never asks the store, so it would never learn of a refund.
        assumeFalse("an unlocked build never locks widgets", Distribution.UNLOCKED)
        assumeFalse("a build that sells Pro verifies its own file", Distribution.SELLS_PRO)
        for (acknowledged in listOf(true, false)) {
            withEntitlement(EntitlementState(status = ProStatus.PRO, verifiedAtMillis = 1L, acknowledged = acknowledged)) { assertTrue("PRO (acknowledged=$acknowledged) is not honoured", PaddockWidgets.locked(target)) }
        }
    }

    @Test fun redrawingWithNoWidgetPlacedReadsNoFile() {
        // Review F14: the 15-minute job's cold process and every Pro change redrew with nothing on a home screen. An unreadable entitlement file is moved aside by the
        // read that finds it, so a file that is still where it was proves nothing read it.
        assumeFalse("a widget is on this device's home screen", PaddockWidgets.anyPlaced(target))
        withEntitlement(null, raw = "{not json") {
            PaddockWidgets.updateAll(target)
            assertEquals("{not json", entitlement.readText())
            assertFalse("the file was read and set aside", aside.exists())
        }
    }

    @Test fun anUnlockedBuildNeverLocksWhateverTheFileSays() {
        assumeTrue("needs the unlocked foss debug build", Distribution.UNLOCKED)
        withEntitlement(EntitlementState(status = ProStatus.FREE, verifiedAtMillis = 1L)) { assertFalse(PaddockWidgets.locked(target)) }
        withEntitlement(null) { assertFalse(PaddockWidgets.locked(target)) }
    }

    @Test fun aLockedWidgetBoundToAHostSaysProAndDrawsNoCountUntilProArrivesInABuildThatSellsIt() {
        assumeFalse("an unlocked build never locks widgets", Distribution.UNLOCKED)
        assumeTrue("a build that sells Pro (the play flavor); a build that does not is held by aLeftoverProFileDoesNotUnlockTheWidgetsOfABuildThatCannotVerifyIt", Distribution.SELLS_PRO)
        assumeTrue("needs UiAutomation.adoptShellPermissionIdentity(String...), API 29+", android.os.Build.VERSION.SDK_INT >= 29)
        val inst = InstrumentationRegistry.getInstrumentation()
        val ui = inst.uiAutomation
        ui.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
        val manager = AppWidgetManager.getInstance(target)
        val host = AppWidgetHost(target, HOST_ID)
        val cacheFile = File(target.filesDir, WidgetCaches.FILE)
        val cacheBefore = cacheFile.takeIf { it.exists() }?.readBytes()
        val ids = mutableListOf<Int>()
        try {
            val readAt = System.currentTimeMillis() - 2 * 60_000L
            WidgetCaches.store(target).save(WidgetCache(
                hostId = "laptop", machine = "Laptop", session = "main", readAtMillis = readAt, counts = WidgetCounts(2, 1, 2, 3, 0),
                urgent = listOf(WidgetRow("approve edit to build.gradle", "claude", "term_1", "w1:p1", 1, readAt)),
            ))
            val asOf = WidgetPresenter().present(WidgetCaches.store(target).load(), System.currentTimeMillis()).asOf!!
            val providers = manager.getInstalledProvidersForPackage(target.packageName, null).filter { it.provider.className.startsWith("io.github.tuthan.paddock.widget.") }
            assertEquals(3, providers.size)
            withEntitlement(EntitlementState(status = ProStatus.FREE, verifiedAtMillis = 1L)) {
                host.startListening()
                val views = providers.map { info ->
                    val id = host.allocateAppWidgetId(); ids += id
                    assertTrue("bind ${info.provider.shortClassName}", manager.bindAppWidgetIdIfAllowed(id, info.provider))
                    lateinit var view: AppWidgetHostView
                    inst.runOnMainSync { view = host.createView(target, id, info) }
                    info.provider.shortClassName.removePrefix(".widget.") to view
                }
                for ((name, view) in views) {
                    val drawn = awaitDrawn(view) { "Pro" in it || "Widgets are Pro" in it }
                    assertTrue("$name says Pro: $drawn", "Widgets are Pro" in drawn || "Pro" in drawn)
                    assertTrue("$name draws no count: $drawn", drawn.none { it == "2" || it.startsWith("2 need") || it == "2 need you" })
                    assertTrue("$name draws no time and no row: $drawn", asOf !in drawn && "approve edit to build.gradle" !in drawn && "Laptop" !in drawn)
                }
                // Pro arrives (a purchase, a restore): the file changes and the app redraws every placed widget.
                runBlocking { FileEntitlementStore(entitlement).save(EntitlementState(status = ProStatus.PRO, verifiedAtMillis = 2L, acknowledged = true)) }
                PaddockWidgets.updateAll(target)
                for ((name, view) in views) {
                    val drawn = awaitDrawn(view) { asOf in it }
                    assertTrue("$name draws the cache's time once Pro is held: $drawn", asOf in drawn)
                    assertTrue("$name draws a count of two: $drawn", drawn.any { it == "2" || it.startsWith("2 need") })
                }
            }
        } finally {
            ids.forEach { host.deleteAppWidgetId(it) }
            host.stopListening()
            if (cacheBefore != null) cacheFile.writeBytes(cacheBefore) else cacheFile.delete()
            ui.dropShellPermissionIdentity()
        }
    }

    private fun awaitDrawn(view: AppWidgetHostView, done: (List<String>) -> Boolean): List<String> {
        val inst = InstrumentationRegistry.getInstrumentation()
        val deadline = System.currentTimeMillis() + 15_000
        var drawn = emptyList<String>()
        while (System.currentTimeMillis() < deadline) {
            inst.runOnMainSync { drawn = texts(view) }
            if (done(drawn)) break
            Thread.sleep(250)
        }
        return drawn
    }

    private companion object { const val HOST_ID = 0x7a12 }
}
