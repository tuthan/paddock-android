package io.github.tuthan.paddock.notify

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import io.github.tuthan.paddock.alerts.AccessRecovery
import io.github.tuthan.paddock.alerts.AlertChannel
import io.github.tuthan.paddock.alerts.NotificationAccess
import io.github.tuthan.paddock.alerts.NotificationAccessRules

/** Reads what the system says about Paddock's notifications and hands the pure rules the facts. Read again on every return to Settings. */
class NotificationAccessReader(private val context: Context) {
    fun read(activity: Activity?, askedBefore: Boolean): NotificationAccess {
        Channels.ensure(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        val granted = Build.VERSION.SDK_INT < NotificationAccessRules.PERMISSION_SDK ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val rationale = Build.VERSION.SDK_INT >= NotificationAccessRules.PERMISSION_SDK && activity?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) == true
        val blocked = AlertChannel.entries.filter { nm.getNotificationChannel(it.id)?.importance == NotificationManager.IMPORTANCE_NONE }.map { it.id }.toSet()
        return NotificationAccessRules.evaluate(Build.VERSION.SDK_INT, granted, nm.areNotificationsEnabled(), blocked, askedBefore, rationale)
    }

    /** The system page for [recovery]'s action; null for the permission request, which is a dialog and not a page. */
    fun settingsIntent(recovery: AccessRecovery, access: NotificationAccess): Intent? = when (recovery.action) {
        AccessRecovery.Action.AskPermission -> null
        AccessRecovery.Action.OpenAppSettings -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        AccessRecovery.Action.OpenChannelSettings -> {
            val first = (access as? NotificationAccess.ChannelsBlocked)?.channels?.firstOrNull()
            if (first == null) Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            else Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, first.id)
        }
    }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
