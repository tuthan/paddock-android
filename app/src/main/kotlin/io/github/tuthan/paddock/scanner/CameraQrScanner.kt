package io.github.tuthan.paddock.scanner

// Ported from steamos-companion-android (app/src/main/kotlin/io/github/tuthan/steamoscompanion/android/scanner/CameraQrScanner.kt), the same author's project,
// which has no LICENSE file; the port is for Paddock (Phase 14, decision D2). Changes: the package and the worker thread's name; a frame that cannot be
// handled is skipped instead of crashing the worker thread; a code is reported once while it stays in view and the camera keeps running (the page
// decides when the scan is over); autofocus is asked for only where the camera has it.

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** What the scanner screen needs from a camera adapter, so a test can stand in for the hardware. */
interface QrScanner : Closeable {
    /**
     * Start once after CAMERA is granted; the screen's `SurfaceView` owns [preview]. Failures arrive through `onFailure`. A scanner that was
     * closed (its surface went away) is not started again: the screen makes a new one.
     */
    fun start(preview: Surface)
}

/** Camera2 capture adapter; the visible scanner screen owns its permission request and preview. */
class CameraQrScanner(
    context: Context,
    private val onPayload: (String) -> Unit,
    private val onFailure: (String) -> Unit,
) : QrScanner {
    private val appContext = context.applicationContext
    private val cameras = appContext.getSystemService(CameraManager::class.java)
        ?: throw IllegalStateException("Camera service is unavailable")
    private val main = Handler(Looper.getMainLooper())
    private val decoder = QrFrameDecoder()
    private val failed = AtomicBoolean()
    private val repeats = RepeatedPayload(REPEAT_WINDOW_NANOS)
    @Volatile private var closed = false
    private var started = false
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var lastDecodeNanos = 0L

    /** Start once after CAMERA is granted. The SurfaceView/TextureView preview owns [preview]. */
    @Synchronized override fun start(preview: Surface) {
        check(!closed && !started) { "Scanner has already started or closed" }
        check(appContext.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            "Camera permission is required"
        }
        require(preview.isValid) { "Preview surface is unavailable" }
        started = true
        try {
            val id = cameras.cameraIdList.firstOrNull { candidate ->
                cameras.getCameraCharacteristics(candidate).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            } ?: throw IllegalStateException("No back camera is available")
            val sizes = cameras.getCameraCharacteristics(id)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.YUV_420_888).orEmpty()
            val autofocus = cameras.getCameraCharacteristics(id).get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                ?.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) == true
            val size = sizes.filter { it.width <= MAX_WIDTH && it.height <= MAX_HEIGHT }
                .minByOrNull { kotlin.math.abs(it.width * it.height - PREFERRED_PIXELS) }
                ?: throw IllegalStateException("No supported QR camera frame size")
            val worker = HandlerThread("paddock-qr-camera").also { it.start() }
            thread = worker
            val handler = Handler(worker.looper)
            val images = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
            reader = images
            images.setOnImageAvailableListener({ source -> handleFrame(source) }, handler)
            @Suppress("MissingPermission") // The permission is checked immediately above and rechecked by the platform.
            cameras.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    val accepted = synchronized(this@CameraQrScanner) {
                        if (closed) false else { camera = device; true }
                    }
                    if (!accepted) { device.close(); return }
                    try {
                        device.createCaptureSession(listOf(preview, images.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(configured: CameraCaptureSession) {
                                val accepted = synchronized(this@CameraQrScanner) {
                                    if (closed) false else { session = configured; true }
                                }
                                if (!accepted) { configured.close(); return }
                                try {
                                    val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                        addTarget(preview)
                                        addTarget(images.surface)
                                        // Continuous autofocus only where the camera has it (a fixed-focus one does not, and the store listing does not require it).
                                        if (autofocus) set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                    }.build()
                                    configured.setRepeatingRequest(request, null, handler)
                                } catch (_: Exception) { fail("Camera preview could not start") }
                            }

                            override fun onConfigureFailed(configured: CameraCaptureSession) {
                                configured.close()
                                fail("Camera preview is unavailable")
                            }
                        }, handler)
                    } catch (_: Exception) { fail("Camera preview is unavailable") }
                }

                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    fail("Camera disconnected")
                }

                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    fail("Camera is unavailable")
                }
            }, handler)
        } catch (error: Exception) {
            fail(error.message ?: "Camera is unavailable")
        }
    }

    private fun handleFrame(source: ImageReader) {
        // This runs on the camera's own thread, where an exception nobody catches ends the app. A frame that cannot be read (the decoder
        // failing on it, or the reader being closed under it as the scanner closes) is skipped and the next one is tried.
        try { readFrame(source) } catch (_: Exception) { }
    }

    private fun readFrame(source: ImageReader) {
        if (closed) return
        val image = try { source.acquireLatestImage() } catch (_: Exception) { null } ?: return
        image.use {
            val now = SystemClock.elapsedRealtimeNanos()
            if (now - lastDecodeNanos < MIN_DECODE_INTERVAL_NANOS) return
            lastDecodeNanos = now
            if (it.format != ImageFormat.YUV_420_888) return
            val y = it.planes[0]
            val buffer = y.buffer.duplicate()
            val bytes = ByteArray(buffer.remaining())
            try {
                buffer.get(bytes)
                val payload = decoder.decode(bytes, it.width, it.height, y.rowStride, y.pixelStride)
                // The camera goes on running: the page says what the code was and decides whether the scan is over. The same code still in
                // view is not reported again, so one that is not a pairing link neither repeats its notice nor starts the camera over.
                if (payload != null && repeats.isNews(payload, now)) main.post { if (!closed) onPayload(payload) }
            } finally {
                bytes.fill(0)
            }
        }
    }

    private fun fail(message: String) {
        if (closed || failed.getAndSet(true)) return
        close()
        main.post { onFailure(message) }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        runCatching { session?.close() }
        runCatching { camera?.close() }
        runCatching { reader?.close() }
        session = null
        camera = null
        reader = null
        thread?.quitSafely()
        thread = null
    }

    companion object {
        private const val MAX_WIDTH = 1_280
        private const val MAX_HEIGHT = 720
        private const val PREFERRED_PIXELS = 640 * 480
        private const val MIN_DECODE_INTERVAL_NANOS = 150_000_000L
        private const val REPEAT_WINDOW_NANOS = 3_000_000_000L
    }
}
