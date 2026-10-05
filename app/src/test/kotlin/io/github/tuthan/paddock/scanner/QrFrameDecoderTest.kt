package io.github.tuthan.paddock.scanner

// Ported from steamos-companion-android (app/src/main/kotlin/io/github/tuthan/steamoscompanion/android/scanner/QrFrameDecoderTest.kt), the same author's project,
// which has no LICENSE file; the port is for Paddock (Phase 14, decision D2). Changes: the package and the payloads (a Paddock pairing link).

import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrFrameDecoderTest {
    @Test fun decodesQrWithPaddedCameraRowStride() {
        val payload = "paddock://pair?v=1&host=192.168.42.86&port=22&user=jdoe&fp=SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        val width = 256
        val height = 256
        val stride = width + 17
        val qr = MultiFormatWriter().encode(payload, BarcodeFormat.QR_CODE, width, height)
        val plane = ByteArray(stride * height) { 0xff.toByte() }
        for (y in 0 until height) for (x in 0 until width) {
            plane[y * stride + x] = if (qr[x, y]) 0 else 0xff.toByte()
        }
        assertEquals(payload, QrFrameDecoder().decode(plane, width, height, stride, 1))
    }

    @Test fun decodesQrWithInterleavedLuminancePixels() {
        val payload = "paddock://pair?v=1&host=192.168.42.86&port=22&user=jdoe&fp=SHA256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        val width = 128
        val height = 128
        val pixelStride = 2
        val rowStride = width * pixelStride + 8
        val qr = MultiFormatWriter().encode(payload, BarcodeFormat.QR_CODE, width, height)
        val plane = ByteArray(rowStride * height) { 0xff.toByte() }
        for (y in 0 until height) for (x in 0 until width) {
            plane[y * rowStride + x * pixelStride] = if (qr[x, y]) 0 else 0xff.toByte()
        }
        assertEquals(payload, QrFrameDecoder().decode(plane, width, height, rowStride, pixelStride))
    }

    @Test fun blankFrameDoesNotInventPayload() {
        assertNull(QrFrameDecoder().decode(ByteArray(64 * 64) { 0xff.toByte() }, 64, 64, 64, 1))
    }

    private fun frame(payload: String, size: Int): ByteArray {
        val qr = MultiFormatWriter().encode(payload, BarcodeFormat.QR_CODE, size, size)
        return ByteArray(size * size) { if (qr[it % size, it / size]) 0 else 0xff.toByte() }
    }

    @Test fun whatTheCameraReadsIsAPairingLinkTheParserAccepts() {
        val link = "paddock://pair?v=1&host=192.168.42.86&port=22&user=jdoe&fp=SHA256:" + "A".repeat(43) + "&pair=41234&sid=" + "s".repeat(22)
        val read = QrFrameDecoder().decode(frame(link, 300), 300, 300, 300, 1)
        val result = io.github.tuthan.paddock.hostprofile.PairingLinks.fromText(read)
        assertTrue(result.toString(), result is io.github.tuthan.paddock.hostprofile.PairingResult.Valid)
    }

    @Test fun anotherQrCodeIsReadButIsNotAPairingLink() {
        val read = QrFrameDecoder().decode(frame("https://example.com/menu", 200), 200, 200, 200, 1)
        assertEquals("https://example.com/menu", read)
        val result = io.github.tuthan.paddock.hostprofile.PairingLinks.fromText(read)
        assertTrue(result is io.github.tuthan.paddock.hostprofile.PairingResult.Rejected)
    }

    @Test fun aFrameThatIsTooBigOrCutShortIsRefusedNotRead() {
        val decoder = QrFrameDecoder()
        for ((w, h, rowStride, size) in listOf(listOf(1_921, 100, 1_921, 192_100), listOf(0, 10, 10, 100), listOf(100, 100, 99, 10_000), listOf(100, 100, 100, 9_999))) {
            val refused = try { decoder.decode(ByteArray(size), w, h, rowStride, 1); false } catch (_: IllegalArgumentException) { true }
            assertTrue("$w x $h stride $rowStride in $size bytes", refused)
        }
    }

    @Test fun theLuminanceCopyIsClearedAndTheReaderIsReusableAfterAMiss() {
        val decoder = QrFrameDecoder()
        assertNull(decoder.decode(ByteArray(64 * 64) { 0x80.toByte() }, 64, 64, 64, 1))
        assertEquals("ok", decoder.decode(frame("ok", 120), 120, 120, 120, 1))
    }
}
