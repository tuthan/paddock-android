package io.github.tuthan.paddock.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
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

    /** D6: the lock glyph and the "Pro" tag never make a button taller, at the design's font and at 200%, for every kind and both sizes. */
    @Test fun aLockedButtonIsTheSameHeightAsAPlainOneAtEveryFontScale() {
        var fontScale by mutableStateOf(1f)
        // Short labels, so neither the plain nor the locked one wraps at 200%: a wrapped label is taller for its own reason, not the tag's.
        fun label(k: ButtonKind, small: Boolean) = "${k.name.take(3)} ${if (small) "s" else "r"}"
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                PaddockTheme(darkTheme = true) {
                    // Scrolled, so every button is measured at its own height however tall the list is at 200%.
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (k in ButtonKind.entries) for (small in listOf(false, true)) {
                            PaddockButton(label(k, small), {}, kind = k, small = small)
                            PaddockButton("${label(k, small)} L", {}, kind = k, small = small, pro = true)
                        }
                    }
                }
            }
        }
        for (scale in listOf(1f, 2f)) {
            rule.runOnIdle { fontScale = scale }
            rule.waitForIdle()
            for (k in ButtonKind.entries) for (small in listOf(false, true)) {
                val plain = heightDp(label(k, small))
                val locked = with(rule.density) { rule.onNode(hasContentDescription("${label(k, small)} L · Pro")).fetchSemanticsNode().size.height.toDp().value }
                assertEquals("$k small=$small at ${scale}x: locked $locked dp, plain $plain dp", Math.round(plain), Math.round(locked))
            }
            if (scale == 1f) shoot("buttons-pro-dark")
        }
    }

    /**
     * D6: a half-width locked button at a large font (Spaces' Delete… beside Restart) has no room for the tag beside its label. The label stays one
     * line and the tag moves, whole, under it: neither is broken nor clipped.
     */
    @Test fun aNarrowLockedButtonMovesItsTagUnderTheLabelAndBreaksNeither() {
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, 1.3f)) {
                PaddockTheme(darkTheme = true) {
                    Row(Modifier.width(300.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PaddockButton("Restart", {}, Modifier.weight(1f), kind = ButtonKind.Secondary, small = true, enabled = false)
                        PaddockButton("Delete…", {}, Modifier.weight(1f), kind = ButtonKind.Danger, small = true, pro = true)
                    }
                }
            }
        }
        fun layout(text: String): TextLayoutResult {
            val node = rule.onNode(hasText(text) and hasAnyAncestor(hasContentDescription("Delete… · Pro")), useUnmergedTree = true).fetchSemanticsNode()
            return mutableListOf<TextLayoutResult>().also { node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(it) }.first()
        }
        val label = layout("Delete…")
        val tag = layout("Pro")
        assertEquals("the label broke across lines", 1, label.lineCount)
        // Not `hasVisualOverflow`: for a plain Text the semantics action rebuilds the result with the node's whole room as the paragraph width, so it
        // says "overflow" whenever the word is narrower than its room (it failed here twice on the emulator with the tag drawn whole). The real check
        // is one line whose laid-out width holds the word.
        assertEquals("the tag wrapped", 1, tag.lineCount)
        val word = tag.getLineRight(0) - tag.getLineLeft(0)
        assertTrue("the tag is clipped: ${tag.size.width} px laid out for a $word px word", tag.size.width + 0.5f >= word)
        // Whole, on a line of its own under the label, and inside the button: not squeezed beside the label, not pushed out of the button.
        fun bounds(text: String) = rule.onNode(hasText(text) and hasAnyAncestor(hasContentDescription("Delete… · Pro")), useUnmergedTree = true).getUnclippedBoundsInRoot()
        val button = rule.onNode(hasContentDescription("Delete… · Pro")).getUnclippedBoundsInRoot()
        val under = bounds("Pro")
        assertTrue("the tag is under the label: label ${bounds("Delete…")}, tag $under", under.top >= bounds("Delete…").bottom)
        assertTrue("the tag is inside the button: button $button, tag $under", under.left >= button.left && under.right <= button.right && under.bottom <= button.bottom)
    }

    @Test fun lightThemeRendersAllFourKinds() {
        rule.setContent { PaddockTheme(darkTheme = false) { Sheet(small = false, dense = false) } }
        for (k in ButtonKind.entries) rule.onNodeWithText(k.name).assertExists()
        shoot("buttons-light")
    }
}
