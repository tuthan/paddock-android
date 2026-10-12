package io.github.tuthan.paddock.ui

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tuthan.paddock.files.IncomingFiles
import io.github.tuthan.paddock.files.Intake
import io.github.tuthan.paddock.files.IntakeRefusal
import io.github.tuthan.paddock.files.PlatformUriCheck
import io.github.tuthan.paddock.files.TransferCache
import io.github.tuthan.paddock.files.TransferFileProvider
import io.github.tuthan.paddock.files.UriRefusal
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** SEC-1 on a device, with the real policy: a `file://` URI and Paddock's own provider are refused and nothing is read; another app's content is taken. */
class IntakeRefusalTest {
    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val cache = TransferCache(ctx.cacheDir)
    private var opens = 0
    private val files = IncomingFiles(ctx, cache, PlatformUriCheck(ctx), open = { opens++; ctx.contentResolver.openInputStream(it) })
    private val toDelete = mutableListOf<File>()
    private val inserted = mutableListOf<Uri>()

    @After fun clean() {
        toDelete.forEach { it.delete() }
        inserted.forEach { runCatching { ctx.contentResolver.delete(it, null, null) } }
        File(ctx.cacheDir, "camera").deleteRecursively()
    }

    @Test fun aFileUriToThePrivateProfilesIsRefusedWithNothingRead() {
        val profiles = File(ctx.filesDir, "host-profiles.json")
        val existed = profiles.exists()
        if (!existed) { profiles.writeText("[]"); toDelete += profiles }
        val before = File(ctx.cacheDir, "incoming").list()?.toSet().orEmpty()
        val r = runBlocking { files.take(Uri.fromFile(profiles), "host-profiles.json") }
        assertEquals(Intake.Refused(IntakeRefusal.Uri), r)
        assertEquals("the URI was never opened", 0, opens)
        assertEquals("nothing was copied", before, File(ctx.cacheDir, "incoming").list()?.toSet().orEmpty())
    }

    @Test fun paddocksOwnProviderIsRefusedWithNothingRead() {
        val f = cache.cameraFile(UUID.randomUUID().toString()).apply { writeText("secret") }.also { toDelete += it }
        val uri = FileProvider.getUriForFile(ctx, TransferFileProvider.authority(ctx.packageName), f)
        assertEquals(Intake.Refused(IntakeRefusal.Uri), runBlocking { files.take(uri, "x.jpg") })
        assertEquals(0, opens)
    }

    @Test fun theRealPolicyNamesTheOwnProviderAndTheSchemeAsTheReasons() {
        val check = PlatformUriCheck(ctx)
        val own = TransferFileProvider.authority(ctx.packageName)
        assertEquals(UriRefusal.OwnProvider, check.check(Uri.parse("content://$own/camera/x")))
        assertEquals(UriRefusal.OwnProvider, check.check(Uri.parse("content://10@$own/camera/x")))
        assertEquals(UriRefusal.NotContent, check.check(Uri.parse("file:///data/data/${ctx.packageName}/files/host-profiles.json")))
        assertEquals(UriRefusal.NotContent, check.check(Uri.parse("http://example.com/a.png")))
        assertNull("another app's authority is allowed", check.check(Uri.parse("content://media/external/images/media/1")))
    }

    @Test fun androidxStartupsProviderIsAlsoPaddocksOwn() {
        // The authority that exists only because a library declared it: the policy lists every provider of the package, not only the one Paddock wrote.
        assertEquals(UriRefusal.OwnProvider, PlatformUriCheck(ctx).check(Uri.parse("content://${ctx.packageName}.androidx-startup/x")))
    }

    @Test fun anImageTheTestInsertsThroughMediaStoreIsTaken() {
        assumeTrue("MediaStore inserts of the app's own media need no grant from API 29", Build.VERSION.SDK_INT >= 29)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "paddock-intake-${UUID.randomUUID()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PaddockTest")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!.also { inserted += it }
        ctx.contentResolver.openOutputStream(uri)!!.use { Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }
        val r = runBlocking { files.take(uri, "picked.png") }
        assertTrue("$r", r is Intake.Taken)
        cache.deleteSnapshot((r as Intake.Taken).snapshot.id)
        assertEquals(1, opens)
    }

    @Test fun aProviderThatThrowsOnOpenIsARefusalNotACrash() {
        val throwing = IncomingFiles(ctx, cache, PlatformUriCheck(ctx), open = { throw SecurityException("grant revoked") })
        assertEquals(Intake.Refused(IntakeRefusal.Unreadable), runBlocking { throwing.take(Uri.parse("content://media/external/images/media/1"), "a.png") })
    }

    @Test fun aStreamThatFailsMidReadIsARefusalAndLeavesNoSnapshot() {
        val failing = IncomingFiles(ctx, cache, PlatformUriCheck(ctx), open = {
            object : InputStream() {
                private var n = 0
                override fun read(): Int = if (n++ < 10) 1 else throw java.io.IOException("provider died")
                override fun read(b: ByteArray, off: Int, len: Int): Int = if (n++ < 1) { b[off] = 1; 1 } else throw java.io.IOException("provider died")
            }
        })
        val before = File(ctx.cacheDir, "incoming").list()?.toSet().orEmpty()
        assertEquals(Intake.Refused(IntakeRefusal.Unreadable), runBlocking { failing.take(Uri.parse("content://media/external/images/media/1"), "a.png") })
        assertEquals(before, File(ctx.cacheDir, "incoming").list()?.toSet().orEmpty())
    }
}
