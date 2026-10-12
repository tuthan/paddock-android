package io.github.tuthan.paddock.files

import io.github.tuthan.paddock.output.SafeText
import java.util.Locale

/** A path an agent printed: [start] to [end] are UTF-16 offsets into the line, [text] is `line.substring(start, end)`. */
data class PathToken(val start: Int, val end: Int, val text: String)

/**
 * Finds the paths in an output line, so the Output tab can offer Get on them (X2), and resolves one against the agent's
 * folder. Both are guesses made harmless by the stat: the host judges the real path, so a wrong token costs one refused
 * tap, never a read outside the folder. They only have to be cheap, bounded and not wrong in obvious ways.
 */
object PathTokens {
    /** A busy line (a `ls` or a diff stat) would underline everything otherwise. */
    const val MAX_PER_LINE = 8

    private const val MAX_TOKEN_CHARS = 1_024

    /** Known extensions: what an agent writes for a person to open. A bare word with one of these is a file. */
    val EXTENSIONS: Set<String> = setOf(
        // documents, sheets, slides
        "pdf", "doc", "docx", "odt", "rtf", "txt", "md", "markdown", "rst", "tex", "epub",
        "xls", "xlsx", "ods", "csv", "tsv", "ppt", "pptx", "odp", "key",
        // images and media
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "heif", "avif", "ico", "tif", "tiff",
        "mp3", "mp4", "mov", "wav", "webm", "mkv", "m4a", "ogg", "flac",
        // archives and packages
        "zip", "tar", "gz", "tgz", "bz2", "xz", "zst", "7z", "rar",
        "apk", "aab", "jar", "aar", "war", "whl", "deb", "rpm", "dmg", "ipa",
        // logs and data
        "log", "trace", "hprof", "json", "jsonl", "ndjson", "yaml", "yml", "toml", "xml", "ini", "cfg", "conf", "properties",
        "html", "htm", "css", "scss", "sql", "db", "sqlite", "sqlite3", "ipynb", "plist", "lock", "patch", "diff", "proto",
        // source
        "kt", "kts", "java", "py", "js", "jsx", "mjs", "cjs", "ts", "tsx", "vue", "svelte", "c", "h", "cc", "cpp", "hpp",
        "cs", "go", "rs", "rb", "php", "swift", "sh", "bash", "zsh", "fish", "ps1", "gradle", "lua", "pl", "dart", "scala",
        "sol", "nix", "tf",
    )

    /**
     * The path tokens of [line], in order, at most [MAX_PER_LINE]. A token is a run of non-space characters (after its
     * quotes, brackets, `*` and box or bullet characters and its trailing `.,:;!?` are trimmed, and a `:line:col` tail
     * is cut off) made of letters, digits and `._-/+@%~#`, with a `/` or a known extension, and at least one letter.
     * So a URL (`:` is no path character), `--out=x.zip`, `3/4`, `2026/10/11`, a directory (`src/`) and a drawing character are not tokens.
     *
     * A path with a space is not supported: a word inside a quoted run that holds a space is never taken, since its
     * pieces would be wrong guesses. [cut] says the line was cut at the display limit, so its last word may be half a
     * path: the last token is dropped, whatever it is.
     */
    fun tokens(line: String, cut: Boolean = false): List<PathToken> {
        val blocked = spacedQuotes(line)
        val found = ArrayList<PathToken>()
        var i = 0
        while (i < line.length && found.size <= MAX_PER_LINE) {
            if (isSpace(line[i])) { i++; continue }
            var j = i
            while (j < line.length && !isSpace(line[j])) j++
            candidate(line, i, j, blocked)?.let { found += it }
            i = j
        }
        // Only the last of at most eight can be the half path, so a longer list is unaffected by `cut`.
        if (cut && found.isNotEmpty() && found.size <= MAX_PER_LINE) found.removeAt(found.lastIndex)
        return if (found.size > MAX_PER_LINE) found.subList(0, MAX_PER_LINE).toList() else found
    }

    fun resolve(folder: String, token: PathToken): String? = resolve(folder, token.text)

    /**
     * The absolute path [token] names under [folder], with `.` and `..` collapsed as text, or null when it is not
     * strictly below [folder] (an absolute token elsewhere, `../x`, the folder itself) or either holds a control
     * character. `~` is not expanded: `~/x` is a folder named `~` below [folder], which the stat will not find. Links are
     * the host's to judge; this only keeps the request inside the folder as text.
     */
    fun resolve(folder: String, token: String): String? {
        if (token.isEmpty() || hasUnsafe(folder) || hasUnsafe(token)) return null
        val base = collapse(folder) ?: return null
        val path = collapse(if (token.startsWith("/")) token else "$base/$token") ?: return null
        val prefix = if (base == "/") "/" else "$base/"
        return if (path.length > prefix.length && path.startsWith(prefix)) path else null
    }

