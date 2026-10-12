package io.github.tuthan.paddock.files

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A name reduced to the allowlist, and whether the reduction changed it, so the screen can say "renamed". */
data class Reduced(val name: String, val changed: Boolean)

/** What a share may be called in the machine's drop folder (X10), or why it may not go there at all. */
sealed interface DropName {
    data class Accepted(val name: String, val changed: Boolean) : DropName

    /** [matched] is the entry of [FileNames.INSTRUCTION_NAMES] the name is, as listed there. */
    data class Refused(val matched: String) : DropName
}

/**
 * Every name Paddock gives a file is its own and comes from an allowlist (SEC-3, SEC-4, SEC-6): `[A-Za-z0-9._-]`, an
 * alphanumeric first, at most [MAX_NAME_CHARS], `file` when nothing is left. The sender's name, the claimed type and
 * the machine's name are hints for the person, never what the file is called. The host script holds the same rule
 * (`[A-Za-z0-9][A-Za-z0-9._-]{0,99}`) and refuses what breaks it, so a name from here always passes.
 */
object FileNames {
    const val MAX_NAME_CHARS = 100

    /**
     * Files an agent reads as instructions or configuration. A drop is a way to put a file on a machine where an agent
     * may read it, so none of these may arrive by it: a prompt-injected share could otherwise rewrite the agent's rules.
     */
    val INSTRUCTION_NAMES: Set<String> = linkedSetOf(
        "CLAUDE.md", "CLAUDE.local.md", "AGENTS.md", "GEMINI.md", "codex.md", ".mcp.json", "opencode.json", "settings.json",
    )

    private const val FALLBACK = "file"
    private const val MAX_EXTENSION_CHARS = 17
    private val STAMP = DateTimeFormatter.ofPattern("uuuu-MM-dd-HHmmss", Locale.ROOT)

    /** `2026-10-11-143205-screenshot.png`: the time [now] first (the caller picks the zone), then [original] reduced. */
    fun inboxName(now: LocalDateTime, original: String): String {
        val prefix = STAMP.format(now) + "-"
        return prefix + reduceTo(original, MAX_NAME_CHARS - prefix.length)
    }

    /** [original] reduced to the allowlist, and whether that changed it (the screen says so; the timestamp is not a change). */
    fun reduce(original: String): Reduced = reduceTo(original, MAX_NAME_CHARS).let { Reduced(it, it != original) }

    /** [name] with `-n` before its extension, for the n-th try when the name exists (n is 2 or more), still within the cap. */
    fun retryName(name: String, n: Int): String {
        require(n >= 2) { "the first try has no suffix" }
        val suffix = "-$n"
        val dot = extensionStart(name)
        val ext = name.substring(dot)
        return name.substring(0, dot).take(MAX_NAME_CHARS - ext.length - suffix.length) + suffix + ext
    }

    /** The name a shared file gets in the drop folder, or a refusal when it is an instruction or configuration file. */
    fun dropName(shared: String): DropName {
        val received = lastComponent(shared)
        val reduced = reduce(shared)
        // Both are compared: `.mcp.json` loses its dot in reduction and would pass as `mcp.json`, while `CLAUDE.md.` and
        // ` CLAUDE.md` only match once reduced.
        val hit = INSTRUCTION_NAMES.firstOrNull { it.equals(received, ignoreCase = true) }
            ?: INSTRUCTION_NAMES.firstOrNull { it.equals(reduced.name, ignoreCase = true) }
        return if (hit != null) DropName.Refused(hit) else DropName.Accepted(reduced.name, reduced.changed)
    }

    /** The name a file from the machine is cached under on the phone (SEC-6). The machine's own name is only displayed. */
    fun cachedName(machineName: String): String = reduceTo(machineName, MAX_NAME_CHARS)

    /**
     * Whether [name] is a plain name that lands directly inside [parentPath] when both are read as text: not empty, not
     * `.` or `..`, no separator and no NUL, and the folder itself absolute. The app still checks `canonicalFile`, which
     * follows links; this is the part that needs no file system.
     */
    fun containedIn(parentPath: String, name: String): Boolean {
        if (name.isEmpty() || name == "." || name == ".." || name.any { it == '/' || it == '\\' || it == '\u0000' }) return false
        val inside = PathTokens.resolve(parentPath, name) ?: return false
        val parent = PathTokens.collapse(parentPath) ?: return false
        return inside.substringBeforeLast('/').ifEmpty { "/" } == parent
    }

    private fun lastComponent(name: String) = name.substringAfterLast('/').substringAfterLast('\\')

    private fun reduceTo(original: String, max: Int): String {
        val kept = StringBuilder()
        var replaced = false
        for (cp in lastComponent(original).codePoints().toArray()) {
            if (cp < 0x80 && (cp.toChar().isLetterOrDigit() || cp.toChar() in "._-")) {
                kept.append(cp.toChar()); replaced = false
            } else if (!replaced) {
                kept.append('_'); replaced = true // a run of anything else is one underscore, so no length trick with a flood
            }
        }
        // The first character is alphanumeric (a dot hides the file, a hyphen is an option to the tools that read it) and
        // a trailing dot says nothing.
        val name = kept.toString().trimStart('.', '_', '-').trimEnd('.')
        if (name.isEmpty()) return FALLBACK
        if (name.length <= max) return name
        val dot = extensionStart(name)
        val ext = name.substring(dot)
        return name.substring(0, dot).take(max - ext.length).trimEnd('.') + ext
    }

    /** Where the extension starts in [name] (its last dot), or its end when it has none or one too long to keep. */
    private fun extensionStart(name: String): Int {
        val dot = name.lastIndexOf('.')
        return if (dot > 0 && name.length - dot <= MAX_EXTENSION_CHARS) dot else name.length
    }
}
