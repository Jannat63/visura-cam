package com.visura.cam.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.visura.cam.correction.ColorCorrectionEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImageSaver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val colorEngine: ColorCorrectionEngine
) {
    suspend fun processExistingPhoto(
        context: Context,
        uri: Uri,
        scene: String,
        lensId: String
    ) = withContext(Dispatchers.IO) {
        try {
            val rotation = context.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).rotationDegrees
            } ?: 0

            val raw = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it)
            } ?: return@withContext

            val oriented = if (rotation != 0) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
                raw.recycle()
                rotated
            } else raw

            val rendered = AIRenderEngine.render(oriented, scene, lensId)
            oriented.recycle()

            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                rendered.compress(Bitmap.CompressFormat.JPEG, 97, out)
            }
            rendered.recycle()

            writeExif(context, uri, scene, lensId)

            Log.d("AJCam", "Photo processed: scene=$scene lens=$lensId rotation=${rotation}°")

        } catch (e: Exception) {
            Log.e("AJCam", "processExistingPhoto failed", e)
        }
    }

    private fun writeExif(context: Context, uri: Uri, scene: String, lensId: String) {
        try {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                exif.setAttribute(ExifInterface.TAG_ARTIST,    "Ahsan Jannat")
                exif.setAttribute(ExifInterface.TAG_COPYRIGHT, "© Ahsan Jannat — AJ Cam")
                exif.setAttribute(ExifInterface.TAG_SOFTWARE,  "AJ Cam v1.0 by Ahsan Jannat")
                exif.setAttribute(ExifInterface.TAG_MAKE,      "Realme 8 Pro")
                exif.setAttribute(ExifInterface.TAG_MODEL,     "Samsung ISOCELL HM2 108MP")
                exif.setAttribute(ExifInterface.TAG_LENS_MODEL, lensName(lensId))
                exif.setAttribute(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL.toString())
                val now = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date())
                exif.setAttribute(ExifInterface.TAG_DATETIME, now)
                exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, now)
                exif.saveAttributes()
            }
        } catch (e: Exception) {
            Log.e("AJCam", "EXIF write failed", e)
        }
    }

    private fun lensName(lensId: String) = when (lensId) {
        "0"  -> "Main f/1.88 26mm PDAF"
        "1"  -> "Ultrawide f/2.25 119°"
        "2"  -> "Macro f/2.4 4cm fixed"
        else -> "Selfie f/2.45"
    }
}
