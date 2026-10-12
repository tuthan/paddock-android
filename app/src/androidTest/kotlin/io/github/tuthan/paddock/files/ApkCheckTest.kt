package io.github.tuthan.paddock.files

import android.os.Build
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Spike S6 and AC-15.29: what `getPackageArchiveInfo` says about an APK on this API level. The three files are Paddock's own installed APK (signed by whatever
 * signed this build), the same APK with its signature files removed (unsigned), and, when the harness put one in `cache/s6/`, a release APK signed by another
 * key. The observed answers are logged under `S6` for the report; the assertions are what the sheet depends on.
 */
class ApkCheckTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val work = File(ctx.cacheDir, "apkcheck-test").also { it.mkdirs() }
    @After fun clean() { work.deleteRecursively() }

    private fun own() = File(ctx.applicationInfo.sourceDir)

    private fun unsigned(): File {
        val out = File(work, "unsigned.apk")
        ZipOutputStream(out.outputStream()).use { zip ->
            ZipFile(own()).use { src ->
                for (e in src.entries()) {
                    if (e.name.startsWith("META-INF/") && Regex("""META-INF/[^/]*\.(SF|RSA|DSA|EC|MF)""").matches(e.name)) continue
                    zip.putNextEntry(ZipEntry(e.name).also { n -> if (e.method == ZipEntry.STORED) { n.method = ZipEntry.STORED; n.size = e.size; n.compressedSize = e.size; n.crc = e.crc } })
                    src.getInputStream(e).use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        return out
    }

    private fun note(what: String, info: ApkInfo?) = Log.i("S6", "API ${Build.VERSION.SDK_INT} $what: " + (info?.let { "package=${it.packageName} version=${it.versionName} (${it.versionCode}) signers=${it.signers.size}${it.signers.firstOrNull()?.let { s -> " first=${s.take(16)}…" } ?: ""}" } ?: "null (unreadable)"))

    @Test fun paddocksOwnInstalledApkIsReadWithItsSigner() {
        val info = ApkCheck.read(ctx, own())
        note("own installed apk", info)
        assertEquals(ctx.packageName, info?.packageName)
        assertTrue("a signed build has a signer", info!!.signers.isNotEmpty())
        assertEquals("the same signer as the Paddock installed here", ApkCheck.ownSigners(ctx), info.signers)
        val lines = ApkNotes.lines(info, ctx.packageName, ApkCheck.ownSigners(ctx))
        assertTrue(ApkNotes.SAME_SIGNER in lines)
        assertFalse(ApkNotes.OTHER_SIGNER in lines)
    }

    @Test fun anApkWithItsSignatureFilesRemovedIsNamedUnsigned() {
        val info = ApkCheck.read(ctx, unsigned())
        note("own apk, signature files removed", info)
        // The v2 and later signing block does not survive a re-zip, and the v1 files are gone, so nothing signs it. With the signers asked for, the platform
        // refuses to parse it on every level the spike ran (26, 36, 37); asked for nothing, it parses, and that is the unsigned package the sheet names.
        assertEquals(ctx.packageName, info?.packageName)
        assertTrue("it never has a signer it was not given", info!!.signers.isEmpty())
        val lines = ApkNotes.lines(info, ctx.packageName, ApkCheck.ownSigners(ctx))
        assertTrue(ApkNotes.UNSIGNED in lines)
        assertFalse(ApkNotes.SAME_SIGNER in lines)
        assertFalse(ApkNotes.OTHER_SIGNER in lines)
    }

    @Test fun aFileThatIsNotAPackageIsUnreadable() {
        val junk = File(work, "junk.apk").also { it.writeText("this is not a zip") }
        assertEquals(null, ApkCheck.read(ctx, junk))
        assertEquals(null, ApkCheck.read(ctx, File(work, "missing.apk")))
        assertEquals(listOf(ApkNotes.UNREADABLE, ApkNotes.WHERE), ApkNotes.lines(null, ctx.packageName, ApkCheck.ownSigners(ctx)))
    }

    @Test fun aReleaseApkSignedByAnotherKeyIsPaddocksOwnPackageUnderAnotherSigner() {
        val other = File(ctx.cacheDir, "s6/other-signer.apk")
        assumeTrue("the harness puts one at cache/s6/other-signer.apk", other.isFile)
        val info = ApkCheck.read(ctx, other)
        note("release apk, another key", info)
        assertEquals(ctx.packageName, info?.packageName)
        assertTrue(info!!.signers.isNotEmpty())
        assertTrue("another key", info.signers != ApkCheck.ownSigners(ctx))
        assertTrue(ApkNotes.OTHER_SIGNER in ApkNotes.lines(info, ctx.packageName, ApkCheck.ownSigners(ctx)))
    }

    @Test fun ownSignersCanBeReadAndAreSha256Hex() {
        val own = ApkCheck.ownSigners(ctx)
        Log.i("S6", "API ${Build.VERSION.SDK_INT} own signers: ${own.size}")
        assertTrue(own.isNotEmpty())
        assertTrue(own.all { it.matches(Regex("[0-9a-f]{64}")) })
    }
}
