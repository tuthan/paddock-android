package io.github.tuthan.paddock.alerts

/**
 * When the screen is the alert. Paddock raises no notification for something the user is already looking at, but the herd on screen is **one** machine's:
 * Paddock watches one machine at a time, so an alert for another saved machine is not on screen however far in front the app is. The rule is about the
 * machine, never about the app alone (a user reported nothing at all for a second machine while Paddock was open on the first).
 */
object AlertScreen {
    /**
     * Whether an alert for [alertProfile] needs no notification: a herd is in front ([herdInFront]: the app is resumed and the app lock does not cover it)
     * and it is the herd of that very machine ([watchedProfile]). Anything else raises the notification, and a tap on it switches the watch.
     */
    fun showsIt(herdInFront: Boolean, watchedProfile: String?, alertProfile: String): Boolean =
        herdInFront && watchedProfile != null && watchedProfile == alertProfile
}
