package io.github.tuthan.paddock.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import io.github.tuthan.paddock.alerts.AlertChannel

/**
 * The four channels (Phase 07): Needs you (high), Done (default but silent, one stack per machine), Machines (low) and
 * Watching (min). Creating an existing channel is a no-op, so this runs before every post and before every read of the
 * user's choices. The ids are the system's key for those choices and never change.
 */
object Channels {
    fun importance(channel: AlertChannel): Int = when (channel) {
        AlertChannel.NeedsYou -> NotificationManager.IMPORTANCE_HIGH
        AlertChannel.Done -> NotificationManager.IMPORTANCE_DEFAULT
        AlertChannel.Machines -> NotificationManager.IMPORTANCE_LOW
        AlertChannel.Watching -> NotificationManager.IMPORTANCE_MIN
    }

    fun ensure(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        for (c in AlertChannel.entries) {
            nm.createNotificationChannel(
                NotificationChannel(c.id, c.title, importance(c)).apply {
                    description = c.description
                    // The lock screen shows the generic version of every Paddock notification unless the user chose otherwise.
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                    if (c == AlertChannel.Done) { setSound(null, null); enableVibration(false) }
                    setShowBadge(c == AlertChannel.NeedsYou)
                },
            )
        }
    }
}