    /** [absolute] with empty and `.` parts dropped and `..` applied; null when it is not absolute or climbs above `/`. */
    internal fun collapse(absolute: String): String? {
        if (!absolute.startsWith("/")) return null
        val parts = ArrayList<String>()
        for (part in absolute.split('/')) {
            when (part) {
                "", "." -> {}
                ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.lastIndex)
                else -> parts += part
            }
        }
        return "/" + parts.joinToString("/")
    }

    private fun hasUnsafe(s: String): Boolean = s.codePoints().anyMatch(SafeText::isUnsafe)

    private fun isSpace(c: Char) = c.isWhitespace()

    private fun candidate(line: String, wordStart: Int, wordEnd: Int, blocked: List<IntRange>): PathToken? {
        if (blocked.any { it.first < wordEnd && it.last >= wordStart }) return null
        if (wordStart >= 2 && line[wordStart - 2] == '\\') return null // the tail of `my\ file.txt`
        var s = wordStart
        var e = wordEnd
        while (true) {
            val (s0, e0) = s to e
            while (s < e && isWrapper(line[s], leading = true)) s++
            while (e > s && isWrapper(line[e - 1], leading = false)) e--
            e = withoutLineSuffix(line, s, e)
            if (s == s0 && e == e0) break
        }
        if (s >= e || e - s > MAX_TOKEN_CHARS) return null
        val text = line.substring(s, e)
        if (text[0] == '-' || text.last() == '/') return null
        if (!text.codePoints().allMatch { Character.isLetterOrDigit(it) || it < 0x80 && "._-/+@%~#".indexOf(it.toChar()) >= 0 }) return null
        if (text.codePoints().noneMatch(Character::isLetter)) return null // 1/2, 2026/10/11
        if ('/' !in text && !hasKnownExtension(text)) return null
        return PathToken(s, e, text)
    }

    private fun hasKnownExtension(name: String): Boolean {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return false
        if (name.substring(0, dot).codePoints().noneMatch(Character::isLetterOrDigit)) return false
        return name.substring(dot + 1).lowercase(Locale.ROOT) in EXTENSIONS
    }

    /** Up to two `:digits` off the end (`Foo.kt:42:7`), the way a compiler or grep prints a place in a file. */
    private fun withoutLineSuffix(line: String, start: Int, end: Int): Int {
        var e = end
        repeat(2) {
            val colon = line.lastIndexOf(':', e - 1)
            if (colon <= start || colon + 1 >= e || (colon + 1 until e).any { line[it] !in '0'..'9' }) return e
            e = colon
        }
        return e
    }

    private const val LEADING = "\"'`“‘([{<>|*"
    private const val TRAILING = "\"'`”’)]}<>|*.,:;!?"

    private fun isWrapper(c: Char, leading: Boolean): Boolean =
        (if (leading) LEADING else TRAILING).indexOf(c) >= 0 || isDecoration(c)

    /** Box drawing and block elements, and the bullets agents put in front of a line (`⏺`, `⎿`, `●`, `•`, `·`). */
    private fun isDecoration(c: Char) = c in '─'..'▟' || c in "⎿⏺●•·"

    private val CLOSERS = mapOf('"' to '"', '\'' to '\'', '“' to '”', '‘' to '’')

    /**
     * The runs between a quote that opens a word and the quote that closes one, when they hold a space: a path with a
     * space in it, or prose in quotes. An apostrophe inside a word opens nothing.
     */
    private fun spacedQuotes(line: String): List<IntRange> {
        val runs = ArrayList<IntRange>()
        var i = 0
        while (i < line.length) {
            val close = CLOSERS[line[i]]
            if (close != null && (i == 0 || isSpace(line[i - 1]) || line[i - 1] in "([{<=,:;")) {
                var j = i + 1
                while (j < line.length && !(line[j] == close && (j + 1 == line.length || isSpace(line[j + 1]) || line[j + 1] in ")]}>.,;:!?"))) j++
                if (j < line.length) {
                    if ((i until j).any { isSpace(line[it]) }) runs += i..j
                    i = j
                }
            }
            i++
        }
        return runs
    }
}
