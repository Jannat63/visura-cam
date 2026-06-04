package com.visura.cam.utils

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.Image
import android.os.Environment
import android.provider.MediaStore
import com.visura.cam.correction.ColorCorrectionEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ImageSaver — Saves photos with full professional pipeline.
 *
 * Every photo from Visura Cam goes through:
 *   1. ProRenderEngine — Hasselblad/Leica-level image rendering
 *   2. ExifWriter — embeds full metadata + Ahsan Jannat ownership
 *   3. MediaStore — saves to gallery at DCIM/VisuraCam
 *
 * Owner: Ahsan Jannat
 */
@Singleton
class ImageSaver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val colorEngine: ColorCorrectionEngine
) {

    suspend fun saveJpeg(
        image: Image,
        lensId: String,
        shotInfo: ProRenderEngine.ShotInfo
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val buffer: ByteBuffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            image.close()

            // Decode JPEG to Bitmap
            val rawBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: return@withContext false

            // *** FULL PROFESSIONAL RENDER PIPELINE ***
            val rendered = ProRenderEngine.render(
                bitmap  = rawBitmap,
                shotInfo = shotInfo
            )
            rawBitmap.recycle()

            // Re-encode at 97% quality
            val outputStream = ByteArrayOutputStream()
            rendered.compress(Bitmap.CompressFormat.JPEG, 97, outputStream)
            val finalBytes = outputStream.toByteArray()
            rendered.recycle()

            // Save to gallery and get file path for EXIF writing
            val filename = "VISURA_${System.currentTimeMillis()}_L${lensId}.jpg"
            val file = saveToGalleryAndGetFile(finalBytes, filename, "image/jpeg", "VisuraCam")
                ?: return@withContext false

            // *** WRITE EXIF METADATA (ownership + shot data) ***
            ExifWriter.write(file, shotInfo)

            true
        } catch (e: Exception) {
            try { image.close() } catch (_: Exception) {}
            false
        }
    }

    suspend fun saveRawDng(image: Image, lensId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            image.close()
            // RAW files bypass all processing — pure sensor data for Lightroom etc.
            saveToGalleryAndGetFile(bytes,
                "VISURA_${System.currentTimeMillis()}_L${lensId}_RAW.dng",
                "image/x-adobe-dng", "VisuraCam/RAW") != null
        } catch (e: Exception) {
            try { image.close() } catch (_: Exception) {}
            false
        }
    }

    private fun saveToGalleryAndGetFile(
        bytes: ByteArray,
        filename: String,
        mimeType: String,
        folder: String
    ): File? {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                put(MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DCIM}/$folder")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null

            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }

            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)

            // Get actual file path for ExifWriter
            val dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
            File(dcimDir, "$folder/$filename")
        } catch (_: Exception) { null }
    }
}
