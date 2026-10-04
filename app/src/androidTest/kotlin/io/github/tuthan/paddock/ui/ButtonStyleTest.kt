package io.github.tuthan.paddock.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tuthan.paddock.ui.components.ButtonKind
import io.github.tuthan.paddock.ui.components.PaddockButton
import io.github.tuthan.paddock.ui.theme.PaddockIcons
import io.github.tuthan.paddock.ui.theme.PaddockTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The four kinds differ in colour only: for a size, every kind has the same height, so a row of mixed kinds still lines up. */
class ButtonStyleTest {
    @get:Rule val rule = createComposeRule()

    private fun heightDp(text: String): Float = with(rule.density) { rule.onNodeWithText(text).fetchSemanticsNode().size.height.toDp().value }

    @Composable private fun Sheet(small: Boolean, dense: Boolean) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (k in ButtonKind.entries) PaddockButton(k.name, {}, kind = k, small = small, dense = dense, icon = if (k == ButtonKind.Secondary) PaddockIcons.Keyboard else null)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (k in ButtonKind.entries) PaddockButton(k.name.take(3), {}, kind = k, small = true, fillWidth = false, dense = dense)
            }
        }
    }

    private fun shoot(name: String) {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.getExternalFilesDir(null), "screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun everyKindIsTheSameHeightAtTheSameSizeAndNeverUnderFortyEightDp() {
        rule.setContent { PaddockTheme(darkTheme = true) { Sheet(small = false, dense = false) } }
        val heights = ButtonKind.entries.map { heightDp(it.name) }
        assertEquals("kinds differ in height: $heights", 1, heights.map { Math.round(it) }.toSet().size)
        assertTrue("under 48 dp: $heights", heights.all { it >= 47.5f })
        val row = ButtonKind.entries.map { heightDp(it.name.take(3)) }
        assertEquals("small kinds differ in height: $row", 1, row.map { Math.round(it) }.toSet().size)
        assertTrue("small buttons are under 48 dp: $row", row.all { it >= 47.5f })
        shoot("buttons-dark")
    }

    @Test fun denseButtonsAreAlsoOneHeightAcrossKinds() {
        rule.setContent { PaddockTheme(darkTheme = true) { Sheet(small = true, dense = true) } }
        val row = ButtonKind.entries.map { heightDp(it.name.take(3)) }
        assertEquals("dense kinds differ in height: $row", 1, row.map { Math.round(it) }.toSet().size)
        assertTrue("dense is 36 dp, not the 48 of a regular button: $row", row.all { it in 35.5f..40f })
        shoot("buttons-dense-dark")
    }

    @Test fun lightThemeRendersAllFourKinds() {
        rule.setContent { PaddockTheme(darkTheme = false) { Sheet(small = false, dense = false) } }
        for (k in ButtonKind.entries) rule.onNodeWithText(k.name).assertExists()
        shoot("buttons-light")
    }
}
