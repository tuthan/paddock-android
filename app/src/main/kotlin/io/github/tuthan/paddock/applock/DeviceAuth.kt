package io.github.tuthan.paddock.applock

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.tuthan.paddock.applock.AuthResult.Cancelled
import io.github.tuthan.paddock.applock.AuthResult.Failed
import io.github.tuthan.paddock.applock.AuthResult.NoLock
import io.github.tuthan.paddock.applock.AuthResult.Success

/** What the phone said when asked to prove the user is the user. */
sealed interface AuthResult {
    data object Success : AuthResult

    /** The user backed out, or the system took the prompt away (the app went to the back, the screen turned off). Nothing is wrong. */
    data object Cancelled : AuthResult

    /** The phone has no screen lock to ask. */
    data object NoLock : AuthResult

    /** The phone refused to ask or gave up (too many wrong tries, no hardware answer): [message] is the phone's own words, never shown as a code. */
    data class Failed(val message: String?) : AuthResult
}

/**
 * Asks the phone's own lock (fingerprint, face, PIN, pattern or password) whether the user is present. Paddock keeps nothing of the answer but "yes": no
 * secret, no key, no code of its own, and no library (the platform prompt, so nothing to review or pin).
 *
 * - **Android 11 and later:** `android.hardware.biometrics.BiometricPrompt` with a weak-or-better biometric or the device credential, so one prompt covers the
 *   fingerprint, the face and the PIN fallback.
 * - **Android 8 to 10:** the system's confirm-credential screen (`KeyguardManager.createConfirmDeviceCredentialIntent`): the phone's PIN, pattern or password.
 *   The platform prompt cannot offer the credential as a fallback before Android 11 without a second button, and a biometric-only prompt would leave a user
 *   whose fingerprint failed with no way in.
 *
 * Both are the system's own screens, drawn by the system, so nothing here can be spoofed by another app drawing over Paddock.
 */
class DeviceAuth internal constructor(
    private val context: Context,
    private val credentialLauncher: ActivityResultLauncher<Intent>,
    private val pending: Array<((AuthResult) -> Unit)?>,
) {
    private val keyguard get() = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

    /** Whether the phone has a screen lock to ask. A phone that can enrol a fingerprint already has a PIN, pattern or password behind it. */
    fun lock(): DeviceLock = if (keyguard.isDeviceSecure) DeviceLock.Ready else DeviceLock.NotSet

    /** Shows the system's prompt. [onResult] is called once, on the main thread. A second call while one is showing replaces nothing: the first is still the one answered. */
    fun authenticate(title: String, subtitle: String?, onResult: (AuthResult) -> Unit) {
        if (!keyguard.isDeviceSecure) { onResult(NoLock); return }
        if (pending[0] != null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) promptR(title, subtitle, onResult) else credentialScreen(title, subtitle, onResult)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun promptR(title: String, subtitle: String?, onResult: (AuthResult) -> Unit) {
        val prompt = BiometricPrompt.Builder(context).setTitle(title).apply { subtitle?.let { setSubtitle(it) } }
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .setConfirmationRequired(false)
            .build()
        pending[0] = onResult
        val finish = { r: AuthResult -> val cb = pending[0]; pending[0] = null; cb?.invoke(r) ?: Unit }
        try {
            prompt.authenticate(CancellationSignal(), context.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { finish(Success) }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    finish(if (errorCode == ERROR_CANCELED || errorCode == ERROR_USER_CANCELED) Cancelled else Failed(errString?.toString()))
                }
                // onAuthenticationFailed is one wrong finger, not the end: the prompt stays up and counts the tries itself.
            })
        } catch (e: RuntimeException) {
            finish(Failed(e.message))
        }
    }

    @Suppress("DEPRECATION")
    private fun credentialScreen(title: String, subtitle: String?, onResult: (AuthResult) -> Unit) {
        val intent = keyguard.createConfirmDeviceCredentialIntent(title, subtitle)
        if (intent == null) { onResult(NoLock); return }
        pending[0] = onResult
        try {
            credentialLauncher.launch(intent)
        } catch (e: RuntimeException) {
            pending[0] = null
            onResult(Failed(e.message))
        }
    }

    private companion object {
        /** `BiometricPrompt.BIOMETRIC_ERROR_CANCELED` (the system took the prompt away) and `..._USER_CANCELED` (the user backed out). */
        const val ERROR_CANCELED = 5
        const val ERROR_USER_CANCELED = 10
    }
}

/** The activity under a Compose `LocalContext` (which may be wrapped), or null in a preview. */
internal fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

/** The device authentication for this screen. The credential screen's answer comes back through the activity-result launcher registered here. */
@Composable
fun rememberDeviceAuth(): DeviceAuth {
    val context = LocalContext.current
    val pending = remember { arrayOfNulls<(AuthResult) -> Unit>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val cb = pending[0]; pending[0] = null
        cb?.invoke(if (r.resultCode == Activity.RESULT_OK) Success else Cancelled)
    }
    return remember(context, launcher) { DeviceAuth(context, launcher, pending) }
}
