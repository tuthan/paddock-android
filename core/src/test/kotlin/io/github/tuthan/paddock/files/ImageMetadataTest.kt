package io.github.tuthan.paddock.files

import io.github.tuthan.paddock.files.ImageFixtures.SENTINEL_BYTES
import io.github.tuthan.paddock.files.ImageFixtures.ascii
import io.github.tuthan.paddock.files.ImageFixtures.contains
import io.github.tuthan.paddock.files.ImageFixtures.segment
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.zip.CRC32
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * X4, SEC-7, SEC-8 and AC-15.22 on fixtures made here, with fake metadata that carries a sentinel. A message never holds
 * file bytes: a leak is reported as a leak, and a mutation by its number.
 */
class ImageMetadataTest {
    // ---- helpers ------------------------------------------------------------------------------------------------

    private fun stripJpeg(bytes: ByteArray, orientation: Int? = null): ByteArray {
        val out = ByteArrayOutputStream()
        val written = ImageMetadata.stripJpeg(ByteArrayInputStream(bytes), out, orientation)
        assertEquals(written, out.size().toLong())
        return out.toByteArray()
    }

    private fun stripPng(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val written = ImageMetadata.stripPng(ByteArrayInputStream(bytes), out)
        assertEquals(written, out.size().toLong())
        return out.toByteArray()
    }

    private fun refusal(body: () -> Unit): ImageRefusal = assertFailsWith<ImageRefused> { body() }.reason

