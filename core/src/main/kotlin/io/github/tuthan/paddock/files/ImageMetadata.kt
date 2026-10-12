package io.github.tuthan.paddock.files

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32

enum class ImageKind {
    Jpeg,
    Png,

    /** The HEIF family by brand: HEIC, AVIF and their sequences. The phone re-encodes these; nothing here strips them. */
    Heif,
}

/** Why an image was refused. The messages never hold file content. */
enum class ImageRefusal {
    NotJpeg, NotPng,

    /** The data ends inside a segment, a chunk or the image. */
    Truncated,

    /** A marker, a fill run or a chunk type that a well-formed file does not have where it is. */
    BadMarker,
    BadLength, BadChunk, BadCrc,

    /** More segments or chunks than any real image has. */
    TooManySegments,

    /** More bytes than a transfer may carry ([TransferLimits.MAX_PUT_BYTES]). */
    TooLarge,

    /** A well-formed file with no image data. */
    NoImage,

    /** A kind of image this pass cannot clean without changing what it draws (hierarchical JPEG, an unknown critical chunk). */
    Unsupported,

    /** The header has no readable size. */
    Dimensions,

    /** The header declares more than [ImageMetadata.MAX_DECODE_PIXELS]. */
    TooManyPixels,
}

/**
 * A typed refusal: the image is not sent, or not decoded. An image that cannot be cleaned is never sent with its
 * metadata (X4), so the caller says [reason] and goes no further. On a refusal from a strip, what was written to the
 * output is a partial file and must be discarded.
 */
class ImageRefused(val reason: ImageRefusal) : Exception(reason.name)

/**
 * X4 and SEC-7: no image leaves the phone with its metadata, Orientation apart. Both strips are streaming ALLOWLIST
 * passes, not blocklists: a segment or chunk this file does not name is dropped, so a kind of metadata nobody has
 * invented yet is dropped too, and everything after the image's own end goes (a motion photo's video, an Ultra HDR gain
 * map, a trailer). The passes keep what draws the image and nothing else, never decode it, and run in constant memory:
 * a 32 KiB block in, a 32 KiB block out, and one JPEG segment (at most 64 KiB) at a time.
 *
 * Every length is checked before it is used, every count is bounded, and what the input claims never sizes an
 * allocation. A malformed or hostile file ends in [ImageRefused]; no input makes a pass loop, because each step consumes
 * input and the input read is capped at [TransferLimits.MAX_PUT_BYTES]. Other I/O errors are the streams' own.
 */
object ImageMetadata {
    /**
     * The most pixels a header may declare for an image the phone decodes (the HEIF re-encode, the chip's thumbnail).
     * PROVISIONAL: spike S4 sets it from the memory a re-encode needs at the bound on the API 28 emulator. The strips
     * do not decode and do not check it.
     */
    const val MAX_DECODE_PIXELS: Long = 50_000_000L

    private const val BLOCK = 32 * 1024
    private const val MAX_SEGMENTS = 10_000
    private const val MAX_CHUNKS = 1_000_000
    private const val MAX_FILL = 1_024

    private val HEIF_BRANDS = setOf(
        "heic", "heix", "heim", "heis", "hevc", "hevx", "hevm", "hevs", "mif1", "mif2", "msf1", "avif", "avis",
    )
    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    // What draws a PNG, and nothing else. The colour and rendering chunks are four to a few hundred bytes (an ICC
    // profile aside); APNG's three are its frames.
    private val PNG_KEPT = setOf(
        "IHDR", "PLTE", "IDAT", "IEND",
        "tRNS", "gAMA", "cHRM", "sRGB", "iCCP", "sBIT", "cICP", "mDCV", "cLLI", "pHYs", "bKGD",
        "acTL", "fcTL", "fdAT",
    )

