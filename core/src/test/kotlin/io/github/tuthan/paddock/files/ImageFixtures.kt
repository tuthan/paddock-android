package io.github.tuthan.paddock.files

import java.awt.color.ColorSpace
import java.awt.color.ICC_Profile
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Deflater
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.random.Random

/**
 * Images made in the test. Every piece of "metadata" is fake and carries [SENTINEL], so a leak is found by searching
 * for it, and no real photo, place or device is ever in the repository. The fixtures are what a phone's camera and an
 * editor leave in a file: Exif with GPS and a thumbnail (with an EOI of its own), XMP (a motion photo's), IPTC, MPF,
 * comments, makers' blocks, and bytes after the image (a gain map, a video).
 */
internal object ImageFixtures {
    const val SENTINEL = "SENTINEL-"
    val SENTINEL_BYTES = SENTINEL.toByteArray(Charsets.ISO_8859_1)

    fun contains(haystack: ByteArray, needle: ByteArray = SENTINEL_BYTES): Boolean = indexOf(haystack, needle) >= 0

    fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int = 0): Int {
        outer@ for (i in from..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    fun ascii(s: String) = s.toByteArray(Charsets.ISO_8859_1)

    /** Noise, so the entropy-coded data has plenty of 0xFF bytes to stuff, and every pixel is a fact to compare. */
    fun image(width: Int = 96, height: Int = 64, seed: Int = 1): BufferedImage {
        val rnd = Random(seed)
        return BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).also { img ->
            for (y in 0 until height) for (x in 0 until width) {
                img.setRGB(x, y, (x * 255 / width shl 16) or (y * 255 / height shl 8) or rnd.nextInt(256))
            }
        }
    }

    fun pixels(bytes: ByteArray): IntArray {
        val img = ImageIO.read(ByteArrayInputStream(bytes)) ?: error("not decodable")
        return img.getRGB(0, 0, img.width, img.height, null, 0, img.width) + intArrayOf(img.width, img.height)
    }

    // ---- JPEG ---------------------------------------------------------------------------------------------------

    fun jpeg(img: BufferedImage, progressive: Boolean = false): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val param = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = 0.85f
            if (progressive) progressiveMode = ImageWriteParam.MODE_DEFAULT
        }
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { ios ->
            writer.output = ios
            writer.write(null, IIOImage(img, null, null), param)
        }
        writer.dispose()
        return out.toByteArray()
    }

    fun segment(marker: Int, payload: ByteArray): ByteArray {
        require(payload.size <= 65_533)
        return byteArrayOf(0xFF.toByte(), marker.toByte(), ((payload.size + 2) shr 8).toByte(), ((payload.size + 2) and 0xFF).toByte()) + payload
    }

    /** One JPEG segment as the test sees it, found by its own parser, not the one under test. */
    class Seg(val marker: Int, val payload: ByteArray)

    class Parsed(val segments: List<Seg>, val scans: Int, val end: Int, val firstEoiOffset: Int)

    /** Walks [jpeg] from SOI to its first EOI. Throws (an error in the fixture or in the output) when it is not a JPEG. */
    fun parseJpeg(jpeg: ByteArray): Parsed {
        check(jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()) { "no SOI" }
        val segs = ArrayList<Seg>()
        var scans = 0
        var i = 2
        while (true) {
            check(jpeg[i] == 0xFF.toByte()) { "no marker at $i" }
            while (jpeg[i + 1] == 0xFF.toByte()) i++
            val m = jpeg[i + 1].toInt() and 0xFF
            if (m == 0xD9) return Parsed(segs, scans, i + 2, i)
            val len = ((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF)
            segs += Seg(m, jpeg.copyOfRange(i + 4, i + 2 + len))
            i += 2 + len
            if (m == 0xDA) {
                scans++
                while (true) {
                    if (jpeg[i] == 0xFF.toByte()) {
                        val n = jpeg[i + 1].toInt() and 0xFF
                        if (n != 0 && n !in 0xD0..0xD7 && n != 0xFF) break
                        i += 2
                    } else i++
                }
            }
        }
    }

    fun markers(jpeg: ByteArray): List<Int> = parseJpeg(jpeg).segments.map { it.marker }

    /** Where in [jpeg] each segment's two length bytes are, for the tests that inflate one. */
    fun lengthOffsets(jpeg: ByteArray): List<Int> {
        val out = ArrayList<Int>()
        var i = 2
        while (true) {
            while (jpeg[i + 1] == 0xFF.toByte()) i++
            val m = jpeg[i + 1].toInt() and 0xFF
            if (m == 0xD9) return out
            out += i + 2
            i += 2 + (((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF))
            if (m == 0xDA) {
                while (true) {
                    if (jpeg[i] == 0xFF.toByte()) {
                        val n = jpeg[i + 1].toInt() and 0xFF
                        if (n != 0 && n !in 0xD0..0xD7 && n != 0xFF) break
                        i += 2
                    } else i++
                }
            }
        }
    }

    /** [jpeg] with [extra] segments after its SOI and JFIF block, where cameras and editors put theirs. */
    fun withSegments(jpeg: ByteArray, extra: List<ByteArray>): ByteArray {
        var at = 2
        if (jpeg[2] == 0xFF.toByte() && jpeg[3] == 0xE0.toByte()) at += 2 + (((jpeg[4].toInt() and 0xFF) shl 8) or (jpeg[5].toInt() and 0xFF))
        return jpeg.copyOfRange(0, at) + extra.fold(ByteArray(0)) { a, b -> a + b } + jpeg.copyOfRange(at, jpeg.size)
    }

    /** A JFIF APP0 whose thumbnail (2 by 1) holds a sentinel: the fake "uncropped original". */
    fun jfifWithThumbnail(): ByteArray =
        segment(0xE0, ascii("JFIF\u0000") + byteArrayOf(1, 2, 1, 0, 72, 0, 72, 2, 1) + ascii("SENTI!"))

    fun xmp(motionPhoto: Boolean): ByteArray = segment(
        0xE1,
        ascii("http://ns.adobe.com/xap/1.0/\u0000") + ascii(
            "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:Description ${SENTINEL}XMP " +
                (if (motionPhoto) "GCamera:MotionPhoto=\"1\" Camera:MotionPhoto=\"1\" GCamera:MicroVideoOffset=\"9999\" " else "") +
                "xmp:CreatorTool=\"${SENTINEL}TOOL\"/></x:xmpmeta>",
        ),
    )

    fun iptc(): ByteArray = segment(0xED, ascii("Photoshop 3.0\u0000") + ascii("8BIM") + byteArrayOf(0x04, 0x04, 0, 0, 0, 0, 0, 16) + ascii("${SENTINEL}IPTC.."))

    fun mpf(): ByteArray = segment(0xE2, ascii("MPF\u0000") + ascii("MM") + byteArrayOf(0, 0x2A, 0, 0, 0, 8) + ascii("${SENTINEL}MPF"))

    fun comment(): ByteArray = segment(0xFE, ascii("${SENTINEL}COMMENT"))

    fun makers(): List<ByteArray> = listOf(
        segment(0xE5, ascii("${SENTINEL}MAKER5\u0000")), segment(0xEC, ascii("Ducky${SENTINEL}")), segment(0xEF, ascii("${SENTINEL}APP15")),
    )

    /** The JDK's sRGB profile as APP2 `ICC_PROFILE` segments. It is metadata that draws, so it must survive. */
    fun iccSegments(): List<ByteArray> {
        val profile = ICC_Profile.getInstance(ColorSpace.CS_sRGB).data
        val parts = profile.toList().chunked(4_000).map { it.toByteArray() }
        return parts.mapIndexed { i, part -> segment(0xE2, ascii("ICC_PROFILE\u0000") + byteArrayOf((i + 1).toByte(), parts.size.toByte()) + part) }
    }

    /** JFXX, the JFIF extension: a thumbnail in an APP0 that is not JFIF. Not what draws the image. */
    fun jfxx(): ByteArray = segment(0xE0, ascii("JFXX\u0000") + byteArrayOf(0x10, 1, 1, 0, 0, 0) + ascii("${SENTINEL}JFXX"))

    /** APP14 blocks that are not Adobe's colour-transform block: another maker's, and an "Adobe" one too long to be it. */
    fun otherApp14(): List<ByteArray> = listOf(segment(0xEE, ascii("${SENTINEL}APP14")), segment(0xEE, ascii("Adobe") + ascii("${SENTINEL}OVERSIZE")))

    /** Adobe's APP14: version 100, no flags, transform 1 (YCbCr), what the JDK's own JFIF decode assumes too. */
    fun adobe(): ByteArray = segment(0xEE, ascii("Adobe") + byteArrayOf(0, 100, 0, 0, 0, 0, 1))

    /**
     * An Exif block with fake coordinates (12 deg 34 min 56 sec N, 78 deg 9 min 10 sec E and a sentinel method), a Make
     * with the sentinel, Orientation when [orientation] is not null, and a thumbnail JPEG that has its own SOI and EOI.
     */
    fun exif(orientation: Int?, thumbnail: ByteArray, littleEndian: Boolean = false): ByteArray {
        val order = if (littleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        val make = ascii("${SENTINEL}MAKE\u0000")
        val method = ascii("${SENTINEL}GPS\u0000")
        class E(val tag: Int, val type: Int, val count: Int, val value: ByteArray?, val inline: Long = 0)
        val ifd0Count = if (orientation != null) 3 else 2
        val gpsAt = 8 + 2 + 12 * ifd0Count + 4
        val ifd1At = gpsAt + 2 + 12 * 5 + 4
        val dataAt = ifd1At + 2 + 12 * 2 + 4
        val data = ByteArrayOutputStream()
        fun place(bytes: ByteArray): Int { val at = dataAt + data.size(); data.write(bytes); return at }
        fun rationals(vararg v: Int): ByteArray = ByteBuffer.allocate(8 * v.size).order(order).also { b -> v.forEach { b.putInt(it); b.putInt(1) } }.array()
        val tiff = ByteArrayOutputStream()
        val mark = if (littleEndian) 'I'.code.toByte() else 'M'.code.toByte()
        tiff.write(ByteBuffer.allocate(8).order(order).put(mark).put(mark).putShort(42).putInt(8).array())
        fun writeIfd(entries: List<E>, next: Int) {
            val b = ByteBuffer.allocate(2 + 12 * entries.size + 4).order(order)
            b.putShort(entries.size.toShort())
            for (e in entries) {
                b.putShort(e.tag.toShort()); b.putShort(e.type.toShort()); b.putInt(e.count)
                when {
                    e.value == null && e.type == 3 -> { b.putShort(e.inline.toShort()); b.putShort(0) }
                    e.value == null -> b.putInt(e.inline.toInt())
                    e.value.size <= 4 -> { b.put(e.value); repeat(4 - e.value.size) { b.put(0) } }
                    else -> b.putInt(place(e.value))
                }
            }
            b.putInt(next)
            tiff.write(b.array())
        }
        writeIfd(
            buildList {
                add(E(0x010F, 2, make.size, make))
                if (orientation != null) add(E(0x0112, 3, 1, null, orientation.toLong()))
                add(E(0x8825, 4, 1, null, gpsAt.toLong()))
            },
            0,
        )
        writeIfd(
            listOf(
                E(0x0001, 2, 2, ascii("N\u0000")), E(0x0002, 5, 3, rationals(12, 34, 56)),
                E(0x0003, 2, 2, ascii("E\u0000")), E(0x0004, 5, 3, rationals(78, 9, 10)), E(0x001B, 7, method.size, method),
            ),
            ifd1At,
        )
        writeIfd(listOf(E(0x0201, 4, 1, null, place(thumbnail).toLong()), E(0x0202, 4, 1, null, thumbnail.size.toLong())), 0)
        check(tiff.size() == dataAt)
        return segment(0xE1, ascii("Exif\u0000\u0000") + tiff.toByteArray() + data.toByteArray())
    }

    /** A JPEG as a phone or an editor leaves it: everything above, then (optionally) a gain map and a video after the end. */
    fun dirtyJpeg(
        img: BufferedImage = image(),
        progressive: Boolean = false,
        orientation: Int? = 6,
        littleEndian: Boolean = false,
        motionPhoto: Boolean = false,
    ): ByteArray {
        val thumbnail = jpeg(image(8, 8, 9))
        val base = jpeg(img, progressive)
        val head = withSegments(base, listOf(exif(orientation, thumbnail, littleEndian), xmp(motionPhoto), iptc(), mpf(), comment(), jfxx()) + otherApp14() + listOf(adobe()) + iccSegments() + makers())
        // The JDK's JFIF has no thumbnail; replace it with one that has.
        val jfifEnd = 2 + 2 + (((head[4].toInt() and 0xFF) shl 8) or (head[5].toInt() and 0xFF))
        val dirty = head.copyOfRange(0, 2) + jfifWithThumbnail() + head.copyOfRange(jfifEnd, head.size)
        val trailer = if (motionPhoto) gainMapAndVideo() else ascii("${SENTINEL}TRAILER") + ByteArray(300) { (it * 7).toByte() }
        return dirty + trailer
    }

    /** What follows the primary image in a motion photo: a second JPEG (an Ultra HDR gain map) and an MP4 with a sentinel. */
    fun gainMapAndVideo(): ByteArray =
        jpeg(image(16, 16, 5)) + byteArrayOf(0, 0, 0, 0x18) + ascii("ftypmp42") + ByteArray(16) + ascii("${SENTINEL}VIDEO") + ByteArray(500) { (it * 3).toByte() }

    // ---- PNG ----------------------------------------------------------------------------------------------------

    val PNG_SIG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    fun png(img: BufferedImage): ByteArray {
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        return out.toByteArray()
    }

    fun chunk(type: String, data: ByteArray, badCrc: Boolean = false): ByteArray {
        val crc = CRC32().apply { update(ascii(type)); update(data) }.value xor (if (badCrc) 1 else 0)
        return ByteBuffer.allocate(12 + data.size).putInt(data.size).put(ascii(type)).put(data).putInt(crc.toInt()).array()
    }

    class PngChunk(val type: String, val data: ByteArray)

    fun pngChunks(png: ByteArray): List<PngChunk> {
        val out = ArrayList<PngChunk>()
        var i = 8
        while (i < png.size) {
            val len = ByteBuffer.wrap(png, i, 4).int
            val type = String(png, i + 4, 4, Charsets.ISO_8859_1)
            out += PngChunk(type, png.copyOfRange(i + 8, i + 8 + len))
            i += 12 + len
            if (type == "IEND") break
        }
        return out
    }

    /** Where the first byte after IEND is. */
    fun pngEnd(png: ByteArray): Int {
        var i = 8
        while (true) {
            val len = ByteBuffer.wrap(png, i, 4).int
            val type = String(png, i + 4, 4, Charsets.ISO_8859_1)
            i += 12 + len
            if (type == "IEND") return i
        }
    }

    /** [png] with [pre] chunks before its first IDAT, [post] after its last, and [trailer] after IEND. */
    fun pngWith(png: ByteArray, pre: List<ByteArray>, post: List<ByteArray> = emptyList(), trailer: ByteArray = ByteArray(0)): ByteArray {
        val chunks = pngChunks(png)
        val out = ByteArrayOutputStream()
        out.write(PNG_SIG)
        out.write(chunk("IHDR", chunks.first().data))
        pre.forEach(out::write)
        chunks.filter { it.type == "IDAT" }.forEach { out.write(chunk("IDAT", it.data)) }
        post.forEach(out::write)
        out.write(chunk("IEND", ByteArray(0)))
        out.write(trailer)
        return out.toByteArray()
    }

    private fun u32(v: Long) = ByteBuffer.allocate(4).putInt(v.toInt()).array()

    private fun deflate(bytes: ByteArray): ByteArray {
        val d = Deflater()
        d.setInput(bytes); d.finish()
        val buf = ByteArray(bytes.size + 64)
        val n = d.deflate(buf)
        d.end()
        return buf.copyOf(n)
    }

    /** A PNG as an editor or a phone leaves it, with the sRGB rendering chunks it should keep. */
    fun dirtyPng(img: BufferedImage = image()): ByteArray {
        val exifBlock = ascii("MM\u0000*") + byteArrayOf(0, 0, 0, 8) + ascii("${SENTINEL}EXIF")
        val pre = listOf(
            chunk("gAMA", u32(45455)), chunk("sRGB", byteArrayOf(0)), chunk("pHYs", u32(2835) + u32(2835) + byteArrayOf(1)),
            chunk("tEXt", ascii("GPS Latitude\u0000${SENTINEL}GPS 12.5N")), chunk("tIME", byteArrayOf(0x07, 0xEA.toByte(), 10, 11, 14, 32, 5)),
            chunk("eXIf", exifBlock),
            chunk("iTXt", ascii("XML:com.adobe.xmp\u0000\u0000\u0000\u0000\u0000<x:xmpmeta ${SENTINEL}XMP/>")),
            chunk("zTXt", ascii("Comment\u0000\u0000") + deflate(ascii("${SENTINEL}ZTXT"))),
            chunk("prVt", ascii("${SENTINEL}PRIVATE")), chunk("sPLT", ascii("pal\u0000\u0008") + ByteArray(12)), chunk("hIST", ByteArray(2)),
        )
        val post = listOf(chunk("tEXt", ascii("Software\u0000${SENTINEL}TOOL")), chunk("tIME", byteArrayOf(0x07, 0xEA.toByte(), 10, 11, 14, 32, 6)))
        return pngWith(png(img), pre, post, ascii("${SENTINEL}TRAILER") + ByteArray(300) { (it * 5).toByte() })
    }

    /** Every chunk the pass keeps, an APNG's three included, plus the metadata it drops among them. Truecolour, 8 bits. */
    fun allKeptPng(img: BufferedImage = image(), dirty: Boolean = true): ByteArray {
        val iccp = ascii("sRGB profile\u0000\u0000") + deflate(ICC_Profile.getInstance(ColorSpace.CS_sRGB).data)
        val kept = listOf(
            chunk("gAMA", u32(45455)),
            chunk("cHRM", ByteArray(8 * 4) { (it + 1).toByte() }), chunk("iCCP", iccp),
            chunk("sBIT", byteArrayOf(8, 8, 8)), chunk("cICP", byteArrayOf(1, 13, 0, 1)),
            chunk("mDCV", ByteArray(24) { (it + 2).toByte() }), chunk("cLLI", ByteArray(8) { (it + 3).toByte() }),
            chunk("pHYs", u32(3780) + u32(3780) + byteArrayOf(1)), chunk("bKGD", ByteArray(6)), chunk("tRNS", byteArrayOf(0, 1, 0, 2, 0, 3)),
            chunk("acTL", u32(2) + u32(0)),
            chunk("fcTL", u32(0) + u32(img.width.toLong()) + u32(img.height.toLong()) + u32(0) + u32(0) + byteArrayOf(0, 1, 0, 10, 0, 0)),
        )
        val noise = if (dirty) listOf(chunk("tEXt", ascii("Comment\u0000${SENTINEL}TEXT")), chunk("tIME", ByteArray(7)), chunk("eXIf", ascii("MM\u0000*${SENTINEL}")), chunk("zzZz", ascii("${SENTINEL}UNKNOWN"))) else emptyList()
        val post = listOf(
            chunk("fcTL", u32(1) + u32(img.width.toLong()) + u32(img.height.toLong()) + u32(0) + u32(0) + byteArrayOf(0, 1, 0, 10, 0, 0)),
            chunk("fdAT", u32(2) + ByteArray(40) { it.toByte() }),
        )
        return pngWith(png(img), kept + noise, post, if (dirty) ascii("${SENTINEL}TRAILER") else ByteArray(0))
    }

    /** The chunk types of [png], through its first IEND, and the number of bytes after it. */
    fun pngTypes(png: ByteArray): List<String> = pngChunks(png).map { it.type }
}
