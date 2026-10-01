package io.github.tuthan.paddock.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

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
    val isDark: Boolean,
) {
    fun line(focused: Boolean = false): Color = text.copy(alpha = if (focused) 0.16f else 0.08f)
    fun wash(state: Color): Color = state.copy(alpha = 0.09f)
    fun washBorder(state: Color): Color = state.copy(alpha = 0.30f)
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
    isDark = false,
)

/**
 * Type roles. IBM Plex Sans and JetBrains Mono are specified (OFL, bundled in the APK) but not yet
 * shipped, so the system families stand in until the font files are reviewed and added.
 */
data class PaddockType(
    val screenTitle: TextStyle,
    val rowTitle: TextStyle,
    val body: TextStyle,
    val secondary: TextStyle,
    val kicker: TextStyle,
    val monoFact: TextStyle,
)

val PaddockTypeTokens = PaddockType(
    screenTitle = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
    rowTitle = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 15.sp),
    body = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 15.sp),
    secondary = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 13.sp),
    kicker = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.1.em),
    monoFact = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Normal, fontSize = 12.sp),
)

data class PaddockRadii(
    val row: Dp = 12.dp,
    val card: Dp = 14.dp,
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
