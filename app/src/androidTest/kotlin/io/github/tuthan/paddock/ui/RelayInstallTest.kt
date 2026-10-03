package io.github.tuthan.paddock.ui

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tuthan.paddock.live.HostPhase
import io.github.tuthan.paddock.relay.PluginMismatch
import io.github.tuthan.paddock.relay.PluginNotes
import io.github.tuthan.paddock.ui.screens.RelayInstall
import io.github.tuthan.paddock.ui.theme.PaddockTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The relay-consent screen from fixtures: it names the file and hash, and nothing happens without the explicit tap. */
class RelayInstallTest {
    @get:Rule val rule = createComposeRule()

    private val ask = HostPhase.NeedsRelayInstall("/home/jdoe/.local/share/paddock/paddock-relay.py", "8effb4b5aa733fccf72b5ca31545bc24f05046d000d463d6939b3486b3339695", replacing = false)
    private class Calls { var install = 0; var notNow = 0 }

    private fun show(ask: HostPhase.NeedsRelayInstall = this.ask, installing: Boolean = false, fontScale: Float? = null, dark: Boolean = true, calls: Calls = Calls()): Calls {
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides if (fontScale != null) Density(base.density, fontScale) else base) {
                PaddockTheme(darkTheme = dark) { RelayInstall("Laptop", ask, installing, { calls.install++ }, { calls.notNow++ }) }
            }
        }
        return calls
    }

    private fun shoot(name: String) {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.getExternalFilesDir(null), "screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun itNamesTheHostTheFileAndTheHashBeforeAnythingIsWritten() {
        show()
        rule.onNodeWithText("Install the relay on Laptop?").assertIsDisplayed()
        rule.onNode(hasContentDescription("File on the host: ${ask.destination}")).assertIsDisplayed()
        rule.onNode(hasContentDescription("SHA-256 of the script: ${ask.expectedSha256}")).assertIsDisplayed()
        shoot("relay-install")
    }

    @Test fun nothingIsInstalledUntilTheExplicitTapAndNotNowNeverInstalls() {
        val c = show()
        assertEquals(0, c.install)
        rule.onNodeWithText("Not now").performClick()
        assertEquals(0, c.install); assertEquals(1, c.notNow)
        rule.onNodeWithText("Install the relay").performClick()
        assertEquals(1, c.install)
    }

    @Test fun aReplacementSaysSoPlainly() {
        show(ask.copy(replacing = true))
        rule.onNodeWithText("A different file is already there", substring = true).assertIsDisplayed()
    }

    @Test fun aPluginWhoseRelayIsNotThePinSaysSoAndStillOffersTheInstall() {
        val note = PluginNotes.mismatch(PluginMismatch("0".repeat(64), "0.0.9"))
        val c = show(ask.copy(pluginNote = note))
        rule.onNodeWithText("The Paddock herdr plugin", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Install the relay").performClick()
        assertEquals(1, c.install)
    }

    @Test fun whileInstallingBothButtonsAreInertAndTheLabelSaysWhy() {
        val c = show(installing = true)
        rule.onNodeWithText("Installing…").performClick()
        rule.onNodeWithText("Not now").performClick()
        assertEquals(0, c.install); assertEquals(0, c.notNow)
    }

    @Test fun buttonsAreAtLeastFortyEightDpAndReachableAtTwoHundredPercentFont() {
        show(fontScale = 2f)
        rule.onNodeWithText("Install the relay").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText("Not now").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        rule.onNode(hasContentDescription("SHA-256 of the script", substring = true)).performScrollTo().assertIsDisplayed()
        shoot("relay-install-200")
    }

    @Test fun lightThemeRenders() {
        show(dark = false)
        rule.onNodeWithText("Install the relay").assertIsDisplayed()
    }
}
