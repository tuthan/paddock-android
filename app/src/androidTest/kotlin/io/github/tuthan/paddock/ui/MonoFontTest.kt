package io.github.tuthan.paddock.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.core.content.res.ResourcesCompat
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tuthan.paddock.R
import io.github.tuthan.paddock.ui.theme.PaddockTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bundled JetBrains Mono joins `//`, `++`, `->`, `!=` and `==` into ligatures unless told not to. Facts (fingerprints, paths,
 * commands) must show the characters that are in them, so the mono token turns them off. Drawn through Android's own text
 * stack, the way Compose draws, so it covers the platform's handling of the feature string on each API level.
 */
class MonoFontTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun paint(features: String?) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = ResourcesCompat.getFont(ctx, R.font.jetbrains_mono_regular); textSize = 60f
        if (features != null) fontFeatureSettings = features
    }
    private fun pixels(text: String, features: String?, apart: Boolean): IntArray {
        val p = paint(features); val advance = paint(null).measureText("-")
        val bmp = Bitmap.createBitmap(500, 120, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); c.drawColor(0xFFFFFFFF.toInt())
        if (apart) text.forEachIndexed { i, ch -> c.drawText(ch.toString(), 10f + i * advance, 80f, p) } else c.drawText(text, 10f, 80f, p)
        return IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
    }

    @Test fun theMonoTokenDrawsEachCharacterAloneNeverAsALigature() {
        val features = PaddockTokens.type.monoFact.fontFeatureSettings
        assertNotNull(features)
        for (text in listOf("->", "//", "++", "!=", "==")) {
            assertTrue("'$text' joined into a ligature with the mono token's features", pixels(text, features, apart = false).contentEquals(pixels(text, features, apart = true)))
        }
    }

    @Test fun theFontDoesJoinThemByDefaultSoTheTestAboveCanFail() {
        // Without the feature string at least these are drawn as ligatures; if this ever stops holding the guard above proves nothing.
        val joined = listOf("->", "//", "++", "!=", "==").count { !pixels(it, null, apart = false).contentEquals(pixels(it, null, apart = true)) }
        assertTrue("expected the default rendering to join ligatures, joined $joined", joined >= 3)
    }

    @Test fun theMonoTokenKeepsTabularFiguresAndTheSlabUsesTheSameFeatures() {
        assertEquals(PaddockTokens.type.monoFact.fontFeatureSettings, PaddockTokens.type.slab.fontFeatureSettings)
        assertTrue(PaddockTokens.type.monoFact.fontFeatureSettings!!.contains("tnum"))
        assertFalse(PaddockTokens.type.monoFact.fontFeatureSettings!!.contains("liga 1"))
    }
}
