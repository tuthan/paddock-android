package io.github.tuthan.paddock.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import io.github.tuthan.paddock.output.SharedText
import io.github.tuthan.paddock.output.SharedTexts

/** What an `ACTION_SEND` came to at the door of the app. */
sealed interface ShareOutcome {
    /** A checked item, waiting for the person to choose where it goes. */
    data class Received(val item: SharedItem) : ShareOutcome

    /** Nothing was taken; [sentence] says why in the notice bar. */
    data class Refused(val sentence: String) : ShareOutcome

    /** Not a share this app takes (`SEND_MULTIPLE`, a `VIEW`, a missing intent): nothing is said. */
    data object Ignored : ShareOutcome
}

object ShareCopy {
    const val NOTHING = "Paddock found nothing it can take in what was shared."
    const val TEXT_TOO_LONG = "That text is longer than a prompt can be, so Paddock did not take it."
    const val UNREADABLE = "Paddock could not read what was shared."
    const val NO_MACHINE = "Add a machine first. Paddock has nowhere to send what was shared, and did not keep it."

    fun removed(n: Int) = if (n == 1) "1 hidden character was removed from the shared text." else "$n hidden characters were removed from the shared text."
}

/**
 * The door for files and text another app shares to Paddock (Phase 15, slice 11, X13, SEC-1 to SEC-3). It reads an `ACTION_SEND` once, and only the parts
 * it understands: nothing in the intent is forwarded, launched or kept. It never connects anywhere and never sends: its output waits in the
 * [ShareInbox] until the person chooses where it goes.
 *
 *  - Only `ACTION_SEND` counts. `SEND_MULTIPLE` has no filter, and an explicit one is ignored here.
 *  - `EXTRA_STREAM` is read through `IntentCompat` inside a `try`, so a malformed bundle is a refusal and not a crash. [IncomingFiles.take] applies SEC-1 (a
 *    `content://` URI of another app, never `file://` or Paddock's own) and SEC-2 (one private copy, sniffed, cleaned of image metadata, hashed).
 *  - `EXTRA_TEXT` is measured as the [CharSequence] it came as, before anything scans it (a huge text costs a length check and nothing more), then cleaned
 *    (SEC-3). With a stream, the text is the words that go with the file.
 *  - The sender's claims (type, name, size) decide nothing: the type is the sniff's, the name is reduced to an allowlist.
 */
class ShareIntake(private val context: Context, private val files: IncomingFiles) {
    suspend fun receive(intent: Intent?): ShareOutcome {
        if (intent?.action != Intent.ACTION_SEND) return ShareOutcome.Ignored
        val stream: Uri?
        val text: CharSequence?
        try {
            stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
        } catch (_: RuntimeException) {
            // A bundle that does not unparcel, or an extra of another type: the sender's mistake or its trick, and a refusal either way.
            return ShareOutcome.Refused(ShareCopy.UNREADABLE)
        }
        val words = SharedTexts.of(text)
        if (stream == null) return when (words) {
            is SharedText.Ok -> ShareOutcome.Received(SharedItem.Text(words.text, words.removed))
            SharedText.TooLong -> ShareOutcome.Refused(ShareCopy.TEXT_TOO_LONG)
            SharedText.Empty -> ShareOutcome.Refused(ShareCopy.NOTHING)
        }
        // With a file, text that is too long is a refusal of the whole share: half of one is not what was sent.
        if (words === SharedText.TooLong) return ShareOutcome.Refused(ShareCopy.TEXT_TOO_LONG)
        // SEC-1 first: not even the sender's name is asked of a URI that is not another app's content.
        if (!files.admits(stream)) return ShareOutcome.Refused(IntakeRefusal.Uri.sentence)
        val name = displayNameOf(context, stream)
        return when (val taken = files.take(stream, name)) {
            is Intake.Refused -> ShareOutcome.Refused(taken.reason.sentence)
            is Intake.Taken -> {
                val ok = words as? SharedText.Ok
                ShareOutcome.Received(SharedItem.File(taken.snapshot, ok?.text, ok?.removed ?: 0, senderName = name))
            }
        }
    }
}
