package io.github.tuthan.paddock.files

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Why an intake did not make a snapshot. Each sentence is what the screen says; none holds a name, a path or a byte of the file. */
enum class IntakeRefusal(val sentence: String) {
    /** SEC-1: the URI is not `content://`, or it is one of Paddock's own. */
    Uri(UriRefusal.OwnProvider.sentence),
    TooLarge("That file is over 25 MB."),
    Unreadable("Paddock could not read that file."),

    /** Not an image, a PDF or text, by its bytes: the filter and the picker only narrow what a sender may offer. */
    UnsupportedType("Paddock sends images, PDFs and text files."),

    /** An image that could not be cleaned of its location and camera details without changing what it draws, or one that is too large to decode. */
    ImageNotCleaned("Paddock could not remove that image's location and camera details, so it was not attached."),
}

sealed interface Intake {
    data class Taken(val snapshot: Snapshot) : Intake
    data class Refused(val reason: IntakeRefusal) : Intake
}

/** The check made on a URI before anything is opened. A seam so a test can admit a URI of the app's own test provider; production uses [UriPolicy]. */
fun interface UriCheck {
    fun check(uri: Uri): UriRefusal?
}

/** [UriPolicy] against the installed packages: Paddock's own providers, and what the platform resolves an authority to. */
class PlatformUriCheck(private val context: Context) : UriCheck {
    private val own: Set<String> by lazy {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PROVIDERS.toLong()))
        } else {
            @Suppress("DEPRECATION") pm.getPackageInfo(context.packageName, PackageManager.GET_PROVIDERS)
        }
        info.providers.orEmpty().flatMap { (it.authority ?: "").split(';') }.filter { it.isNotEmpty() }.toSet()
    }

    @Suppress("DEPRECATION")
    override fun check(uri: Uri): UriRefusal? = UriPolicy.check(uri.scheme, uri.authority, own, context.packageName) { a ->
        context.packageManager.resolveContentProvider(a, 0)?.packageName
    }
}

/**
 * The intake of a picked, captured or shared file (Phase 15, SEC-1 and SEC-2): one private copy in the cache, checked before it is opened and sniffed,
 * cleaned and hashed after, so what is sent is what was checked. The sender's URI is read once, here, and never again; the claimed name, type and
 * size are hints for the person and decide nothing. Nothing here logs a name, a URI or a byte (SEC-7).
 *
 * A snapshot lives in `cache/incoming/<id>/data`. [source] streams it, and the draft keeps only the id.
 */
