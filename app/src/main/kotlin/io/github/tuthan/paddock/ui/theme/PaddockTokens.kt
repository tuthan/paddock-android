package io.github.tuthan.paddock.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.tuthan.paddock.R

/** Semantic colours from the UX brainstorm. `line` and the washes are alpha derivations, kept as functions. */
data class PaddockColors(
    val ground: Color,
    val surface: Color,
    val field: Color,
    val slab: Color,
    val title: Color,
    val text: Color,
    val dim: Color,
    /** Decorative only: ready dots and ornamental rules. Never meaningful text. */
    val faint: Color,
    val accent: Color,
    val needsYou: Color,
    val done: Color,
    val attention: Color,
    /** A switch's off track and the slab's quiet chrome. */
    val track: Color,
    val isDark: Boolean,
) {
    /** Hairlines: 8% for rows and cards, 12% for chips and controls, 14% for fields and dialogs, 16% when focused. */
    fun line(focused: Boolean = false): Color = text.copy(alpha = if (focused) 0.16f else 0.08f)
    fun control(): Color = text.copy(alpha = 0.12f)
    fun fieldLine(): Color = text.copy(alpha = 0.14f)
    /** A state's row wash: colour only where attention is, at 9% with a 30% edge (35% for the cooler hues). */
    fun wash(state: Color): Color = state.copy(alpha = 0.09f)
    fun washBorder(state: Color): Color = state.copy(alpha = if (state == accent || state == attention) 0.35f else 0.30f)
    /** A host banner is a little louder than a row: 12% with a 40% edge. */
    fun bannerWash(state: Color): Color = state.copy(alpha = 0.12f)
    fun bannerBorder(state: Color): Color = state.copy(alpha = 0.40f)
}

/** Dark is complete (tokyo-night seed); light is reviewed second. */
val PaddockDarkColors = PaddockColors(
    ground = Color(0xFF16161E),
    surface = Color(0xFF1A1B26),
    field = Color(0xFF292E42),
    slab = Color(0xFF0F0F14),
    title = Color(0xFFE2E6FB),
    text = Color(0xFFC0CAF5),
    dim = Color(0xFF8B95C0),
    faint = Color(0xFF565F89),
    accent = Color(0xFF7AA2F7),
    needsYou = Color(0xFFF7768E),
    done = Color(0xFF9ECE6A),
    attention = Color(0xFFE0AF68),
    track = Color(0xFF414868),
    isDark = true,
)

/** PLACEHOLDER VALUES, flagged for Phase 10: hues follow the dark set but no contrast review has been done. */
val PaddockLightColors = PaddockColors(
    ground = Color(0xFFE6E7ED),
    surface = Color(0xFFFFFFFF),
    field = Color(0xFFD5D6DB),
    slab = Color(0xFFF1F2F6),
    title = Color(0xFF1A1B26),
    text = Color(0xFF343B58),
    dim = Color(0xFF5A607F),
    faint = Color(0xFF9699A3),
    accent = Color(0xFF34548A),
    needsYou = Color(0xFF8C4351),
    done = Color(0xFF485E30),
    attention = Color(0xFF8F5E15),
    track = Color(0xFFB4B8CC),
    isDark = false,
)

/**
 * Type roles, as the design language sets them (size / weight / line height). IBM Plex Sans and JetBrains Mono are
 * specified (OFL, bundled in the APK) but not yet shipped, so the system families stand in until the font files are
 * reviewed and added; the roles do not change when they are. Numbers in mono use tabular figures so ages and times
 * do not jitter as they tick.
 */
data class PaddockType(
    /** 22 / 600: a screen's name. */
    val screenTitle: TextStyle,
    /** 17 / 600: an agent's name at the top of its screen, a dialog's title. */
    val headerTitle: TextStyle,
    /** 14 / 400, dim: the herd summary under the title. */
    val summary: TextStyle,
    /** 15 / 500. */
    val rowTitle: TextStyle,
    /** 15 / 400. */
    val body: TextStyle,
    /** 13 / 400, dim. */
    val secondary: TextStyle,
    /** 12 / 400, dim: the small explanatory notes ("facts") under a control. */
    val note: TextStyle,
    /** 11 / 600, tracked: section labels. Callers uppercase the words, never the facts inside them. */
    val kicker: TextStyle,
    /** 11 / 600, tracked a little less: a state as a word. */
    val stateWord: TextStyle,
    /** 15 / 600 and 14 / 600 for a small button. */
    val button: TextStyle,
    val buttonSmall: TextStyle,
    /** 13 / 400: chips. 11 / 500: the navigation bar. */
    val chip: TextStyle,
    val nav: TextStyle,
    /** 12 mono: ids, paths, fingerprints, times. */
    val monoFact: TextStyle,
    /** 12 mono at 1.55: terminal output on the slab. */
    val slab: TextStyle,
)

