package io.github.tuthan.paddock.files

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import io.github.tuthan.paddock.lifecycle.TransferHold
import io.github.tuthan.paddock.ui.screens.AttachSource
import java.util.UUID

/** What the composer can start; each one holds the watch first (X11) and the result releases it. */
class AttachLaunchers(private val start: (AttachSource) -> Unit) {
    fun launch(source: AttachSource) = start(source)
}

/**
 * The three ways to get a file into the composer (Phase 15, slice 10): the system photo picker, the document picker, and the camera app writing one
 * capture into Paddock's own cache through its provider. None of them reads the file: a result is a URI (or a capture's id) handed to [onPicked] or
 * [onCaptured], and [IncomingFiles] makes the one snapshot (SEC-1, SEC-2).
 *
 * Each launch first calls [TransferHold.hold], so the connection stays while Paddock is behind the picker; the result, or a back press out of it,
 * releases it. A capture's id is kept in saved state, because the camera app can take the process's place in memory and the result then arrives at a
 * recreated activity. The capture URI's grant is revoked as soon as the camera returns.
 *
 * The camera needs the `CAMERA` grant that Phase 14 already declares for the scanner: with it declared and not granted, starting the camera app is
 * a `SecurityException`. So the grant is asked for first, in context, and a refusal says so ([onCameraDenied]) rather than doing nothing.
 */
@Composable
fun rememberAttachSources(
    hold: TransferHold,
    onPicked: (uri: Uri, displayName: String?) -> Unit,
    onCaptured: (captureId: String) -> Unit,
    onCameraDenied: () -> Unit,
    onNothing: () -> Unit = {},
    /** Whether the camera grant is held; a seam so a test does not depend on what the device was left with. */
    cameraGranted: (Context) -> Boolean = { it.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED },
): AttachLaunchers {
    val context = LocalContext.current
    val picked by rememberUpdatedState(onPicked)
    val captured by rememberUpdatedState(onCaptured)
    val denied by rememberUpdatedState(onCameraDenied)
    val nothing by rememberUpdatedState(onNothing)
    var captureId by rememberSaveable { mutableStateOf<String?>(null) }

    fun finished(uri: Uri?) {
        hold.release()
        if (uri == null) nothing() else picked(uri, displayNameOf(context, uri))
    }

    val photo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { finished(it) }
    val document = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { finished(it) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        hold.release()
        val id = captureId
        captureId = null
        if (id != null) revokeCapture(context, id)
        if (ok && id != null) captured(id) else if (id != null) { TransferCacheHandle.of(context).deleteCamera(id); nothing() } else nothing()
    }
    fun startCamera() {
        val id = UUID.randomUUID().toString()
        val file = TransferCacheHandle.of(context).cameraFile(id)
        val uri = FileProvider.getUriForFile(context, TransferFileProvider.authority(context.packageName), file)
        captureId = id
        hold.hold()
        try {
            camera.launch(uri)
        } catch (_: RuntimeException) {
            // No camera app, or one that refuses: nothing was started, so nothing is held.
            hold.release(); captureId = null; TransferCacheHandle.of(context).deleteCamera(id); nothing()
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hold.release()
        if (granted) startCamera() else denied()
    }
    return remember {
        AttachLaunchers { source ->
            when (source) {
                AttachSource.Photo -> { hold.hold(); photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                AttachSource.Document -> { hold.hold(); document.launch(arrayOf("image/*", "application/pdf", "text/*")) }
                AttachSource.Camera ->
                    if (cameraGranted(context)) startCamera()
                    else { hold.hold(); permission.launch(Manifest.permission.CAMERA) }
            }
        }
    }
}

private fun revokeCapture(context: Context, id: String) {
    val file = TransferCacheHandle.of(context).cameraFile(id)
    val uri = FileProvider.getUriForFile(context, TransferFileProvider.authority(context.packageName), file)
    context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
}

/** The sender's name for a picked document, a hint for the person only: it is reduced to the allowlist before anything uses it. Null when the provider has none. */
internal fun displayNameOf(context: Context, uri: Uri): String? = try {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
} catch (_: RuntimeException) { null }

/** The one cache object of the process, found from a context, so a composable need not be handed it (the graph builds the same one over the same directory). */
internal object TransferCacheHandle {
    fun of(context: Context): TransferCache = TransferCache(context.applicationContext.cacheDir)
}
