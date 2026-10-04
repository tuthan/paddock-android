package io.github.tuthan.paddock.widget

import androidx.compose.ui.graphics.Color
import io.github.tuthan.paddock.ui.theme.PaddockColors
import io.github.tuthan.paddock.ui.theme.PaddockDarkColors
import io.github.tuthan.paddock.ui.theme.PaddockLightColors
import java.io.File
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A widget draws with RemoteViews, which cannot read `PaddockTokens`, so its colours are resources. This keeps them equal to the tokens
 * `docs/contrast.md` measures: change a token without the resource (or the reverse) and the build fails.
 */
class WidgetColorsTest {
    private val res = File("src/main/res")

    private fun colors(dir: String): Map<String, String> =
        Regex("""<color name="([a-z_]+)">(#[0-9A-Fa-f]{6,8})</color>""").findAll(File(res, "$dir/colors.xml").readText()).associate { it.groupValues[1] to it.groupValues[2].uppercase() }

    private fun hex(c: Color): String {
        val a = (c.alpha * 255).roundToInt(); val r = (c.red * 255).roundToInt(); val g = (c.green * 255).roundToInt(); val b = (c.blue * 255).roundToInt()
        return if (a == 255) "#%02X%02X%02X".format(r, g, b) else "#%02X%02X%02X%02X".format(a, r, g, b)
    }

    private fun check(name: String, p: PaddockColors, found: Map<String, String>) {
        val want = mapOf(
            "widget_surface" to p.surface, "widget_edge" to p.line(), "widget_title" to p.title, "widget_text" to p.text, "widget_dim" to p.dim,
            "widget_accent" to p.accent, "widget_accent_edge" to p.accent.copy(alpha = 0.35f), "widget_needs_you" to p.needsYou, "window_ground" to p.ground,
        )
        for ((k, v) in want) assertEquals("$name $k", hex(v), found[k])
    }

    @Test fun lightWidgetColoursAreTheLightTokens() = check("light", PaddockLightColors, colors("values"))
    @Test fun nightWidgetColoursAreTheDarkTokens() = check("dark", PaddockDarkColors, colors("values-night"))

    @Test fun everyWidgetColourExistsInBothThemes() {
        val light = colors("values").keys.filter { it.startsWith("widget_") }.toSet()
        val dark = colors("values-night").keys.filter { it.startsWith("widget_") }.toSet()
        assertEquals(light, dark)
        assertTrue(light.isNotEmpty())
    }

    @Test fun layoutsUseOnlyTheWidgetColoursSoNoThemeIsForgotten() {
        val offenders = File(res, "layout").listFiles().orEmpty().flatMap { f ->
            Regex("""@color/([a-z_]+)""").findAll(f.readText()).map { it.groupValues[1] to f.name }.filter { !it.first.startsWith("widget_") }.toList()
        }
        assertEquals(emptyList<Pair<String, String>>(), offenders)
        val literals = File(res, "layout").listFiles().orEmpty().filter { Regex("""#[0-9A-Fa-f]{6}""").containsMatchIn(it.readText()) }.map { it.name }
        assertEquals("a colour literal in a widget layout would ignore the theme: $literals", emptyList<String>(), literals)
    }
}
