package io.github.tuthan.paddock.ssh

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import io.github.tuthan.paddock.net.GateDecision
import io.github.tuthan.paddock.net.LocalNetworkPolicy
import io.github.tuthan.paddock.net.classifyEndpoint
import io.github.tuthan.paddock.ports.DownReason

/**
 * Owns the Android 17 local-network grant. It answers before any socket opens, on every connect attempt, so a
 * revoked grant is noticed on the next attempt and a denied one never turns into a bare timeout.
 *
 * Requesting the permission is UI (an activity result launcher); this class only decides, reads the grant and
 * builds the recovery intent, so it works the same from a service or a test.
 */
class LocalNetworkGate(
    private val context: Context,
    private val deviceSdk: Int = Build.VERSION.SDK_INT,
    private val targetSdk: Int = context.applicationInfo.targetSdkVersion,
) : ConnectGate {

    fun isGranted(): Boolean = context.checkSelfPermission(LocalNetworkPolicy.PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun decide(host: String): GateDecision =
        LocalNetworkPolicy.decide(targetSdk, deviceSdk, classifyEndpoint(host), isGranted())

    /** Whether this device and build enforce the grant for LAN endpoints at all, and it is missing. For the Settings row. */
    fun lanAccessMissing(): Boolean =
        LocalNetworkPolicy.decide(targetSdk, deviceSdk, io.github.tuthan.paddock.net.EndpointClass.Local, isGranted()) == GateDecision.NeedsGrant

    /** Whether the grant applies here whatever its state. */
    fun lanAccessApplies(): Boolean =
        LocalNetworkPolicy.decide(targetSdk, deviceSdk, io.github.tuthan.paddock.net.EndpointClass.Local, granted = true) != GateDecision.NotRequired

    /** True when the UI should ask for the grant in context before connecting to [host]. */
    fun needsRequest(host: String): Boolean = decide(host) == GateDecision.NeedsGrant

    override suspend fun check(target: SshTarget): DownReason? =
        if (decide(target.host) == GateDecision.NeedsGrant) DownReason.PermissionDenied else null

    /** The recovery row opens this: the app's system settings page, where the grant can be switched on. */
    fun settingsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
