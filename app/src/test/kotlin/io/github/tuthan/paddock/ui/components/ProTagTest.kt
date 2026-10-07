package io.github.tuthan.paddock.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import io.github.tuthan.paddock.ui.theme.PaddockColors
import io.github.tuthan.paddock.ui.theme.PaddockDarkColors
import io.github.tuthan.paddock.ui.theme.PaddockLightColors
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A locked button (`PaddockButton(pro = true)`, decision D6): what TalkBack is given for it, and that the "Pro" tag's word can be read on every
 * kind in both palettes. The tag's pill is drawn over the button's own fill, which for Ghost and Danger is whatever the button sits on, so the
 * word is measured over each of those ([bases]). The semantics on screen (the merged node, a caller's own description winning) are instrumented
 * (`ProGateTest`), since this module has no Robolectric.
 */
class ProTagTest {
    @Test fun aLockedControlIsReadAsItsLabelThenPro() {
        assertEquals("Pro", PRO_TAG)
        assertEquals("Stop… · Pro", proSpoken("Stop…"))
        assertEquals("Start an agent… · Pro", proSpoken("Start an agent…"))
    }

    /**
     * Where the tag goes, from the widths each part measures with the whole button's room (`LockedLabel`). The numbers are the half-width Delete… of
     * `ButtonStyleTest` at 130 % in dp (IBM Plex Sans SemiBold's advances): 122 of room, lock and label 95, tag 38. Measured into what the label
     * leaves (19), the tag would always "fit" and be clipped; at its own width it goes under.
     */
    @Test fun theTagGoesBesideTheLabelOnlyAtItsOwnFullWidth() {
        assertTrue("room for all of it", proTagBeside(labelWidth = 60, tagWidth = 38, gap = 8, maxWidth = 122))
        assertTrue("exactly enough room is room", proTagBeside(labelWidth = 76, tagWidth = 38, gap = 8, maxWidth = 122))
        assertTrue("half-width Delete… at 130 %: under", !proTagBeside(labelWidth = 95, tagWidth = 38, gap = 8, maxWidth = 122))
        assertTrue("the gap counts", !proTagBeside(labelWidth = 77, tagWidth = 38, gap = 8, maxWidth = 122))
        assertTrue("an unbounded row never wraps it", proTagBeside(labelWidth = 95, tagWidth = 38, gap = 8, maxWidth = Int.MAX_VALUE))
    }

    @Test fun aTextRowSaysProTheSameWayAButtonIsRead() {
        assertEquals(proSpoken("Guarded answers"), io.github.tuthan.paddock.ui.screens.proLabel("Guarded answers", locked = true))
        assertEquals("Guarded answers", io.github.tuthan.paddock.ui.screens.proLabel("Guarded answers", locked = false))
    }

    private fun ratio(a: Color, b: Color): Double {
        val l1 = a.luminance().toDouble(); val l2 = b.luminance().toDouble()
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    /**
     * What a button of [kind] can sit on: the ground and a card or dialog's surface; a Ghost also on a banner's 12% wash, because a banner's action
     * is a Ghost tinted like the banner (`Banner`). No other kind is drawn on a banner. Measured on 2026-10-06, a Danger tag's word on a banner wash
     * would fall to 3.9:1, so a Danger button moved onto a banner needs this test widened and its tag looked at again.
     */
    private fun bases(kind: ButtonKind, c: PaddockColors) = listOf(c.ground, c.surface) + if (kind != ButtonKind.Ghost) emptyList() else
        listOf(c.needsYou, c.done, c.attention, c.accent).flatMap { h -> listOf(h.copy(alpha = 0.12f).compositeOver(c.ground), h.copy(alpha = 0.12f).compositeOver(c.surface)) }

    @Test fun theTagsWordIsAtLeastFourPointFiveOnEveryKindInBothPalettes() {
        val failures = mutableListOf<String>()
        for ((name, c) in listOf("dark" to PaddockDarkColors, "light" to PaddockLightColors)) {
            val tints = listOf<Color?>(null, c.attention, c.needsYou, c.accent, c.done)
            for (kind in ButtonKind.entries) for (tint in (if (kind == ButtonKind.Ghost) tints else listOf(null))) {
                val button = buttonColors(kind, c, tint)
                val tag = proTagColors(kind, c, tint)
                for (base in bases(kind, c)) {
                    val under = tag.fill.compositeOver(button.fill.compositeOver(base))
                    val r = ratio(tag.text, under)
                    if (r < 4.5) failures += "$name $kind tint=$tint on $base: ${String.format(Locale.ROOT, "%.2f", r)}:1"
                }
            }
        }
        assertTrue("the Pro tag's word is under 4.5:1: $failures", failures.isEmpty())
    }

    @Test fun aLockedDangerButtonKeepsItsOwnColourOnTheTag() {
        for (c in listOf(PaddockDarkColors, PaddockLightColors)) {
            assertEquals(c.needsYou, proTagColors(ButtonKind.Danger, c).text)
            assertEquals(c.ground, proTagColors(ButtonKind.Primary, c).text)
            assertEquals(c.title, proTagColors(ButtonKind.Secondary, c).text)
            assertEquals(c.accent.copy(alpha = 0.14f), proTagColors(ButtonKind.Secondary, c).fill)
        }
    }
}
