package io.github.tuthan.paddock.notify

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import java.util.concurrent.CopyOnWriteArrayList

/** What the app asked of the distributor, with who the distributor could tell was asking. */
data class DistributorRequest(val action: String, val token: String?, val id: String?, val message: String?, val sender: String?, val hadPendingIntent: Boolean)

/**
 * A UnifiedPush distributor for tests, following the specification's distributor side, as a receiver registered in the test's own
 * process and addressed by package name: the system will not start a process for the test package itself, so a distributor in a
 * separate package cannot run under instrumentation. What that leaves untested is discovery through the manifest's `queries` entry
 * (the app never lists itself) and a distributor's own process; both are checks for a real distributor on a phone.
 *
 * It identifies the caller (the shared identity on Android 14 and later, the PendingIntent's creator before that), answers
 * REGISTER with NEW_ENDPOINT or REGISTRATION_FAILED and UNREGISTER with UNREGISTERED, and records every request.
 * [push] plays the push server's delivery.
 */
object FakeDistributor {
    private const val APP_RECEIVER = "io.github.tuthan.paddock.notify.UnifiedPushReceiver"
    const val DEFAULT_ENDPOINT = "https://ntfy.test/upSecret0123456789"

    val requests = CopyOnWriteArrayList<DistributorRequest>()
    @Volatile private var mode = "accept"
    @Volatile private var value = DEFAULT_ENDPOINT
    private var listener: BroadcastReceiver? = null

    /** Starts answering as a distributor, in its default behaviour. */
    fun start(context: Context) {
        stop(context)
        requests.clear(); mode = "accept"; value = DEFAULT_ENDPOINT
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = onRequest(c, i, if (Build.VERSION.SDK_INT >= 34) sentFromPackage else null)
        }
        val filter = IntentFilter().apply { listOf("REGISTER", "UNREGISTER", "MESSAGE_ACK").forEach { addAction("org.unifiedpush.android.distributor.$it") } }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED) else @Suppress("UnspecifiedRegisterReceiverFlag") context.registerReceiver(r, filter)
        listener = r
    }

    fun stop(context: Context) { listener?.let { runCatching { context.unregisterReceiver(it) } }; listener = null }

    fun requests(action: String) = requests.filter { it.action.endsWith(".$action") }.toList()

    fun accept(endpoint: String) { mode = "accept"; value = endpoint }
    fun fail(reason: String) { mode = "fail"; value = reason }

    private fun onRequest(context: Context, intent: Intent, sentFrom: String?) {
        val token = intent.getStringExtra("token")
        val pi = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra("pi", PendingIntent::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra<PendingIntent>("pi")
        requests += DistributorRequest(intent.action.orEmpty(), token, intent.getStringExtra("id"), intent.getStringExtra("message"), sentFrom ?: pi?.creatorPackage, pi != null)
        when (intent.action) {
            "org.unifiedpush.android.distributor.REGISTER" -> when (mode) {
                "fail" -> deliver(context, "REGISTRATION_FAILED") { putExtra("token", token); putExtra("reason", value) }
                else -> deliver(context, "NEW_ENDPOINT") { putExtra("token", token); putExtra("endpoint", value); putExtra("id", "ep-1") }
            }
            "org.unifiedpush.android.distributor.UNREGISTER" -> deliver(context, "UNREGISTERED") { putExtra("token", token) }
        }
    }

    /** A push arriving from the push server, as the distributor forwards it. */
    fun push(context: Context, token: String, bytes: ByteArray, id: String? = "m-1") =
        deliver(context, "MESSAGE") { putExtra("token", token); putExtra("bytesMessage", bytes); id?.let { putExtra("id", it) } }

    /** Any inbound action with any extras, for the requests a distributor should never send. */
    fun raw(context: Context, action: String, extras: Intent.() -> Unit) = deliver(context, action, extras)

    private fun deliver(context: Context, action: String, extras: Intent.() -> Unit) {
        val full = if (action.startsWith("org.")) action else "org.unifiedpush.android.connector.$action"
        context.sendBroadcast(Intent(full).setComponent(ComponentName(context.packageName, APP_RECEIVER)).apply(extras))
    }
}
