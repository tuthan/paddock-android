package io.github.tuthan.paddock.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Paddock's own accessibility audit (AC-10.3). Google's `ui-test-junit4-accessibility` (`enableAccessibilityChecks()`) was declined on 2026-10-02
 * (docs/dependency-reviews.md), so this does what the project can with the platform's semantics API and the rendered pixels. It is **not** the
 * Accessibility Test Framework and does not claim its coverage; `docs/accessibility.md` lists what it checks and what it does not.
 *
 * Over the merged tree (what TalkBack walks): every actionable node has a spoken label; every actionable node's touch area is at least 48 x 48 dp
 * unless the caller exempts it (the terminal's dense mode, named in `docs/buttons.md`); no two actionable nodes share one rectangle; a switch,
 * checkbox, radio button or tab announces its state; the screen has a heading when asked. Over the unmerged tree and a screenshot of the same
 * window: the contrast of each text run as drawn, with the background taken as the most common pixel in the text's box and the foreground as the
 * pixel furthest from it (4.5:1, or 3:1 for text of 18 sp, or 14 sp bold, and larger). With [overflow]: no text that is cut off or ellipsized, except
 * where the caller says a row title may be.
 */
object SemanticsAudit {
    data class Finding(val rule: String, val node: String, val detail: String) { override fun toString() = "[$rule] $node: $detail" }
    class Result(val screen: String, val findings: List<Finding>, val contrast: List<String>)

    data class Options(
        val heading: Boolean = true,
        val overflow: Boolean = false,
        /** A node is exempt from the 48 dp rule when this says so (its label). */
        val smallTargetOk: (String) -> Boolean = { false },
        /** Text allowed to be ellipsized under [overflow] (its text). */
        val ellipsisOk: (String) -> Boolean = { false },
        val minTargetDp: Float = 48f,
        val contrast: Boolean = true,
        /** Regions that must keep a height whatever the font and the screen, by test tag or content description, in dp: the request on the decision sheet, the output. */
        val regions: Map<String, Float> = emptyMap(),
    )

    fun run(rule: ComposeContentTestRule, screen: String, options: Options = Options()): Result {
        val findings = mutableListOf<Finding>()
        val merged = rule.onAllNodes(isRoot()).fetchSemanticsNodes()
        var sawHeading = false
        val clickable = mutableListOf<Pair<SemanticsNode, String>>()
        fun walk(n: SemanticsNode, rootWidthDp: Float) {
            val c = n.config
            val label = labelOf(n)
            val actionable = c.contains(SemanticsActions.OnClick) || c.contains(SemanticsActions.OnLongClick) || c.getOrNull(SemanticsProperties.Role) in ACTIONABLE_ROLES
            if (c.contains(SemanticsProperties.Heading)) sawHeading = true
            val editable = c.contains(SemanticsProperties.EditableText)
            if (actionable && !editable) {
                val name = label.ifEmpty { "(no label) ${n.config.getOrNull(SemanticsProperties.TestTag).orEmpty()} @${n.boundsInRoot}" }
                if (label.isEmpty()) findings += Finding("label", name, "an actionable node has nothing for TalkBack to say")
                val b = outer(rule, n)
                val w = (b.right - b.left).value; val h = (b.bottom - b.top).value
                if ((w + 0.5f < options.minTargetDp || h + 0.5f < options.minTargetDp) && !options.smallTargetOk(label)) {
                    findings += Finding("target", name, "touch area ${w.roundToInt()} x ${h.roundToInt()} dp, under ${options.minTargetDp.toInt()} x ${options.minTargetDp.toInt()}")
                }
                clickable += n to name
            }
            if (options.overflow && (actionable || c.contains(SemanticsProperties.Text)) && !insideHorizontalScroll(n)) {
                val b = outer(rule, n)
                if (b.right.value > rootWidthDp + 1f || b.left.value < -1f) findings += Finding("offscreen", labelOf(n).take(60).ifEmpty { "(node)" }, "reaches x ${b.left.value.roundToInt()}..${b.right.value.roundToInt()} dp on a ${rootWidthDp.roundToInt()} dp wide screen, outside a horizontal scroller")
            }
            val key = c.getOrNull(SemanticsProperties.TestTag) ?: c.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
            options.regions[key]?.let { min ->
                // What the user can see of it: the clipped bounds, so a region squeezed to nothing by its siblings counts as nothing even if its content is tall.
                val h = n.boundsInRoot.height / rule.density.density
                if (h + 0.5f < min) findings += Finding("collapsed", key.orEmpty(), "${h.roundToInt()} dp of this region is visible, under the ${min.toInt()} dp it must keep")
            }
            val role = c.getOrNull(SemanticsProperties.Role)
            if (role == Role.Switch || role == Role.Checkbox) {
                if (!c.contains(SemanticsProperties.ToggleableState)) findings += Finding("state", labelOf(n).ifEmpty { "(switch)" }, "a ${role} that does not announce on or off")
            }
            if (role == Role.RadioButton || role == Role.Tab) {
                if (!c.contains(SemanticsProperties.Selected)) findings += Finding("state", labelOf(n).ifEmpty { "(radio or tab)" }, "a ${role} that does not announce selected")
            }
            n.children.forEach { walk(it, rootWidthDp) }
        }
        merged.forEach { walk(it, it.size.width / rule.density.density) }
        // Two actionable nodes on one rectangle read as the same control twice (or one of them is dead).
        clickable.groupBy { (n, _) -> outer(rule, n).let { listOf(it.left.value.roundToInt(), it.top.value.roundToInt(), it.right.value.roundToInt(), it.bottom.value.roundToInt()) } }.values.filter { it.size > 1 && it.map { p -> p.first.id }.distinct().size > 1 }
            .forEach { g -> findings += Finding("duplicate", g.joinToString(" | ") { it.second }, "${g.size} actionable nodes on the same rectangle") }
        if (options.heading && !sawHeading) findings += Finding("heading", screen, "no node is marked as a heading")

        val contrast = mutableListOf<String>()
        val unmerged = rule.onAllNodes(isRoot(), useUnmergedTree = true)
        val roots = unmerged.fetchSemanticsNodes()
        for ((i, root) in roots.withIndex()) {
            val bmp: Bitmap? = if (options.contrast) try { unmerged[i].captureToImage().asAndroidBitmap() } catch (e: Throwable) { contrast += "window $i: not captured (${e.javaClass.simpleName})"; null } else null
            fun walkText(n: SemanticsNode) {
                val c = n.config
                val text = c.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
                // An inactive component is exempt from the contrast rule (WCAG 1.4.3); its reason is a separate, enabled text and is measured.
                val inactive = generateSequence(n) { it.parent }.any { it.config.contains(SemanticsProperties.Disabled) }
                if (text.isNotBlank() && c.contains(SemanticsActions.GetTextLayoutResult)) {
                    val results = mutableListOf<TextLayoutResult>()
                    c[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
                    val layout = results.firstOrNull()
                    if (layout != null && options.overflow && cutOff(layout) && !options.ellipsisOk(text)) {
                        findings += Finding("overflow", text.take(60), "text is cut off or ellipsized at this size")
                    }
                    // Only text that is wholly on screen can be measured: a row below the fold is not in the screenshot.
                    if (bmp != null && !inactive && n.boundsInRoot.width > 1f && n.boundsInRoot.height > 1f && n.boundsInRoot.width >= unclipped(n).width - 1f && n.boundsInRoot.height >= unclipped(n).height - 1f) {
                        val style = layout?.layoutInput?.style
                        val sp = style?.fontSize?.takeIf { it.isSp }?.value ?: 14f
                        val bold = (style?.fontWeight?.weight ?: 400) >= 600
                        val needed = if (sp >= 18f || (bold && sp >= 14f)) 3.0 else 4.5
                        val m = measure(bmp, n.boundsInRoot)
                        if (m != null) {
                            contrast += "%.2f needs %.1f  %s".format(m, needed, text.take(40))
                            if (m < needed - TOLERANCE) findings += Finding("contrast", text.take(60), "%.2f:1 as drawn, needs %.1f".format(m, needed))
                        }
                    }
                }
                n.children.forEach(::walkText)
            }
            walkText(root)
        }
        return Result(screen, findings, contrast)
    }

    /** Fails with every finding listed, and writes what was measured next to the screenshots (`a11y-<screen>.txt`). */
    fun expectClean(rule: ComposeContentTestRule, screen: String, options: Options = Options()) {
        // `-e a11yLarge <font scale>` is how tools/run-a11y-large.sh asks every audit test to also check for cut-off text and off-screen controls, on a 360 x 640 dp screen at that font scale.
        val large = InstrumentationRegistry.getArguments().getString("a11yLarge")
        val opts = if (large != null) options.copy(overflow = true) else options
        val name = if (large != null) "$screen, font $large" else screen
        val r = run(rule, name, opts)
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "screens").apply { mkdirs() }
        File(dir, "a11y-${name.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-')}.txt").writeText(
            "screen: $name\nfindings: ${r.findings.size}\n" + r.findings.joinToString("") { "  $it\n" } + "contrast measured (${r.contrast.size} text runs):\n" + r.contrast.sorted().joinToString("") { "  $it\n" },
        )
        // The picture of what was audited, for the evidence: the dialog's window when one is up, else the activity's. Older APIs cannot capture a dialog; the audit still ran.
        try {
            val target = if (rule.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty()) rule.onNode(isDialog()) else rule.onRoot()
            File(dir, "a11y-${name.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-')}.png").outputStream().use { target.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (_: Throwable) { }
        if (r.findings.isNotEmpty()) throw AssertionError("accessibility audit of '$name' found ${r.findings.size}:\n" + r.findings.joinToString("\n") { "  $it" })
    }

    /** The node's rectangle as the user touches it, in dp, not clipped to the window: what the test API's `getUnclippedBoundsInRoot` says, which counts the padding and the minimum-size expansion that `layoutInfo.coordinates` leaves out. */
    private fun outer(rule: ComposeContentTestRule, n: SemanticsNode) =
        rule.onNode(SemanticsMatcher("node ${n.id}") { it.id == n.id }).getUnclippedBoundsInRoot()

    /** The node's layout rectangle in the root, not clipped to the window: a node below the fold has real bounds even when the window shows none of it. */
    private fun unclipped(n: SemanticsNode): androidx.compose.ui.geometry.Rect {
        val c = n.layoutInfo.coordinates
        val p = c.positionInRoot()
        return androidx.compose.ui.geometry.Rect(p.x, p.y, p.x + c.size.width, p.y + c.size.height)
    }

    /**
     * Text that is cut: an ellipsis on any line, or more lines than the height allows. (`hasVisualOverflow` is not used: a text laid out with the
     * window's full width as its constraint and then sized to its content reports overflow whenever it is not as wide as the window, and a centred line
     * sits right of its node's width without being cut.)
     */
    private fun cutOff(l: TextLayoutResult) = l.didOverflowHeight || (0 until l.lineCount).any { l.isLineEllipsized(it) }

    private fun insideHorizontalScroll(n: SemanticsNode) = generateSequence(n) { it.parent }.any { it.config.contains(SemanticsProperties.HorizontalScrollAxisRange) }

    /** What TalkBack would say for the node: its content description and its text, in that order, as the merged tree has them. */
    private fun labelOf(n: SemanticsNode): String {
        val c = n.config
        val d = c.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(", ").orEmpty()
        val t = c.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
        return listOf(d, t).filter { it.isNotBlank() }.joinToString(", ")
    }

    private val ACTIONABLE_ROLES = setOf(Role.Button, Role.Switch, Role.Checkbox, Role.RadioButton, Role.Tab)

    /** Slack for anti-aliasing: a thin glyph never reaches its full colour, so the measured ratio sits a little under the true one. */
    private const val TOLERANCE = 0.35

    private fun lin(v: Int): Double { val s = v / 255.0; return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4) }
    private fun lum(p: Int) = 0.2126 * lin(p shr 16 and 255) + 0.7152 * lin(p shr 8 and 255) + 0.0722 * lin(p and 255)
    private fun ratio(a: Int, b: Int): Double { val l1 = lum(a); val l2 = lum(b); return (max(l1, l2) + 0.05) / (min(l1, l2) + 0.05) }
    private fun dist(a: Int, b: Int): Int { val dr = (a shr 16 and 255) - (b shr 16 and 255); val dg = (a shr 8 and 255) - (b shr 8 and 255); val db = (a and 255) - (b and 255); return dr * dr + dg * dg + db * db }

    /** The contrast of the text drawn in [b] of [bmp]: background = the most common pixel, foreground = the mean of the pixels furthest from it. Null when nothing differs from the background. */
    private fun measure(bmp: Bitmap, b: androidx.compose.ui.geometry.Rect): Double? {
        val x0 = b.left.toInt().coerceIn(0, bmp.width - 1); val y0 = b.top.toInt().coerceIn(0, bmp.height - 1)
        val x1 = b.right.toInt().coerceIn(x0 + 1, bmp.width); val y1 = b.bottom.toInt().coerceIn(y0 + 1, bmp.height)
        val w = x1 - x0; val h = y1 - y0
        if (w < 2 || h < 2) return null
        val px = IntArray(w * h); bmp.getPixels(px, 0, w, x0, y0, w, h)
        val counts = HashMap<Int, Int>()
        for (p in px) { val k = p or (0xFF shl 24); counts[k] = (counts[k] ?: 0) + 1 }
        val bg = counts.maxByOrNull { it.value }!!.key
        var far = 0
        for (p in px) far = max(far, dist(p or (0xFF shl 24), bg))
        if (far < 30 * 30) return null
        var r = 0L; var g = 0L; var bl = 0L; var n = 0
        for (p in px) { val q = p or (0xFF shl 24); if (dist(q, bg) >= far * 0.81) { r += q shr 16 and 255; g += q shr 8 and 255; bl += q and 255; n++ } }
        val fg = (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (bl / n).toInt()
        return ratio(fg, bg)
    }
}
