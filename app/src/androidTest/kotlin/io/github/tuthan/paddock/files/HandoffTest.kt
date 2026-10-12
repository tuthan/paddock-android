package io.github.tuthan.paddock.files

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** SEC-5, SEC-6: what Open and Share hand to another app is one `content://` URI of Paddock's own provider, read-only, and never a path. */
class HandoffTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val cache = TransferCache(ctx.cacheDir)
    private val file = cache.fetchFile("handoff-test", "id1", "report.txt").also { it.writeText("hello") }

    @After fun clean() = cache.forgetMachine("handoff-test")

    @Test fun openIsAViewOfOneContentUriOfOurProviderWithAReadGrantAndNothingElse() {
        val i = Handoff.openIntent(ctx, file, OpenableTypes.Kind.Openable("text/plain"))
        assertEquals(Intent.ACTION_VIEW, i.action)
        assertEquals("text/plain", i.type)
        assertEquals("content", i.data?.scheme)
        assertEquals(TransferFileProvider.authority(ctx.packageName), i.data?.authority)
        assertEquals("the grant is read only", Intent.FLAG_GRANT_READ_URI_PERMISSION, i.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION))
        assertFalse("the URI does not carry the path", i.data.toString().contains(ctx.cacheDir.path))
        assertTrue("it names the file", i.data.toString().endsWith("/report.txt"))
    }

    @Test fun openRefusesWhatIsNotOpenable() {
        for (kind in listOf(OpenableTypes.Kind.Apk, OpenableTypes.kindOf("page.html"), OpenableTypes.kindOf("logo.svg"), OpenableTypes.kindOf("a.zip"))) {
            assertThrows(kind.toString(), IllegalArgumentException::class.java) { Handoff.openIntent(ctx, file, kind) }
        }
    }

    @Test fun shareIsASendOfTheStreamWithAClipAndAReadGrant() {
        val i = Handoff.shareIntent(ctx, file, "text/plain")
        assertEquals(Intent.ACTION_SEND, i.action)
        assertEquals("text/plain", i.type)
        val uri = i.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
        assertEquals(TransferFileProvider.authority(ctx.packageName), uri?.authority)
        assertEquals(uri, i.clipData?.getItemAt(0)?.uri)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, i.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION))
        assertNull("no text goes with it", i.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test fun theProviderServesOnlyTheTransferFoldersAndRefusesEverythingElse() {
        val outside = File(ctx.filesDir, "secret.txt").also { it.writeText("no") }
        try {
            assertThrows(IllegalArgumentException::class.java) { Handoff.uriFor(ctx, outside) }
            val incoming = cache.snapshotDir("snap-for-test").let { File(it, "file.bin").also { f -> f.writeText("x") } }
            assertThrows("a snapshot is the app's own", IllegalArgumentException::class.java) { Handoff.uriFor(ctx, incoming) }
            cache.deleteSnapshot("snap-for-test")
        } finally { outside.delete() }
    }

    @Test fun theProviderIsNotExportedAndGrantsOnlyWhatItIsGiven() {
        val info = ctx.packageManager.getProviderInfo(android.content.ComponentName(ctx, TransferFileProvider::class.java), 0)
        assertFalse(info.exported)
        assertTrue(info.grantUriPermissions)
        assertNotNull(info.authority)
    }

    @Test fun saveCopiesTheWholeFileIntoTheDestinationAndReplacesWhatWasThere() {
        val dest = File(ctx.cacheDir, "save-target.txt").also { it.writeText("old and longer than the new content") }
        try {
            assertTrue(Handoff.saveTo(ctx, file, android.net.Uri.fromFile(dest)))
            assertEquals("hello", dest.readText())
        } finally { dest.delete() }
    }

    @Test fun saveToADestinationThatCannotBeWrittenIsAFailureNotACrash() {
        assertFalse(Handoff.saveTo(ctx, file, android.net.Uri.parse("content://${ctx.packageName}.nothing/none")))
    }

    @Test fun mimeIsTheKindsTypeAndAnApkIsAPackageArchive() {
        assertEquals("image/png", Handoff.mimeOf(OpenableTypes.kindOf("a.png")))
        assertEquals(OpenableTypes.APK, Handoff.mimeOf(OpenableTypes.kindOf("a.apk")))
        assertEquals("application/octet-stream", Handoff.mimeOf(OpenableTypes.kindOf("a.bin")))
    }
}