    /** The kind of image [head] starts as, by its magic bytes only. A claimed type or name decides nothing. */
    fun sniff(head: ByteArray): ImageKind? = when {
        head.size >= 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte() -> ImageKind.Jpeg
        head.size >= PNG_SIGNATURE.size && PNG_SIGNATURE.indices.all { head[it] == PNG_SIGNATURE[it] } -> ImageKind.Png
        isHeif(head) -> ImageKind.Heif
        else -> null
    }

    private fun isHeif(head: ByteArray): Boolean {
        if (head.size < 12 || String(head, 4, 4, Charsets.ISO_8859_1) != "ftyp") return false
        if (String(head, 8, 4, Charsets.ISO_8859_1) in HEIF_BRANDS) return true
        // The compatible brands follow the minor version; the box's own size says where they end, the head where we can see.
        val size = ((head[0].toLong() and 0xFF) shl 24) or ((head[1].toLong() and 0xFF) shl 16) or
            ((head[2].toLong() and 0xFF) shl 8) or (head[3].toLong() and 0xFF)
        val end = minOf(size, head.size.toLong()).toInt()
        var at = 16
        while (at + 4 <= end) {
            if (String(head, at, 4, Charsets.ISO_8859_1) in HEIF_BRANDS) return true
            at += 4
        }
        return false
    }

    // ---- Pixels -------------------------------------------------------------------------------------------------

    /** Whether a declared size is within [MAX_DECODE_PIXELS]. For a decoder's own header callback (HEIF). */
    fun pixelsWithinBound(width: Long, height: Long): Boolean =
        width > 0 && height > 0 && width <= Int.MAX_VALUE && height <= Int.MAX_VALUE && width * height <= MAX_DECODE_PIXELS

    /**
     * The width times the height a JPEG's first frame header or a PNG's IHDR declares, read from [head] with no
     * allocation that depends on it; null when [head] is neither, holds no size, or declares a zero side. A JPEG's
     * frame header can sit after large segments, so [head] should be the first 256 KiB or so; null then means "not seen".
     */
    fun imagePixels(head: ByteArray): Long? = imagePixels(ByteArrayInputStream(head))

    fun imagePixels(input: InputStream): Long? = try {
        val r = Reader(input)
        when (r.byte()) {
            0xFF -> jpegPixels(r)
            0x89 -> pngPixels(r)
            else -> null
        }
    } catch (_: ImageRefused) {
        null
    }

    /**
     * Refuses before any decode: [ImageRefusal.TooManyPixels] when [head] declares more than [MAX_DECODE_PIXELS],
     * [ImageRefusal.Dimensions] when it declares nothing readable. For a JPEG or PNG the phone is about to decode (the
     * chip's thumbnail); a HEIF's size comes from the decoder's header callback and [pixelsWithinBound].
     */
    fun requireWithinPixelBound(head: ByteArray) {
        val pixels = imagePixels(head) ?: throw ImageRefused(ImageRefusal.Dimensions)
        if (pixels > MAX_DECODE_PIXELS) throw ImageRefused(ImageRefusal.TooManyPixels)
    }

    private fun jpegPixels(r: Reader): Long? {
        if (r.byte() != 0xD8) return null
        repeat(MAX_SEGMENTS) {
            val m = r.marker()
            when {
                isSof(m) -> {
                    val length = r.u16()
                    if (length < 8) return null
                    r.byte() // sample precision
                    val height = r.u16().toLong()
                    val width = r.u16().toLong()
                    return if (width == 0L || height == 0L) null else width * height
                }
                m == 0xD9 || m == 0xDA -> return null
                m == 0x01 -> {}
                m in 0xD0..0xD8 || m == 0 -> return null
                else -> { val length = r.u16(); if (length < 2) return null; r.skip(length - 2L) }
            }
        }
        return null
    }

    private fun pngPixels(r: Reader): Long? {
        for (i in 1 until PNG_SIGNATURE.size) if (r.byte() != (PNG_SIGNATURE[i].toInt() and 0xFF)) return null
        if (r.u32() != 13L || r.u32() != IHDR) return null
        val width = r.u32()
        val height = r.u32()
        // The format allows 2^31 - 1 at most; a larger claim is malformed, and would overflow the product.
        return if (width == 0L || height == 0L || width > Int.MAX_VALUE || height > Int.MAX_VALUE) null else width * height
    }

