package io.github.tuthan.paddock.notify

import android.app.BroadcastOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import io.github.tuthan.paddock.alerts.AlertContent
import io.github.tuthan.paddock.alerts.MachineHint
import io.github.tuthan.paddock.alerts.NotificationContent
import io.github.tuthan.paddock.alerts.PushAck
import io.github.tuthan.paddock.alerts.PushEffect
import io.github.tuthan.paddock.alerts.PushExtras
import io.github.tuthan.paddock.alerts.PushProtocol
import io.github.tuthan.paddock.alerts.PushRegistration
import io.github.tuthan.paddock.alerts.PushRegistry
import io.github.tuthan.paddock.alerts.UnifiedPush
import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.ports.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Connector mode of Phase 07: Paddock as a UnifiedPush end-user application, written against the specification (AND_3.1.0) with
 * no library (the connector library has no Socket result, so it has no review row). It registers one token per machine with the
 * distributor the user picked, learns the address the distributor gives it, and turns a push into a generic notification that opens
 * that machine's herd. A push is a wake-up: nothing in its bytes is shown or acted on, and nothing reaches an agent because one arrived.
 *
 * Android cannot say who sent a broadcast, so the token (a random UUID per registration, never reused) is the only authentication:
 * a broadcast with a token this phone does not hold is dropped by [PushRegistry].
 */
class UnifiedPushConnector(
    private val context: Context,
    private val registry: PushRegistry,
    private val scope: CoroutineScope,
    private val clock: Clock,
    /** The phone's name for a machine, or null for one it no longer has. */
    private val machineName: suspend (profileId: String) -> String?,
    private val show: (NotificationContent) -> Unit,
    /** True while the herd is in front: the screen is the alert, so no notification is raised. */
    private val herdInFront: () -> Boolean,
    private val hideOnLockScreen: suspend () -> Boolean,
) {
    data class Distributor(val packageName: String, val label: String)

    private val _registrations = MutableStateFlow<List<PushRegistration>>(emptyList())
    val registrations: StateFlow<List<PushRegistration>> = _registrations.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    /** What the distributor last told the user needs attention (a failure, or it dropped the registration); cleared by the next registration. */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** The apps that answer a registration, other than this one. Visible through the `queries` entry in the manifest. */
    fun distributors(): List<Distributor> {
        val pm = context.packageManager
        val query = Intent(UnifiedPush.REGISTER)
        val found = if (Build.VERSION.SDK_INT >= 33) pm.queryBroadcastReceivers(query, PackageManager.ResolveInfoFlags.of(0)) else @Suppress("DEPRECATION") pm.queryBroadcastReceivers(query, 0)
        return found.mapNotNull { it.activityInfo }
            .filter { it.exported && it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .map { Distributor(it.packageName, it.applicationInfo.loadLabel(pm).toString().take(60)) }
            .sortedBy { it.label.lowercase() }
    }

    suspend fun refresh() { _registrations.value = registry.all() }

    /** The address was written to the host: the screen can say so, and says otherwise if the distributor changes it later. */
    suspend fun markShared(profileId: String) { registry.markShared(profileId); refresh() }

    /**
     * Registers [profileId] with [distributor] under a new token. An earlier registration of the machine, possibly with another
     * distributor, is unregistered first; the old token is gone before the new request is sent.
     */
    suspend fun register(profileId: String, machine: String, distributor: String) {
        val (fresh, old) = registry.begin(profileId, distributor)
        _notice.value = null
        if (old != null) send(old.distributor, UnifiedPush.UNREGISTER, shareIdentity = true) { putExtra("token", old.token) }
        sendRegister(fresh, machine)
        refresh()
    }

    /** The user turned it off: the token is forgotten at once, then the distributor is told. */
    suspend fun unregister(profileId: String) {
        registry.end(profileId)?.let { old -> send(old.distributor, UnifiedPush.UNREGISTER, shareIdentity = true) { putExtra("token", old.token) } }
        refresh()
    }

    /** Registers again with the token already held, as the specification asks of every start, so the two sides cannot drift apart. */
    suspend fun resume() {
        refresh()
        for (reg in _registrations.value) sendRegister(reg, machineName(reg.profile) ?: reg.profile)
    }

    private fun sendRegister(reg: PushRegistration, machine: String) =
        send(reg.distributor, UnifiedPush.REGISTER, shareIdentity = true) {
            putExtra("token", reg.token)
            putExtra("message", UnifiedPush.description(machine))
        }

    fun ack(ack: PushAck) = send(ack.distributor, UnifiedPush.MESSAGE_ACK, shareIdentity = false) { putExtra("token", ack.token); putExtra("id", ack.id) }

    /**
     * An inbound broadcast from the receiver. Anything the specification does not allow, or that carries a token this phone does not
     * hold, ends here. [done] is called when the work is finished, so the receiver can release the broadcast.
     */
    fun handle(intent: Intent, done: () -> Unit) {
        val parsed = runCatching {
            val e = intent.extras
            PushProtocol.parse(
                intent.action,
                PushExtras(
                    mapOf("token" to e?.getString("token"), "endpoint" to e?.getString("endpoint"), "id" to e?.getString("id"), "reason" to e?.getString("reason")),
                    e?.getByteArray("bytesMessage"),
                ),
            )
        }.getOrNull()
        if (parsed == null) { done(); return }
        scope.launch {
            try {
                when (val effect = registry.onInbound(parsed)) {
                    PushEffect.Ignore -> Unit
                    is PushEffect.Alert -> { raise(effect); effect.ack?.let(::ack) }
                    is PushEffect.EndpointKnown -> { effect.ack?.let(::ack); refresh() }
                    is PushEffect.Failed -> { _notice.value = effect.reason.label; refresh() }
                    is PushEffect.Unregistered -> { _notice.value = "The distributor removed this registration. Register again to receive alerts."; refresh() }
                }
            } finally { done() }
        }
    }

    private suspend fun raise(alert: PushEffect.Alert) {
        if (herdInFront()) return
        val machine = machineName(alert.profile) ?: return
        // The nonce is for dedupe only; a push without one still alerts, under a nonce of the phone's own.
        val nonce = alert.nonce ?: ("t" + clock.nowMillis())
        runCatching { show(AlertContent.ofPush(machine, MachineHint(HostProfileId(alert.profile), nonce), clock.nowMillis(), hideOnLockScreen())) }
    }

    /**
     * REGISTER and UNREGISTER carry the app's identity: Android 14 and later through the broadcast option, older versions through an
     * immutable PendingIntent aimed at a dummy package, from which the distributor reads this app's package name.
     */
    private fun send(distributor: String, action: String, shareIdentity: Boolean, extras: Intent.() -> Unit) {
        val intent = Intent(action).setPackage(distributor).apply(extras)
        runCatching {
            if (!shareIdentity) context.sendBroadcast(intent)
            else if (Build.VERSION.SDK_INT >= 34) context.sendBroadcast(intent, null, BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
            else {
                intent.putExtra("pi", PendingIntent.getBroadcast(context, 0, Intent().setPackage(UnifiedPush.DUMMY_APP), PendingIntent.FLAG_IMMUTABLE))
                context.sendBroadcast(intent)
            }
        }.onFailure { android.util.Log.w("paddock-push", "could not send $action: ${it.javaClass.simpleName}: ${it.message?.take(120)}") }
    }
}
