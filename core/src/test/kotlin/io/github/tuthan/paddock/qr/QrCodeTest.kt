package io.github.tuthan.paddock.qr

import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The encoder is checked three ways: structure that any valid symbol has, a decode by an independent reader (zbarimg),
 * and the same module matrix as an independent encoder (qrencode). The last two skip when the tool is not installed.
 */
class QrCodeTest {
    private fun tool(name: String) = listOf("/usr/bin/$name", "/usr/local/bin/$name").map(::File).firstOrNull { it.canExecute() }

    private fun payload(bytes: Int) = buildString { var i = 0; while (length < bytes) append(('a' + (i++ * 7) % 26)) }.take(bytes)

    private fun render(q: QrCode, scale: Int = 6, quiet: Int = 4): BufferedImage {
        val n = (q.size + 2 * quiet) * scale
        val img = BufferedImage(n, n, BufferedImage.TYPE_INT_RGB)
        for (py in 0 until n) for (px in 0 until n) {
            val dark = q.isDark(px / scale - quiet, py / scale - quiet)
            img.setRGB(px, py, if (dark) 0x000000 else 0xFFFFFF)
        }
        return img
    }

    private fun run(vararg cmd: String): Pair<Int, String>? {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(30, TimeUnit.SECONDS)) { p.destroyForcibly(); return null }
        return p.exitValue() to out
    }

    @Test fun theFinderAndTimingPatternsAreWhereTheSpecSaysForEveryVersion() {
        for (len in listOf(1, 20, 60, 130, 300, 800, 2000, 2953)) {
            val q = QrCode.encodeText(payload(len))
            val n = q.size
            for ((ox, oy) in listOf(0 to 0, n - 7 to 0, 0 to n - 7)) for (d in 0..6) {
                assertTrue(q.isDark(ox + d, oy) && q.isDark(ox + d, oy + 6) && q.isDark(ox, oy + d) && q.isDark(ox + 6, oy + d), "finder ring v${q.version}")
            }
            for (i in 8 until n - 8) assertEquals(i % 2 == 0, q.isDark(i, 6), "timing row v${q.version} at $i")
            for (i in 8 until n - 8) assertEquals(i % 2 == 0, q.isDark(6, i), "timing col v${q.version} at $i")
            assertTrue(q.isDark(8, n - 8), "the dark module")
        }
    }

    @Test fun versionsGrowWithThePayloadAndTheLimitIsEnforced() {
        assertEquals(1, QrCode.encodeText("hi").version)
        assertEquals(40, QrCode.encodeText(payload(2953)).version)
        assertFailsWith<IllegalArgumentException> { QrCode.encodeText(payload(2954)) }
        assertTrue(QrCode.encodeText(payload(200), QrEcc.Medium).version > QrCode.encodeText(payload(200), QrEcc.Low).version - 1)
    }

    @Test fun anIndependentReaderDecodesEveryVersionAtBothLevels() {
        val zbar = tool("zbarimg") ?: return
        // Sizes chosen so each version 1..40 is the smallest that fits at least once, plus the boundary sizes.
        val seen = HashSet<Pair<Int, QrEcc>>()
        val dir = File.createTempFile("qrt", "").let { it.delete(); it.mkdirs(); it }
        try {
            for (ecc in QrEcc.entries) for (len in (1..2953 step 17) + listOf(2953, 2331, 1273, 154, 192)) {
                if (ecc == QrEcc.Medium && len > 2331) continue
                val text = payload(len)
                val q = QrCode.encodeText(text, ecc)
                if (!seen.add(q.version to ecc) && len % 3 != 0) continue
                val png = File(dir, "q.png"); ImageIO.write(render(q, scale = if (q.version > 25) 4 else 6), "png", png)
                val (code, out) = run(zbar.path, "--raw", "-q", "--nodbus", png.path) ?: error("zbarimg timed out")
                assertEquals(0, code, "zbarimg failed on v${q.version} $ecc len=$len: $out")
                assertEquals(text, out.trimEnd('\n'), "decoded text differs on v${q.version} $ecc len=$len")
            }
            for (v in 1..40) assertTrue((v to QrEcc.Low) in seen, "never exercised version $v at L")
        } finally { dir.deleteRecursively() }
    }

    @Test fun textThatIsNotAsciiRoundTripsAsUtf8() {
        val zbar = tool("zbarimg") ?: return
        val text = "paddock → Ключ ✓"
        val dir = File.createTempFile("qrt", "").let { it.delete(); it.mkdirs(); it }
        try {
            val png = File(dir, "u.png"); ImageIO.write(render(QrCode.encodeText(text)), "png", png)
            val (code, out) = run(zbar.path, "--raw", "-q", "--nodbus", png.path) ?: error("timed out")
            assertEquals(0, code, out)
            assertEquals(text, out.trimEnd('\n'))
        } finally { dir.deleteRecursively() }
    }

    /** The mask an independent encoder chose, read from its format information (it need not match ours: both are valid). */
    private fun maskOf(rows: List<BooleanArray>): Int {
        val n = rows.size
        var bits = 0
        for (i in 0..7) if (rows[8][n - 1 - i]) bits = bits or (1 shl i)
        for (i in 8..14) if (rows[n - 15 + i][8]) bits = bits or (1 shl i)
        return (((bits xor 0x5412) ushr 10) and 0x7)
    }

    @Test fun theUnmaskedDataEqualsAnIndependentEncodersForTheSameVersionAndLevel() {
        val qrencode = tool("qrencode") ?: return
        var compared = 0
        for (ecc in QrEcc.entries) for (len in listOf(5, 40, 100, 140, 200, 400, 900, 1500, 2300)) {
            if (ecc == QrEcc.Medium && len > 2300) continue
            val text = payload(len)
            val q = QrCode.encodeText(text, ecc)
            val level = if (ecc == QrEcc.Low) "L" else "M"
            val (code, out) = run(qrencode.path, "-t", "ASCII", "-m", "0", "-l", level, "-v", q.version.toString(), "-o", "-", text) ?: continue
            assertEquals(0, code, out)
            val theirs = out.trimEnd('\n').split('\n').map { row -> BooleanArray(q.size) { x -> row.substring(2 * x, 2 * x + 2) == "##" } }
            assertEquals(q.size, theirs.size, "row count v${q.version} $ecc")
            val theirMask = maskOf(theirs)
            for (y in 0 until q.size) for (x in 0 until q.size) {
                if (q.isFunction(x, y)) {
                    if (!(x == 8 || y == 8)) assertEquals(q.isDark(x, y), theirs[y][x], "function module ($x,$y) v${q.version} $ecc")
                } else {
                    val mine = q.isDark(x, y) != QrCode.maskInverts(q.mask, x, y)
                    val their = theirs[y][x] != QrCode.maskInverts(theirMask, x, y)
                    assertEquals(their, mine, "unmasked data module ($x,$y) differs v${q.version} $ecc len=$len (masks ${q.mask}/$theirMask)")
                }
            }
            compared++
        }
        assertTrue(compared > 0)
    }
}