class IncomingFiles(
    private val context: Context,
    private val cache: TransferCache,
    private val uriCheck: UriCheck = PlatformUriCheck(context),
    /** Opens the sender's URI; a seam so a test can count the opens (a refusal must read nothing). */
    private val open: (Uri) -> InputStream? = { context.contentResolver.openInputStream(it) },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val now: () -> LocalDateTime = { LocalDateTime.now() },
) {
    /** Whether SEC-1 admits [uri]: asked before anything else touches it (a name query is a read of the sender's provider too). */
    fun admits(uri: Uri): Boolean = uriCheck.check(uri) == null

    /** Takes the file at [uri]. [displayName] is the sender's name for it, reduced to the allowlist and never used as it came. */
    suspend fun take(uri: Uri, displayName: String?): Intake = withContext(io) {
        uriCheck.check(uri)?.let { return@withContext Intake.Refused(IntakeRefusal.Uri) }
        intake(displayName) { open(uri) }
    }

    /**
     * Takes a capture the camera app wrote into Paddock's own `cache/camera/` and deletes it, whatever the outcome: the raw capture is never kept (SEC-2).
     * It is a local file, so there is no URI to check; [TransferCache.cameraFile] only ever names a file under that folder.
     */
    suspend fun takeCapture(captureId: String): Intake = withContext(io) {
        val file = cache.cameraFile(captureId)
        try {
            if (!file.isFile) Intake.Refused(IntakeRefusal.Unreadable) else intake("camera.jpg") { FileInputStream(file) }
        } finally {
            cache.deleteCamera(captureId)
        }
    }

    private suspend fun intake(displayName: String?, opener: () -> InputStream?): Intake {
        val id = newId()
        val dir = cache.snapshotDir(id)
        return try {
            val raw = File(dir, RAW)
            val copied = try {
                opener()?.use { copyBounded(it, raw, TransferLimits.MAX_PUT_BYTES) }
            } catch (_: IOException) { null } catch (_: SecurityException) { null } catch (_: IllegalArgumentException) { null }
            when {
                copied == null -> fail(id, IntakeRefusal.Unreadable)
                copied < 0 -> fail(id, IntakeRefusal.TooLarge)
                else -> finish(id, dir, raw, displayName)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            cache.deleteSnapshot(id)
            throw e
        } catch (_: IOException) {
            fail(id, IntakeRefusal.Unreadable)
        }
    }

    /** The bytes of a snapshot for [FileTransfers.put], or null when it was swept: the draft then says the file is gone. */
    fun source(id: String): ByteSource? {
        val file = cache.existingSnapshotDir(id)?.let { File(it, DATA) }?.takeIf { it.isFile } ?: return null
        return ByteSource { FileInputStream(file) }
    }

    /** The snapshot file itself, or null when it was swept: the chip's thumbnail reads it, and so does [snapshot]. */
    fun file(id: String): File? = cache.existingSnapshotDir(id)?.let { File(it, DATA) }?.takeIf { it.isFile }

    /**
     * The [Snapshot] for a draft's attachment: the hash is taken now from the snapshot's own file, so it is the hash of exactly what the send will read.
     * Null when the file is gone.
     */
    suspend fun snapshot(id: String, name: String, mime: String): Snapshot? = withContext(io) {
        val f = file(id) ?: return@withContext null
        Snapshot(id, name, mime, f.length(), sha256(f))
    }

    fun delete(id: String) = cache.deleteSnapshot(id)

    private fun fail(id: String, reason: IntakeRefusal): Intake {
        cache.deleteSnapshot(id)
        return Intake.Refused(reason)
    }

    private fun finish(id: String, dir: File, raw: File, displayName: String?): Intake {
        val head = ByteArray(HEAD).let { buf -> FileInputStream(raw).use { s -> val n = readFully(s, buf); buf.copyOf(n) } }
        val data = File(dir, DATA)
        val (mime, ext) = try {
            when (ImageMetadata.sniff(head)) {
                ImageKind.Jpeg -> {
                    val orientation = ImageMetadata.jpegOrientation(head)
                    FileInputStream(raw).use { i -> FileOutputStream(data).use { o -> ImageMetadata.stripJpeg(i, o, orientation) } }
                    "image/jpeg" to "jpg"
                }
                ImageKind.Png -> {
                    FileInputStream(raw).use { i -> FileOutputStream(data).use { o -> ImageMetadata.stripPng(i, o) } }
                    "image/png" to "png"
                }
                ImageKind.Heif -> {
                    reencodeHeif(raw, data)
                    "image/jpeg" to "jpg"
                }
                null -> when {
                    isPdf(head) -> { raw.copyTo(data, overwrite = true); "application/pdf" to "pdf" }
                    isText(head) -> { raw.copyTo(data, overwrite = true); "text/plain" to textExtension(displayName) }
                    else -> return fail(id, IntakeRefusal.UnsupportedType)
                }
            }
        } catch (_: ImageRefused) {
            return fail(id, IntakeRefusal.ImageNotCleaned)
        } catch (_: ImageNotCleaned) {
            return fail(id, IntakeRefusal.ImageNotCleaned)
        }
        raw.delete()
        val size = data.length()
        if (size > TransferLimits.MAX_PUT_BYTES) return fail(id, IntakeRefusal.TooLarge)
        val base = (displayName ?: "").substringBeforeLast('.', displayName ?: "")
        val name = FileNames.inboxName(now(), "$base.$ext")
        return Intake.Taken(Snapshot(id, name, mime, size, sha256(data)))
    }

    /** A HEIC or AVIF is decoded and written again as a JPEG with nothing but pixels (X4). API 28 and later; before that it is refused. */
    private fun reencodeHeif(raw: File, out: File) {
        if (Build.VERSION.SDK_INT < 28) throw ImageNotCleaned()
        try {
            val source = ImageDecoder.createSource(raw)
            val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                if (!ImageMetadata.pixelsWithinBound(info.size.width.toLong(), info.size.height.toLong())) throw ImageNotCleaned()
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            try {
                FileOutputStream(out).use { if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)) throw ImageNotCleaned() }
            } finally {
                bitmap.recycle()
            }
        } catch (e: ImageNotCleaned) {
            throw e
        } catch (_: IOException) {
            throw ImageNotCleaned()
        } catch (_: RuntimeException) {
            throw ImageNotCleaned()
        } catch (_: OutOfMemoryError) {
            throw ImageNotCleaned()
        }
    }

    private class ImageNotCleaned : Exception()

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(32 * 1024)
        FileInputStream(file).use { s -> while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val RAW = "raw"
        private const val DATA = "data"
        private const val HEAD = 256 * 1024
        private const val JPEG_QUALITY = 92

        private val TEXT_EXTENSIONS = setOf("md", "log", "json", "csv", "yaml", "yml", "toml", "xml", "diff", "patch", "ini", "conf")

        /**
         * Copies at most [cap] bytes of [input] into [out]; the answer is the count, or -1 when the stream holds more than [cap] (one more byte is read to
         * know), in which case [out] is deleted. A stream that ends early is simply the file.
         */
        fun copyBounded(input: InputStream, out: File, cap: Long): Long {
            var total = 0L
            val buf = ByteArray(32 * 1024)
            FileOutputStream(out).use { o ->
                while (true) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), cap + 1 - total).toInt())
                    if (n < 0) break
                    total += n
                    if (total > cap) { o.flush(); break }
                    o.write(buf, 0, n)
                }
            }
            if (total > cap) { out.delete(); return -1 }
            return total
        }

        private fun readFully(s: InputStream, buf: ByteArray): Int {
            var n = 0
            while (n < buf.size) { val r = s.read(buf, n, buf.size - n); if (r < 0) break; n += r }
            return n
        }

        fun isPdf(head: ByteArray): Boolean = head.size >= 5 && String(head, 0, 5, Charsets.ISO_8859_1) == "%PDF-"

        /** UTF-8 with no NUL and no control character but tab, line feed, carriage return and form feed; a truncated last character is not held against it. */
        fun isText(head: ByteArray): Boolean {
            if (head.isEmpty()) return false
            var end = head.size
            // A head cut mid-character: drop up to three trailing continuation bytes and their lead.
            if (end == HEAD) {
                var back = 0
                while (back < 3 && end - back > 0 && (head[end - back - 1].toInt() and 0xC0) == 0x80) back++
                if (back in 1..3 && end - back > 0 && (head[end - back - 1].toInt() and 0xC0) == 0xC0) end -= back + 1
            }
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            val text = try { decoder.decode(java.nio.ByteBuffer.wrap(head, 0, end)).toString() } catch (_: java.nio.charset.CharacterCodingException) { return false }
            return text.none { it.code < 0x20 && it != '\t' && it != '\n' && it != '\r' && it != '\u000c' }
        }

        fun textExtension(displayName: String?): String {
            val ext = displayName?.substringAfterLast('.', "")?.lowercase().orEmpty()
            return if (ext in TEXT_EXTENSIONS) ext else "txt"
        }
    }
}
