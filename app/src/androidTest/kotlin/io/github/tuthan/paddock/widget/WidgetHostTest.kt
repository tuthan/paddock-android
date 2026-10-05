package io.github.tuthan.paddock.widget

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The platform path, not only the renderer: each provider is bound to a real `AppWidgetHost` (the shell's identity grants the bind permission a
 * launcher holds), the system sends it `onUpdate`, and the view the host holds is drawn from the cache file the app wrote. Nothing here calls the
 * renderer directly, so the manifest entries, the provider metadata, `onUpdate` and the cache read are all in the path.
 */
class WidgetHostTest {
    private val target: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun texts(root: View): List<String> = buildList {
        fun walk(v: View) { if (v is TextView && v.visibility == View.VISIBLE && v.text.isNotEmpty()) add(v.text.toString()); if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i)) }
        walk(root)
    }

    @Test fun eachProviderBoundToAHostIsDrawnFromTheCacheByTheSystemsOwnUpdate() {
        // Before API 29 a test has no way to hold the bind permission (no shell identity to adopt, no `cmd appwidget`): API 26 is covered by WidgetRenderTest alone.
        assumeTrue("needs UiAutomation.adoptShellPermissionIdentity(String...), API 29+", android.os.Build.VERSION.SDK_INT >= 29)
        // A build that cannot hold Pro (the locked foss build, which never honours a saved PRO) draws no widget content at all; WidgetLockTest holds that path.
        assumeTrue("a build whose widgets can be unlocked: the unlocked foss build, or a build that sells Pro", io.github.tuthan.paddock.billing.Distribution.UNLOCKED || io.github.tuthan.paddock.billing.Distribution.SELLS_PRO)
        val inst = InstrumentationRegistry.getInstrumentation()
        val ui = inst.uiAutomation
        ui.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
        val manager = AppWidgetManager.getInstance(target)
        val host = AppWidgetHost(target, HOST_ID)
        val cacheFile = File(target.filesDir, WidgetCaches.FILE)
        val before = cacheFile.takeIf { it.exists() }?.readBytes()
        // Widgets are a Pro capability: on a build that sells Pro this test holds Pro for its duration (WidgetLockTest covers the locked path).
        val entitlementFile = File(target.filesDir, io.github.tuthan.paddock.billing.FileEntitlementStore.FILE_NAME)
        val entitlementBefore = entitlementFile.takeIf { it.exists() }?.readBytes()
        kotlinx.coroutines.runBlocking {
            io.github.tuthan.paddock.billing.FileEntitlementStore(entitlementFile).save(
                io.github.tuthan.paddock.billing.EntitlementState(status = io.github.tuthan.paddock.billing.ProStatus.PRO, verifiedAtMillis = 1L, acknowledged = true),
            )
        }
        val ids = mutableListOf<Int>()
        try {
            val readAt = System.currentTimeMillis() - 2 * 60_000L
            WidgetCaches.store(target).save(WidgetCache(
                hostId = "laptop", machine = "Laptop", session = "main", readAtMillis = readAt, counts = WidgetCounts(2, 1, 2, 3, 0),
                urgent = listOf(
                    WidgetRow("approve edit to build.gradle", "claude", "term_1", "w1:p1", 1, readAt),
                    WidgetRow("run the database migrations", "codex", "term_2", "w1:p2", 2, readAt),
                ),
            ))
            val expectedTime = WidgetPresenter().present(WidgetCaches.store(target).load(), System.currentTimeMillis()).asOf!!
            val providers = manager.getInstalledProvidersForPackage(target.packageName, null).filter { it.provider.className.startsWith("io.github.tuthan.paddock.widget.") }
            assertEquals(3, providers.size)
            host.startListening()
            for (info in providers) {
                val id = host.allocateAppWidgetId(); ids += id
                assertTrue("the system lets a host with the bind permission bind ${info.provider.shortClassName}", manager.bindAppWidgetIdIfAllowed(id, info.provider))
                lateinit var view: AppWidgetHostView
                inst.runOnMainSync { view = host.createView(target, id, info) }
                val deadline = System.currentTimeMillis() + 15_000
                var drawn = emptyList<String>()
                while (System.currentTimeMillis() < deadline) {
                    inst.runOnMainSync { drawn = texts(view) }
                    if (expectedTime in drawn) break
                    Thread.sleep(250)
                }
                val name = info.provider.shortClassName.removePrefix(".widget.")
                assertTrue("$name draws the cache's time '$expectedTime': $drawn", expectedTime in drawn)
                assertTrue("$name draws a count of two: $drawn", drawn.any { it == "2" || it.startsWith("2 need you") })
                if (name == "SummaryWidgetProvider") assertTrue("summary has its first row and a Review button: $drawn", "approve edit to build.gradle" in drawn && "Review" in drawn)
                shoot(name, view, info)
            }
        } finally {
            ids.forEach { host.deleteAppWidgetId(it) }
            host.stopListening()
            if (before != null) cacheFile.writeBytes(before) else cacheFile.delete()
            if (entitlementBefore != null) entitlementFile.writeBytes(entitlementBefore) else entitlementFile.delete()
            ui.dropShellPermissionIdentity()
        }
    }

    private fun shoot(name: String, view: AppWidgetHostView, info: android.appwidget.AppWidgetProviderInfo) {
        val d = target.resources.displayMetrics.density
        val w = (maxOf(info.minWidth, 110) * d).toInt(); val h = (maxOf(info.minHeight, 40) * d).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, w, h)
            Canvas(bmp).apply { drawColor(0xFF6B7280.toInt()); view.draw(this) }
        }
        val dir = File(target.getExternalFilesDir(null), "screens").apply { mkdirs() }
        File(dir, "widget-host-${name.removeSuffix("Provider").lowercase()}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object { const val HOST_ID = 0x7a11 }
}
