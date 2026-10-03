package io.github.tuthan.paddock.notify

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import io.github.tuthan.paddock.MainActivity
import io.github.tuthan.paddock.R
import io.github.tuthan.paddock.alerts.AlertAction
import io.github.tuthan.paddock.alerts.AlertNotifier
import io.github.tuthan.paddock.alerts.NotificationContent

/**
 * Draws [NotificationContent] with the framework's own builder (no support library). Every tap target, the notification
 * itself and each action, is the same immutable, explicit VIEW intent for the alert's link into this app: nothing here can send input,
 * and the only actions are Open and Review. With the lock-screen redaction on, the notification is private and its public
 * version holds the generic words, so what the lock screen shows never includes the agent's title.
 */
class AndroidAlertNotifier(private val context: Context) : AlertNotifier {
    private val nm: NotificationManager = context.getSystemService(NotificationManager::class.java)

    override fun show(content: NotificationContent) {
        Channels.ensure(context)
        val base = (content.id and 0x3fffffff) * 2
        val open = pending(base, content.link)
        val review = pending(base + 1, content.link)
        fun builder(title: String, text: String) = Notification.Builder(context, content.channel.id)
            .setSmallIcon(R.drawable.ic_stat_paddock)
            .setContentTitle(title).setContentText(text)
            .setWhen(content.whenMillis).setShowWhen(true)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setContentIntent(open)
        val public = builder(content.publicTitle, content.publicText).setVisibility(Notification.VISIBILITY_PUBLIC).build()
        val notification = builder(content.title, content.text).apply {
            if (content.hideOnLockScreen) { setVisibility(Notification.VISIBILITY_PRIVATE); setPublicVersion(public) }
            else setVisibility(Notification.VISIBILITY_PUBLIC)
            content.group?.let { setGroup(it) }
            val icon = Icon.createWithResource(context, R.drawable.ic_stat_paddock)
            for (action in content.actions) addAction(Notification.Action.Builder(icon, action.label, if (action == AlertAction.Open) open else review).build())
        }.build()
        nm.notify(TAG, content.id, notification)
        content.group?.let { group ->
            // A stack needs its summary to show as one per machine; the summary is as generic as the lock-screen version.
            nm.notify(TAG, group.hashCode() and 0x7fffffff, Notification.Builder(context, content.channel.id)
                .setSmallIcon(R.drawable.ic_stat_paddock).setContentTitle(content.publicTitle).setContentText("Agents finished.")
                .setGroup(group).setGroupSummary(true).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PUBLIC).build())
        }
    }

    override fun cancelAll() {
        nm.activeNotifications.filter { it.tag == TAG }.forEach { nm.cancel(TAG, it.id) }
    }

    private fun pending(requestCode: Int, link: String): PendingIntent = PendingIntent.getActivity(
        context, requestCode,
        // Explicit, so the link needs no intent filter: the filter admits only `paddock://open`, and the push form is this app's own.
        Intent(Intent.ACTION_VIEW, Uri.parse(link), context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        /** All of Paddock's alert notifications carry this tag, so cancelling them never touches anything else. */
        const val TAG = "paddock-alert"
    }
}
