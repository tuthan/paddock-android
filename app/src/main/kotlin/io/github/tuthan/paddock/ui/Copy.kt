package io.github.tuthan.paddock.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipEntry

/** Whether a copy is marked as sensitive, so the keyboard and the system's confirmation do not show it. */
enum class Sensitivity {
    /** The text is not a secret: a public key, a command the user is about to paste on their own machine. */
    Never,

    /** Agent output: marked while *Protect sensitive screens* is on (it is on by default), because output can show anything. */
    WhileProtected,

    /** A secret the app made itself, such as the ntfy topic and a self-hosted server's token. Marked whatever the setting says. */
    Always,
}

/**
 * Every copy the app makes (Phase 15, C4), with its clip label and its [Sensitivity]. One list, so a new copy has to say whether it is a secret, and the
 * source scan in `CopyKindTest` finds no clipboard write outside this file.
 */
enum class CopyKind(val label: String, val sensitivity: Sensitivity) {
    Output("Paddock output", Sensitivity.WhileProtected),
    PublicKey("Paddock public key", Sensitivity.Never),
    AuthorizeCommand("Paddock authorize command", Sensitivity.Never),
    WakeCommand("Paddock wake command", Sensitivity.Never),

    /** Its routes pass their own labels (one per agent), which say what was copied. */
    GuardedSetup("Paddock guarded answers", Sensitivity.Never),
    AlertSetup("Paddock alert relay setup", Sensitivity.Always),
    SubscribeLink("ntfy subscribe link", Sensitivity.Always),

    /** A path on a machine (Phase 15): where a shared file went. A location is not a secret, so it is never marked. */
    MachinePath("Paddock machine path", Sensitivity.Never),
}

fun CopyKind.marked(protect: Boolean): Boolean = when (sensitivity) {
    Sensitivity.Never -> false
    Sensitivity.WhileProtected -> protect
    Sensitivity.Always -> true
}

/**
 * `ClipDescription.EXTRA_IS_SENSITIVE`, spelled out: the constant exists from API 33, but the extra is an ordinary key that an older platform keeps and a
 * newer keyboard or clipboard UI reads, and `setExtras` has been there since API 24.
 */
const val EXTRA_IS_SENSITIVE_KEY = "android.content.extra.IS_SENSITIVE"

/** Adds the sensitive mark to [clip]'s description, keeping any extras already there. */
fun markSensitive(clip: ClipData): ClipData {
    val extras = clip.description.extras?.let { PersistableBundle(it) } ?: PersistableBundle()
    extras.putBoolean(EXTRA_IS_SENSITIVE_KEY, true)
    clip.description.extras = extras
    return clip
}

/** The clip for a copy of [text]: plain text under [label], marked when [kind] says so. */
fun clipFor(kind: CopyKind, text: String, protect: Boolean, label: String = kind.label): ClipData {
    val clip = ClipData.newPlainText(label, text)
    return if (kind.marked(protect)) markSensitive(clip) else clip
}

/**
 * Puts [text] on the clipboard. False when the clipboard service refuses (the caller says "Could not copy"). It never logs the text, and nothing here
 * journals a copy. [protect] is the *Protect sensitive screens* setting; it defaults to on, so a caller that forgets it marks rather than leaks.
 */
fun copyText(context: Context, kind: CopyKind, text: String, protect: Boolean = true, label: String = kind.label): Boolean {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
    return try { cm.setPrimaryClip(clipFor(kind, text, protect, label)); true } catch (_: RuntimeException) { false }
}

/** Empties the clipboard: `clearPrimaryClip` from API 28, an empty clip below it, which is the closest those levels allow. */
fun clearClip(context: Context) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    try {
        if (android.os.Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip() else cm.setPrimaryClip(ClipData.newPlainText("", ""))
    } catch (_: RuntimeException) {
        // A clipboard that cannot be cleared is left as it is; the clip was marked sensitive when it was written.
    }
}

/**
 * The clipboard for Compose's own text widgets: the system toolbar's Copy in the Select text view goes through here, so it is marked like Copy all.
 * [protect] is read at each copy, so the setting changing while the view is open takes effect on the next one.
 */
class SensitiveClipboard(private val inner: Clipboard, private val protect: () -> Boolean) : Clipboard {
    override suspend fun getClipEntry(): ClipEntry? = inner.getClipEntry()
    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        if (clipEntry != null && protect()) markSensitive(clipEntry.clipData)
        inner.setClipEntry(clipEntry)
    }
    override val nativeClipboard: ClipboardManager get() = inner.nativeClipboard
}

/** What the clipboard says about the clip it holds: its label and when it was set, never its text. */
data class ClipStamp(val label: String?, val timestamp: Long)

/**
 * C7: the alert setup script and the ntfy subscribe link hold the topic and, for a self-hosted server, its token. Android 12L and lower never clear the
 * clipboard, and the sensitive mark only hides a preview, so these two copies are cleared when Paddock next comes to the front, if the clipboard still
 * holds them. The match is by label and timestamp through the clip's description, so the text is never read and another app's clip is never touched.
 * One shot: [onFront] disarms whether or not it cleared. After a process death nothing is armed, and the copy stays until something replaces it.
 */
class AlertClipGuard(private val peek: () -> ClipStamp?, private val clear: () -> Unit) {
    @Volatile private var armed: ClipStamp? = null

    /** Called right after one of the two copies succeeded. */
    fun arm() { armed = peek() }

    /** Called when Paddock comes to the front again. */
    fun onFront() {
        val mine = armed ?: return
        armed = null
        if (peek() == mine) clear()
    }

    companion object {
        fun of(context: Context): AlertClipGuard {
            val app = context.applicationContext
            return AlertClipGuard(
                peek = {
                    val d = try { (app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.primaryClipDescription } catch (_: RuntimeException) { null }
                    d?.let { ClipStamp(it.label?.toString(), it.timestamp) }
                },
                clear = { clearClip(app) },
            )
        }
    }
}

/** The words a copy says, beside the clipboard's own confirmation on Android 13 and later. */
object CopyWords {
    fun copied(lines: Int) = if (lines == 1) "Copied 1 line." else "Copied $lines lines."
    fun cut(lines: Int, limit: Int) = if (lines == 1) "1 line was cut at ${"%,d".format(limit)} characters." else "$lines lines were cut at ${"%,d".format(limit)} characters."
    fun leftOut(lines: Int) = if (lines == 1) "1 older line was left out: the copy would be too long." else "$lines older lines were left out: the copy would be too long."
    const val COULD_NOT = "Could not copy."
}

/**
 * What to say after a copy of [lines] lines, of which [cut] were cut at the line limit and [leftOut] older ones did not fit. Android 13 and later show their own
 * confirmation, so there it is only the part that confirmation cannot know (a cut or a left-out part), or nothing; on 12L and lower the app says it all.
 */
fun copyNotice(sdkInt: Int, lines: Int, cut: Int, leftOut: Int, limit: Int = io.github.tuthan.paddock.output.Ansi.MAX_LINE_CHARS): String? {
    val parts = buildList {
        if (sdkInt < 33) add(CopyWords.copied(lines))
        if (cut > 0) add(CopyWords.cut(cut, limit))
        if (leftOut > 0) add(CopyWords.leftOut(leftOut))
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
}
