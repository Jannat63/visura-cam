package com.visura.cam.utils

import android.content.ContentValues
import android.content.Context
import android.media.Image
import android.os.Environment
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ImageSaver — Saves captured images to the device gallery.
 *
 * Saves to DCIM/VisuraCam folder.
 * JPEG → standard gallery photo
 * RAW DNG → saved alongside JPEG for post-processing
 */
@Singleton
class ImageSaver @Inject constructor(
    @ApplicationContext private val context: Context
) {
    suspend fun saveJpeg(image: Image, lensId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val buffer: ByteBuffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            val filename = "VISURA_${System.currentTimeMillis()}_L${lensId}.jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DCIM}/VisuraCam")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }

            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            ) ?: return@withContext false

            context.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(bytes)
            }

            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)

            image.close()
            true
        } catch (e: Exception) {
            image.close()
            false
        }
    }

    suspend fun saveRawDng(image: Image, lensId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val filename = "VISURA_${System.currentTimeMillis()}_L${lensId}_RAW.dng"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
                put(MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DCIM}/VisuraCam/RAW")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }

            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            ) ?: return@withContext false

            // RAW planes: single plane for RAW_SENSOR
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }

            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)

            image.close()
            true
        } catch (e: Exception) {
            image.close()
            false
        }
    }
}