    // ---- JPEG ---------------------------------------------------------------------------------------------------

    /**
     * Copies the JPEG on [input] to [output] with only what draws it, and stops at the first EOI, never reading past it.
     *
     * Kept: SOI; APP0 when it is JFIF (rewritten without its thumbnail); APP2 when it is an ICC profile; APP14 when it
     * is Adobe's colour-transform block; DQT, DHT, DAC, DRI; every SOF; every SOS with its entropy-coded data, so every
     * scan of a progressive file. Dropped: every other APPn (Exif, XMP, IPTC, MPF, makers), COM, and the rest.
     *
     * When [orientation] is 2 to 8, a new APP1 Exif that holds only Orientation follows the JFIF block; 1, null and
     * anything else write none. The caller reads it from the original (the platform's `ExifInterface`, or
     * [jpegOrientation]) before this drops it. Returns the bytes written.
     *
     * A file that is not a JPEG, ends before EOI, has a byte that is not a marker where one belongs, a hierarchical
     * frame, no frame or no scan, or more than 10,000 segments is refused.
     */
    fun stripJpeg(input: InputStream, output: OutputStream, orientation: Int?): Long {
        val r = Reader(input)
        val w = Sink(output)
        if (r.byte() != 0xFF || r.byte() != 0xD8) throw ImageRefused(ImageRefusal.NotJpeg)
        w.write(0xFF); w.write(0xD8)
        var exif = orientationSegment(orientation)
        var frame = false
        var scan = false
        var segments = 0
        var next = -1
        while (true) {
            val m = if (next >= 0) next else r.marker()
            next = -1
            if (++segments > MAX_SEGMENTS) throw ImageRefused(ImageRefusal.TooManySegments)
            if (m == 0xD9) {
                if (!scan) throw ImageRefused(ImageRefusal.NoImage)
                w.write(0xFF); w.write(0xD9)
                w.flush()
                return w.written // the first EOI is the end: nothing after it is read, so nothing after it can be written
            }
            when {
                m == 0x01 -> continue // TEM has no length and no use
                m == 0xD8 || m in 0xD0..0xD7 || m == 0 || m in 0x02..0xBF -> throw ImageRefused(ImageRefusal.BadMarker)
                m == 0xDC || m == 0xDE || m == 0xDF -> throw ImageRefused(ImageRefusal.Unsupported) // DNL, DHP, EXP
            }
            val length = r.u16()
            if (length < 2) throw ImageRefused(ImageRefusal.BadLength)
            var n = length - 2
            val payload: ByteArray? = when {
                m == 0xE0 || m == 0xE2 || m == 0xEE -> {
                    val prefix = r.bytes(minOf(n, 12))
                    n -= prefix.size
                    if (appKept(m, prefix, prefix.size + n)) prefix + r.bytes(n) else { r.skip(n.toLong()); null }
                }
                isSof(m) || m == 0xC4 || m == 0xCC || m == 0xDB || m == 0xDD || m == 0xDA -> r.bytes(n)
                else -> { r.skip(n.toLong()); null } // COM, JPGn, the other APPn
            }
            if (payload == null) continue
            if (isSof(m)) {
                if (payload.size < 6) throw ImageRefused(ImageRefusal.BadLength)
                frame = true
            }
            if (m == 0xDA && !frame) throw ImageRefused(ImageRefusal.NoImage)
            val body = if (m == 0xE0) payload.copyOf(14).also { it[12] = 0; it[13] = 0 } else payload // JFIF: no thumbnail
            if (m != 0xE0 && exif != null) { w.write(exif); exif = null }
            w.write(0xFF); w.write(m); w.write((body.size + 2) shr 8); w.write((body.size + 2) and 0xFF)
            w.write(body, 0, body.size)
            if (m == 0xE0 && exif != null) { w.write(exif); exif = null }
            if (m == 0xDA) {
                next = r.copyScan(w)
                scan = true
            }
        }
    }

