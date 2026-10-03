package io.github.tuthan.paddock.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.tuthan.paddock.PaddockApp

/**
 * The messaging receiver the UnifiedPush specification asks of an end-user application. It is exported because a distributor is
 * another app and no permission can name it; [UnifiedPushConnector] drops everything but a well-formed request carrying a token
 * this phone issued.
 */
class UnifiedPushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = (context.applicationContext as PaddockApp).graph
        val pending = goAsync()
        graph.push.handle(intent) { pending.finish() }
    }
}
