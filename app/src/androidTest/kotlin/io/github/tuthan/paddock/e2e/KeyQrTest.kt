package io.github.tuthan.paddock.e2e

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tuthan.paddock.hostprofile.AddMachineForm
import io.github.tuthan.paddock.hostprofile.AddMachineInput
import io.github.tuthan.paddock.net.GateDecision
import io.github.tuthan.paddock.ssh.KeyBacking
import io.github.tuthan.paddock.ssh.OpenSshKeys
import io.github.tuthan.paddock.ui.screens.AddMachine
import io.github.tuthan.paddock.ui.screens.AddMachineState
import io.github.tuthan.paddock.ui.theme.PaddockTheme
import java.io.File
import org.junit.Rule
import org.junit.Test

/**
 * Phase 14 spike S4, the part an emulator can do (`paddock-harness/check-key-qr.sh`): the phone's key drawn as a QR by Add machine, captured
 * from the real screen and written with the key line it encodes, so a different decoder (zbarimg, the one the desktop's camera path uses)
 * can read it back on the host. A real camera reading a real phone is not this test.
 */
class KeyQrTest {
    @get:Rule val rule = createComposeRule()

    private val line = OpenSshKeys.publicLine(
        java.security.KeyPairGenerator.getInstance("EC").apply { initialize(java.security.spec.ECGenParameterSpec("secp256r1")) }.generateKeyPair().public as java.security.interfaces.ECPublicKey,
        "paddock@phone",
    )

    private fun capture(dark: Boolean, name: String) {
        rule.setContent {
            PaddockTheme(darkTheme = dark) {
                AddMachine(
                    AddMachineState({ AddMachineForm.route(it, GateDecision.NotRequired) }, line, KeyBacking.Tee),
                    onConnect = {}, onGenerateKey = {}, onCopyPublicKey = {}, onOpenSettings = {}, onBack = {}, initial = AddMachineInput("192.168.1.20", "22", "jdoe"),
                )
            }
        }
        rule.onNodeWithText("Show as QR").performScrollTo().performClick()
        rule.waitForIdle()
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "qr").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { rule.onNode(hasContentDescription("QR code of this phone's public key")).performScrollTo().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(dir, "key-line.txt").writeText(line)
    }

    @Test fun theKeyQrOnTheDarkTheme() = capture(true, "key-qr-dark")
    @Test fun theKeyQrOnTheLightTheme() = capture(false, "key-qr-light")
}
