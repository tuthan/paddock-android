package io.github.tuthan.paddock.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileInputStream

/**
 * The chip's thumbnail: a decode at a bounded size of a snapshot that was already cleaned (X4). The header's own pixel count is checked first
 * ([ImageMetadata.requireWithinPixelBound]), because a sample size still inflates a whole PNG; a file over the bound, or one the platform cannot decode,
 * simply has no thumbnail and the chip shows the file glyph. Nothing here throws.
 */
object Thumbnails {
    const val MAX_SIDE = 192
    private const val HEAD = 256 * 1024

    /** [maxSide] is the longest edge to aim for; the result may be up to twice that, since a sample size is a power of two. Null when there is nothing to show. */
    fun load(file: File, maxSide: Int = MAX_SIDE): Bitmap? = try {
        val head = ByteArray(HEAD).let { buf -> FileInputStream(file).use { s -> var n = 0; while (n < buf.size) { val r = s.read(buf, n, buf.size - n); if (r < 0) break; n += r }; buf.copyOf(n) } }
        when (ImageMetadata.sniff(head)) {
            ImageKind.Jpeg, ImageKind.Png -> {
                ImageMetadata.requireWithinPixelBound(head)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null
                else BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample(bounds.outWidth, bounds.outHeight, maxSide) })
            }
            else -> null // a PDF or a text file has no picture; HEIC was written again as a JPEG at intake
        }
    } catch (_: ImageRefused) { null } catch (_: RuntimeException) { null } catch (_: java.io.IOException) { null } catch (_: OutOfMemoryError) { null }

    /** The largest power of two that keeps the longest edge at or above [maxSide]. */
    fun sample(width: Int, height: Int, maxSide: Int): Int {
        var s = 1
        val longest = maxOf(width, height)
        while (longest / (s * 2) >= maxSide) s *= 2
        return s
    }
}