    /** Whether APP[m] is one of the three the pass keeps: its [prefix] says so, and for JFIF and Adobe so does its size. */
    private fun appKept(m: Int, prefix: ByteArray, payloadSize: Int): Boolean = when (m) {
        0xE0 -> payloadSize >= 14 && prefix.startsWith("JFIF\u0000")
        0xE2 -> prefix.startsWith("ICC_PROFILE\u0000")
        else -> payloadSize == 12 && prefix.startsWith("Adobe") // APP14
    }

    /** A new APP1 Exif of 36 bytes holding one IFD0 entry, Orientation, big-endian. Null for 1, null and what is not 1 to 8. */
    private fun orientationSegment(orientation: Int?): ByteArray? {
        if (orientation == null || orientation !in 2..8) return null
        return byteArrayOf(
            0xFF.toByte(), 0xE1.toByte(), 0x00, 0x22, // APP1, length 34
            'E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0,
            'M'.code.toByte(), 'M'.code.toByte(), 0x00, 0x2A, 0, 0, 0, 8, // TIFF header, IFD0 at 8
            0x00, 0x01, // one entry
            0x01, 0x12, 0x00, 0x03, 0, 0, 0, 1, 0x00, orientation.toByte(), 0, 0, // Orientation, SHORT, count 1, value
            0, 0, 0, 0, // no IFD1
        )
    }

    /**
     * The Orientation (1 to 8) in the first Exif block of the JPEG in [bytes], or null when there is none or it is not
     * readable. [bytes] may be only the head: Exif is among the first segments. Never throws.
     */
    fun jpegOrientation(bytes: ByteArray): Int? = try {
        val r = Reader(ByteArrayInputStream(bytes))
        var found: Int? = null
        if (r.byte() == 0xFF && r.byte() == 0xD8) {
            segments@ for (i in 0 until MAX_SEGMENTS) {
                val m = r.marker()
                if (m == 0xDA || m == 0xD9) break
                if (m == 0x01 || m in 0xD0..0xD8) continue
                val length = r.u16()
                if (length < 2) break
                if (m != 0xE1) { r.skip(length - 2L); continue }
                val payload = r.bytes(length - 2)
                if (payload.startsWith("Exif\u0000\u0000")) { found = tiffOrientation(payload); break@segments }
            }
        }
        found
    } catch (_: ImageRefused) {
        null
    }

    private fun tiffOrientation(exif: ByteArray): Int? {
        val tiff = 6 // after "Exif\0\0"
        if (exif.size < tiff + 8) return null
        val little = when {
            exif[tiff] == 'I'.code.toByte() && exif[tiff + 1] == 'I'.code.toByte() -> true
            exif[tiff] == 'M'.code.toByte() && exif[tiff + 1] == 'M'.code.toByte() -> false
            else -> return null
        }
        fun u16(at: Int): Int = if (little) (exif[at].toInt() and 0xFF) or ((exif[at + 1].toInt() and 0xFF) shl 8)
        else ((exif[at].toInt() and 0xFF) shl 8) or (exif[at + 1].toInt() and 0xFF)
        fun u32(at: Int): Long = if (little) u16(at).toLong() or (u16(at + 2).toLong() shl 16) else (u16(at).toLong() shl 16) or u16(at + 2).toLong()
        if (u16(tiff + 2) != 42) return null
        val ifd = tiff + (u32(tiff + 4).takeIf { it in 8..exif.size.toLong() } ?: return null).toInt()
        if (ifd + 2 > exif.size) return null
        val count = minOf(u16(ifd), (exif.size - ifd - 2) / 12)
        for (i in 0 until count) {
            val e = ifd + 2 + i * 12
            if (u16(e) == 0x0112 && u16(e + 2) == 3 && u32(e + 4) == 1L) return u16(e + 8).takeIf { it in 1..8 }
        }
        return null
    }

