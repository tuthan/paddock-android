package io.github.tuthan.paddock.ui.theme

import androidx.compose.ui.graphics.vector.ImageVector
import io.github.tuthan.paddock.attention.AgentGlyph

/**
 * One original line glyph for each of the ten common agents (Phase 12), in the same 24-unit grid and 1.8 stroke as
 * [PaddockIcons] and built by the same code, so there is no icon library. Each is an idea (a cursor dot in an open C, code
 * brackets, an arrow, the letter pi), never a vendor's mark, and none uses colour: the tile tints them like its letters.
 * The reference sheet is `assets/icon/agent-marks.html` in the docs vault.
 */
object PaddockAgentGlyphs {
    private val icons: Map<AgentGlyph, ImageVector> = mapOf(
        AgentGlyph.Claude to PaddockIcons.icon("agent-claude", "M18.3 7.1A8 8 0 1 0 18.3 16.9", PaddockIcons.circle(13.2f, 12f, 1f)),
        AgentGlyph.Codex to PaddockIcons.icon("agent-codex", "M8 8l-4 4 4 4", "M16 8l4 4-4 4", "M13.5 6l-3 12"),
        AgentGlyph.OpenCode to PaddockIcons.icon("agent-opencode", "M19.8 10V7.5L12 3 4.2 7.5v9L12 21l7.8-4.5V14", "M8.5 12h8"),
        AgentGlyph.Gemini to PaddockIcons.icon("agent-gemini", PaddockIcons.circle(9f, 12f, 5.5f), PaddockIcons.circle(15f, 12f, 5.5f)),
        AgentGlyph.Copilot to PaddockIcons.icon("agent-copilot", PaddockIcons.circle(12f, 12f, 9f), "M15.5 8.5l-2 5-5 2 2-5z"),
        AgentGlyph.Cursor to PaddockIcons.icon("agent-cursor", "M5 4l14 6-6 2-2 6.5z"),
        AgentGlyph.Pi to PaddockIcons.icon("agent-pi", "M5 7h14", "M9 7v11", "M15 7v9.5c0 1 .6 1.5 1.5 1.5"),
        AgentGlyph.Amp to PaddockIcons.icon("agent-amp", "M6 10v4", "M10 6v12", "M14 9v6", "M18 7v10"),
        AgentGlyph.Hermes to PaddockIcons.icon(
            "agent-hermes", "M12 4v16", "M12 8c-2.5 0-5-.8-7-3", "M12 8c2.5 0 5-.8 7-3", "M12 12c-2 0-4-.6-5.5-2.2", "M12 12c2 0 4-.6 5.5-2.2",
        ),
        AgentGlyph.Shell to PaddockIcons.icon("agent-shell", "M6 8l4 4-4 4", "M12 17h6"),
    )

    fun of(glyph: AgentGlyph): ImageVector = icons.getValue(glyph)
}
