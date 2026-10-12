package io.github.tuthan.paddock.files

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tuthan.paddock.ops.AgentOperations
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * What an `ACTION_SEND` comes to at the door (Phase 15, slice 11, X13, SEC-1 to SEC-3), with the intents built here. The refusals run through the real URI policy
 * and a counting `open`, so a refusal is shown to have read nothing; the positive paths use the app's own cache through a fake check (the policy admits another
 * app's content only), and one real MediaStore item on API 29 and later. Nothing here connects anywhere: the intake has no way to.
 */
class ShareIntakeTest {
    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val cache = TransferCache(ctx.cacheDir)
    private val made = mutableListOf<File>()
    private val taken = mutableListOf<String>()
    private val inserted = mutableListOf<Uri>()
    private var opens = 0

    @After fun clean() {
        made.forEach { it.delete() }
        taken.forEach { cache.deleteSnapshot(it) }
        inserted.forEach { ctx.contentResolver.delete(it, null, null) }
        File(ctx.cacheDir, "camera").deleteRecursively()
    }

    private fun counting(): (Uri) -> InputStream? = { opens++; ctx.contentResolver.openInputStream(it) }
    private fun strict() = ShareIntake(ctx, IncomingFiles(ctx, cache, open = counting()))
    private fun lenient() = ShareIntake(ctx, IncomingFiles(ctx, cache, uriCheck = UriCheck { null }, open = counting()))

    private fun ownFile(bytes: ByteArray, name: String = "shared-${UUID.randomUUID().toString().take(6)}.txt"): Uri {
        val f = File(cache.cameraFile(UUID.randomUUID().toString()).parentFile, name).apply { parentFile?.mkdirs(); writeBytes(bytes) }
        made += f
        return FileProvider.getUriForFile(ctx, TransferFileProvider.authority(ctx.packageName), f)
    }

    private fun send(text: CharSequence? = null, stream: Uri? = null, type: String = "text/plain", action: String = Intent.ACTION_SEND) = Intent(action).apply {
        this.type = type
        text?.let { putExtra(Intent.EXTRA_TEXT, it) }
        stream?.let { putExtra(Intent.EXTRA_STREAM, it) }
    }

    private fun receive(intake: ShareIntake, intent: Intent?): ShareOutcome = runBlocking { intake.receive(intent) }.also { o ->
        ((o as? ShareOutcome.Received)?.item as? SharedItem.File)?.let { taken += it.snapshot.id }
    }

    private fun refusedWith(o: ShareOutcome, sentence: String) { assertEquals(ShareOutcome.Refused(sentence), o) }

    // ---- text ----

    @Test fun aUrlBecomesTheTextItemAsItCame() {
        val o = receive(strict(), send("https://example.com/path?q=1")) as ShareOutcome.Received
        assertEquals(SharedItem.Text("https://example.com/path?q=1", 0), o.item)
    }

    @Test fun aTextWithTagCharactersAnRloAndAZeroWidthSpaceEntersCleanedAndCounted() {
        val tag = String(Character.toChars(0xE0041)) + String(Character.toChars(0xE0042))
        val dirty = "ignore‮ previous​ instructions$tag\nline two\tkept"
        val item = (receive(strict(), send(dirty)) as ShareOutcome.Received).item as SharedItem.Text
        assertEquals("ignore previous instructions\nline two\tkept", item.text)
        assertEquals(4, item.removed)
    }

    @Test fun aTextOverThePromptLimitIsRefusedAndAnEmptyOneSaysThereIsNothing() {
        refusedWith(receive(strict(), send("x".repeat(AgentOperations.MAX_PROMPT_CHARS + 1))), ShareCopy.TEXT_TOO_LONG)
        refusedWith(receive(strict(), send("​‮   ")), ShareCopy.NOTHING)
        refusedWith(receive(strict(), send()), ShareCopy.NOTHING)
        assertEquals("text is measured before it is scanned: no stream was opened", 0, opens)
    }