    // ---- PNG ----------------------------------------------------------------------------------------------------

    /**
     * Copies the PNG on [input] to [output] with only the chunks that draw it (IHDR, PLTE, IDAT, IEND; tRNS, gAMA,
     * cHRM, sRGB, iCCP, sBIT, cICP, mDCV, cLLI, pHYs, bKGD; APNG's acTL, fcTL, fdAT) and stops after IEND, never reading
     * past it. Kept chunks are copied as they are and their CRCs checked, so the output's are valid. Everything else
     * (eXIf, tEXt, zTXt, iTXt, tIME, private chunks) is skipped. Returns the bytes written.
     *
     * Refused: not a PNG; a first chunk that is not a 13-byte IHDR; a chunk longer than the transfer cap; a bad type or
     * CRC on a kept chunk; an unknown critical chunk; a file that ends before IEND or has no IDAT.
     */
    fun stripPng(input: InputStream, output: OutputStream): Long {
        val r = Reader(input)
        val w = Sink(output)
        for (b in PNG_SIGNATURE) if (r.byte() != (b.toInt() and 0xFF)) throw ImageRefused(ImageRefusal.NotPng)
        w.write(PNG_SIGNATURE, 0, PNG_SIGNATURE.size)
        var data = false
        for (index in 0 until MAX_CHUNKS) {
            val length = r.u32()
            if (length > TransferLimits.MAX_PUT_BYTES) throw ImageRefused(ImageRefusal.BadLength)
            val type = r.bytes(4)
            if (!type.all { it in 'A'.code.toByte()..'Z'.code.toByte() || it in 'a'.code.toByte()..'z'.code.toByte() }) {
                throw ImageRefused(ImageRefusal.BadChunk)
            }
            val name = String(type, Charsets.ISO_8859_1)
            if ((index == 0) != (name == "IHDR")) throw ImageRefused(ImageRefusal.BadChunk)
            if (name == "IHDR" && length != 13L || name == "IEND" && length != 0L) throw ImageRefused(ImageRefusal.BadLength)
            if (name !in PNG_KEPT) {
                if (type[0] in 'A'.code.toByte()..'Z'.code.toByte()) throw ImageRefused(ImageRefusal.Unsupported) // an unknown critical chunk
                r.skip(length + 4)
                continue
            }
            if (name == "IEND" && !data) throw ImageRefused(ImageRefusal.NoImage)
            if (name == "IDAT" || name == "fdAT") data = true
            val crc = CRC32().apply { update(type) }
            w.write((length shr 24).toInt() and 0xFF); w.write((length shr 16).toInt() and 0xFF)
            w.write((length shr 8).toInt() and 0xFF); w.write(length.toInt() and 0xFF)
            w.write(type, 0, 4)
            r.copy(w, length, crc)
            val stored = r.u32()
            if (stored != crc.value) throw ImageRefused(ImageRefusal.BadCrc)
            w.write((stored shr 24).toInt() and 0xFF); w.write((stored shr 16).toInt() and 0xFF)
            w.write((stored shr 8).toInt() and 0xFF); w.write(stored.toInt() and 0xFF)
            if (name == "IEND") {
                w.flush()
                return w.written // everything after IEND is dropped unread
            }
        }
        throw ImageRefused(ImageRefusal.TooManySegments)
    }

    // ---- Shared -------------------------------------------------------------------------------------------------

    private const val IHDR = 0x49484452L // "IHDR"

    private fun isSof(m: Int) = m in 0xC0..0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC

    private fun ByteArray.startsWith(prefix: String): Boolean =
        size >= prefix.length && prefix.indices.all { this[it] == prefix[it].code.toByte() }

    /**
     * A buffered reader that refuses on end of data and past the transfer cap, so no caller can loop on a stream that
     * does not end. Offsets and sizes the file declares are checked by the callers before they ask for bytes.
     */
    private class Reader(private val input: InputStream) {
        private val buf = ByteArray(BLOCK)
        private var pos = 0
        private var lim = 0
        private var total = 0L

