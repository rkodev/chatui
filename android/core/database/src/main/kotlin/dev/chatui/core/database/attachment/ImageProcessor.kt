package dev.chatui.core.database.attachment

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Bytes produced by image processing. */
data class ProcessedImage(
    val bytes: ByteArray,
    val mimeType: String,
    val width: Int,
    val height: Int,
)

/** Decode, downscale, and re-encode an image before it is stored. */
interface ImageProcessor {
    suspend fun process(bytes: ByteArray, mimeType: String): ProcessedImage
}

/**
 * Android image processor.
 *
 * The image is decoded with [BitmapFactory], rotated/flipped according to its
 * EXIF orientation, scaled so the longest edge is at most [MAX_IMAGE_EDGE], and
 * re-encoded. Re-encoding drops every EXIF tag, including GPS and timestamps;
 * orientation is preserved visually by applying it to the pixels first.
 *
 * When the image cannot be decoded the original bytes are returned unchanged
 * rather than losing the attachment.
 */
class AndroidImageProcessor : ImageProcessor {
    override suspend fun process(bytes: ByteArray, mimeType: String): ProcessedImage =
        withContext(Dispatchers.IO) {
            try {
                processBlocking(bytes, mimeType)
            } catch (_: Exception) {
                ProcessedImage(bytes, mimeType, 0, 0)
            }
        }

    private fun processBlocking(bytes: ByteArray, mimeType: String): ProcessedImage {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return ProcessedImage(bytes, mimeType, 0, 0)
        }

        val orientation = readOrientation(bytes)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: return ProcessedImage(bytes, mimeType, 0, 0)

        val oriented = applyOrientation(decoded, orientation)
        if (oriented !== decoded) decoded.recycle()

        val (targetWidth, targetHeight) = scaledDimensions(oriented.width, oriented.height)
        val scaled = if (oriented.width != targetWidth || oriented.height != targetHeight) {
            Bitmap.createScaledBitmap(oriented, targetWidth, targetHeight, true)
        } else {
            oriented
        }
        if (scaled !== oriented) oriented.recycle()

        val output = ByteArrayOutputStream()
        val format = if (mimeType.equals("image/jpeg", ignoreCase = true)) {
            Bitmap.CompressFormat.JPEG
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        val compressed = scaled.compress(format, OUTPUT_QUALITY, output)
        val width = scaled.width
        val height = scaled.height
        scaled.recycle()

        if (!compressed) {
            return ProcessedImage(bytes, mimeType, 0, 0)
        }

        return ProcessedImage(
            bytes = output.toByteArray(),
            mimeType = outputMimeTypeFor(mimeType),
            width = width,
            height = height,
        )
    }

    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= MAX_IMAGE_EDGE &&
            height / (sample * 2) >= MAX_IMAGE_EDGE
        ) {
            sample *= 2
        }
        return sample
    }

    private fun readOrientation(bytes: ByteArray): Int = try {
        ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    } catch (_: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }

        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private companion object {
        const val OUTPUT_QUALITY = 90
    }
}
