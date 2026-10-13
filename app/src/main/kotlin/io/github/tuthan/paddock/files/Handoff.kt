package io.github.tuthan.paddock.files

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * Handing a fetched file to another app or to a place the person chooses (Phase 15, SEC-5, SEC-6, X5). Every intent here names the file by a `content://`
 * URI of Paddock's own provider (never a path), carries a read-only grant for that one URI, and is built for an explicit act of the person: a tap on Open,
 * Share or Save. Nothing here is persistable and nothing grants write.
 *
 * **Open** is for [OpenableTypes.Kind.Openable] only (images, PDF, plain-text formats): the caller never asks for it for anything else, and
 * [openIntent] refuses a kind that is not openable, so an APK, an SVG or an HTML file cannot be opened by mistake. **Share** takes any type and **Save**
 * copies into a document the person created with the system's own picker.
 */
object Handoff {
    /** The URI the provider gives for [file], which must be under `cache/transfers/`; the provider refuses anything else. */
    fun uriFor(context: Context, file: File): Uri = FileProvider.getUriForFile(context, TransferFileProvider.authority(context.packageName), file)

    /** `ACTION_VIEW` of [file] as [kind]'s type, read-only. Throws for a kind Open is not offered for. */
    fun openIntent(context: Context, file: File, kind: OpenableTypes.Kind): Intent {
        require(kind is OpenableTypes.Kind.Openable) { "Open is only for images, PDF and text" }
        return Intent(Intent.ACTION_VIEW).setDataAndType(uriFor(context, file), kind.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** `ACTION_SEND` of [file] as [mime], the stream and a clip for the grant, read-only. */
    fun shareIntent(context: Context, file: File, mime: String): Intent {
        val uri = uriFor(context, file)
        return Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            .apply { clipData = ClipData.newRawUri(null, uri) }
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** The type Share and Save are given for [kind]. */
    fun mimeOf(kind: OpenableTypes.Kind): String = when (kind) {
        is OpenableTypes.Kind.Openable -> kind.mime
        OpenableTypes.Kind.Apk -> OpenableTypes.APK
        is OpenableTypes.Kind.ShareOnly -> kind.mime
    }

    /** Copies [file] into the document at [to], replacing what it holds. False when the destination cannot be written. */
    fun saveTo(context: Context, file: File, to: Uri): Boolean = saveTo(file) { context.contentResolver.openOutputStream(to, "wt") }

    /**
     * [saveTo] with the opening of the destination as a seam, so a test can be a provider that throws. The destination's provider is another app's code: its
     * open or write may throw any unchecked exception (binder carries an IllegalStateException, a NullPointerException or an UnsupportedOperationException
     * back to this caller), and each is a save that failed, never a crash.
     */
    internal fun saveTo(file: File, open: () -> OutputStream?): Boolean = try {
        val out = open() ?: return false
        out.use { sink -> file.inputStream().use { it.copyTo(sink) } }
        true
    } catch (_: IOException) { false } catch (_: RuntimeException) { false }
}
