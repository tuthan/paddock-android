package io.github.tuthan.paddock.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import io.github.tuthan.paddock.MainActivity
import io.github.tuthan.paddock.R

/**
 * Draws [WidgetContent] into [RemoteViews]. This is the whole widget: no socket, no coroutine, no read of anything but the content it is
 * given. Tapping the widget opens the herd; a Review button, and the row it sits in, opens the same link a notification's Review does
 * (`paddock://open?...`), which the app resolves against a fresh read before it says anything about the agent. Every PendingIntent is
 * explicit (this app's activity), immutable, and carries nothing the user typed.
 *
 * Colours that change with the theme are never set from code: each tone is its own view in the layout with its colour from a resource,
 * and the renderer shows one of them, so the host's own dark or light configuration picks the palette when it draws.
 */
object WidgetRenderer {
    enum class Kind(val layout: Int, val code: Int) { Summary(R.layout.widget_summary, 10), Count(R.layout.widget_count, 20), Strip(R.layout.widget_strip, 30) }

    /**
     * A widget cannot grow with the user's font size: the launcher's grid gives it a fixed cell. So every text in it is sized in dp, at the user's
     * font scale up to [FONT_CAP] times (never smaller than 1), and the layout draws less (no second row, no detail line, the count and its label on
     * one line) before any text would be cut. Dp, not sp, so the result is the same on the platform's linear and non-linear font scaling.
     */
    const val FONT_CAP = 1.3f

    /** The scale the widgets lay out at: the system's, held between 1 and [FONT_CAP]. */
    fun scale(context: Context): Float = context.resources.configuration.fontScale.coerceIn(1f, FONT_CAP)

    /**
     * Heights, in dp, at which the summary has room for one row, a detail line, and a second row at scale [fs]: the header and the detail line
     * grow with the font, a row (a 48 dp touch target) does not. Below them it draws less, never clipped text. 94, 120 and 164 at scale 1.
     */
    fun firstRowFromDp(fs: Float) = (72 + 22 * fs).toInt()
    fun detailFromDp(fs: Float) = (82 + 38 * fs).toInt()
    fun secondRowFromDp(fs: Float) = (126 + 38 * fs).toInt()

    /** The header's second line: an old read's time ("as of 14:02 · old") is long enough to push the headline onto two lines once the font is bigger than the default. */
    fun headerExtraDp(content: WidgetContent, fs: Float) = if (content.stale && fs > 1.15f) (18 * fs * 1.3f).toInt() else 0

    /** The 2 x 2 puts the number and its label on one line when the number over the label would not fit: a big font on a small cell. */
    fun compactCount(heightDp: Int, fs: Float) = heightDp in 1 until 130 && fs > 1.15f

