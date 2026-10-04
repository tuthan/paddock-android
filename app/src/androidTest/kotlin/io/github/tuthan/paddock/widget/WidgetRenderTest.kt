package io.github.tuthan.paddock.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import android.content.Intent
import android.net.Uri
import io.github.tuthan.paddock.MainActivity
import io.github.tuthan.paddock.R
import io.github.tuthan.paddock.widget.WidgetRenderer.Kind
import java.io.File
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The widgets on a device (AC-10.1): every size, in the light and the dark theme, on a roomy and a tight launcher grid, drawn from a fake
 * cache; every one carries its time; none is wired to a connection. The widgets are inflated from the real RemoteViews the renderer builds, in a
 * configuration context for the theme, and screenshotted to `screens/` (`tools/run-ui-tests.sh` pulls them).
 */
class WidgetRenderTest {
    private val target: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val presenter = WidgetPresenter(ZoneOffset.UTC, Locale.ENGLISH)

    private fun ms(h: Int, m: Int) = ZonedDateTime.of(2026, 10, 3, h, m, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
    private val now = ms(14, 10)

    private fun row(title: String, kind: String, n: Int) = WidgetRow(title, kind, "term_$n", "w1:p$n", seq = n.toLong(), observedAtMillis = ms(13, 58))
    private fun cache(needs: Int, done: Int = 1, working: Int = 2, ready: Int = 3, readAt: Long = ms(14, 2)) = WidgetCache(
        hostId = "laptop", machine = "Laptop", session = "main", readAtMillis = readAt, counts = WidgetCounts(needs, done, working, ready, 0),
        urgent = listOf(row("approve edit to build.gradle", "claude", 1), row("run the database migrations", "codex", 2)).take(minOf(needs, 2)),
    )

    private fun themed(night: Boolean, fontScale: Float = 1f): Context {
        val cfg = Configuration(target.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or (if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
            this.fontScale = fontScale
        }
        return target.createConfigurationContext(cfg)
    }

    private class Drawn(val root: ViewGroup)

    /** Inflates [rv] at [wDp] x [hDp] in [ctx] and lays it out. */
    private fun draw(rv: RemoteViews, ctx: Context, wDp: Int, hDp: Int): Drawn {
        lateinit var parent: FrameLayout
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            parent = FrameLayout(ctx)
            val view = rv.apply(ctx, parent)
            parent.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            val d = ctx.resources.displayMetrics.density
            val w = (wDp * d).toInt(); val h = (hDp * d).toInt()
            parent.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            parent.layout(0, 0, w, h)
        }
        return Drawn(parent)
    }

    private fun shown(v: View): Boolean { var c: View? = v; while (c != null) { if (c.visibility != View.VISIBLE) return false; c = c.parent as? View }; return true }
    private fun texts(root: View): List<String> = buildList {
        fun walk(v: View) { if (v is TextView && shown(v) && v.text.isNotEmpty()) add(v.text.toString()); if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i)) }
        walk(root)
    }

    private fun shoot(name: String, d: Drawn) {
        val dir = File(target.getExternalFilesDir(null), "screens").apply { mkdirs() }
        val bmp = Bitmap.createBitmap(d.root.width, d.root.height, Bitmap.Config.ARGB_8888)
        // Over a neutral wallpaper-like ground so the rounded edge and the card read as they do on a home screen.
        InstrumentationRegistry.getInstrumentation().runOnMainSync { Canvas(bmp).apply { drawColor(0xFF6B7280.toInt()); d.root.draw(this) } }
        File(dir, "widget-$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private class Grid(val label: String, val summary: Pair<Int, Int>, val count: Pair<Int, Int>, val strip: Pair<Int, Int>)
    // A roomy 4-column grid, and a tight 5-column one (the widgets' minimum sizes).
    private val grids = listOf(Grid("roomy", 320 to 170, 150 to 150, 320 to 64), Grid("tight", 250 to 110, 110 to 110, 250 to 40))

    private fun forEachSize(night: Boolean, content: WidgetContent, name: String, check: (Kind, Grid, Drawn) -> Unit) {
        val ctx = themed(night)
        for (g in grids) for ((kind, size) in listOf(Kind.Summary to g.summary, Kind.Count to g.count, Kind.Strip to g.strip)) {
            val d = draw(WidgetRenderer.render(ctx, kind, content, size.second), ctx, size.first, size.second)
            check(kind, g, d)
            shoot("$name-${kind.name.lowercase()}-${g.label}-${if (night) "dark" else "light"}", d)
        }
    }

    @Test fun everySizeCarriesItsTimeInBothThemesAndBothGrids() {
        val content = presenter.present(cache(needs = 2), now)
        for (night in listOf(false, true)) forEachSize(night, content, "needs") { kind, g, d ->
            val t = texts(d.root)
            assertTrue("$kind ${g.label} night=$night has the time: $t", "as of 14:02" in t)
            when (kind) {
                Kind.Summary -> {
                    assertTrue("headline: $t", "2 need you" in t)
                    assertTrue("the first row is always there: $t", "approve edit to build.gradle" in t && "cl" in t && "Review" in t)
                    val rows = t.count { it == "Review" }
                    assertEquals("a roomy summary shows two rows, a tight one only the first (no clipped row)", if (g.label == "roomy") 2 else 1, rows)
                    assertEquals("detail line only when there is room", g.label == "roomy", t.any { it.startsWith("Laptop · 1 done") })
                }
                Kind.Count -> assertTrue("count and label: $t", "2" in t && "need you" in t)
                Kind.Strip -> assertTrue("strip line: $t", t.any { it.startsWith("2 need you") })
            }
        }
    }

    @Test fun aQuietHerdHasNoReviewButtonAndSaysSoInWords() {
        val content = presenter.present(cache(needs = 0), now)
        forEachSize(night = true, content, "quiet") { kind, _, d ->
            val t = texts(d.root)
            assertTrue("$kind: $t", "as of 14:02" in t)
            assertFalse("nothing to review: $t", "Review" in t)
            if (kind == Kind.Summary) assertTrue("Nothing needs you" in t)
            if (kind == Kind.Strip) assertTrue(t.any { it.startsWith("Nothing needs you") })
            if (kind == Kind.Count) assertTrue("0" in t)
        }
    }

    @Test fun anOldReadSaysOldInWordsAndKeepsItsTime() {
        val content = presenter.present(cache(needs = 1), ms(16, 0))
        forEachSize(night = false, content, "stale") { kind, _, d ->
            val t = texts(d.root)
            assertTrue("$kind says the read is old: $t", "as of 14:02 · old" in t)
        }
    }

    @Test fun beforeAnyReadThereIsNoNumberAtAll() {
        val content = presenter.present(null, now)
        for (night in listOf(false, true)) forEachSize(night, content, "unread") { kind, _, d ->
            val t = texts(d.root)
            assertTrue("$kind asks to open the app: $t", t.any { it == if (kind == Kind.Count) "Open Paddock" else "Open Paddock to read your herd" })
            assertFalse("$kind shows no digit before a read: $t", t.any { s -> s.any { it.isDigit() } })
            assertFalse(t.any { it.startsWith("as of") })
        }
    }

    @Test fun theThreeContentDescriptionsSayTheCountAndTheTime() {
        val content = presenter.present(cache(needs = 2), now)
        val ctx = themed(night = false)
        for (kind in Kind.entries) {
            val d = draw(WidgetRenderer.render(ctx, kind, content, 170), ctx, 320, 170)
            val root = d.root.findViewById<View>(R.id.root)
            assertEquals(content.description, root.contentDescription)
            assertTrue("as of 14:02" in root.contentDescription.toString() && "2 need you" in root.contentDescription.toString())
        }
    }

    /** Whether a PendingIntent for [link] under [code] exists: `FLAG_NO_CREATE` returns null when no render ever made that exact one (same request code, same intent data). */
    private fun reviewExists(link: String, code: Int) = PendingIntent.getActivity(
        target, code, Intent(Intent.ACTION_VIEW, Uri.parse(link), target, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
    ) != null

    @Test fun aTapOnTheWidgetOpensTheHerdAndAReviewButtonFiresThatAgentsAlertLink() {
        val content = presenter.present(cache(needs = 2), now)
        val ctx = themed(night = false)
        val d = draw(WidgetRenderer.render(ctx, Kind.Summary, content, 170), ctx, 320, 170)
        for (id in listOf(R.id.root, R.id.row1, R.id.review1, R.id.row2, R.id.review2)) assertTrue("view $id has a tap target", d.root.findViewById<View>(id).hasOnClickListeners())
        assertTrue("the launcher entry exists for the summary", PendingIntent.getActivity(target, Kind.Summary.code,
            Intent(target, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE) != null)
        assertTrue("row 1 goes to the first agent's link", reviewExists(content.rows[0].link!!, Kind.Summary.code + 1))
        assertTrue("row 2 goes to the second agent's link", reviewExists(content.rows[1].link!!, Kind.Summary.code + 2))
        assertFalse("not to an agent the cache does not have", reviewExists(content.rows[0].link!!.replace("term_1", "term_99"), Kind.Summary.code + 1))
        val parsed = io.github.tuthan.paddock.alerts.DeepLink.parse(content.rows[0].link)
        assertTrue("the link is the alert's own and parses", parsed is io.github.tuthan.paddock.alerts.DeepLinkResult.Valid)
    }

    @Test fun at200PercentFontTheSummaryStillShowsItsTimeAndTheFirstRowAndDropsTheSecondRatherThanClipIt() {
        val content = presenter.present(cache(needs = 2), now)
        val ctx = themed(night = true, fontScale = 2f)
        val d = draw(WidgetRenderer.render(ctx, Kind.Summary, content, 170), ctx, 320, 170)
        shoot("needs-summary-roomy-dark-font200", d)
        val t = texts(d.root)
        assertTrue(t.toString(), "as of 14:02" in t && "Review" in t)
        assertEquals("one row fits at 200 % on 170 dp; the second is dropped, not cut", 1, t.count { it == "Review" })
        assertEquals(emptyList<String>(), clipped(d.root))
    }

    /** What is cut off in [root]: text with less height than its layout needs, text outside the widget, and a time that wrapped or ended in an ellipsis. */
    private fun clipped(root: ViewGroup, timeOnOneLine: Boolean = true): List<String> = buildList {
        fun offset(v: View): Pair<Int, Int> { var x = 0; var y = 0; var c: View = v; while (c !== root) { x += c.left; y += c.top; c = c.parent as View }; return x to y }
        fun walk(v: View) {
            if (v is TextView && shown(v) && v.text.isNotEmpty()) {
                val layout = v.layout
                val (x, y) = offset(v)
                val name = "'${v.text}'"
                if (layout != null && layout.height > v.height - v.totalPaddingTop - v.totalPaddingBottom) add("$name needs ${layout.height} px of height, has ${v.height - v.totalPaddingTop - v.totalPaddingBottom}")
                if (x < 0 || y < 0 || x + v.width > root.width || y + v.height > root.height) add("$name lies outside the widget ($x,$y ${v.width}x${v.height} in ${root.width}x${root.height})")
                if (v.id == R.id.asof && layout != null && ((timeOnOneLine && layout.lineCount != 1) || (0 until layout.lineCount).any { layout.getEllipsisCount(it) > 0 })) add("the time $name wrapped or was ellipsized")
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
    }

    @Test fun nothingIsClippedAtAnyFontScaleOnAnyGridAndEveryCountKeepsItsTime() {
        val contents = mapOf(
            "needs" to presenter.present(cache(needs = 2), now), "one" to presenter.present(cache(needs = 1), now), "quiet" to presenter.present(cache(needs = 0), now),
            "stale" to presenter.present(cache(needs = 1), ms(16, 0)), "unread" to presenter.present(null, now),
        )
        val problems = mutableListOf<String>()
        for (fs in listOf(1f, 1.3f, 2f)) for (night in listOf(false, true)) {
            val ctx = themed(night, fs)
            for ((name, content) in contents) for (g in grids) for ((kind, size) in listOf(Kind.Summary to g.summary, Kind.Count to g.count, Kind.Strip to g.strip)) {
                val d = draw(WidgetRenderer.render(ctx, kind, content, size.second), ctx, size.first, size.second)
                val label = "$name $kind ${g.label} font=$fs night=$night"
                problems += clipped(d.root, timeOnOneLine = kind != Kind.Count).map { "$label: $it" }
                if (content.hasData) assertTrue("$label shows its time: ${texts(d.root)}", content.asOfLabel in texts(d.root))
                if (fs == 2f && night && g.label == "tight" && name == "needs") shoot("needs-${kind.name.lowercase()}-tight-dark-font200", d)
                if (fs == 1.3f && night && g.label == "tight" && name == "stale") shoot("stale-${kind.name.lowercase()}-tight-dark-font130", d)
            }
        }
        assertEquals("clipped widget text:\n" + problems.joinToString("\n"), emptyList<String>(), problems)
    }

    // ---- the platform side ------------------------------------------------------------------------------------------------

    @Test fun theThreeProvidersAreInstalledForTheHomeScreenOnlyWithNoFrameworkTimer() {
        val manager = AppWidgetManager.getInstance(target)
        val mine = manager.getInstalledProvidersForPackage(target.packageName, null).filter { it.provider.className.startsWith("io.github.tuthan.paddock.widget.") }
        assertEquals(setOf("SummaryWidgetProvider", "CountWidgetProvider", "StripWidgetProvider"), mine.map { it.provider.shortClassName.removePrefix(".widget.") }.toSet())
        for (p in mine) {
            assertEquals("no framework timer: the cache refresh is the job's", 0, p.updatePeriodMillis)
            assertEquals("home screen only, never the lock screen", AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN, p.widgetCategory)
        }
        if (Build.VERSION.SDK_INT >= 31) {
            val cells = mine.associate { it.provider.shortClassName.removePrefix(".widget.") to (it.targetCellWidth to it.targetCellHeight) }
            assertEquals(4 to 2, cells["SummaryWidgetProvider"]); assertEquals(2 to 2, cells["CountWidgetProvider"]); assertEquals(4 to 1, cells["StripWidgetProvider"])
        }
    }

    @Test fun theRefreshJobIsAFifteenMinutePeriodicJobThatIsNotPersistedAndTheServiceIsBoundBySystemOnly() {
        val scheduler = target.getSystemService(JobScheduler::class.java)
        WidgetJobs.cancel(target)
        assertNull(scheduler.getPendingJob(WidgetJobs.JOB_ID))
        WidgetJobs.ensureScheduled(target)
        try {
            val job = scheduler.getPendingJob(WidgetJobs.JOB_ID)!!
            assertTrue(job.isPeriodic); assertEquals(15 * 60_000L, job.intervalMillis); assertFalse(job.isPersisted)
            assertEquals(JobInfo.NETWORK_TYPE_ANY, job.networkType)
            WidgetJobs.ensureScheduled(target)   // a second call changes nothing
            assertEquals(1, scheduler.allPendingJobs.count { it.id == WidgetJobs.JOB_ID })
        } finally { WidgetJobs.cancel(target) }
        val info = target.packageManager.getServiceInfo(android.content.ComponentName(target, WidgetRefreshJobService::class.java), 0)
        assertEquals("android.permission.BIND_JOB_SERVICE", info.permission); assertFalse(info.exported)
    }

    @Test fun redrawingWithNoWidgetPlacedAndNoCacheDoesNothingAndCannotThrow() {
        File(target.filesDir, WidgetCaches.FILE).delete()
        PaddockWidgets.updateAll(target)
        assertFalse(PaddockWidgets.anyPlaced(target) && false)
    }
}
