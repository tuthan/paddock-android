package io.github.tuthan.paddock.applock

import android.app.KeyguardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/**
 * The phone's own prompt, really shown: the one thing the other lock tests cannot see, because they hand the screens a result instead of asking the
 * system. Needs a screen lock on the device (on an emulator: `adb shell locksettings set-pin 1234`, and `locksettings clear --old 1234` afterwards);
 * without one the tests are skipped, never failed, and they never set or clear a lock themselves.
 *
 * Nobody can type the PIN here, so what is asserted is that the system accepts the request and shows its prompt (no answer arrives, in particular no
 * `Failed` saying a permission is missing), and that backing out of it is `Cancelled`, which is what leaves the app locked and the Unlock button usable.
 */
class DeviceAuthTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var auth: DeviceAuth

    private fun start() {
        rule.setContent { auth = rememberDeviceAuth() }
        rule.waitForIdle()
        val keyguard = rule.activity.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        assumeTrue("the device has no screen lock; set one to run this test", keyguard.isDeviceSecure)
    }

    @Test fun aPhoneWithAScreenLockIsReadyToBeAsked() {
        start()
        assertEquals(DeviceLock.Ready, auth.lock())
    }

    @Test fun theSystemPromptOpensAndBackingOutOfItIsCancelledNotFailed() {
        start()
        val results = CopyOnWriteArrayList<AuthResult>()
        rule.runOnUiThread { auth.authenticate("Unlock Paddock", "Confirm it is you") { results += it } }
        // The prompt is the system's own window: give it time to appear. A missing permission or a refused request would answer at once with Failed.
        Thread.sleep(2_500)
        assertTrue("the system refused to show its prompt: $results", results.none { it is AuthResult.Failed })
        assertTrue("nothing could have answered yet, but got $results", results.isEmpty())
        // Backing out of the prompt (Back, or the credential screen's Back on Android 8 to 10) gives up.
        // Through the shell, not `sendKeyDownUpSync`: before Android 11 the prompt is the system's own activity, and an app's instrumentation may not inject into another app.
        // The older credential screen takes a first Back to close its keyboard, so Back is pressed again until the answer arrives.
        val deadline = System.currentTimeMillis() + 8_000
        while (results.isEmpty() && System.currentTimeMillis() < deadline) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").close()
            Thread.sleep(1_000)
        }
        assertEquals(listOf<AuthResult>(AuthResult.Cancelled), results.toList())
    }
}
