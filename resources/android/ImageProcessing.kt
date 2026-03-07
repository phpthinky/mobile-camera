package com.nativephp.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class ImageProcessingOptions(
    val maxWidth: Int?,
    val maxHeight: Int?,
    val format: String,
    val quality: Int,
    val normalizeOrientation: Boolean
) {
    companion object {
        fun fromBridgeInput(input: Any?): ImageProcessingOptions {
            val map = input as? Map<*, *>

            val maxWidth = (map?.get("maxWidth") as? Number)?.toInt()?.takeIf { it > 0 }
            val maxHeight = (map?.get("maxHeight") as? Number)?.toInt()?.takeIf { it > 0 }
            val requestedFormat = (map?.get("format") as? String)?.lowercase()
            val format = when (requestedFormat) {
                "png" -> "png"
                "webp" -> "webp"
                else -> "jpeg"
            }
            val quality = ((map?.get("quality") as? Number)?.toInt() ?: 85).coerceIn(1, 100)
            val normalizeOrientation = map?.get("normalizeOrientation") as? Boolean ?: true

            return ImageProcessingOptions(
                maxWidth = maxWidth,
                maxHeight = maxHeight,
                format = format,
                quality = quality,
                normalizeOrientation = normalizeOrientation
            )
        }
    }
}

data class ProcessedImageResult(
    val path: String,
    val mimeType: String,
    val extension: String,
    val width: Int,
    val height: Int,
    val bytes: Long,
    val sourcePath: String,
    val processed: Boolean
)

object ImageProcessor {
    private const val TAG = "ImageProcessor"

    fun processImageFile(
        context: Context,
        sourceFile: File,
        options: ImageProcessingOptions,
        outputPrefix: String
    ): ProcessedImageResult {
        val sourcePath = sourceFile.absolutePath

        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(sourcePath, boundsOptions)

        val sourceWidth = boundsOptions.outWidth
        val sourceHeight = boundsOptions.outHeight

        if (sourceWidth <= 0 || sourceHeight <= 0) {
            throw IOException("Could not decode source image bounds")
        }

        val rotationDegrees = if (options.normalizeOrientation) readRotationDegrees(sourcePath) else 0
        val rotatedSourceWidth = if (rotationDegrees == 90 || rotationDegrees == 270) sourceHeight else sourceWidth
        val rotatedSourceHeight = if (rotationDegrees == 90 || rotationDegrees == 270) sourceWidth else sourceHeight

        val targetWidth = options.maxWidth ?: rotatedSourceWidth
        val targetHeight = options.maxHeight ?: rotatedSourceHeight

        // Never upscale. We only preserve or downscale.
        val scale = min(
            1.0,
            min(
                targetWidth.toDouble() / rotatedSourceWidth.toDouble(),
                targetHeight.toDouble() / rotatedSourceHeight.toDouble()
            )
        )

        val finalWidth = max(1, (rotatedSourceWidth * scale).roundToInt())
        val finalHeight = max(1, (rotatedSourceHeight * scale).roundToInt())

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = computeInSampleSize(rotatedSourceWidth, rotatedSourceHeight, finalWidth, finalHeight)
        }

        val decoded = BitmapFactory.decodeFile(sourcePath, decodeOptions)
            ?: throw IllegalStateException("Could not decode source image")

        val rotated = if (rotationDegrees != 0) {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                .also { if (it !== decoded) decoded.recycle() }
        } else {
            decoded
        }

        val resized = if (rotated.width != finalWidth || rotated.height != finalHeight) {
            Bitmap.createScaledBitmap(rotated, finalWidth, finalHeight, true)
                .also { if (it !== rotated) rotated.recycle() }
        } else {
            rotated
        }

        val outputDir = File(context.cacheDir, "Processed")
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw IOException("Failed to create output directory: ${outputDir.absolutePath}")
        }

        val timestamp = System.currentTimeMillis()
        val extension = when (options.format) {
            "png" -> "png"
            "webp" -> "webp"
            else -> "jpg"
        }

        val outputFile = File(outputDir, "${outputPrefix}_${timestamp}.${extension}")
        if (outputFile.exists()) {
            outputFile.delete()
        }

        FileOutputStream(outputFile).use { out ->
            val compressFormat = when (options.format) {
                "png" -> Bitmap.CompressFormat.PNG
                "webp" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    Bitmap.CompressFormat.WEBP_LOSSY
                else
                    @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
                else -> Bitmap.CompressFormat.JPEG
            }

            val ok = resized.compress(compressFormat, options.quality, out)
            if (!ok) {
                throw IllegalStateException("Failed to compress processed image")
            }
        }

        // Recycle all intermediate bitmaps. A Set ensures each distinct
        // bitmap instance is recycled exactly once, regardless of which
        // transformation steps produced new objects vs. returned references.
        setOf(decoded, rotated, resized).forEach { it.recycle() }

        val mimeType = when (extension) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> "image/jpeg"
        }

        val processed = rotationDegrees != 0 || finalWidth != rotatedSourceWidth || finalHeight != rotatedSourceHeight ||
            outputFile.absolutePath != sourcePath || extension != sourceFile.extension.lowercase()

        Log.d(TAG, "🖼️ Processed image ${sourceFile.name} -> ${outputFile.name} (${finalWidth}x${finalHeight})")

        return ProcessedImageResult(
            path = outputFile.absolutePath,
            mimeType = mimeType,
            extension = extension,
            width = finalWidth,
            height = finalHeight,
            bytes = outputFile.length(),
            sourcePath = sourcePath,
            processed = processed
        )
    }

    fun toJson(result: ProcessedImageResult): JSONObject {
        return JSONObject().apply {
            put("path", result.path)
            put("sourcePath", result.sourcePath)
            put("mimeType", result.mimeType)
            put("extension", result.extension)
            put("type", "image")
            put("width", result.width)
            put("height", result.height)
            put("bytes", result.bytes)
            put("processed", result.processed)
        }
    }

    private fun readRotationDegrees(path: String): Int {
        return try {
            when (ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (e: Exception) {
            0
        }
    }

    private fun computeInSampleSize(sourceWidth: Int, sourceHeight: Int, reqWidth: Int, reqHeight: Int): Int {
        var inSampleSize = 1
        if (sourceHeight > reqHeight || sourceWidth > reqWidth) {
            val halfHeight = sourceHeight / 2
            val halfWidth = sourceWidth / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return max(1, inSampleSize)
    }
}