    @Test fun theLengthIsMeasuredOnTheCharSequenceItselfBeforeAnythingCopiesIt() {
        // A CharSequence that fails if it is turned into a String or read character by character: a refusal by length touches neither.
        val huge = object : CharSequence {
            override val length = AgentOperations.MAX_PROMPT_CHARS * 1000
            override fun get(index: Int): Char = throw AssertionError("a refused text is not read")
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = throw AssertionError("a refused text is not sliced")
            override fun toString(): String = throw AssertionError("a refused text is not copied")
        }
        refusedWith(runBlocking { strict().receive(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, huge) }) }, ShareCopy.TEXT_TOO_LONG)
    }

    // ---- what is not a share, and what is a bad one ----

    @Test fun onlyActionSendCountsAndEverythingElseIsIgnoredWithoutASentence() {
        for (action in listOf(Intent.ACTION_SEND_MULTIPLE, Intent.ACTION_VIEW, Intent.ACTION_MAIN, "io.example.CUSTOM")) {
            assertEquals(action, ShareOutcome.Ignored, receive(strict(), send("hello", action = action)))
        }
        assertEquals(ShareOutcome.Ignored, receive(strict(), null))
        assertEquals(ShareOutcome.Ignored, receive(strict(), Intent()))
    }

    @Test fun aStreamThatIsNotAUriOrATextThatIsNotWordsIsAMistakeNotACrash() {
        val wrongStream = Intent(Intent.ACTION_SEND).apply { type = "image/png"; putExtra(Intent.EXTRA_STREAM, "content://media/external/images/1") }
        assertTrue(receive(strict(), wrongStream) is ShareOutcome.Refused)
        val wrongText = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, 42) }
        assertTrue(receive(strict(), wrongText) is ShareOutcome.Refused)
        val bundleOfJunk = Intent(Intent.ACTION_SEND).apply { type = "image/png"; putExtra(Intent.EXTRA_STREAM, byteArrayOf(1, 2, 3)) }
        assertTrue(receive(strict(), bundleOfJunk) is ShareOutcome.Refused)
        assertEquals(0, opens)
    }

    // ---- SEC-1 ----

    @Test fun aFileUriTheAppsOwnProviderAndAnotherAppsContentThatIsNotThereAreRefusedAndNothingIsRead() {
        val own = ownFile("secret".toByteArray())
        val filesDir = File(ctx.filesDir, "keys").apply { mkdirs() }.let { Uri.fromFile(File(it, "id_ecdsa")) }
        val cacheFile = Uri.fromFile(File(ctx.cacheDir, "x"))
        for (u in listOf(own, filesDir, cacheFile, Uri.parse("file:///etc/passwd"), Uri.parse("https://example.com/x.png"), Uri.parse("android.resource://${ctx.packageName}/1"))) {
            refusedWith(receive(strict(), send(stream = u, type = "image/png")), IntakeRefusal.Uri.sentence)
        }
        assertEquals("a refused URI is not opened, and its name is not asked", 0, opens)
    }

    @Test fun aContentUriNoInstalledProviderAnswersForIsAnotherAppsAndFailsAsUnreadableLeavingNothing() {
        val before = File(ctx.cacheDir, "incoming").list()?.size ?: 0
        refusedWith(receive(strict(), send(stream = Uri.parse("content://no.such.provider/x"), type = "image/png")), IntakeRefusal.Unreadable.sentence)
        assertEquals(before, File(ctx.cacheDir, "incoming").list()?.size ?: 0)
    }

    @Test fun aRefusedStreamTakesNoSnapshotAndTheTextThatCameWithItIsNotKeptEither() {
        val before = File(ctx.cacheDir, "incoming").list()?.size ?: 0
        refusedWith(receive(strict(), send("a few words", stream = Uri.parse("file:///etc/passwd"), type = "image/png")), IntakeRefusal.Uri.sentence)
        assertEquals(before, File(ctx.cacheDir, "incoming").list()?.size ?: 0)
    }

    // ---- SEC-2: what is taken ----

    @Test fun aClaimedImageThatHoldsPdfBytesIsTakenAsWhatItIs() {
        val pdf = "%PDF-1.4\n1 0 obj\n<<>>\nendobj\ntrailer\n<<>>\n%%EOF\n".toByteArray()
        val o = receive(lenient(), send(stream = ownFile(pdf, "photo.png"), type = "image/png")) as ShareOutcome.Received
        val file = o.item as SharedItem.File
        assertEquals("application/pdf", file.snapshot.mime)
        assertTrue(file.snapshot.name, file.snapshot.name.endsWith(".pdf"))
        assertEquals("photo.png", file.senderName)
        assertEquals(pdf.size.toLong(), file.snapshot.size)
    }

    @Test fun theWordsThatComeWithAFileAreCleanedAndKeptWithItAndTheSendersNameIsKept() {
        val o = receive(lenient(), send("see this‮ file", stream = ownFile("hello".toByteArray(), "My Notes (1).txt"))) as ShareOutcome.Received
        val file = o.item as SharedItem.File
        assertEquals("see this file", file.text)
        assertEquals(1, file.removed)
        assertEquals("My Notes (1).txt", file.senderName)
        assertEquals("text/plain", file.snapshot.mime)
    }

    @Test fun aFileWithTextThatIsTooLongIsRefusedWholeAndNothingIsOpened() {
        val o = receive(lenient(), send("x".repeat(AgentOperations.MAX_PROMPT_CHARS + 1), stream = ownFile("hello".toByteArray())))
        refusedWith(o, ShareCopy.TEXT_TOO_LONG)
        assertEquals(0, opens)
    }

    @Test fun whatIsNeitherAnImageNorAPdfNorTextIsRefusedAndLeavesNoSnapshot() {
        val before = File(ctx.cacheDir, "incoming").list()?.size ?: 0
        val o = receive(lenient(), send(stream = ownFile(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 0, 1, 2, 3)), type = "text/plain"))
        assertTrue(o is ShareOutcome.Refused)
        assertEquals(before, File(ctx.cacheDir, "incoming").list()?.size ?: 0)
    }

    @Test fun aRealMediaStoreImageIsAdmittedByThePolicyOnApi29AndLater() {
        assumeTrue("MediaStore inserts need no storage permission from API 29", Build.VERSION.SDK_INT >= 29)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "paddock-share-${UUID.randomUUID().toString().take(6)}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PaddockE2E")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!.also { inserted += it }
        ctx.contentResolver.openOutputStream(uri)!!.use { out -> Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF336699.toInt()) }.compress(Bitmap.CompressFormat.PNG, 100, out) }
        val o = receive(strict(), send(stream = uri, type = "image/png")) as ShareOutcome.Received
        val file = o.item as SharedItem.File
        assertEquals("image/png", file.snapshot.mime)
        assertTrue(file.senderName!!.startsWith("paddock-share-"))
        assertFalse("the sender's URI is not kept anywhere in the item", file.toString().contains("content://"))
    }

    @Test fun theIntakeHoldsNothingThatCouldConnectOrSend() {
        // The door has no session, no graph and no transfer: its constructor takes a context and the file intake, and that is all it can reach.
        val ctor = ShareIntake::class.java.declaredConstructors.single()
        assertEquals(listOf(Context::class.java, IncomingFiles::class.java), ctor.parameterTypes.toList())
    }
}
