package io.github.tuthan.paddock.ssh

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import io.github.tuthan.paddock.net.EndpointClass
import io.github.tuthan.paddock.net.GateDecision
import io.github.tuthan.paddock.net.HostResolver
import io.github.tuthan.paddock.net.LocalNetworkPolicy
import io.github.tuthan.paddock.net.classifyEndpoint
import io.github.tuthan.paddock.net.classifyResolved
import io.github.tuthan.paddock.ports.DownReason
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Owns the Android 17 local-network grant. It answers before any socket opens, on every connect attempt, so a
 * revoked grant is noticed on the next attempt and a denied one never turns into a bare timeout.
 *
 * The OS enforces the grant on the address a socket goes to, not on what the user typed, so connecting decides from the
 * resolved address ([decideResolved]): a LAN name such as `nas.lan` or a bare hostname is treated as local when it resolves to
 * a LAN address. The lookup runs off the caller's thread with a short bound and only where the grant is enforced.
 *
 * Requesting the permission is UI (an activity result launcher); this class only decides, reads the grant and
 * builds the recovery intent, so it works the same from a service or a test.
 */
class LocalNetworkGate(
    private val context: Context,
    private val deviceSdk: Int = Build.VERSION.SDK_INT,
    private val targetSdk: Int = context.applicationInfo.targetSdkVersion,
    private val resolver: HostResolver = HostResolver.System,
    private val resolveTimeout: Duration = 3.seconds,
) : ConnectGate {

    fun isGranted(): Boolean = context.checkSelfPermission(LocalNetworkPolicy.PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** From the typed text only, with no DNS: for the route hint while the user types. Connecting uses [decideResolved]. */
    fun decide(host: String): GateDecision =
        LocalNetworkPolicy.decide(targetSdk, deviceSdk, classifyEndpoint(host), isGranted())

    /** The decision a connect gets: a plain name is resolved (bounded, off the caller's thread) and is local if any address is. */
    suspend fun decideResolved(host: String): GateDecision {
        if (!LocalNetworkPolicy.enforced(targetSdk, deviceSdk)) return GateDecision.NotRequired
        return LocalNetworkPolicy.decide(targetSdk, deviceSdk, classifyResolved(host, resolver, resolveTimeout), isGranted())
    }

    /** Whether this device and build enforce the grant for LAN endpoints at all, and it is missing. For the Settings row. */
    fun lanAccessMissing(): Boolean =
        LocalNetworkPolicy.decide(targetSdk, deviceSdk, EndpointClass.Local, isGranted()) == GateDecision.NeedsGrant

    /** Whether the grant applies here whatever its state. */
    fun lanAccessApplies(): Boolean =
        LocalNetworkPolicy.decide(targetSdk, deviceSdk, EndpointClass.Local, granted = true) != GateDecision.NotRequired

    /** True when the UI should ask for the grant in context before connecting to [host]. Resolves [host]; call it off the main thread's critical path. */
    suspend fun needsRequest(host: String): Boolean = decideResolved(host) == GateDecision.NeedsGrant

    override suspend fun check(target: SshTarget): DownReason? =
        if (decideResolved(target.host) == GateDecision.NeedsGrant) DownReason.PermissionDenied else null

    /** A timeout where the grant is enforced and missing may be the OS dropping the connect silently: say so, with the settings recovery. */
    override suspend fun timeoutHint(target: SshTarget): DownReason? =
        if (LocalNetworkPolicy.timeoutHint(targetSdk, deviceSdk, isGranted())) DownReason.LocalNetworkTimeout else null

    /** The recovery row opens this: the app's system settings page, where the grant can be switched on. */
    fun settingsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
