package io.github.tuthan.paddock.scanner

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import kotlin.math.abs
import kotlin.math.hypot

/** A frame size in pixels. */
data class FrameSize(val width: Int, val height: Int) {
    val pixels: Long get() = width.toLong() * height
}

/** One back-facing camera as the choice sees it (the camera classes are not available to a JVM test). [focalLength35mm] is null when the camera does not say. */
internal data class CameraCandidate(val id: String, val autofocus: Boolean, val focalLength35mm: Float?, val sizes: List<FrameSize>)

/** The camera and the frame size the scanner asks it for. The preview surface is given the same size, so the picture is not stretched. */
internal data class CameraPlan(val cameraId: String, val frame: FrameSize, val autofocus: Boolean)

/**
 * Which camera and which frame the scanner uses. A phone lists several back cameras (main, ultra-wide, telephoto, macro) in an order the maker chose, so the
 * first one is not always the main camera: the ultra-wide bends straight lines at the edges and shows a code far smaller, and a macro camera has no focus.
 * The choice is a cameras-with-autofocus-first, closest-to-a-standard-lens one, and the frame is the largest 4:3 one a decoder can read in time.
 */
internal object CameraPlans {
    /** The 35 mm equivalent of the main camera of nearly every phone sits between 23 and 28; the ultra-wide is about 13 to 16 and a telephoto 50 and more. */
    private const val MAIN_LENS_35MM = 26f

    /**
     * A QR code of a pairing link is 57 modules wide and is read from a screen, where it fills a part of the frame: 640x480 gives it about 2 to 3
     * pixels a module, which no decoder reads, and 1280x960 about 5. More than this costs decoding time on every frame for no gain.
     */
    private const val MAX_PIXELS = 1_300_000L

    fun choose(candidates: List<CameraCandidate>): CameraPlan? =
        candidates.withIndex()
            .mapNotNull { (index, c) -> frameSize(c.sizes)?.let { Triple(index, c, it) } }
            .sortedWith(
                compareBy<Triple<Int, CameraCandidate, FrameSize>> { if (it.second.autofocus) 0 else 1 }
                    .thenBy { it.second.focalLength35mm?.let { f -> abs(f - MAIN_LENS_35MM) } ?: Float.MAX_VALUE }
                    .thenBy { it.first },
            )
            .firstOrNull()
            ?.let { (_, c, size) -> CameraPlan(c.id, size, c.autofocus) }

    /** The largest 4:3 size within [MAX_PIXELS]; with no 4:3 size, the largest one that is. */
    fun frameSize(sizes: List<FrameSize>): FrameSize? {
        val usable = sizes.filter { it.width > 0 && it.height > 0 && it.pixels <= MAX_PIXELS }
        return usable.filter { it.width * 3 == it.height * 4 }.ifEmpty { usable }.maxByOrNull { it.pixels }
    }

    /** The plan for this phone, or null when it has no back camera that gives a frame of a usable size. Reads characteristics only; no camera is opened. */
    fun query(manager: CameraManager): CameraPlan? = choose(
        runCatching { manager.cameraIdList.toList() }.getOrDefault(emptyList()).mapNotNull { id ->
            runCatching {
                val c = manager.getCameraCharacteristics(id)
                if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) return@runCatching null
                // A depth, infrared or monochrome camera lists YUV sizes too but is not for a picture.
                val capabilities = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                if (capabilities != null && !capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)) return@runCatching null
                val sizes = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(ImageFormat.YUV_420_888).orEmpty().map { FrameSize(it.width, it.height) }
                val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
                val sensor = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                val focal35 = if (focal != null && sensor != null && sensor.width > 0f && sensor.height > 0f) {
                    focal * FULL_FRAME_DIAGONAL_MM / hypot(sensor.width, sensor.height)
                } else null
                val autofocus = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) == true
                CameraCandidate(id, autofocus, focal35, sizes)
            }.getOrNull()
        },
    )

    private const val FULL_FRAME_DIAGONAL_MM = 43.27f
}
