package io.github.tuthan.paddock.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tuthan.paddock.files.IncomingFiles
import io.github.tuthan.paddock.files.Intake
import io.github.tuthan.paddock.files.IntakeRefusal
import io.github.tuthan.paddock.files.TransferCache
import io.github.tuthan.paddock.files.TransferFileProvider
import io.github.tuthan.paddock.files.TransferLimits
import io.github.tuthan.paddock.files.UriCheck
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.UUID
import java.util.zip.CRC32
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The intake on a device (Phase 15, SEC-2): one private copy that is sniffed, cleaned and hashed, so what is sent is what was checked. The sender's URI
 * here is a file in Paddock's own cache read through its provider, admitted by a fake [UriCheck]; the real policy's refusals are `IntakeRefusalTest`'s job.
 */
class IncomingFilesTest {
    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val cache = TransferCache(ctx.cacheDir)
    private val admit = UriCheck { null }
    private val at = LocalDateTime.of(2026, 10, 11, 14, 32, 5)
    private val files = IncomingFiles(ctx, cache, admit, now = { at })
    private val made = mutableListOf<File>()
    private val taken = mutableListOf<String>()

    @After fun clean() {
        made.forEach { it.delete() }
        taken.forEach { cache.deleteSnapshot(it) }
        File(ctx.cacheDir, "camera").deleteRecursively()
    }

    private fun source(bytes: ByteArray): Uri {
        val f = cache.cameraFile(UUID.randomUUID().toString()).apply { writeBytes(bytes) }
        made += f
        return FileProvider.getUriForFile(ctx, TransferFileProvider.authority(ctx.packageName), f)
    }

    private fun take(bytes: ByteArray, name: String? = "photo.jpg"): Intake = runBlocking { files.take(source(bytes), name) }.also { if (it is Intake.Taken) taken += it.snapshot.id }

