package io.github.tuthan.paddock.notify

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import io.github.tuthan.paddock.MainActivity
import io.github.tuthan.paddock.R
import io.github.tuthan.paddock.alerts.AlertAction
import io.github.tuthan.paddock.alerts.AlertNotifier
import io.github.tuthan.paddock.alerts.DeepLink
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
            // The link says which machine this is for, so coming to the front can clear that machine's notifications and no other's.
            .addExtras(Bundle().apply { putString(EXTRA_LINK, content.link) })
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

    override fun cancelFor(profileId: String) {
        val mine = nm.activeNotifications.filter { it.tag == TAG }
        fun summary(n: android.service.notification.StatusBarNotification) = n.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0
        val gone = mine.filter { !summary(it) && DeepLink.profileOf(it.notification.extras.getString(EXTRA_LINK)) == profileId }
        gone.forEach { nm.cancel(TAG, it.id) }
        // A Done stack's summary is not for one machine's link; it goes when no notification of its stack is left. What is left is judged from this list, not from
        // a second read of the active ones, which may still show what was just cancelled.
        val left = mine - gone.toSet()
        left.filter { summary(it) && left.none { c -> !summary(c) && c.notification.group == it.notification.group } }.forEach { nm.cancel(TAG, it.id) }
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
        private const val EXTRA_LINK = "paddock.link"
    }
}
