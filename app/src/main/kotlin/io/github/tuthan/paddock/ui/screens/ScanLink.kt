package io.github.tuthan.paddock.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import io.github.tuthan.paddock.scanner.CameraQrScanner
import io.github.tuthan.paddock.scanner.QrScanner
import io.github.tuthan.paddock.scanner.ScannerSession
import io.github.tuthan.paddock.ui.components.Banner
import io.github.tuthan.paddock.ui.components.ButtonKind
import io.github.tuthan.paddock.ui.components.PaddockButton
import io.github.tuthan.paddock.ui.components.ScreenHeader
import io.github.tuthan.paddock.ui.theme.PaddockIcons
import io.github.tuthan.paddock.ui.theme.PaddockTokens

const val SCAN_INTRO =
    "On the machine, run Paddock: pair a phone in herdr. It shows a code. Hold this phone so the whole code is in view. The code is read on this phone and goes nowhere else."

const val SCAN_CAMERA_OFF =
    "Paddock needs the camera only to read that code. Nothing is recorded, saved or sent. Without it, paste the pairing link instead."

/**
 * Scan the code on the desktop. The camera is asked for when this screen opens (the caller's [onRequestCamera]), the preview shows what
 * the camera sees, and each code that is read goes to [onPayload] as text (the same code still in view is reported once). The camera
 * keeps running until the page is left, hidden or stopped, so [onPayload] decides when the scan is over by leaving. A camera that cannot
 * start says so and offers Try again; every state offers Paste a pairing link, which does the same without the camera.
 */
@Composable
fun ScanLink(
    cameraGranted: Boolean,
    onRequestCamera: () -> Unit,
    onOpenSettings: () -> Unit,
    onPayload: (String) -> Unit,
    onPaste: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Why the last code could not be used (`PairingCopy.invalid`, which never repeats the code); the scan starts again under it. */
    notice: String? = null,
    scannerFactory: (Context, (String) -> Unit, (String) -> Unit) -> QrScanner = { context, payload, failure -> CameraQrScanner(context, payload, failure) },
) {
    val context = LocalContext.current
    var failure by rememberSaveable { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    // The scanner is made once per surface, long after this call: it reports to the page as it is now, not as it was.
    val latestPayload by rememberUpdatedState(onPayload)
    Column(modifier.fillMaxSize()) {
        ScreenHeader("Scan the code", onBack = onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = PaddockTokens.spacing.gutter, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(SCAN_INTRO, style = PaddockTokens.type.body, color = PaddockTokens.colors.dim)
            Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (notice != null) Banner(notice)
                failure?.let { Banner("The camera could not start: $it") }
            }
            if (cameraGranted && failure == null) {
                // A scanner starts once and is closed for good, so the camera is tied to the surface and to the page being visible: a scanner is made
                // when the preview surface appears and closed when it goes (Home, the lock screen) or the activity stops, and the way back makes a new one.
                val session = remember(context, attempt) {
                    ScannerSession({ scannerFactory(context, { text -> latestPayload(text) }) { message -> failure = message } }) { message -> failure = message }
                }
                val owner = context as? LifecycleOwner
                DisposableEffect(session, owner) {
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_START -> session.start()
                            Lifecycle.Event.ON_STOP -> session.stop()
                            else -> Unit
                        }
                    }
                    owner?.lifecycle?.addObserver(observer)
                    onDispose { owner?.lifecycle?.removeObserver(observer); session.dispose() }
                }
                // The frames are 4:3 sensor frames; the preview keeps that shape (turned on a portrait screen) instead of stretching them.
                val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
                AndroidView(
                    factory = { viewContext ->
                        SurfaceView(viewContext).also { view ->
                            view.holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) session.surfaceCreated(holder.surface)
                                }
                                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                                override fun surfaceDestroyed(holder: SurfaceHolder) = session.surfaceDestroyed()
                            })
                        }
                    },
                    modifier = Modifier.fillMaxWidth().aspectRatio(if (landscape) 4f / 3f else 3f / 4f).semantics { contentDescription = "Camera preview" },
                )
            } else if (!cameraGranted) {
                Banner(SCAN_CAMERA_OFF, icon = PaddockIcons.Qr)
            }
        }
        Column(Modifier.padding(horizontal = PaddockTokens.spacing.gutter, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!cameraGranted) {
                PaddockButton("Allow the camera", onRequestCamera, icon = PaddockIcons.Qr)
                PaddockButton("Open settings", onOpenSettings, kind = ButtonKind.Secondary)
            } else if (failure != null) PaddockButton("Try again", { failure = null; attempt++ }, kind = ButtonKind.Ghost, icon = PaddockIcons.Qr)
            PaddockButton("Paste a pairing link", onPaste, kind = ButtonKind.Ghost, icon = PaddockIcons.Copy)
        }
    }
}