    /** Runs [body] on a thread that gives up after [seconds]: a loop is a failure, not a hung build. */
    private fun <T> within(seconds: Long, body: () -> T): T {
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "image-test").apply { isDaemon = true } }
        try {
            return executor.submit(Callable { body() }).get(seconds, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (e: TimeoutException) {
            throw AssertionError("did not finish within ${seconds}s")
        } finally {
            executor.shutdownNow()
        }
    }

    private class Counting(private val head: ByteArray, private val fill: ByteArray) : InputStream() {
        var consumed = 0L
        private var at = 0L
        private fun next(): Int {
            val b = if (at < head.size) head[at.toInt()] else fill[((at - head.size) % fill.size).toInt()]
            at++
            return b.toInt() and 0xFF
        }
        override fun read(): Int { consumed++; return next() }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            for (i in 0 until len) b[off + i] = next().toByte()
            consumed += len
            return len
        }
    }

    /** An output that fails the test when something writes past [limit], instead of exhausting the heap. */
    private class Bounded(private val limit: Long) : OutputStream() {
        var size = 0L
        override fun write(b: Int) { count(1) }
        override fun write(b: ByteArray, off: Int, len: Int) { count(len) }
        private fun count(n: Int) { size += n; if (size > limit) throw AssertionError("wrote more than $limit bytes") }
    }

    private val hex = { n: Int -> "0x%02X".format(n) }

    /** What a JPEG written by the pass may hold, judged by a parser that is not the pass's. */
    private fun assertCleanJpeg(out: ByteArray, orientation: Int? = null, secretsAbsent: Boolean = true) {
        val parsed = ImageFixtures.parseJpeg(out)
        assertEquals(out.size, parsed.end, "bytes after the first EOI")
        var exifs = 0
        for (seg in parsed.segments) {
            val m = seg.marker
            when {
                m == 0xE0 -> {
                    assertTrue(seg.payload.size == 14 && String(seg.payload, 0, 5, Charsets.ISO_8859_1) == "JFIF\u0000", "APP0 is JFIF and nothing more")
                    assertEquals(0, seg.payload[12].toInt()); assertEquals(0, seg.payload[13].toInt()) // no thumbnail
                }
                m == 0xE1 -> {
                    exifs++
                    assertContentEquals(orientationApp1(orientation ?: error("an APP1 with no orientation asked for")).copyOfRange(4, 36), seg.payload)
                }
                m == 0xE2 -> assertTrue(String(seg.payload, 0, 12, Charsets.ISO_8859_1) == "ICC_PROFILE\u0000", "APP2 is only an ICC profile")
                m == 0xEE -> assertTrue(String(seg.payload, 0, 5, Charsets.ISO_8859_1) == "Adobe" && seg.payload.size == 12)
                m in setOf(0xC4, 0xCC, 0xDB, 0xDD, 0xDA) || m in 0xC0..0xCF && m != 0xC8 -> {}
                else -> throw AssertionError("a ${hex(m)} segment was kept")
            }
        }
        assertEquals(if (orientation == null) 0 else 1, exifs, "orientation blocks")
        assertTrue(parsed.scans >= 1)
        if (secretsAbsent) assertFalse(contains(out), "metadata leaked")
    }

    /** The 36-byte APP1 that carries only Orientation, written out here from the format, not from the pass. */
    private fun orientationApp1(o: Int): ByteArray = byteArrayOf(
        0xFF.toByte(), 0xE1.toByte(), 0, 0x22, 'E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0,
        'M'.code.toByte(), 'M'.code.toByte(), 0, 0x2A, 0, 0, 0, 8, 0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, o.toByte(), 0, 0, 0, 0, 0, 0,
    )

    private val pngAllowed = setOf(
        "IHDR", "PLTE", "IDAT", "IEND", "tRNS", "gAMA", "cHRM", "sRGB", "iCCP", "sBIT", "cICP", "mDCV", "cLLI", "pHYs", "bKGD", "acTL", "fcTL", "fdAT",
    )

    /** Every chunk is allowed, every CRC valid, IHDR first, IEND last and the last byte. */
    private fun assertCleanPng(out: ByteArray, secretsAbsent: Boolean = true) {
        assertContentEquals(ImageFixtures.PNG_SIG, out.copyOf(8))
        var i = 8
        var first = true
        while (true) {
            val length = ByteBuffer.wrap(out, i, 4).int
            val type = String(out, i + 4, 4, Charsets.ISO_8859_1)
            assertTrue(type in pngAllowed, "a $type chunk was kept")
            assertEquals(first, type == "IHDR")
            first = false
            val crc = CRC32().apply { update(out, i + 4, 4 + length) }.value
            assertEquals(crc, ByteBuffer.wrap(out, i + 8 + length, 4).int.toLong() and 0xFFFFFFFFL, "CRC of $type")
            i += 12 + length
            if (type == "IEND") break
        }
        assertEquals(out.size, i, "bytes after IEND")
        if (secretsAbsent) assertFalse(contains(out), "metadata leaked")
    }

    private fun assertSamePixels(a: ByteArray, b: ByteArray) {
        assertContentEquals(ImageFixtures.pixels(a), ImageFixtures.pixels(b), "decoded pixels")
    }

    // ---- sniff --------------------------------------------------------------------------------------------------

    private fun ftyp(brand: String, vararg compatible: String): ByteArray {
        val body = ascii("ftyp") + ascii(brand) + byteArrayOf(0, 0, 0, 0) + compatible.fold(ByteArray(0)) { a, c -> a + ascii(c) }
        return ByteBuffer.allocate(4).putInt(body.size + 4).array() + body + ByteArray(32)
    }

    @Test fun sniffReadsMagicBytesOnly() {
        assertEquals(ImageKind.Jpeg, ImageMetadata.sniff(ImageFixtures.jpeg(ImageFixtures.image(8, 8))))
        assertEquals(ImageKind.Png, ImageMetadata.sniff(ImageFixtures.png(ImageFixtures.image(8, 8))))
        assertEquals(ImageKind.Jpeg, ImageMetadata.sniff(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
        for (brand in listOf("heic", "heix", "mif1", "avif", "avis", "msf1", "heim")) {
            assertEquals(ImageKind.Heif, ImageMetadata.sniff(ftyp(brand)), brand)
        }
        assertEquals(ImageKind.Heif, ImageMetadata.sniff(ftyp("isom", "mp41", "heic")), "a compatible brand")
    }

    @Test fun sniffRefusesWhatIsNotAnImageItCanHandle() {
        assertNull(ImageMetadata.sniff(ByteArray(0)))
        assertNull(ImageMetadata.sniff(ascii("GIF89a....")))
        assertNull(ImageMetadata.sniff(ascii("%PDF-1.7")))
        assertNull(ImageMetadata.sniff(ascii("<svg xmlns=")))
        assertNull(ImageMetadata.sniff(ftyp("isom", "mp41", "mp42"))) // an MP4 video
        assertNull(ImageMetadata.sniff(ftyp("qt  ")))
        assertNull(ImageMetadata.sniff(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))) // too short to be sure
        assertNull(ImageMetadata.sniff(ImageFixtures.PNG_SIG.copyOf(7)))
        assertNull(ImageMetadata.sniff(ByteArray(11)))
        // A name or a claimed type is not an input: a PNG with a .jpg name is a PNG.
        assertEquals(ImageKind.Png, ImageMetadata.sniff(ImageFixtures.png(ImageFixtures.image(8, 8))))
    }

    // ---- JPEG ---------------------------------------------------------------------------------------------------

    @Test fun aDirtyJpegKeepsOnlyWhatDrawsItAndDecodesToTheSamePixels() {
        val dirty = ImageFixtures.dirtyJpeg()
        // The fixture really holds all of it.
        val before = ImageFixtures.markers(dirty)
        assertTrue(listOf(0xE0, 0xE1, 0xE2, 0xED, 0xFE, 0xE5, 0xEC, 0xEF).all { it in before })
        assertTrue(contains(dirty, ascii("${ImageFixtures.SENTINEL}GPS")) && contains(dirty, ascii("${ImageFixtures.SENTINEL}TRAILER")))
        val clean = stripJpeg(dirty, orientation = 6)
        assertCleanJpeg(clean, orientation = 6)
        val kept = ImageFixtures.markers(clean)
        assertTrue(0xE2 in kept && 0xEE in kept && 0xE0 in kept, "JFIF, ICC and Adobe stay")
        assertSamePixels(dirty, clean)
        assertTrue(clean.size < dirty.size)
    }

    @Test fun theIccProfileIsKeptWholeAndOtherApp2BlocksAreNot() {
        val dirty = ImageFixtures.dirtyJpeg()
        val icc = ImageFixtures.parseJpeg(dirty).segments.filter { it.marker == 0xE2 && String(it.payload, 0, 4, Charsets.ISO_8859_1) == "ICC_" }
        val out = ImageFixtures.parseJpeg(stripJpeg(dirty)).segments.filter { it.marker == 0xE2 }
        assertTrue(icc.isNotEmpty())
        assertEquals(icc.size, out.size)
        icc.zip(out).forEach { (a, b) -> assertContentEquals(a.payload, b.payload) }
    }

    @Test fun theJfifThumbnailIsDropped() {
        val dirty = ImageFixtures.dirtyJpeg()
        val jfif = ImageFixtures.parseJpeg(dirty).segments.first()
        assertEquals(0xE0, jfif.marker)
        assertEquals(20, jfif.payload.size) // 14 + 2 x 1 x 3
        val out = ImageFixtures.parseJpeg(stripJpeg(dirty)).segments.first()
        assertEquals(14, out.payload.size)
        assertContentEquals(jfif.payload.copyOf(12), out.payload.copyOf(12)) // version, units and density stay
    }

    @Test fun aProgressiveJpegKeepsEveryScan() {
        val dirty = ImageFixtures.dirtyJpeg(progressive = true)
        val scansBefore = ImageFixtures.parseJpeg(dirty).scans
        assertTrue(scansBefore > 1, "the fixture is progressive")
        val clean = stripJpeg(dirty, orientation = 3)
        assertCleanJpeg(clean, orientation = 3)
        assertEquals(scansBefore, ImageFixtures.parseJpeg(clean).scans)
        assertSamePixels(dirty, clean)
        assertTrue(0xC2 in ImageFixtures.markers(clean), "SOF2")
    }

    @Test fun aMotionPhotoArrivesAsItsStillAndEndsAtTheFirstEoi() {
        val dirty = ImageFixtures.dirtyJpeg(motionPhoto = true)
        assertTrue(contains(dirty, ascii("Camera:MotionPhoto")) && contains(dirty, ascii("${ImageFixtures.SENTINEL}VIDEO")))
        // The still alone, as the JDK wrote it, plus what is kept: where the primary image ends is the first EOI.
        val primaryEnd = ImageFixtures.parseJpeg(dirty).end
        assertTrue(primaryEnd < dirty.size - 400, "a gain map and a video follow")
        val clean = stripJpeg(dirty)
        assertCleanJpeg(clean)
        assertFalse(contains(clean, ascii("ftyp")) || contains(clean, ascii("MotionPhoto")))
        assertEquals(1, clean.indices.count { it + 1 < clean.size && clean[it] == 0xFF.toByte() && clean[it + 1] == 0xD8.toByte() }, "one SOI: no gain map")
        assertSamePixels(dirty, clean)
    }

    @Test fun anExifThumbnailsOwnEoiDoesNotEndTheImage() {
        val dirty = ImageFixtures.dirtyJpeg()
        val thumbnailEoi = ImageFixtures.indexOf(dirty, byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
        assertTrue(thumbnailEoi in 0 until ImageFixtures.parseJpeg(dirty).firstEoiOffset, "an EOI sits inside the Exif block")
        val clean = stripJpeg(dirty)
        assertEquals(1, ImageFixtures.parseJpeg(clean).scans)
        assertSamePixels(dirty, clean)
    }

    @Test fun aJpegWithNothingToDropComesOutByteForByte() {
        val base = ImageFixtures.jpeg(ImageFixtures.image())
        assertContentEquals(base, stripJpeg(base))
        val progressive = ImageFixtures.jpeg(ImageFixtures.image(), progressive = true)
        assertContentEquals(progressive, stripJpeg(progressive))
        // Metadata and a trailer only: what is left is exactly the image.
        val onlyDropped = ImageFixtures.withSegments(base, listOf(ImageFixtures.exif(null, ImageFixtures.jpeg(ImageFixtures.image(8, 8))), ImageFixtures.xmp(false), ImageFixtures.iptc(), ImageFixtures.mpf(), ImageFixtures.comment()))
        assertContentEquals(base, stripJpeg(onlyDropped + ImageFixtures.gainMapAndVideo()))
    }

    @Test fun orientationIsKeptAsANewBlockAfterJfifAndNothingElse() {
        val base = ImageFixtures.jpeg(ImageFixtures.image())
        for (o in 2..8) {
            val dirty = ImageFixtures.withSegments(base, listOf(ImageFixtures.exif(o, ImageFixtures.jpeg(ImageFixtures.image(8, 8)), littleEndian = o % 2 == 0), ImageFixtures.xmp(false)))
            assertEquals(o, ImageMetadata.jpegOrientation(dirty), "read from the original")
            val clean = stripJpeg(dirty, orientation = ImageMetadata.jpegOrientation(dirty))
            assertCleanJpeg(clean, orientation = o)
            assertEquals(o, ImageMetadata.jpegOrientation(clean), "kept")
            assertContentEquals(ImageFixtures.withSegments(base, listOf(orientationApp1(o))), clean)
        }
    }

    @Test fun orientationOneNullOrOutOfRangeWritesNoBlock() {
        val dirty = ImageFixtures.dirtyJpeg(orientation = 1)
        for (o in listOf(null, 1, 0, 9, -1, 100)) {
            val clean = stripJpeg(dirty, orientation = o)
            assertCleanJpeg(clean, orientation = null)
            assertNull(ImageMetadata.jpegOrientation(clean))
        }
        assertEquals(1, ImageMetadata.jpegOrientation(dirty))
    }

    @Test fun orientationIsWrittenBeforeTheFirstKeptSegmentWhenThereIsNoJfif() {
        val base = ImageFixtures.jpeg(ImageFixtures.image())
        val jfifEnd = 4 + (((base[4].toInt() and 0xFF) shl 8) or (base[5].toInt() and 0xFF))
        val noJfif = base.copyOfRange(0, 2) + base.copyOfRange(jfifEnd, base.size)
        val clean = stripJpeg(noJfif, orientation = 8)
        assertEquals(listOf(0xE1, 0xDB), ImageFixtures.markers(clean).take(2))
        assertCleanJpeg(clean, orientation = 8)
    }

    @Test fun jpegOrientationReadsBothByteOrdersAndNeverThrows() {
        for (littleEndian in listOf(false, true)) {
            for (o in 1..8) assertEquals(o, ImageMetadata.jpegOrientation(ImageFixtures.dirtyJpeg(orientation = o, littleEndian = littleEndian)), "order $littleEndian value $o")
        }
        assertNull(ImageMetadata.jpegOrientation(ImageFixtures.dirtyJpeg(orientation = null)))
        assertNull(ImageMetadata.jpegOrientation(ImageFixtures.jpeg(ImageFixtures.image())))
        assertNull(ImageMetadata.jpegOrientation(ByteArray(0)))
        assertNull(ImageMetadata.jpegOrientation(ascii("not a jpeg")))
        val dirty = ImageFixtures.dirtyJpeg()
        val exifEnd = 2 + (2 + 2 + 20) + 2 + 2 + ImageFixtures.parseJpeg(dirty).segments[1].payload.size // SOI, the JFIF with its thumbnail, the Exif block
        for (cut in 0 until exifEnd) assertNull(ImageMetadata.jpegOrientation(dirty.copyOf(cut)), "cut at $cut")
        assertEquals(6, ImageMetadata.jpegOrientation(dirty.copyOf(exifEnd)))
        assertNull(ImageMetadata.jpegOrientation(ImageFixtures.withSegments(ImageFixtures.jpeg(ImageFixtures.image(8, 8)), listOf(segment(0xE1, ascii("Exif\u0000\u0000II*\u0000ÿÿÿÿ"))))))
        val rnd = Random(7)
        repeat(500) { ImageMetadata.jpegOrientation(ByteArray(rnd.nextInt(200)) { (if (it < 2) 0xFF else rnd.nextInt(256)).toByte() }) }
    }

    @Test fun anOrientationOutsideOneToEightIsNotReadBack() {
        val dirty = ImageFixtures.dirtyJpeg(orientation = 9)
        assertNull(ImageMetadata.jpegOrientation(dirty))
        val zero = ImageFixtures.dirtyJpeg(orientation = 0)
        assertNull(ImageMetadata.jpegOrientation(zero))
    }

    @Test fun restartMarkersAndByteStuffingAreCopiedAndFillBytesGo() {
        val f = 0xFF.toByte()
        val d9 = 0xD9.toByte()
        fun jpegWith(scanAndEnd: ByteArray) = byteArrayOf(f, 0xD8.toByte()) +
            segment(0xDB, byteArrayOf(0) + ByteArray(64) { 1 }) +
            segment(0xC0, byteArrayOf(8, 0, 1, 0, 1, 1, 1, 0x11, 0)) +
            segment(0xDD, byteArrayOf(0, 2)) +
            segment(0xC4, byteArrayOf(0x00) + byteArrayOf(0, 1) + ByteArray(14) + byteArrayOf(0)) +
            segment(0xDA, byteArrayOf(1, 1, 0, 0, 63, 0)) + scanAndEnd
        fun synthetic(entropy: ByteArray) = jpegWith(entropy + byteArrayOf(f, d9))
        val entropy = byteArrayOf(0x12, f, 0, 0x34, f, 0xD0.toByte(), 0x56, f, 0, f, 0, 0x78, f, 0xD7.toByte(), 0x9A.toByte())
        assertContentEquals(synthetic(entropy), stripJpeg(synthetic(entropy)))
        // Fill bytes before a restart marker or before EOI are padding and go; everything else stays.
        val padded = byteArrayOf(0x12, f, f, f, 0xD1.toByte(), 0x34, f, f, 0, 0x56)
        assertContentEquals(synthetic(byteArrayOf(0x12, f, 0xD1.toByte(), 0x34, f, 0, 0x56)), stripJpeg(synthetic(padded)))
        assertContentEquals(synthetic(byteArrayOf(0x12)), stripJpeg(jpegWith(byteArrayOf(0x12, f, f, f, d9))))
    }

    @Test fun otherSegmentsInTheAllowlistComeThroughAndTheRestAreDropped() {
        val f = 0xFF.toByte()
        val sof = segment(0xC0, byteArrayOf(8, 0, 1, 0, 1, 1, 1, 0x11, 0))
        val scanAndEnd = segment(0xDA, byteArrayOf(1, 1, 0, 0, 63, 0)) + byteArrayOf(0x12, f, 0xD9.toByte())
        val kept = listOf(segment(0xDB, byteArrayOf(0) + ByteArray(64)), segment(0xC4, ByteArray(20)), segment(0xCC, byteArrayOf(0, 1)), segment(0xDD, byteArrayOf(0, 8)))
        val dropped = listOf(segment(0xC8, ByteArray(4)), segment(0xF0, ByteArray(4)), segment(0xFD, ByteArray(4)), segment(0xFE, ByteArray(4)), segment(0xE3, ByteArray(10)), segment(0xE4, ascii("Meta\u0000")))
        val soi = byteArrayOf(f, 0xD8.toByte())
        val input = soi + dropped.fold(ByteArray(0)) { a, b -> a + b } + kept.fold(ByteArray(0)) { a, b -> a + b } + sof + scanAndEnd
        assertContentEquals(soi + kept.fold(ByteArray(0)) { a, b -> a + b } + sof + scanAndEnd, stripJpeg(input))
        // Every SOF kind is kept, the three that are not SOF are decided by their own rule.
        for (m in (0xC0..0xCF) - setOf(0xC4, 0xC8, 0xCC)) {
            val out = stripJpeg(soi + segment(m, byteArrayOf(8, 0, 1, 0, 1, 1, 1, 0x11, 0)) + scanAndEnd)
            assertEquals(listOf(m, 0xDA), ImageFixtures.markers(out), "SOF ${hex(m)}")
        }
    }

    @Test fun aJpegThatIsNotWellFormedIsRefusedWithItsReason() {
        val f = 0xFF.toByte()
        val soi = byteArrayOf(f, 0xD8.toByte())
        val sof = segment(0xC0, byteArrayOf(8, 0, 1, 0, 1, 1, 1, 0x11, 0))
        val sos = segment(0xDA, byteArrayOf(1, 1, 0, 0, 63, 0))
        val eoi = byteArrayOf(f, 0xD9.toByte())
        val table: List<Pair<ImageRefusal, ByteArray>> = listOf(
            ImageRefusal.Truncated to ByteArray(0),
            ImageRefusal.Truncated to soi,
            ImageRefusal.NotJpeg to ascii("GIF89a"),
            ImageRefusal.NotJpeg to ImageFixtures.PNG_SIG + ByteArray(40),
            ImageRefusal.NoImage to soi + eoi,
            ImageRefusal.NoImage to soi + sof + eoi,
            ImageRefusal.NoImage to soi + sos + byteArrayOf(1) + eoi,
            ImageRefusal.Truncated to soi + sof + sos + byteArrayOf(1, 2, 3),
            ImageRefusal.Truncated to soi + sof + sos + byteArrayOf(1, 2, f),
            ImageRefusal.Truncated to soi + sof + byteArrayOf(f, 0xFE.toByte(), 0, 10, 1, 2),
            ImageRefusal.BadLength to soi + byteArrayOf(f, 0xDB.toByte(), 0, 1),
            ImageRefusal.BadLength to soi + byteArrayOf(f, 0xFE.toByte(), 0, 0),
            ImageRefusal.BadLength to soi + segment(0xC0, byteArrayOf(8, 0)) + sos + eoi,
            ImageRefusal.BadMarker to soi + byteArrayOf(0, 0, 0, 0),
            ImageRefusal.BadMarker to soi + sof + byteArrayOf(7, 7, 7), // bytes between segments
            ImageRefusal.BadMarker to soi + byteArrayOf(f, 0xD0.toByte()),
            ImageRefusal.BadMarker to soi + byteArrayOf(f, 0xD8.toByte()),
            ImageRefusal.BadMarker to soi + byteArrayOf(f, 0x02, 0, 2),
            ImageRefusal.BadMarker to soi + byteArrayOf(f, 0x00, 0, 2),
            ImageRefusal.BadMarker to soi + ByteArray(2_000) { f },
            ImageRefusal.Unsupported to soi + segment(0xDC, byteArrayOf(0, 1)) + sof + sos + eoi,
            ImageRefusal.Unsupported to soi + segment(0xDE, byteArrayOf(0, 1)),
            ImageRefusal.Unsupported to soi + segment(0xDF, byteArrayOf(0, 1)),
            ImageRefusal.TooManySegments to soi + ByteArray(4 * 10_001) { byteArrayOf(f, 0xFE.toByte(), 0, 2)[it % 4] },
        )
        for ((i, row) in table.withIndex()) {
            val (expected, input) = row
            assertEquals(expected, refusal { stripJpeg(input) }, "row $i")
        }
    }

    @Test fun anEndlessRunOfFillBytesIsRefusedAfterAFewKilobytes() {
        val input = Counting(byteArrayOf(0xFF.toByte(), 0xD8.toByte()), byteArrayOf(0xFF.toByte()))
        assertEquals(ImageRefusal.BadMarker, refusal { within(10) { ImageMetadata.stripJpeg(input, Bounded(1_000), null) } })
        assertTrue(input.consumed <= 64 * 1024, "read ${input.consumed} bytes of fill")
        // Inside a scan the same: a run of fill before a marker never ends.
        val scan = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + segment(0xC0, byteArrayOf(8, 0, 1, 0, 1, 1, 1, 0x11, 0)) + segment(0xDA, byteArrayOf(1, 1, 0, 0, 63, 0)) + byteArrayOf(1)
        val inScan = Counting(scan, byteArrayOf(0xFF.toByte()))
        assertEquals(ImageRefusal.BadMarker, refusal { within(10) { ImageMetadata.stripJpeg(inScan, Bounded(1_000_000), null) } })
        assertTrue(inScan.consumed <= 64 * 1024, "read ${inScan.consumed} bytes in a scan")
    }

    @Test fun aScanThatNeverEndsIsCutAtTheTransferCap() {
        val head = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + segment(0xC0, byteArrayOf(8, 0, 1, 0, 1, 1, 1, 0x11, 0)) + segment(0xDA, byteArrayOf(1, 1, 0, 0, 63, 0))
        val input = Counting(head, byteArrayOf(0x55))
        val out = Bounded(TransferLimits.MAX_PUT_BYTES + 1_000_000)
        assertEquals(ImageRefusal.TooLarge, refusal { within(30) { ImageMetadata.stripJpeg(input, out, null) } })
        assertTrue(out.size <= TransferLimits.MAX_PUT_BYTES + 100_000)
    }

    @Test fun theStripNeverReadsPastTheEnd() {
        val png = ImageFixtures.dirtyPng()
        val after = Counting(png, byteArrayOf(0x41))
        ImageMetadata.stripPng(after, ByteArrayOutputStream())
        assertTrue(after.consumed <= png.size + 64 * 1024, "read ${after.consumed}")
        val jpeg = ImageFixtures.dirtyJpeg(motionPhoto = true)
        val counted = Counting(jpeg, byteArrayOf(0x42))
        ImageMetadata.stripJpeg(counted, ByteArrayOutputStream(), null)
        assertTrue(counted.consumed <= jpeg.size + 64 * 1024, "read ${counted.consumed}")
    }

    // ---- PNG ----------------------------------------------------------------------------------------------------

    @Test fun aDirtyPngKeepsOnlyItsRenderingChunksAndEndsAtIend() {
        val dirty = ImageFixtures.dirtyPng()
        val before = ImageFixtures.pngTypes(dirty)
        assertTrue(listOf("tEXt", "tIME", "eXIf", "iTXt", "zTXt", "prVt", "sPLT", "hIST").all { it in before })
        assertTrue(dirty.size > ImageFixtures.pngEnd(dirty) + 300, "bytes follow IEND")
        val clean = stripPng(dirty)
        assertCleanPng(clean)
        assertEquals(listOf("IHDR", "gAMA", "sRGB", "pHYs"), ImageFixtures.pngTypes(clean).takeWhile { it != "IDAT" })
        assertSamePixels(dirty, clean)
        // And exactly the image: IHDR, the three, the IDATs, IEND.
        val base = ImageFixtures.png(ImageFixtures.image())
        val expected = ImageFixtures.pngWith(base, listOf(ImageFixtures.chunk("gAMA", ByteBuffer.allocate(4).putInt(45455).array()), ImageFixtures.chunk("sRGB", byteArrayOf(0)), ImageFixtures.chunk("pHYs", ByteBuffer.allocate(9).putInt(2835).putInt(2835).put(1).array())))
        assertContentEquals(expected, clean)
    }

    @Test fun everyKeptChunkIncludingApngsIsCopiedAsItIs() {
        val dirty = ImageFixtures.allKeptPng()
        val clean = stripPng(dirty)
        assertCleanPng(clean)
        val kept = ImageFixtures.pngTypes(clean)
        for (type in listOf("gAMA", "cHRM", "iCCP", "sBIT", "cICP", "mDCV", "cLLI", "pHYs", "bKGD", "tRNS", "acTL", "fcTL", "fdAT", "IDAT", "IEND")) assertTrue(type in kept, "$type kept")
        assertEquals(listOf("fcTL", "fdAT"), kept.dropWhile { it != "IDAT" }.filter { it == "fcTL" || it == "fdAT" })
        assertContentEquals(ImageFixtures.allKeptPng(dirty = false), clean)
        assertSamePixels(dirty, clean)
    }

    @Test fun aCleanPngComesOutByteForByte() {
        val base = ImageFixtures.png(ImageFixtures.image())
        assertContentEquals(base, stripPng(base))
    }

    @Test fun aPngThatIsNotWellFormedIsRefusedWithItsReason() {
        val sig = ImageFixtures.PNG_SIG
        val ihdr = ImageFixtures.chunk("IHDR", ByteBuffer.allocate(13).putInt(1).putInt(1).put(8).put(2).put(0).put(0).put(0).array())
        val idat = ImageFixtures.chunk("IDAT", ByteArray(10))
        val iend = ImageFixtures.chunk("IEND", ByteArray(0))
        val table: List<Pair<ImageRefusal, ByteArray>> = listOf(
            ImageRefusal.Truncated to ByteArray(0),
            ImageRefusal.Truncated to sig,
            ImageRefusal.NotPng to ascii("GIF89a....."),
            ImageRefusal.NotPng to byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(30),
            ImageRefusal.BadChunk to sig + idat + iend,
            ImageRefusal.BadChunk to sig + ImageFixtures.chunk("tEXt", ByteArray(2)) + ihdr + idat + iend,
            ImageRefusal.BadChunk to sig + ihdr + ihdr + idat + iend,
            ImageRefusal.BadChunk to sig + ihdr + ImageFixtures.chunk("ID4T", ByteArray(2)) + idat + iend,
            ImageRefusal.BadLength to sig + ImageFixtures.chunk("IHDR", ByteArray(12)) + idat + iend,
            ImageRefusal.BadLength to sig + ihdr + idat + ImageFixtures.chunk("IEND", ByteArray(1)),
            ImageRefusal.BadLength to sig + ByteBuffer.allocate(8).putInt(0x7FFFFFFF).put(ascii("IHDR")).array(),
            ImageRefusal.Unsupported to sig + ihdr + ImageFixtures.chunk("ABCD", ByteArray(2)) + idat + iend,
            ImageRefusal.NoImage to sig + ihdr + iend,
            ImageRefusal.Truncated to sig + ihdr + idat,
            ImageRefusal.Truncated to sig + ihdr + idat.copyOf(15),
            ImageRefusal.BadCrc to sig + ihdr + ImageFixtures.chunk("IDAT", ByteArray(10), badCrc = true) + iend,
            ImageRefusal.BadCrc to sig + ihdr + ImageFixtures.chunk("gAMA", ByteArray(4), badCrc = true) + idat + iend,
            ImageRefusal.BadCrc to sig + ImageFixtures.chunk("IHDR", ByteArray(13), badCrc = true) + idat + iend,
        )
        for ((i, row) in table.withIndex()) {
            val (expected, input) = row
            assertEquals(expected, refusal { stripPng(input) }, "row $i")
        }
        // A chunk that is dropped is not read for its CRC: it is skipped.
        val dropped = sig + ihdr + ImageFixtures.chunk("tEXt", ByteArray(5), badCrc = true) + idat + iend
        assertContentEquals(sig + ihdr + idat + iend, stripPng(dropped))
    }

    @Test fun aChunkLongerThanTheCapIsRefusedWithoutReadingIt() {
        val head = ImageFixtures.PNG_SIG + ImageFixtures.chunk("IHDR", ByteBuffer.allocate(13).putInt(1).putInt(1).put(8).put(2).put(0).put(0).put(0).array()) +
            ByteBuffer.allocate(8).putInt(0x7FFFFFFF).put(ascii("IDAT")).array()
        val input = Counting(head, byteArrayOf(0))
        assertEquals(ImageRefusal.BadLength, refusal { within(10) { ImageMetadata.stripPng(input, Bounded(1_000_000)) } })
        assertTrue(input.consumed <= 64 * 1024, "read ${input.consumed}")
    }

    @Test fun aStreamOfEmptyChunksIsCutAtTheChunkCount() {
        val head = ImageFixtures.PNG_SIG + ImageFixtures.chunk("IHDR", ByteBuffer.allocate(13).putInt(1).putInt(1).put(8).put(2).put(0).put(0).put(0).array())
        val input = Counting(head, ImageFixtures.chunk("tEXt", ByteArray(0)))
        assertEquals(ImageRefusal.TooManySegments, refusal { within(60) { ImageMetadata.stripPng(input, Bounded(1_000)) } })
    }

    @Test fun anEndlessChunkIsCutAtTheTransferCap() {
        // A kept chunk of the longest allowed length, then no end: the total read is capped.
        val head = ImageFixtures.PNG_SIG + ImageFixtures.chunk("IHDR", ByteBuffer.allocate(13).putInt(1).putInt(1).put(8).put(2).put(0).put(0).put(0).array()) +
            ByteBuffer.allocate(8).putInt(TransferLimits.MAX_PUT_BYTES.toInt()).put(ascii("IDAT")).array()
        val input = Counting(head, byteArrayOf(7))
        assertEquals(ImageRefusal.TooLarge, refusal { within(30) { ImageMetadata.stripPng(input, Bounded(TransferLimits.MAX_PUT_BYTES + 1_000_000)) } })
    }

    // ---- Pixels -------------------------------------------------------------------------------------------------

    private fun pngHeader(width: Long, height: Long): ByteArray =
        ImageFixtures.PNG_SIG + ByteBuffer.allocate(8 + 8).putInt(13).put(ascii("IHDR")).putInt(width.toInt()).putInt(height.toInt()).array()

    private fun jpegHeader(marker: Int, width: Int, height: Int): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + segment(0xE1, ByteArray(300)) + segment(0xC4, ByteArray(30)) +
            segment(marker, byteArrayOf(8, (height shr 8).toByte(), height.toByte(), (width shr 8).toByte(), width.toByte(), 1, 1, 0x11, 0))

    private fun allocated(block: () -> Unit): Long? {
        val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean ?: return null
        if (!bean.isThreadAllocatedMemorySupported) return null
        bean.isThreadAllocatedMemoryEnabled = true
        val id = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(id)
        block()
        return bean.getThreadAllocatedBytes(id) - before
    }

    @Test fun theHeadersOfRealImagesGiveTheirPixelCount() {
        assertEquals(96L * 64, ImageMetadata.imagePixels(ImageFixtures.png(ImageFixtures.image())))
        assertEquals(96L * 64, ImageMetadata.imagePixels(ImageFixtures.jpeg(ImageFixtures.image())))
        assertEquals(96L * 64, ImageMetadata.imagePixels(ImageFixtures.dirtyJpeg())) // after Exif, XMP, IPTC and the rest
        assertEquals(96L * 64, ImageMetadata.imagePixels(ImageFixtures.dirtyJpeg(progressive = true, motionPhoto = true)))
        assertEquals(96L * 64, ImageMetadata.imagePixels(ImageFixtures.dirtyPng()))
        assertEquals(96L * 64, ImageMetadata.imagePixels(ByteArrayInputStream(ImageFixtures.dirtyJpeg())))
        for (marker in listOf(0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC9, 0xCA, 0xCF)) assertEquals(640L * 480, ImageMetadata.imagePixels(jpegHeader(marker, 640, 480)), "SOF ${hex(marker)}")
    }

    @Test fun aHeaderThatClaimsHugeIsRefusedBeforeAnyAllocation() {
        // The header alone: no pixel data exists, and none may be wanted.
        val png = pngHeader(100_000, 100_000)
        assertEquals(10_000_000_000L, ImageMetadata.imagePixels(png))
        assertEquals(ImageRefusal.TooManyPixels, refusal { ImageMetadata.requireWithinPixelBound(png) })
        // A JPEG cannot declare 100,000 (its sizes are 16-bit); 65,535 by 65,535 is the most it can.
        val jpeg = jpegHeader(0xC0, 65_535, 65_535)
        assertEquals(65_535L * 65_535, ImageMetadata.imagePixels(jpeg))
        assertEquals(ImageRefusal.TooManyPixels, refusal { ImageMetadata.requireWithinPixelBound(jpeg) })
        assertEquals(ImageRefusal.TooManyPixels, refusal { ImageMetadata.requireWithinPixelBound(jpegHeader(0xC2, 20_000, 20_000)) })
        // Nothing sized by the claim is allocated: a 10^10-pixel image would need gigabytes.
        val grew = allocated { repeat(20) { assertFailsWith<ImageRefused> { ImageMetadata.requireWithinPixelBound(png) } } }
        if (grew != null) assertTrue(grew < 2L * 1024 * 1024, "allocated $grew bytes for 20 refusals")
    }

    @Test fun theBoundIsInclusive() {
        assertEquals(50_000_000L, ImageMetadata.MAX_DECODE_PIXELS)
        ImageMetadata.requireWithinPixelBound(pngHeader(10_000, 5_000))
        ImageMetadata.requireWithinPixelBound(jpegHeader(0xC0, 7_071, 7_071))
        assertEquals(ImageRefusal.TooManyPixels, refusal { ImageMetadata.requireWithinPixelBound(pngHeader(10_000, 5_001)) })
        assertEquals(ImageRefusal.TooManyPixels, refusal { ImageMetadata.requireWithinPixelBound(jpegHeader(0xC0, 7_072, 7_071)) })
        assertTrue(ImageMetadata.pixelsWithinBound(10_000, 5_000))
        assertFalse(ImageMetadata.pixelsWithinBound(10_000, 5_001))
        assertFalse(ImageMetadata.pixelsWithinBound(0, 5))
        assertFalse(ImageMetadata.pixelsWithinBound(-1, 5))
        assertFalse(ImageMetadata.pixelsWithinBound(Int.MAX_VALUE.toLong() + 1, 1))
        assertFalse(ImageMetadata.pixelsWithinBound(Long.MAX_VALUE, Long.MAX_VALUE)) // the product would overflow
    }

    @Test fun aHeaderWithNoReadableSizeIsRefusedAndNeverGuessed() {
        val table = listOf(
            ByteArray(0), ascii("hello"), ImageFixtures.PNG_SIG, ImageFixtures.PNG_SIG + ByteArray(20),
            pngHeader(0, 5), pngHeader(5, 0), pngHeader(0x80000000L, 2), pngHeader(0xFFFFFFFFL, 0xFFFFFFFFL),
            jpegHeader(0xC0, 0, 5), jpegHeader(0xC0, 5, 0), byteArrayOf(0xFF.toByte(), 0xD8.toByte()), ftypForPixels(),
            ImageFixtures.dirtyJpeg().copyOf(100), // cut before the frame header
            byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + segment(0xDB, ByteArray(65)) + segment(0xDA, ByteArray(6)), // a scan before any frame
        )
        for ((i, head) in table.withIndex()) {
            assertNull(ImageMetadata.imagePixels(head), "row $i")
            assertEquals(ImageRefusal.Dimensions, refusal { ImageMetadata.requireWithinPixelBound(head) }, "row $i")
        }
    }

    private fun ftypForPixels(): ByteArray = ByteBuffer.allocate(4).putInt(24).array() + ascii("ftypheic") + ByteArray(12)

    // ---- Hostile inputs, seeded ---------------------------------------------------------------------------------

    private class Fixture(val name: String, val bytes: ByteArray, val jpeg: Boolean, val lengthOffsets: List<Int>)

    private fun pngLengthOffsets(png: ByteArray): List<Int> {
        val out = ArrayList<Int>()
        var i = 8
        while (i < png.size) {
            out += i
            val len = ByteBuffer.wrap(png, i, 4).int
            val type = String(png, i + 4, 4, Charsets.ISO_8859_1)
            i += 12 + len
            if (type == "IEND") break
        }
        return out
    }

    private val fixtures: List<Fixture> by lazy {
        fun jpeg(name: String, bytes: ByteArray) = Fixture(name, bytes, true, ImageFixtures.lengthOffsets(bytes))
        fun png(name: String, bytes: ByteArray) = Fixture(name, bytes, false, pngLengthOffsets(bytes))
        listOf(
            jpeg("baseline", ImageFixtures.dirtyJpeg()),
            jpeg("progressive", ImageFixtures.dirtyJpeg(progressive = true, littleEndian = true)),
            jpeg("motion photo", ImageFixtures.dirtyJpeg(motionPhoto = true)),
            jpeg("no metadata", ImageFixtures.jpeg(ImageFixtures.image(40, 40, 3))),
            png("dirty", ImageFixtures.dirtyPng()),
            png("all kept (APNG)", ImageFixtures.allKeptPng()),
        )
    }

    private fun mutate(f: Fixture, rnd: Random): ByteArray {
        val b = f.bytes
        return when (rnd.nextInt(6)) {
            0 -> b.copyOf(rnd.nextInt(b.size)) // truncation
            1 -> b.copyOf().also { out -> repeat(rnd.nextInt(1, 9)) { out[rnd.nextInt(out.size)] = rnd.nextInt(256).toByte() } } // flipped bytes
            2 -> b.copyOf().also { out -> // an inflated length
                val at = f.lengthOffsets[rnd.nextInt(f.lengthOffsets.size)]
                val size = if (f.jpeg) 2 else 4
                val v = if (rnd.nextBoolean()) -1 else rnd.nextInt()
                for (k in 0 until size) out[at + k] = (v shr (8 * (size - 1 - k))).toByte()
            }
            3 -> { // an endless-looking run of 0xFF inserted
                val at = rnd.nextInt(b.size)
                b.copyOf(at) + ByteArray(rnd.nextInt(1, 200_000)) { 0xFF.toByte() } + b.copyOfRange(at, b.size)
            }
            4 -> b.copyOf().also { out -> // a region overwritten with 0xFF
                val at = rnd.nextInt(out.size)
                java.util.Arrays.fill(out, at, minOf(out.size, at + rnd.nextInt(1, 5_000)), 0xFF.toByte())
            }
            else -> b.copyOf().also { out -> // a length cut to zero or one
                val at = f.lengthOffsets[rnd.nextInt(f.lengthOffsets.size)]
                val size = if (f.jpeg) 2 else 4
                for (k in 0 until size) out[at + k] = (if (k == size - 1) rnd.nextInt(2) else 0).toByte()
            }
        }
    }

    @Test fun seededMutationsEndInARefusalOrABoundedAllowlistedOutputWithinATimeLimit() {
        for (f in fixtures) {
            within(120) {
                val rnd = Random(20261011 + f.name.hashCode())
                val rounds = if (f.jpeg) 600 else 1_500 // a PNG's CRCs refuse most, so it takes more to reach the rest
                var refused = 0
                var passed = 0
                repeat(rounds) { round ->
                    val input = mutate(f, rnd)
                    val out = ByteArrayOutputStream()
                    try {
                        if (f.jpeg) ImageMetadata.stripJpeg(ByteArrayInputStream(input), out, 1 + round % 8) else ImageMetadata.stripPng(ByteArrayInputStream(input), out)
                        passed++
                        assertTrue(out.size() <= input.size + 40, "${f.name} round $round: the output grew past its input")
                        if (f.jpeg) assertAllowlistedJpeg(out.toByteArray(), "${f.name} round $round") else assertCleanPng(out.toByteArray(), secretsAbsent = false)
                    } catch (e: ImageRefused) {
                        refused++
                    }
                }
                assertTrue(refused > 0 && passed + refused == rounds, "${f.name}: $refused refused, $passed passed")
            }
        }
    }

    /** Structure only: a mutated file may turn metadata into a marker the pass keeps, and what it keeps must be bounded and ordered. */
    private fun assertAllowlistedJpeg(out: ByteArray, where: String) {
        val parsed = try { ImageFixtures.parseJpeg(out) } catch (e: Exception) { throw AssertionError("$where: the output is not a JPEG structure") }
        assertEquals(out.size, parsed.end, "$where: bytes after the first EOI")
        for (seg in parsed.segments) {
            val m = seg.marker
            assertTrue(m in 0xC0..0xCF && m != 0xC8 || m in setOf(0xDB, 0xDD, 0xDA, 0xE0, 0xE1, 0xE2, 0xEE), "$where: a ${hex(m)} segment was kept")
            if (m == 0xE0) assertTrue(seg.payload.size == 14 && seg.payload[12].toInt() == 0 && seg.payload[13].toInt() == 0, "$where: APP0")
            if (m == 0xE1) assertContentEquals(orientationApp1(seg.payload[25].toInt()).copyOfRange(4, 36), seg.payload, "$where: APP1") // only ours: Orientation
            if (m == 0xE2) assertTrue(String(seg.payload, 0, minOf(12, seg.payload.size), Charsets.ISO_8859_1) == "ICC_PROFILE\u0000", "$where: APP2")
            if (m == 0xEE) assertTrue(seg.payload.size == 12, "$where: APP14")
        }
    }

    @Test fun everyTruncationOfTheImageIsRefusedAndACutAfterItChangesNothing() {
        // Not a sample: a cut every seven bytes of the small fixtures, so no point in the image is left to luck.
        for (f in fixtures.filter { it.bytes.size < 40_000 }) {
            within(120) {
                val end = if (f.jpeg) ImageFixtures.parseJpeg(f.bytes).end else ImageFixtures.pngEnd(f.bytes)
                fun run(input: ByteArray): ByteArray {
                    val out = ByteArrayOutputStream()
                    val stream = ByteArrayInputStream(input)
                    if (f.jpeg) ImageMetadata.stripJpeg(stream, out, 6) else ImageMetadata.stripPng(stream, out)
                    return out.toByteArray()
                }
                val whole = run(f.bytes)
                for (cut in 0 until f.bytes.size step 7) {
                    if (cut < end) {
                        assertEquals(ImageRefusal.Truncated, refusal { run(f.bytes.copyOf(cut)) }, "${f.name} cut at $cut")
                    } else {
                        assertContentEquals(whole, run(f.bytes.copyOf(cut).let { if (cut < end) it else it }), "${f.name} cut at $cut, after the image")
                    }
                }
                assertContentEquals(whole, run(f.bytes.copyOf(end)), "${f.name}: cut exactly at the end")
            }
        }
    }
}
