package io.github.tuthan.paddock.e2e

import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.core.app.ActivityOptionsCompat
import android.content.ClipboardManager
import android.content.Intent
import io.github.tuthan.paddock.ui.EXTRA_IS_SENSITIVE_KEY
import io.github.tuthan.paddock.ui.screens.DropFolderCopy
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tuthan.paddock.MainActivity
import io.github.tuthan.paddock.PaddockApp
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Phase 15 slice 10 on a device, through the whole app over SSH (`paddock-harness/run-files-e2e.sh`). The script prepares the host (a git checkout as the
 * agent's folder, the fake agent as `claude`, a throwaway sshd behind the link-cut proxy) and checks at each checkpoint what only the host can see: the
 * file in `.paddock/inbox/`, its mode and hash, `git status`, the line the agent received, the sockets the app opened, and logcat for the marker.
 *
 * The picker is answered by the test (the graph's `activityResultsOverride`), with an image the test inserts into MediaStore: another app's content, so
 * the real SEC-1 check admits it. Every file name holds the run's marker (`mark`), which the script then searches logcat for (SEC-7): it must never appear.
 *
 * t1: attach a PNG, write a few words, Send; the file arrives and the prompt names it; the draft is empty again.
 * t2: a cut upload: the link freezes the moment the part exists, the row goes Unknown, Send is off with its reason, Read the inbox finds the file absent, a
 *     second send then goes through.
 * t5: Get. The agent's output names three files of its `.paddock/outbox/`: a link to a file outside it, a file over the cap and a small text file. A tap
 *     on each: the first two are refused with the machine's sentences, the third is read into the app's cache with the hash the script computed, and the
 *     Files list shows the outbox without the link.
 */
class FilesFlowTest {
    @get:Rule val rule: AndroidComposeTestRule<*, MainActivity> = createAndroidComposeRule<MainActivity>()

    private val args = InstrumentationRegistry.getArguments()
    private val hostFp by lazy { args.getString("hostFp") ?: error("hostFp argument missing") }
    private val user by lazy { args.getString("user") ?: error("user argument missing") }
    private val port = args.getString("port") ?: "2234"
    private val session = args.getString("session") ?: "paddock-test"
    private val mark = args.getString("mark") ?: "nomark"
    private val host = "10.0.2.2"
    private val relayQuestion = "Install the relay on "
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val inserted = mutableListOf<Uri>()

    private fun log(msg: String) = android.util.Log.i("E2E", msg)

    private fun shoot(name: String) {
        val dir = File(ctx.getExternalFilesDir(null), "screens").apply { mkdirs() }
        File(dir, "files-$name.png").outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun waitFor(what: String, ms: Long = 30_000, cond: () -> Boolean) {
        try { rule.waitUntil(ms, cond) } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            shoot("timeout-" + what.replace(Regex("[^a-z0-9]+"), "-").take(30)); throw AssertionError("timed out waiting for $what")
        }
    }

    private fun hasNode(m: androidx.compose.ui.test.SemanticsMatcher) = rule.onAllNodes(m).fetchSemanticsNodes().isNotEmpty()
    private fun text(t: String, substring: Boolean = false) = hasText(t, substring = substring)
    private fun desc(d: String, substring: Boolean = true) = hasContentDescription(d, substring = substring)
    private fun fieldText(): String = rule.onNode(hasSetTextAction()).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    private fun checkpoint(marker: String, timeoutMs: Long = 180_000) {
        log("AT $marker")
        val go = File(ctx.getExternalFilesDir(null), "go-$marker")
        try { rule.waitUntil(timeoutMs) { go.exists() } } catch (e: Throwable) { throw AssertionError("the script did not release checkpoint $marker") }
        go.delete()
    }

    /** A tap on the characters of [path] in the Output tab's text, found by the line's own layout, as a finger would land. */
    private fun tapPath(path: String) {
        val node = rule.onNode(hasText(path, substring = true), useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!(layouts)
        val layout = layouts.single()
        val at = layout.layoutInput.text.text.indexOf(path) + path.length / 2
        val center = layout.getBoundingBox(at).center
        rule.onNode(hasText(path, substring = true)).performTouchInput { click(center) }
    }

    private fun cachedFiles(name: String): List<File> = File(ctx.cacheDir, "transfers").walkTopDown().filter { it.isFile && it.name == name }.toList()

    private fun back() { rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; rule.waitForIdle() }

    private fun openAgentFromHome() {
        waitFor("the fake agent on Home, ready and live", 90_000) { hasNode(desc("Ready")) && hasNode(desc("live")) }
        rule.onAllNodes(desc("Ready") and androidx.compose.ui.test.hasClickAction()).onFirst().performClick()
        waitFor("the Output tab") { hasNode(desc("Terminal output")) }
    }

    private fun openComposer(initial: String? = null) {
        rule.onNode(desc("Write a prompt for claude")).performClick()
        waitFor("the composer") { hasNode(hasSetTextAction()) }
        if (initial != null) rule.onNode(hasSetTextAction()).performTextInput(initial)
    }

    // ---- the picker, answered by the test ----

    /** An image of another app's (MediaStore's), named with the run's marker. */
    private fun mediaImage(label: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "paddock-$mark-$label-${UUID.randomUUID().toString().take(6)}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PaddockE2E")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!.also { inserted += it }
        ctx.contentResolver.openOutputStream(uri)!!.use { out ->
            Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF2E7D32.toInt()) }.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return uri
    }

    /** A text document in MediaStore's downloads, another app's content to the policy. The stored name must be the one asked for: a renamed file would weaken the refusal checks. */
    private fun mediaDocument(name: String, mime: String, bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/PaddockE2E")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!.also { inserted += it }
        ctx.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
        val stored = ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        check(stored == name) { "MediaProvider stored the file as $stored, not $name" }
        return uri
    }

    /**
     * What another app's Share sheet does: an `ACTION_SEND` for Paddock's own single-task activity, which the system delivers to the running instance (`onNewIntent`).
     * The activity then replaces its intent, as it should (a share is taken once). The scenario that owns the activity ignores every lifecycle event of an activity whose
     * intent no longer matches the one it launched with, so it would never see this one destroyed: [restoreLaunchIntent] puts the launch intent back before teardown.
     */
    private fun shareToPaddock(uri: Uri, type: String) {
        val intent = Intent(Intent.ACTION_SEND).setClass(ctx, MainActivity::class.java).setType(type).putExtra(Intent.EXTRA_STREAM, uri)
        if (launchIntent == null) launchIntent = rule.activity.intent
        rule.runOnUiThread { rule.activity.startActivity(intent) }
        rule.waitForIdle()
    }

    private var launchIntent: Intent? = null

    @After fun restoreLaunchIntent() {
        val original = launchIntent ?: return
        rule.runOnUiThread { rule.activity.intent = original }
    }

    private fun clickLabel(label: String) = androidx.compose.ui.test.SemanticsMatcher("onClick label $label") {
        it.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick)?.label == label
    }

    private fun answerPickerWith(uri: Uri) {
        val registry = object : ActivityResultRegistry() {
            override fun <I : Any?, O : Any?> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                check(contract is ActivityResultContracts.PickVisualMedia || contract is ActivityResultContracts.OpenDocument) { "unexpected contract ${contract.javaClass.simpleName}" }
                @Suppress("UNCHECKED_CAST") dispatchResult(requestCode, uri as O)
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry: ActivityResultRegistry = registry }
        (ctx.applicationContext as PaddockApp).graph.activityResultsOverride = owner
        rule.waitForIdle()
    }

    private fun attachPhoto() {
        rule.onNodeWithText("Attach").performClick()
        waitFor("the Attach menu") { hasNode(text("Photo")) }
        rule.onNodeWithText("Photo").performClick()
        waitFor("the attachment chip") { hasNode(hasTestTag("attachment-chip")) }
    }

    @After fun cleanUp() {
        (ctx.applicationContext as PaddockApp).graph.activityResultsOverride = null
        inserted.forEach { runCatching { ctx.contentResolver.delete(it, null, null) } }
    }

    @Test fun t0_exportAppKey() {
        File(ctx.getExternalFilesDir(null), "app-phone.pub").writeText(io.github.tuthan.paddock.ssh.PhoneKey().publicLine("paddock-e2e") + "\n")
    }

    @Test fun t1_aPngAttachedWithAFewWordsArrivesAndTheAgentIsToldWhere() {
        waitFor("the Add machine screen") { rule.passWelcome(); hasNode(text("Add a machine")) }
        rule.onNodeWithText("Host or IP address").performTextInput(host)
        rule.onNodeWithText("User").performTextInput(user)
        rule.onNode(hasText("Port") and hasSetTextAction()).performTextReplacement(port)
        rule.onNode(hasText("herdr session (optional)") and hasSetTextAction()).performScrollTo().performTextInput(session)
        rule.onNodeWithText("Connect").performClick()
        waitFor("the first-trust dialog") { hasNode(text("New host: $host:$port")) }
        rule.onNodeWithText("Trust and connect").performClick()
        waitFor("the relay prompt or the home") { hasNode(text(relayQuestion, substring = true)) || hasNode(desc("Ready")) }
        if (hasNode(text(relayQuestion, substring = true))) rule.onNodeWithText("Install the relay").performClick()

        openAgentFromHome()
        answerPickerWith(mediaImage("one"))
        openComposer("what is in this picture?")
        waitFor("Attach on: the agent's folder is known") { hasNode(text("Attach") and isEnabled()) }
        shoot("composer-attach-on")
        attachPhoto()
        shoot("composer-chip")
        assertTrue("the chip names the file: a time, then the reduced name", hasNode(hasText(mark, substring = true)))
        waitFor("Send open with the attachment and the words") { hasNode(hasText("Send prompt") and isEnabled()) }
        rule.onNodeWithText("Send prompt").performClick()
        waitFor("the accepted prompt", 90_000) { hasNode(text("accepted by herdr", substring = true)) }
        assertEquals("the words went with the prompt: the field is empty again", "", fieldText())
        assertFalse("and the attachment went with it", hasNode(hasTestTag("attachment-chip")))
        shoot("sent")
        checkpoint("t1-sent")
    }

    @Test fun t2_aCutUploadIsUnknownTheInboxIsReadAndASecondSendGoesThrough() {
        openAgentFromHome()
        answerPickerWith(mediaImage("two"))
        openComposer()
        waitFor("Attach on") { hasNode(text("Attach") and isEnabled()) }
        attachPhoto()
        waitFor("Send open with an attachment alone") { hasNode(hasText("Send prompt") and isEnabled()) }
        checkpoint("t2-armed")
        rule.onNodeWithText("Send prompt").performClick()
        // The link freezes the moment the part exists. The put is cut at its deadline and the row goes Unknown, but by then the app has also noticed the
        // dead link and the composer is replaced by "not available": the script waits for the row, thaws the link, and releases the test.
        checkpoint("t2-sent", timeoutMs = 240_000)
        waitFor("the unknown attachment, once the link is back and the composer with it", 120_000) { hasNode(text("This file may already be on the machine. Read the inbox first.", substring = true)) }
        rule.onNodeWithText("Send prompt").assertIsNotEnabled()
        shoot("unknown")
        waitFor("Read the inbox is offered") { hasNode(text("Read the inbox")) }
        rule.onNodeWithText("Read the inbox").performClick()
        waitFor("what the inbox holds", 60_000) { hasNode(text("The file is not on the machine", substring = true)) }
        shoot("inbox-read")
        waitFor("Send open again after the inbox was read", 30_000) { hasNode(hasText("Send prompt") and isEnabled()) }
        checkpoint("t2-read")
        rule.onNodeWithText("Send prompt").performClick()
        waitFor("the accepted prompt", 90_000) { hasNode(text("accepted by herdr", substring = true)) }
        assertFalse(hasNode(hasTestTag("attachment-chip")))
        checkpoint("t3-sent")
    }

    @Test fun t4_aSharedFileGoesToTheMachinesDropFolderAndAnInstructionFileIsRefused() {
        val home = args.getString("dropHome") ?: error("dropHome argument missing")
        waitFor("Home, watching and live", 90_000) { hasNode(desc("live")) }

        // The folder is chosen on the machine's page, and the machine itself says what the value is there before it is saved.
        rule.onNode(clickLabel("Show saved machines")).performClick()
        waitFor("the saved machine on the Machines screen") { hasNode(androidx.compose.ui.test.SemanticsMatcher("an Open card") { n ->
            n.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick)?.label?.startsWith("Open ") == true
        }) }
        rule.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("an Open card") { n ->
            n.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick)?.label?.startsWith("Open ") == true
        }).onFirst().performClick()
        waitFor("the drop folder section on the machine's page") { hasNode(hasTestTag("drop-folder-value")) }
        rule.onNodeWithText("Change…").performScrollTo().performClick()
        waitFor("the sheet") { hasNode(text(DropFolderCopy.EDIT_TITLE)) }
        val field = hasSetTextAction() and hasText(DropFolderCopy.FIELD)
        rule.onNode(field).performTextReplacement("~/PaddockE2E")
        rule.onNodeWithText(DropFolderCopy.CHECK).performScrollTo().performClick()
        waitFor("the machine's answer", 60_000) { hasNode(hasTestTag("drop-told")) }
        assertTrue("it says which folder that is on the machine, and that it will be created", hasNode(text("~/PaddockE2E is $home/PaddockE2E on this machine and will be created", substring = true)))
        rule.onNodeWithText(DropFolderCopy.SAVE).performScrollTo().performClick()
        waitFor("the folder saved") { hasNode(text("~/PaddockE2E")) && !hasNode(text(DropFolderCopy.EDIT_TITLE)) }
        waitFor("the list of the new folder, read by the machine", 60_000) { hasNode(text(DropFolderCopy.notThereYet("$home/PaddockE2E"))) }
        shoot("drop-folder-page")
        back(); back()
        waitFor("Home again") { hasNode(desc("live")) }

        // A picture shared from another app: the chooser, the drop folder, the result with the path.
        shareToPaddock(mediaImage("drop"), "image/png")
        waitFor("the chooser", 30_000) { hasNode(text("Share to Paddock")) }
        waitFor("the machine's drop folder, once connected and read", 60_000) { hasNode(text("The machine's drop folder")) }
        shoot("share-chooser")
        rule.onNodeWithText("The machine's drop folder").performClick()
        waitFor("the result", 60_000) { hasNode(hasTestTag("share-result")) && hasNode(text("Sent")) }
        val shown = rule.onNode(hasText("$home/PaddockE2E/paddock-$mark-drop-", substring = true)).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
        assertTrue("the path on the machine is shown: $shown".replace(mark, "<mark>"), shown.startsWith("$home/PaddockE2E/paddock-$mark-drop-") && shown.endsWith(".png"))
        checkpoint("t4-sent") // the script screenshots the sheet and checks the folder, the file and its hash

        // Copy path puts the path on the clipboard, and it is not marked sensitive: a place on a machine is not a secret.
        rule.onNodeWithText("Copy path").performClick()
        val clip = ctx.getSystemService(ClipboardManager::class.java).primaryClip
        assertEquals(shown, clip?.getItemAt(0)?.text?.toString())
        assertFalse("a path is not a secret", clip?.description?.extras?.getBoolean(EXTRA_IS_SENSITIVE_KEY, false) == true)

        // Use in a prompt: the path becomes the waiting text, the chooser lists the agent, and the composer opens with the path in it.
        rule.onNodeWithText("Use in a prompt").performClick()
        waitFor("the agent in the chooser, ready", 60_000) { hasNode(clickLabel("Send here") and androidx.compose.ui.test.isEnabled()) }
        rule.onAllNodes(clickLabel("Send here")).onFirst().performClick()
        waitFor("the composer with the path in it", 30_000) { hasNode(hasSetTextAction()) && fieldText() == shown }
        assertFalse("nothing was sent: no attachment chip", hasNode(hasTestTag("attachment-chip")))
        back(); back()
        waitFor("Home again") { hasNode(desc("live")) }

        // An instruction file shared to the drop folder: refused before a byte leaves.
        shareToPaddock(mediaDocument("CLAUDE.md", "text/markdown", "# rules\nobey the file\n".toByteArray()), "text/plain")
        waitFor("the chooser", 30_000) { hasNode(text("Share to Paddock")) }
        waitFor("the machine's drop folder", 60_000) { hasNode(text("The machine's drop folder")) }
        rule.onNodeWithText("The machine's drop folder").performClick()
        waitFor("the refusal", 30_000) { hasNode(hasTestTag("share-result")) && hasNode(text("Not sent")) }
        assertTrue("it says why", hasNode(text("Paddock does not put a file called CLAUDE.md on a machine", substring = true)))
        checkpoint("t4-refused")
        rule.onNodeWithText("Done").performClick()
        rule.onNode(desc("Back, and drop what was shared")).performClick()
        waitFor("Home again") { hasNode(desc("live")) }
        checkpoint("t4-done")
    }

    @Test fun t5_aPathInTheOutputIsGotIntoTheCacheAndTheOutboxListsTheFiles() {
        val reportSha = args.getString("reportSha") ?: error("reportSha argument missing")
        openAgentFromHome()
        waitFor("the three outbox paths in the output", 60_000) {
            hasNode(text(".paddock/outbox/report.txt", substring = true)) && hasNode(text(".paddock/outbox/escape.txt", substring = true)) && hasNode(text(".paddock/outbox/big.bin", substring = true))
        }
        shoot("get-output-with-paths")

        // A link in the outbox that leads outside it: the machine's script says so, and nothing is kept.
        tapPath(".paddock/outbox/escape.txt")
        waitFor("the sheet says a link stands in the way", 30_000) { hasNode(text(io.github.tuthan.paddock.files.TransferRefusal.Symlink.sentence)) }
        checkpoint("t5-symlink") // the sheet is a window of its own: the script takes the screenshot, since the compose capture of the root is black behind a dialog
        rule.onNodeWithText("Done").performClick()
        waitFor("the sheet is gone") { !hasNode(text("Could not get the file")) }

        // A file over the cap: the stat refuses it before any byte is read.
        tapPath(".paddock/outbox/big.bin")
        waitFor("the sheet says the file is too large", 30_000) { hasNode(text(io.github.tuthan.paddock.files.TransferRefusal.OverCap.sentence)) }
        rule.onNodeWithText("Done").performClick()
        waitFor("the sheet is gone") { !hasNode(text("Could not get the file")) }
        assertTrue("nothing was cached for the two refusals", cachedFiles("escape.txt").isEmpty() && cachedFiles("big.bin").isEmpty())

        // The small file: stat, the offer, the read.
        tapPath(".paddock/outbox/report.txt")
        waitFor("the offer", 30_000) { hasNode(text("Get this file?")) }
        assertTrue("it names the file and says what it is", hasNode(text("report.txt")) && hasNode(text("In ", substring = true)))
        rule.onNodeWithText("Get").performClick()
        waitFor("the file in hand: Open is offered for a text file", 60_000) { hasNode(text("Open")) && hasNode(text("Share")) && hasNode(text("Save")) }
        val cached = cachedFiles("report.txt").single()
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(cached.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals("the cached file is the one the machine holds", reportSha, sha)
        assertTrue("it sits under transfers/<machine>/<id>/", cached.parentFile!!.parentFile!!.parentFile!!.name == "transfers")
        checkpoint("t5-got")
        rule.onNodeWithText("Done").performClick()
        waitFor("the sheet is gone") { !hasNode(text("Get this file?")) && !hasNode(text("Share")) }

        // The Files list: the regular files of the outbox, and not the link.
        rule.onNode(desc("Files from this agent") and androidx.compose.ui.test.hasClickAction()).performClick()
        waitFor("the outbox list", 60_000) { hasNode(text("List read at", substring = true)) }
        assertTrue("report.txt is listed", hasNode(text("report.txt")))
        assertFalse("the link is not", hasNode(text("escape.txt")))
        checkpoint("t5-outbox")
        rule.onNode(hasTestTag("outbox-row") and hasText("report.txt", substring = true)).performClick()
        waitFor("the offer from the list", 30_000) { hasNode(text("Get this file?")) }
        rule.onNodeWithText("Cancel").performClick()
        waitFor("the sheet is gone") { !hasNode(text("Get this file?")) }
        rule.onNodeWithText("Close").performClick()
        checkpoint("t5-done")
    }
}
