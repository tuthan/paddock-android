package io.github.tuthan.paddock.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contrast of every colour pair the app draws, for both palettes (AC-10.2): text at 4.5:1 or more, and the things that are not text (state
 * dots and icons, the switch's thumb) at 3:1 or more. `docs/contrast.md` holds the same table, generated here, and the test fails when the file
 * and the tokens disagree, so the table cannot go stale. The ratio is WCAG 2's, with Compose's own sRGB luminance; a translucent wash is
 * composited over the ground and over a card's surface and the worse of the two is taken.
 */
class PaletteContrastTest {
    private class Palette(val name: String, val c: PaddockColors)

    private val palettes = listOf(Palette("Dark (the reviewed default)", PaddockDarkColors), Palette("Light", PaddockLightColors))

    private fun ratio(a: Color, b: Color): Double {
        val l1 = a.luminance().toDouble(); val l2 = b.luminance().toDouble()
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }
    private fun over(fg: Color, alpha: Float, bg: Color) = fg.copy(alpha = alpha).compositeOver(bg)

    /** A background the text can sit on. A wash is a list: the same wash over each base it can be drawn on, and the worst ratio counts. */
    private class Background(val label: String, val of: (PaddockColors) -> List<Color>)

    private val textBackgrounds = listOf(
        Background("ground") { listOf(it.ground) },
        Background("surface") { listOf(it.surface) },
        Background("field") { listOf(it.field) },
        Background("slab") { listOf(it.slab) },
        // Row washes: 9% of the state's hue; the rows sit on the ground.
        Background("needs-you row") { listOf(over(it.needsYou, 0.09f, it.ground), over(it.needsYou, 0.09f, it.surface)) },
        Background("done row") { listOf(over(it.done, 0.09f, it.ground), over(it.done, 0.09f, it.surface)) },
        // Banners: 12% of any hue (a banner is tinted by its own state), selected chips 10% accent.
        Background("banner, any hue") { c -> listOf(c.needsYou, c.done, c.attention, c.accent).flatMap { h -> listOf(over(h, 0.12f, c.ground), over(h, 0.12f, c.surface)) } + over(c.accent, 0.10f, c.surface) },
    )

    private class Text(val label: String, val of: (PaddockColors) -> Color, val use: String)
    private val texts = listOf(
        Text("title", { it.title }, "headings, row titles"),
        Text("text", { it.text }, "body, terminal and facts"),
        Text("dim", { it.dim }, "summaries, notes, placeholders"),
        Text("accent", { it.accent }, "Ghost buttons, links, selected labels"),
        Text("needs-you", { it.needsYou }, "Danger buttons, errors, blocked words"),
        Text("done", { it.done }, "done words"),
        Text("attention", { it.attention }, "warnings and the Replaced banner"),
    )

    private fun worst(fg: Color, bg: Background, c: PaddockColors) = bg.of(c).minOf { ratio(fg, it) }

    /** Things that are not text: a state's dot or icon on the ground and on a card, the switch thumb on its track, the Primary button's label on its fill. */
    private class NonText(val label: String, val ratios: (PaddockColors) -> List<Double>)
    private val nonText = listOf(
        NonText("accent dot or icon, on ground and surface") { c -> listOf(ratio(c.accent, c.ground), ratio(c.accent, c.surface)) },
        NonText("needs-you dot or icon, on ground and surface") { c -> listOf(ratio(c.needsYou, c.ground), ratio(c.needsYou, c.surface)) },
        NonText("done dot or icon, on ground and surface") { c -> listOf(ratio(c.done, c.ground), ratio(c.done, c.surface)) },
        NonText("attention dot or icon, on ground and surface") { c -> listOf(ratio(c.attention, c.ground), ratio(c.attention, c.surface)) },
        NonText("switch thumb (off) on its track") { c -> listOf(ratio(c.text, c.track)) },
        NonText("switch thumb (on) on the accent track") { c -> listOf(ratio(c.ground, c.accent)) },
        NonText("focus ring (text colour) on ground and surface") { c -> listOf(ratio(c.text, c.ground), ratio(c.text, c.surface)) },
    )

    /** The Primary button's label (`ground`) on its fill (`accent`) is text, so it is held to 4.5. */
    private fun primary(c: PaddockColors) = ratio(c.ground, c.accent)

    private fun f(x: Double) = String.format(Locale.ROOT, "%.2f", x)

    private fun table(): String = buildString {
        for (p in palettes) {
            appendLine("### ${p.name}")
            appendLine()
            appendLine("Text, WCAG ratio (4.5 or more is a pass); the last column is the lowest in the row.")
            appendLine()
            appendLine("| Text colour | " + textBackgrounds.joinToString(" | ") { it.label } + " | Lowest | Used for |")
            appendLine("| --- | " + textBackgrounds.joinToString(" | ") { "---" } + " | --- | --- |")
            for (t in texts) {
                val fg = t.of(p.c)
                val cells = textBackgrounds.map { worst(fg, it, p.c) }
                appendLine("| ${t.label} | " + cells.joinToString(" | ") { f(it) + if (it < 4.5) " FAIL" else "" } + " | **${f(cells.min())}** | ${t.use} |")
            }
            appendLine()
            appendLine("Primary button label (ground on the accent fill): **${f(primary(p.c))}**.")
            appendLine()
            appendLine("Not text (3 or more is a pass):")
            appendLine()
            appendLine("| Pair | Lowest |")
            appendLine("| --- | --- |")
            for (n in nonText) { val r = n.ratios(p.c).min(); appendLine("| ${n.label} | ${f(r)}${if (r < 3.0) " FAIL" else ""} |") }
            appendLine()
        }
    }.trimEnd()

    @Test fun everyTextPairIsAtLeastFourPointFive() {
        for (p in palettes) for (t in texts) for (b in textBackgrounds) {
            val r = worst(t.of(p.c), b, p.c)
            assertTrue("${p.name}: ${t.label} on ${b.label} is ${f(r)}:1, under 4.5:1", r >= 4.5)
        }
        for (p in palettes) assertTrue("${p.name}: Primary label is ${f(primary(p.c))}:1", primary(p.c) >= 4.5)
    }

    @Test fun everyNonTextPairIsAtLeastThree() {
        for (p in palettes) for (n in nonText) {
            val r = n.ratios(p.c).min()
            assertTrue("${p.name}: ${n.label} is ${f(r)}:1, under 3:1", r >= 3.0)
        }
    }

    @Test fun faintIsNeverUsedAsText() {
        // `faint` is below 4.5:1 on purpose (chevrons, ornamental rules); this pins that nothing draws text with it.
        val offenders = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { Regex("""color\s*=\s*(c|colors|PaddockTokens\.colors)\.faint""").containsMatchIn(it.readText()) }.map { it.name }.toList()
        assertEquals("faint drawn as text in: $offenders", emptyList<String>(), offenders)
    }

    @Test fun theCheckedInTableMatchesTheTokens() {
        val doc = File("../docs/contrast.md")
        assertTrue("docs/contrast.md is missing", doc.exists())
        val text = doc.readText()
        val start = text.indexOf(START); val end = text.indexOf(END)
        assertTrue("docs/contrast.md lacks the $START and $END markers", start >= 0 && end > start)
        val checkedIn = text.substring(start + START.length, end).trim()
        val generated = table()
        if (checkedIn != generated) {
            File("build").mkdirs()
            File("build/contrast-table.generated.md").writeText(generated + "\n")
            throw AssertionError("docs/contrast.md is out of date. The table the tokens give is in app/build/contrast-table.generated.md: paste it between the markers.")
        }
    }

    private companion object {
        const val START = "<!-- contrast-table:start -->"
        const val END = "<!-- contrast-table:end -->"
    }
}
