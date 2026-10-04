package io.github.tuthan.paddock.ui.theme

import androidx.compose.ui.graphics.Color
import io.github.tuthan.paddock.output.AnsiColor

/**
 * The sixteen ANSI colours as the theme draws them on the output slab. "Black" and "bright black" are meaningful in
 * agent output (Claude Code and Codex write real lines in them), so both map to `dim`, never `faint`. Every entry has
 * at least 4.5:1 against the slab of its theme; `AnsiPaletteTest` checks it.
 */
object AnsiPalette {
    private val dark: Map<AnsiColor, Color> = mapOf(
        AnsiColor.Black to PaddockDarkColors.dim,
        AnsiColor.Red to Color(0xFFF7768E),
        AnsiColor.Green to Color(0xFF9ECE6A),
        AnsiColor.Yellow to Color(0xFFE0AF68),
        AnsiColor.Blue to Color(0xFF7AA2F7),
        AnsiColor.Magenta to Color(0xFFBB9AF7),
        AnsiColor.Cyan to Color(0xFF7DCFFF),
        AnsiColor.White to PaddockDarkColors.text,
        AnsiColor.BrightBlack to PaddockDarkColors.dim,
        AnsiColor.BrightRed to Color(0xFFFF8FA3),
        AnsiColor.BrightGreen to Color(0xFFB9F27C),
        AnsiColor.BrightYellow to Color(0xFFFFC777),
        AnsiColor.BrightBlue to Color(0xFF9DB8FF),
        AnsiColor.BrightMagenta to Color(0xFFD0B0FF),
        AnsiColor.BrightCyan to Color(0xFFA4DAFF),
        AnsiColor.BrightWhite to PaddockDarkColors.title,
    )

    /** The light terminal palette, from the light tokens (measured in Phase 10, docs/contrast.md); the contrast floor is pinned by `AnsiPaletteTest`. */
    private val light: Map<AnsiColor, Color> = mapOf(
        AnsiColor.Black to PaddockLightColors.dim,
        AnsiColor.Red to Color(0xFF8C4351),
        AnsiColor.Green to Color(0xFF485E30),
        AnsiColor.Yellow to Color(0xFF8F5E15),
        AnsiColor.Blue to Color(0xFF34548A),
        AnsiColor.Magenta to Color(0xFF6B3FA0),
        AnsiColor.Cyan to Color(0xFF0B5C7A),
        AnsiColor.White to PaddockLightColors.text,
        AnsiColor.BrightBlack to PaddockLightColors.dim,
        AnsiColor.BrightRed to Color(0xFFA8324A),
        AnsiColor.BrightGreen to Color(0xFF2F6B14),
        AnsiColor.BrightYellow to Color(0xFF7A5200),
        AnsiColor.BrightBlue to Color(0xFF1E4AA8),
        AnsiColor.BrightMagenta to Color(0xFF5B2A9E),
        AnsiColor.BrightCyan to Color(0xFF05607A),
        AnsiColor.BrightWhite to PaddockLightColors.title,
    )

    fun table(isDark: Boolean): Map<AnsiColor, Color> = if (isDark) dark else light

    fun color(colors: PaddockColors, color: AnsiColor?): Color = if (color == null) colors.text else table(colors.isDark).getValue(color)
}
