package io.github.tuthan.paddock.net

/** What the gate must do before the SSH library is touched. */
sealed interface GateDecision {
    /** The grant does not apply (older build or device, or a non-local endpoint). */
    data object NotRequired : GateDecision
    data object Granted : GateDecision
    /** Required and missing: request it in context, or surface the recovery row. No socket may open. */
    data object NeedsGrant : GateDecision
}

object LocalNetworkPolicy {
    /** Android 17, the first release that enforces ACCESS_LOCAL_NETWORK for apps targeting it. */
    const val ENFORCED_FROM_SDK = 37
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

    /**
     * The grant is required only when the build targets SDK 37 or later, the device runs Android 17 or later,
     * and the endpoint is local. A non-local endpoint never needs it, so VPN routes pass the gate; whether the
     * OS still blocks them is recorded by the device test, not assumed here.
     */
    fun decide(targetSdk: Int, deviceSdk: Int, endpoint: EndpointClass, granted: Boolean): GateDecision = when {
        !enforced(targetSdk, deviceSdk) -> GateDecision.NotRequired
        endpoint != EndpointClass.Local -> GateDecision.NotRequired
        granted -> GateDecision.Granted
        else -> GateDecision.NeedsGrant
    }

    /** Whether this build on this device enforces the grant at all. Where it does not, nothing needs resolving. */
    fun enforced(targetSdk: Int, deviceSdk: Int): Boolean = targetSdk >= ENFORCED_FROM_SDK && deviceSdk >= ENFORCED_FROM_SDK

    /**
     * Whether a connect timeout should carry the "check local-network access" hint: the grant is enforced and missing, and the
     * OS drops LAN traffic without the grant silently, so an endpoint the gate let through (a name that did not resolve, a
     * stale DNS answer, a route the classification does not know) can still end as a bare timeout.
     */
    fun timeoutHint(targetSdk: Int, deviceSdk: Int, granted: Boolean): Boolean = enforced(targetSdk, deviceSdk) && !granted
}
