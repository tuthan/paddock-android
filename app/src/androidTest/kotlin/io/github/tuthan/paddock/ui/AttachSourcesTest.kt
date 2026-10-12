package io.github.tuthan.paddock.ui

import android.content.Context
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tuthan.paddock.files.TransferCache
import io.github.tuthan.paddock.files.TransferFileProvider
import io.github.tuthan.paddock.files.rememberAttachSources
import io.github.tuthan.paddock.lifecycle.TransferHold
import io.github.tuthan.paddock.ui.screens.AttachSource
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The three sources behind Attach (Phase 15, slice 10), with the system's activities replaced by a test [ActivityResultRegistry]: each contract is answered
 * by the test, so no picker or camera is needed, and what the test checks is Paddock's side of each one: the hold around a trip, the capture file and
 * its URI, the camera grant asked for first, and a refusal said.
 */
class AttachSourcesTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val hold = TransferHold(scope)
    private val cache = TransferCache(ctx.cacheDir)

    @After fun stop() { scope.cancel(); File(ctx.cacheDir, "camera").deleteRecursively() }

    /** Answers each launch by [answer]; records the hold's state at the moment of each launch, and every contract it was asked to run. */
    private class Registry(private val hold: TransferHold, private val answer: (ActivityResultContract<*, *>, Any?) -> Any?) : ActivityResultRegistry() {
        val launched = mutableListOf<String>()
        val heldAtLaunch = mutableListOf<Boolean>()
        override fun <I : Any?, O : Any?> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            launched += contract.javaClass.simpleName
            heldAtLaunch += hold.active.value
            @Suppress("UNCHECKED_CAST") dispatchResult(requestCode, answer(contract, input) as O)
        }
    }

    private class Seen { val picked = mutableListOf<Uri>(); val captured = mutableListOf<String>(); var denied = 0; var nothing = 0 }

    private fun show(registry: Registry, seen: Seen, cameraGranted: Boolean = true, source: AttachSource) {
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry: ActivityResultRegistry = registry }
        rule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                val sources = rememberAttachSources(
                    hold, onPicked = { uri, _ -> seen.picked += uri }, onCaptured = { seen.captured += it }, onCameraDenied = { seen.denied++ }, onNothing = { seen.nothing++ },
                    cameraGranted = { cameraGranted },
                )
                Column { Text("go", Modifier.clickable { sources.launch(source) }) }
            }
        }
        rule.onNodeWithText("go").performClick()
        rule.waitForIdle()
    }

    private fun providerUri(): Uri {
        val f = cache.cameraFile(UUID.randomUUID().toString()).apply { writeText("x") }
        return FileProvider.getUriForFile(ctx, TransferFileProvider.authority(ctx.packageName), f)
    }

    @Test fun thePhotoPickerHoldsTheWatchWhileItIsOpenAndHandsOverTheUri() {
        val uri = providerUri()
        val registry = Registry(hold) { c, _ -> assertTrue(c is ActivityResultContracts.PickVisualMedia); uri }
        val seen = Seen()
        show(registry, seen, source = AttachSource.Photo)
        assertEquals(listOf(true), registry.heldAtLaunch)
        assertEquals(listOf(uri), seen.picked)
        assertFalse("the result releases the hold", hold.active.value)
    }

    @Test fun theDocumentPickerAsksForImagesPdfsAndText() {
        var types: List<String> = emptyList()
        val registry = Registry(hold) { c, input -> assertTrue(c is ActivityResultContracts.OpenDocument); types = (input as Array<*>).map { it as String }; providerUri() }
        val seen = Seen()
        show(registry, seen, source = AttachSource.Document)
        assertEquals(listOf("image/*", "application/pdf", "text/*"), types)
        assertEquals(1, seen.picked.size)
        assertFalse(hold.active.value)
    }

    @Test fun backingOutOfAPickerReleasesTheHoldAndSaysNothingWasPicked() {
        val registry = Registry(hold) { _, _ -> null }
        val seen = Seen()
        show(registry, seen, source = AttachSource.Photo)
        assertEquals(0, seen.picked.size)
        assertEquals(1, seen.nothing)
        assertFalse(hold.active.value)
    }

    @Test fun theCameraWritesIntoTheCacheThroughTheProviderAndItsIdComesBack() {
        var target: Uri? = null
        val registry = Registry(hold) { c, input ->
            when (c) {
                is ActivityResultContracts.TakePicture -> { target = input as Uri; ctx.contentResolver.openOutputStream(target!!)!!.use { it.write(byteArrayOf(1, 2, 3)) }; true }
                else -> error("unexpected contract")
            }
        }
        val seen = Seen()
        show(registry, seen, cameraGranted = true, source = AttachSource.Camera)
        assertEquals(listOf("TakePicture"), registry.launched)
        assertEquals(listOf(true), registry.heldAtLaunch)
        assertEquals(TransferFileProvider.authority(ctx.packageName), target!!.authority)
        assertEquals(1, seen.captured.size)
        val capture = cache.cameraFile(seen.captured[0])
        assertEquals("the capture is under cache/camera/", File(ctx.cacheDir, "camera").canonicalPath, capture.parentFile!!.canonicalPath)
        assertEquals(3L, capture.length())
        assertFalse(hold.active.value)
    }

    @Test fun aCameraThatReturnsNothingLeavesNoFileAndNoHold() {
        val registry = Registry(hold) { _, _ -> false }
        val seen = Seen()
        show(registry, seen, cameraGranted = true, source = AttachSource.Camera)
        assertEquals(0, seen.captured.size)
        assertEquals(1, seen.nothing)
        assertTrue("an unused capture file is deleted", File(ctx.cacheDir, "camera").list().isNullOrEmpty())
        assertFalse(hold.active.value)
    }

    @Test fun theCameraGrantIsAskedForFirstAndGrantedStartsTheCamera() {
        val registry = Registry(hold) { c, _ -> if (c is ActivityResultContracts.RequestPermission) true else if (c is ActivityResultContracts.TakePicture) false else error("?") }
        val seen = Seen()
        show(registry, seen, cameraGranted = false, source = AttachSource.Camera)
        assertEquals(listOf("RequestPermission", "TakePicture"), registry.launched)
        assertEquals(0, seen.denied)
        assertFalse(hold.active.value)
    }

    @Test fun aRefusedCameraGrantIsSaidAndStartsNoCamera() {
        val registry = Registry(hold) { c, _ -> if (c is ActivityResultContracts.RequestPermission) false else error("the camera must not start without its grant") }
        val seen = Seen()
        show(registry, seen, cameraGranted = false, source = AttachSource.Camera)
        assertEquals(listOf("RequestPermission"), registry.launched)
        assertEquals(1, seen.denied)
        assertFalse(hold.active.value)
        assertNotNull(seen)
    }
}