/**
 * IBM Plex Sans for text and JetBrains Mono for facts, bundled unmodified in `res/font` (docs/fonts.md). A weight that is not
 * bundled is matched to the nearest one that is; nothing is synthesised for the weights the tokens use.
 */
object PaddockFonts {
    /**
     * Tabular figures, and the programming ligatures off: JetBrains Mono draws `//`, `++`, `->`, `!=` and `==` as joined shapes by
     * default, and a fingerprint, a path or a command must show exactly the characters that are in it.
     */
    const val MONO_FEATURES = "tnum, calt 0, liga 0"

    val sans = FontFamily(
        Font(R.font.ibm_plex_sans_regular, FontWeight.Normal),
        Font(R.font.ibm_plex_sans_medium, FontWeight.Medium),
        Font(R.font.ibm_plex_sans_semibold, FontWeight.SemiBold),
    )
    val mono = FontFamily(
        Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
        Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    )
}

private val centered = LineHeightStyle(alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.None)
private fun sans(size: Int, weight: FontWeight, lineHeight: Double, tracking: Double = 0.0) = TextStyle(
    fontFamily = PaddockFonts.sans, fontWeight = weight, fontSize = size.sp, lineHeight = (size * lineHeight).sp,
    letterSpacing = tracking.em, lineHeightStyle = centered,
)
private fun mono(size: Double, lineHeight: Double) = TextStyle(
    fontFamily = PaddockFonts.mono, fontWeight = FontWeight.Normal, fontSize = size.sp, lineHeight = (size * lineHeight).sp,
    fontFeatureSettings = PaddockFonts.MONO_FEATURES, lineHeightStyle = centered,
)

val PaddockTypeTokens = PaddockType(
    screenTitle = sans(22, FontWeight.SemiBold, 1.2, -0.01),
    headerTitle = sans(17, FontWeight.SemiBold, 1.3),
    summary = sans(14, FontWeight.Normal, 1.4),
    rowTitle = sans(15, FontWeight.Medium, 1.3),
    body = sans(15, FontWeight.Normal, 1.4),
    secondary = sans(13, FontWeight.Normal, 1.35),
    note = sans(12, FontWeight.Normal, 1.45),
    kicker = sans(11, FontWeight.SemiBold, 1.3, 0.1),
    stateWord = sans(11, FontWeight.SemiBold, 1.3, 0.08),
    button = sans(15, FontWeight.SemiBold, 1.2),
    buttonSmall = sans(14, FontWeight.SemiBold, 1.2),
    chip = sans(13, FontWeight.Normal, 1.3),
    nav = sans(11, FontWeight.Medium, 1.3),
    monoFact = mono(12.0, 1.45),
    slab = mono(12.0, 1.55),
)

data class PaddockRadii(
    val row: Dp = 12.dp,
    val card: Dp = 14.dp,
    val dialog: Dp = 16.dp,
    val slab: Dp = 10.dp,
    /** An icon button's pressed shape. */
    val iconButton: Dp = 12.dp,
    /** Applied to the top corners of a bottom sheet. */
    val sheet: Dp = 20.dp,
    val button: Dp = 10.dp,
    val field: Dp = 10.dp,
    val key: Dp = 8.dp,
)

/** 4 dp grid. */
data class PaddockSpacing(
    val unit: Dp = 4.dp,
    val gutter: Dp = 16.dp,
    val rowGap: Dp = 10.dp,
    val cardPadding: Dp = 14.dp,
    val kickerGap: Dp = 6.dp,
    /** Minimum touch target in both dimensions. */
    val touchTarget: Dp = 48.dp,
)

/** Single entry point for the token set; [PaddockTheme] provides the active colours. */
object PaddockTokens {
    val colors: PaddockColors
        @androidx.compose.runtime.Composable
        @androidx.compose.runtime.ReadOnlyComposable
        get() = LocalPaddockColors.current
    val type: PaddockType = PaddockTypeTokens
    val radii: PaddockRadii = PaddockRadii()
    val spacing: PaddockSpacing = PaddockSpacing()
}