    private fun size(rv: RemoteViews, id: Int, baseDp: Float, fs: Float) = rv.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_DIP, baseDp * fs)

    fun render(context: Context, kind: Kind, content: WidgetContent, heightDp: Int = 0): RemoteViews {
        val rv = RemoteViews(context.packageName, kind.layout)
        val fs = scale(context)
        rv.setContentDescription(R.id.root, content.description)
        rv.setOnClickPendingIntent(R.id.root, openHerd(context, kind.code))
        when (kind) {
            Kind.Summary -> summary(context, rv, content, heightDp, fs)
            Kind.Count -> count(context, rv, content, heightDp, fs)
            Kind.Strip -> strip(rv, content, fs)
        }
        return rv
    }

    private fun tone(content: WidgetContent) = when { content.stale || !content.hasData -> "old"; content.tone == WidgetTone.NeedsYou -> "needs"; else -> "quiet" }

    /** Shows the one tone's view of [base] with [text] and hides the others. */
    private fun toned(rv: RemoteViews, content: WidgetContent, text: String, ids: Map<String, Int>) {
        val shown = if (content.hasData) tone(content) else "quiet"
        for ((t, id) in ids) { rv.setViewVisibility(id, if (t == shown) View.VISIBLE else View.GONE); rv.setTextViewText(id, text) }
    }

    private fun summary(context: Context, rv: RemoteViews, content: WidgetContent, heightDp: Int, fs: Float) {
        toned(rv, content, content.headline, mapOf("needs" to R.id.headline_needs, "quiet" to R.id.headline_quiet, "old" to R.id.headline_old))
        for (id in listOf(R.id.headline_needs, R.id.headline_quiet, R.id.headline_old)) size(rv, id, 18f, fs)
        size(rv, R.id.asof, 12f, fs); size(rv, R.id.detail, 12f, fs)
        for (id in listOf(R.id.code1, R.id.code2)) size(rv, id, 12f, fs)
        for (id in listOf(R.id.title1, R.id.title2)) size(rv, id, 14f, fs)
        for (id in listOf(R.id.review1, R.id.review2)) size(rv, id, 13f, fs)
        rv.setViewVisibility(R.id.asof, if (content.asOfLabel != null) View.VISIBLE else View.GONE)
        content.asOfLabel?.let { rv.setTextViewText(R.id.asof, it) }
        val room = heightDp - headerExtraDp(content, fs)
        val showDetail = content.hasData && (heightDp == 0 || room >= detailFromDp(fs))
        rv.setViewVisibility(R.id.detail, if (showDetail) View.VISIBLE else View.GONE)
        if (showDetail) rv.setTextViewText(R.id.detail, listOf(content.machine, content.detail).filter { it.isNotEmpty() }.joinToString(" · "))
        val rows = when { heightDp == 0 || room >= secondRowFromDp(fs) -> 2; room >= firstRowFromDp(fs) -> 1; else -> 0 }
        val shown = content.rows.take(rows)
        val slots = listOf(Triple(R.id.row1, R.id.code1, Pair(R.id.title1, R.id.review1)), Triple(R.id.row2, R.id.code2, Pair(R.id.title2, R.id.review2)))
        slots.forEachIndexed { i, (row, code, ids) ->
            val r = shown.getOrNull(i)
            rv.setViewVisibility(row, if (r != null) View.VISIBLE else View.GONE)
            if (r == null) return@forEachIndexed
            rv.setTextViewText(code, r.code); rv.setTextViewText(ids.first, r.title)
            rv.setContentDescription(row, r.description); rv.setContentDescription(ids.second, r.description)
            val link = r.link
            if (link != null) {
                val pi = review(context, Kind.Summary.code + 1 + i, link)
                rv.setOnClickPendingIntent(row, pi); rv.setOnClickPendingIntent(ids.second, pi)
            }
        }
    }

    private fun count(context: Context, rv: RemoteViews, content: WidgetContent, heightDp: Int, fs: Float) {
        val compact = content.hasData && compactCount(heightDp, fs)
        rv.setViewVisibility(R.id.count_frame, if (content.hasData) View.VISIBLE else View.GONE)
        if (content.hasData) {
            val ids = mapOf("needs" to R.id.count_needs, "quiet" to R.id.count_quiet, "old" to R.id.count_old)
            toned(rv, content, if (compact) "${content.count} ${content.countLabel}" else content.count.orEmpty(), ids)
            for (id in ids.values) size(rv, id, if (compact) 15f else 34f, fs)
        }
        rv.setViewVisibility(R.id.label, if (compact) View.GONE else View.VISIBLE)
        // Two by two is too small for the whole sentence; the description (what TalkBack reads) keeps it.
        rv.setTextViewText(R.id.label, if (content.hasData || content.countLabel.isNotEmpty()) content.countLabel else context.getString(R.string.widget_unread_short))
        size(rv, R.id.label, 14f, fs); size(rv, R.id.asof, 12f, fs)
        rv.setViewVisibility(R.id.asof, if (content.asOfLabel != null) View.VISIBLE else View.GONE)
        content.asOfLabel?.let { rv.setTextViewText(R.id.asof, it) }
    }

    /** One line and the time. The detail counts are not on it: a line that ends in "1 ..." says less than the headline alone, and TalkBack reads them from the description. */
    private fun strip(rv: RemoteViews, content: WidgetContent, fs: Float) {
        toned(rv, content, content.headline, mapOf("needs" to R.id.line_needs, "quiet" to R.id.line_quiet, "old" to R.id.line_old))
        for (id in listOf(R.id.line_needs, R.id.line_quiet, R.id.line_old)) size(rv, id, 15f, fs)
        size(rv, R.id.asof, 12f, fs)
        rv.setViewVisibility(R.id.asof, if (content.asOfLabel != null) View.VISIBLE else View.GONE)
        content.asOfLabel?.let { rv.setTextViewText(R.id.asof, it) }
    }

    /** The launcher entry, as the launcher itself would send it. */
    fun openHerd(context: Context, requestCode: Int): PendingIntent = PendingIntent.getActivity(
        context, requestCode,
        Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** The alert link for one agent: explicit, so it needs no intent filter beyond the one that admits only `paddock://open`. */
    fun review(context: Context, requestCode: Int, link: String): PendingIntent = PendingIntent.getActivity(
        context, requestCode, Intent(Intent.ACTION_VIEW, Uri.parse(link), context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
