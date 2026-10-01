package io.github.tuthan.paddock.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

internal val LocalPaddockColors = staticCompositionLocalOf { PaddockDarkColors }

/** Follows the system setting. Dark is the reviewed palette; light is a placeholder until Phase 10. */
@Composable
fun PaddockTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (darkTheme) PaddockDarkColors else PaddockLightColors
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = c.accent, onPrimary = c.ground, background = c.ground, onBackground = c.text,
            surface = c.surface, onSurface = c.text, surfaceVariant = c.field, onSurfaceVariant = c.dim,
            error = c.needsYou, onError = c.ground, outline = c.faint, outlineVariant = c.line(),
        )
    } else {
        lightColorScheme(
            primary = c.accent, onPrimary = c.surface, background = c.ground, onBackground = c.text,
            surface = c.surface, onSurface = c.text, surfaceVariant = c.field, onSurfaceVariant = c.dim,
            error = c.needsYou, onError = c.surface, outline = c.faint, outlineVariant = c.line(),
        )
    }
    CompositionLocalProvider(LocalPaddockColors provides c) {
        MaterialTheme(colorScheme = scheme) {
            // The theme paints its own ground and sets the content colour; it never relies on the window background.
            Surface(color = c.ground, contentColor = c.text, content = content)
        }
    }
}
