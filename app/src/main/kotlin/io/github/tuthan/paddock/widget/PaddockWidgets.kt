package io.github.tuthan.paddock.widget

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import io.github.tuthan.paddock.PaddockApp
import io.github.tuthan.paddock.billing.Distribution
import io.github.tuthan.paddock.billing.EntitlementState
import io.github.tuthan.paddock.billing.Entitlements
import io.github.tuthan.paddock.billing.FileEntitlementStore
import io.github.tuthan.paddock.billing.ProCapabilities
import io.github.tuthan.paddock.billing.ProGate
import io.github.tuthan.paddock.widget.WidgetRenderer.Kind
import io.github.tuthan.paddock.widget.WidgetRenderer.render
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** The one place a widget's data comes from: the cache file the app writes. Rendering reads that and, to know whether to draw it at all, `entitlement.json` ([PaddockWidgets.locked]). */
object WidgetCaches {
    const val FILE = "widget-cache.json"
    fun store(context: Context): FileWidgetCacheStore = FileWidgetCacheStore(File(context.filesDir, FILE))
}

object PaddockWidgets {
    private val providers = listOf(SummaryWidgetProvider::class.java, CountWidgetProvider::class.java, StripWidgetProvider::class.java)

    private fun kindOf(provider: Class<*>) = when (provider) {
        SummaryWidgetProvider::class.java -> Kind.Summary
        CountWidgetProvider::class.java -> Kind.Count
        else -> Kind.Strip
    }

    /**
     * Widgets are a Pro capability (vault M8). A widget callback can run in a process that built nothing else, so this reads the saved
     * entitlement file itself (the same one the app writes) and never the graph; an unreadable file is "unknown", which does not hold Pro. The one
     * place that decides is [lockedBy], and the app's background refresh asks this too, so no second derivation can disagree with what is drawn.
     */
    internal fun locked(context: Context): Boolean =
        lockedBy(runBlocking(Dispatchers.IO) { FileEntitlementStore(File(context.filesDir, FileEntitlementStore.FILE_NAME)).load() })

    /** Whether [saved] leaves the widgets locked in this build: [Entitlements.hasPro] with the flavor's constants, so a foss build never honours a leftover PRO file. */
    internal fun lockedBy(saved: EntitlementState, unlockedBuild: Boolean = Distribution.UNLOCKED, sellsPro: Boolean = Distribution.SELLS_PRO): Boolean =
        ProGate.locked(ProCapabilities.WIDGETS.id, Entitlements.hasPro(saved, unlockedBuild, sellsPro))

    /**
     * Redraws every placed widget from the cache, or as locked. Cheap and local: safe to call after any cache write and from any process state.
     * With no widget on any home screen it returns before reading a file: the 15-minute job's cold process and every Pro change would otherwise
     * read the cache and the entitlement for nobody.
     */
    fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val placed = providers.associateWith { manager.getAppWidgetIds(ComponentName(context, it)) }.filterValues { it.isNotEmpty() }
        if (placed.isEmpty()) return
        val content = WidgetPresenter().present(WidgetCaches.store(context).load(), System.currentTimeMillis(), locked(context))
        for ((p, ids) in placed) for (id in ids) manager.updateAppWidget(id, render(context, kindOf(p), content, heightDp(manager.getAppWidgetOptions(id))))
    }

    /** True while any Paddock widget is on a home screen. */
    fun anyPlaced(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        return providers.any { manager.getAppWidgetIds(ComponentName(context, it)).isNotEmpty() }
    }

    /** The widget's current height: the portrait height the host reports (its maximum), else the smallest one it will ever be. */
    internal fun heightDp(options: Bundle): Int =
        options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0).takeIf { it > 0 } ?: options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
}

abstract class PaddockWidgetProvider : AppWidgetProvider() {
    /** Every redraw, whoever asked for it, reads the cache and draws it. The ids in an intent are never used: the provider asks the manager for its own. */
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        PaddockWidgets.updateAll(context)
        WidgetJobs.ensureScheduled(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        PaddockWidgets.updateAll(context)
    }

    override fun onEnabled(context: Context) { WidgetJobs.ensureScheduled(context) }

    /** The last widget of this kind went away; the job stops only when none of the three kinds is left. */
    override fun onDisabled(context: Context) { if (!PaddockWidgets.anyPlaced(context)) WidgetJobs.cancel(context) }
}

class SummaryWidgetProvider : PaddockWidgetProvider()
class CountWidgetProvider : PaddockWidgetProvider()
class StripWidgetProvider : PaddockWidgetProvider()

/**
 * The cache refresh, as a platform periodic job (no WorkManager: Socket has no result for it, docs/dependency-reviews.md). Fifteen minutes is
 * JobScheduler's own floor. Not persisted across a reboot (that needs a boot receiver and its permission); the widgets' own `onUpdate`, which the system
 * sends after a boot, schedules it again.
 */
object WidgetJobs {
    const val JOB_ID = 0x50_57
    const val PERIOD_MILLIS = 15 * 60_000L

    fun ensureScheduled(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        if (scheduler.getPendingJob(JOB_ID) != null) return
        scheduler.schedule(
            JobInfo.Builder(JOB_ID, ComponentName(context, WidgetRefreshJobService::class.java))
                .setPeriodic(PERIOD_MILLIS).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(false).build(),
        )
    }

    fun cancel(context: Context) { context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID) }
}

class WidgetRefreshJobService : JobService() {
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val graph = (application as PaddockApp).graph
        work = graph.scope.launch {
            val outcome = try { graph.refreshWidgetCache() } catch (e: Exception) { null }
            android.util.Log.i("Widgets", "refresh: ${outcome ?: "failed"}")
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean { work?.cancel(); return false }
}