    private fun bitmap(): Bitmap = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF3366CC.toInt()) }
    private fun jpeg(): ByteArray = ByteArrayOutputStream().also { bitmap().compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    private fun png(): ByteArray = ByteArrayOutputStream().also { bitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    /** A JPEG with an Exif segment holding a marker where a location would be. */
    private fun jpegWithExif(marker: String): ByteArray {
        val j = jpeg()
        val payload = "Exif\u0000\u0000$marker".toByteArray(Charsets.ISO_8859_1)
        val len = payload.size + 2
        return j.copyOfRange(0, 2) + byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), len.toByte()) + payload + j.copyOfRange(2, j.size)
    }

    private fun pngWithText(marker: String): ByteArray {
        val p = png()
        val data = "Comment\u0000$marker".toByteArray(Charsets.ISO_8859_1)
        val type = "tEXt".toByteArray(Charsets.ISO_8859_1)
        val crc = CRC32().apply { update(type); update(data) }.value
        fun u32(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
        val chunk = u32(data.size.toLong()) + type + data + u32(crc)
        return p.copyOfRange(0, p.size - 12) + chunk + p.copyOfRange(p.size - 12, p.size)
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun snapshot(i: Intake) = (i as Intake.Taken).snapshot
    private fun dataOf(i: Intake) = files.file(snapshot(i).id)!!.readBytes()

    @Test fun aJpegIsCleanedOfItsMetadataAndStillDecodes() {
        val i = take(jpegWithExif("SENTINEL-GPS-1.2345N"))
        val data = dataOf(i)
        assertFalse("the metadata is gone", String(data, Charsets.ISO_8859_1).contains("SENTINEL"))
        assertNotNull("the cleaned image still decodes", BitmapFactory.decodeByteArray(data, 0, data.size))
        assertEquals("image/jpeg", snapshot(i).mime)
    }

    @Test fun aPngIsCleanedOfItsTextChunks() {
        val i = take(pngWithText("SENTINEL-AUTHOR"), "shot.png")
        val data = dataOf(i)
        assertFalse(String(data, Charsets.ISO_8859_1).contains("SENTINEL"))
        assertNotNull(BitmapFactory.decodeByteArray(data, 0, data.size))
        assertEquals("image/png", snapshot(i).mime)
        assertTrue(snapshot(i).name.endsWith(".png"))
    }

    @Test fun theSnapshotsHashAndSizeAreThoseOfTheFileItHolds() {
        val s = snapshot(take(jpegWithExif("SENTINEL-X")))
        val data = files.file(s.id)!!.readBytes()
        assertEquals(data.size.toLong(), s.size)
        assertEquals(sha(data), s.sha256)
    }

    @Test fun aSenderThatChangesItsFileAfterIntakeChangesNothingThatLeaves() = runBlocking {
        val bytes = jpegWithExif("SENTINEL-Y")
        val f = cache.cameraFile(UUID.randomUUID().toString()).apply { writeBytes(bytes) }.also { made += it }
        val uri = FileProvider.getUriForFile(ctx, TransferFileProvider.authority(ctx.packageName), f)
        val s = (files.take(uri, "a.jpg") as Intake.Taken).snapshot.also { taken += it.id }
        val before = files.file(s.id)!!.readBytes()
        f.writeBytes(ByteArray(2_000) { 9 }) // the sender rewrites its file
        val again = files.snapshot(s.id, s.name, s.mime)!!
        assertArrayEquals(before, files.file(s.id)!!.readBytes())
        assertEquals("what would be hashed for the send is still what was taken", s.sha256, again.sha256)
        assertEquals(s.size, again.size)
    }

    @Test fun theSendersNameAndTypeDecideNothing() {
        val i = take(jpeg(), "../../evil.sh")
        val name = snapshot(i).name
        assertTrue(name, Regex("2026-10-11-143205-[A-Za-z0-9._-]+").matches(name))
        assertTrue("the extension is the sniff's, not the name's: $name", name.endsWith(".jpg"))
        assertFalse(name.contains("/"))
        // PDF bytes under an image name are taken as the PDF they are.
        val pdf = take("%PDF-1.7\n1 0 obj\n<<>>\nendobj\n".toByteArray(), "holiday.png")
        assertEquals("application/pdf", snapshot(pdf).mime)
        assertTrue(snapshot(pdf).name.endsWith(".pdf"))
    }

    @Test fun aTextFileIsTakenAsTextWithAnAllowedExtension() {
        val md = take("# notes\nsome text\n".toByteArray(), "notes.md")
        assertEquals("text/plain", snapshot(md).mime)
        assertTrue(snapshot(md).name.endsWith(".md"))
        val other = take("echo hi\n".toByteArray(), "run.sh")
        assertTrue(snapshot(other).name.endsWith(".txt"))
    }

    @Test fun whatIsNeitherAnImageNorAPdfNorTextIsRefusedAndLeavesNothingBehind() {
        val before = File(ctx.cacheDir, "incoming").list()?.toSet().orEmpty()
        val i = take(byteArrayOf(0x7F, 0x45, 0x4C, 0x46, 2, 1, 1, 0, 0, 0, 0, 0), "tool.jpg")
        assertEquals(Intake.Refused(IntakeRefusal.UnsupportedType), i)
        assertEquals(before, File(ctx.cacheDir, "incoming").list()?.toSet().orEmpty())
    }

    @Test fun aMalformedJpegIsRefusedNotSentWithItsMetadata() {
        val broken = jpegWithExif("SENTINEL-Z").let { it.copyOfRange(0, it.size / 2) }
        assertEquals(Intake.Refused(IntakeRefusal.ImageNotCleaned), take(broken))
    }

    @Test fun anImageThatCannotBeDecodedByTheBrandIsRefusedNotSentRaw() {
        // A HEIC brand with nothing behind it: API 28 and later cannot decode it, and before that the phone refuses HEIC outright. Either way it is not sent as it came.
        val heic = byteArrayOf(0, 0, 0, 24) + "ftypheic".toByteArray() + ByteArray(12)
        assertEquals(Intake.Refused(IntakeRefusal.ImageNotCleaned), take(heic, "IMG.heic"))
    }

    @Test fun aFileOverTheCapIsRefusedAtTheCapPlusOneByteAndTheStreamIsNotDrained() {
        var served = 0L
        val endless = object : InputStream() {
            override fun read(): Int { served++; return 7 }
            override fun read(b: ByteArray, off: Int, len: Int): Int { served += len; java.util.Arrays.fill(b, off, off + len, 7); return len }
        }
        val f = IncomingFiles(ctx, cache, admit, open = { endless })
        val r = runBlocking { f.take(Uri.parse("content://x/y"), "big.bin") }
        assertEquals(Intake.Refused(IntakeRefusal.TooLarge), r)
        assertTrue("read $served bytes", served <= TransferLimits.MAX_PUT_BYTES + 32 * 1024)
        assertTrue("no snapshot is left", File(ctx.cacheDir, "incoming").list().orEmpty().none { it in taken })
    }

    @Test fun aCameraCaptureIsGoneOnceItsSnapshotExists() {
        val id = UUID.randomUUID().toString()
        cache.cameraFile(id).writeBytes(jpegWithExif("SENTINEL-CAM"))
        val i = runBlocking { files.takeCapture(id) }
        taken += snapshot(i).id
        assertFalse("the raw capture is deleted", cache.cameraFile(id).exists())
        assertFalse(String(dataOf(i), Charsets.ISO_8859_1).contains("SENTINEL"))
    }

    @Test fun aCaptureThatIsNotThereIsRefusedAndStillCleansUp() {
        val id = UUID.randomUUID().toString()
        assertEquals(Intake.Refused(IntakeRefusal.Unreadable), runBlocking { files.takeCapture(id) })
    }

    @Test fun aSourceThatCannotBeOpenedIsRefused() {
        val f = IncomingFiles(ctx, cache, admit, open = { null })
        assertEquals(Intake.Refused(IntakeRefusal.Unreadable), runBlocking { f.take(Uri.parse("content://x/y"), "a.jpg") })
        val g = IncomingFiles(ctx, cache, admit, open = { throw SecurityException("no grant") })
        assertEquals(Intake.Refused(IntakeRefusal.Unreadable), runBlocking { g.take(Uri.parse("content://x/y"), "a.jpg") })
    }

    @Test fun aSweptSnapshotHasNoSourceNoFileAndNoSnapshot() {
        val s = snapshot(take(jpeg()))
        cache.deleteSnapshot(s.id)
        assertNull(files.source(s.id))
        assertNull(files.file(s.id))
        assertNull(runBlocking { files.snapshot(s.id, s.name, s.mime) })
    }
}
