package io.github.tuthan.paddock.files

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The pure parts of the intake: the bounded copy and the sniffs for what is not an image. The rest runs on a device (`IncomingFilesTest`). */
class IncomingFilesRulesTest {
    @get:Rule val tmp = TemporaryFolder()

    /** A stream of [n] bytes that counts how many it handed out, so a test can show a copy stops at the cap and does not drain the sender. */
    private class Counting(private val n: Long) : InputStream() {
        var served = 0L
        override fun read(): Int = if (served >= n) -1 else { served++; 7 }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (served >= n) return -1
            val k = minOf(len.toLong(), n - served).toInt()
            java.util.Arrays.fill(b, off, off + k, 7)
            served += k
            return k
        }
    }

    @Test fun aFileUnderTheCapIsCopiedWhole() {
        val out = tmp.newFile()
        assertEquals(1000L, IncomingFiles.copyBounded(ByteArrayInputStream(ByteArray(1000) { 1 }), out, cap = 5000))
        assertEquals(1000L, out.length())
    }

    @Test fun aFileExactlyAtTheCapIsTaken() {
        val out = tmp.newFile()
        assertEquals(4096L, IncomingFiles.copyBounded(Counting(4096), out, cap = 4096))
        assertEquals(4096L, out.length())
    }

    @Test fun oneByteOverTheCapIsRefusedAndNothingIsKept() {
        val out = tmp.newFile()
        assertEquals(-1L, IncomingFiles.copyBounded(Counting(4097), out, cap = 4096))
        assertFalse("the over-cap copy is deleted", out.exists())
    }

    @Test(timeout = 10_000) fun aHugeStreamIsNotDrainedPastTheCapPlusOne() {
        val src = Counting(Long.MAX_VALUE / 2)
        val out = tmp.newFile()
        assertEquals(-1L, IncomingFiles.copyBounded(src, out, cap = 100_000))
        assertTrue("read ${src.served}", src.served <= 100_001)
    }

    @Test fun anEmptyStreamIsAnEmptyFile() {
        val out = tmp.newFile()
        assertEquals(0L, IncomingFiles.copyBounded(ByteArrayInputStream(ByteArray(0)), out, cap = 10))
    }

    @Test fun aPdfIsKnownByItsMagicNotItsName() {
        assertTrue(IncomingFiles.isPdf("%PDF-1.7\n".toByteArray()))
        assertFalse(IncomingFiles.isPdf("%PDX-1.7".toByteArray()))
        assertFalse(IncomingFiles.isPdf(" %PDF-1.7".toByteArray()))
        assertFalse(IncomingFiles.isPdf(ByteArray(0)))
        assertFalse(IncomingFiles.isPdf("%PDF".toByteArray()))
    }

    @Test fun plainUtf8TextIsText() {
        assertTrue(IncomingFiles.isText("hello\tworld\r\nline two\n\u000cpage".toByteArray()))
        assertTrue(IncomingFiles.isText("héllo wörld, 日本語, 🙂".toByteArray()))
    }

    @Test fun whatHasAControlByteOrBadUtf8OrNothingInItIsNotText() {
        assertFalse(IncomingFiles.isText(ByteArray(0)))
        assertFalse(IncomingFiles.isText("a\u0000b".toByteArray()))
        assertFalse(IncomingFiles.isText("a\u0007b".toByteArray()))
        assertFalse(IncomingFiles.isText("esc \u001b[31m".toByteArray()))
        assertFalse(IncomingFiles.isText(byteArrayOf(0xC3.toByte(), 0x28)))
        assertFalse(IncomingFiles.isText(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x41)))
        assertFalse("an ELF header is not text", IncomingFiles.isText(byteArrayOf(0x7F, 0x45, 0x4C, 0x46, 0x02, 0x01, 0x01, 0x00)))
    }

    @Test fun aHeadCutInsideAMultiByteCharacterIsStillText() {
        val head = ByteArray(256 * 1024) { 'a'.code.toByte() }
        val euro = "€".toByteArray() // three bytes, cut after two
        head[head.size - 2] = euro[0]
        head[head.size - 1] = euro[1]
        assertTrue(IncomingFiles.isText(head))
    }

    @Test fun aTextFileKeepsAnExtensionFromTheShortListAndAnythingElseBecomesTxt() {
        assertEquals("md", IncomingFiles.textExtension("notes.MD"))
        assertEquals("json", IncomingFiles.textExtension("a.b.json"))
        assertEquals("txt", IncomingFiles.textExtension("run.sh"))
        assertEquals("txt", IncomingFiles.textExtension("evil.exe"))
        assertEquals("txt", IncomingFiles.textExtension("noext"))
        assertEquals("txt", IncomingFiles.textExtension(null))
        assertEquals("txt", IncomingFiles.textExtension(".md/../x"))
    }
}
