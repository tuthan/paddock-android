package io.github.tuthan.paddock.alerts

/** Whether a notification Paddock raises can reach the user, and if not, why. Read again on every return to Settings. */
sealed interface NotificationAccess {
    data object Allowed : NotificationAccess

    /** Android 13 and later: the notification permission is not granted. [canAsk] is false once the system no longer shows its dialog. */
    data class NeedsPermission(val canAsk: Boolean) : NotificationAccess

    /** Notifications are off for the whole app in system settings. */
    data object AppBlocked : NotificationAccess

    /** The app is allowed but these channels are turned off, so what they carry neither shows nor sounds. */
    data class ChannelsBlocked(val channels: List<AlertChannel>) : NotificationAccess
}

/** What the recovery row says and offers. */
data class AccessRecovery(val message: String, val action: Action, val actionLabel: String) {
    enum class Action { AskPermission, OpenAppSettings, OpenChannelSettings }
}

object NotificationAccessRules {
    const val PERMISSION_SDK = 33

    /**
     * [blockedChannels] are the ids of channels whose importance is "none". Only the two channels that carry alerts matter here;
     * a user who silenced Machines or Watching has lost nothing this phase sends. [askedBefore] and [rationale] say whether the
     * permission dialog can still appear: it can until the system has refused to show it again.
     */
    fun evaluate(sdk: Int, permissionGranted: Boolean, appEnabled: Boolean, blockedChannels: Set<String>, askedBefore: Boolean, rationale: Boolean): NotificationAccess {
        if (sdk >= PERMISSION_SDK && !permissionGranted) return NotificationAccess.NeedsPermission(canAsk = !askedBefore || rationale)
        if (!appEnabled) return NotificationAccess.AppBlocked
        val blocked = listOf(AlertChannel.NeedsYou, AlertChannel.Done).filter { it.id in blockedChannels }
        return if (blocked.isEmpty()) NotificationAccess.Allowed else NotificationAccess.ChannelsBlocked(blocked)
    }

    fun recovery(access: NotificationAccess): AccessRecovery? = when (access) {
        NotificationAccess.Allowed -> null
        is NotificationAccess.NeedsPermission ->
            if (access.canAsk) AccessRecovery("Paddock needs your permission to show notifications. Alerts stay off until you allow it.", AccessRecovery.Action.AskPermission, "Allow notifications")
            else AccessRecovery("Notifications are blocked for Paddock in system settings, so alerts cannot appear. Turn them on there.", AccessRecovery.Action.OpenAppSettings, "Open settings")
        NotificationAccess.AppBlocked ->
            AccessRecovery("Notifications are turned off for Paddock in system settings, so alerts cannot appear. Turn them on there.", AccessRecovery.Action.OpenAppSettings, "Open settings")
        is NotificationAccess.ChannelsBlocked -> {
            val names = access.channels.joinToString(" and ") { "\"${it.title}\"" }
            val verb = if (access.channels.size == 1) "is" else "are"
            AccessRecovery("The $names channel $verb turned off in system settings, so what ${if (access.channels.size == 1) "it carries" else "they carry"} will not appear or make a sound.", AccessRecovery.Action.OpenChannelSettings, "Open channel settings")
        }
    }
}