        private fun fill() {
            val n = input.read(buf, 0, buf.size)
            if (n <= 0) throw ImageRefused(ImageRefusal.Truncated)
            total += n
            if (total > TransferLimits.MAX_PUT_BYTES) throw ImageRefused(ImageRefusal.TooLarge)
            pos = 0
            lim = n
        }

        fun byte(): Int {
            if (pos == lim) fill()
            return buf[pos++].toInt() and 0xFF
        }

        fun u16(): Int = (byte() shl 8) or byte()

        fun u32(): Long = (u16().toLong() shl 16) or u16().toLong()

        /** The next marker's code: the 0xFF that opens it, any fill bytes (a run is capped), then a code that is not 0. */
        fun marker(): Int {
            if (byte() != 0xFF) throw ImageRefused(ImageRefusal.BadMarker)
            var fill = 0
            var m = byte()
            while (m == 0xFF) { if (++fill > MAX_FILL) throw ImageRefused(ImageRefusal.BadMarker); m = byte() }
            if (m == 0) throw ImageRefused(ImageRefusal.BadMarker)
            return m
        }

        /** [n] bytes in an array. Only ever called with a size the format bounds (a segment's 16-bit length). */
        fun bytes(n: Int): ByteArray {
            val out = ByteArray(n)
            var at = 0
            while (at < n) {
                if (pos == lim) fill()
                val c = minOf(n - at, lim - pos)
                System.arraycopy(buf, pos, out, at, c)
                pos += c
                at += c
            }
            return out
        }

        fun skip(n: Long) {
            var left = n
            while (left > 0) {
                if (pos == lim) fill()
                val c = minOf(left, (lim - pos).toLong()).toInt()
                pos += c
                left -= c
            }
        }

        /** Copies [n] bytes to [w], feeding [crc]. */
        fun copy(w: Sink, n: Long, crc: CRC32) {
            var left = n
            while (left > 0) {
                if (pos == lim) fill()
                val c = minOf(left, (lim - pos).toLong()).toInt()
                crc.update(buf, pos, c)
                w.write(buf, pos, c)
                pos += c
                left -= c
            }
        }

        /**
         * Copies a scan's entropy-coded data to [w] and returns the marker that ends it. `FF 00` and `FF D0` to `FF D7`
         * belong to the data and are copied; fill bytes before the ending marker are dropped (a run is capped).
         */
        fun copyScan(w: Sink): Int {
            while (true) {
                val start = pos
                while (pos < lim && buf[pos] != 0xFF.toByte()) pos++
                w.write(buf, start, pos - start)
                if (pos == lim) { fill(); continue }
                pos++ // the 0xFF
                var fill = 0
                var b = byte()
                while (b == 0xFF) { if (++fill > MAX_FILL) throw ImageRefused(ImageRefusal.BadMarker); b = byte() }
                when {
                    b == 0 || b in 0xD0..0xD7 -> { w.write(0xFF); w.write(b) }
                    else -> return b
                }
            }
        }
    }

    private class Sink(private val out: OutputStream) {
        private val buf = ByteArray(BLOCK)
        private var n = 0
        var written = 0L
            private set

        fun write(b: Int) {
            if (n == buf.size) drain()
            buf[n++] = b.toByte()
            written++
        }

        fun write(src: ByteArray, off: Int, len: Int) {
            if (len <= 0) return
            if (len >= buf.size) {
                drain()
                out.write(src, off, len)
            } else {
                if (len > buf.size - n) drain()
                System.arraycopy(src, off, buf, n, len)
                n += len
            }
            written += len
        }

        fun write(src: ByteArray) = write(src, 0, src.size)

        private fun drain() {
            if (n > 0) { out.write(buf, 0, n); n = 0 }
        }

        fun flush() {
            drain()
            out.flush()
        }
    }
}
