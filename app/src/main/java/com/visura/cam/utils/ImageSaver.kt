package com.visura.cam.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.visura.cam.correction.ColorCorrectionEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
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
        shotInfo: ProRenderEngine.ShotInfo
    ) = withContext(Dispatchers.IO) {
        try {
            val raw = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it)
            } ?: return@withContext

            val rendered = ProRenderEngine.render(raw, shotInfo)
            raw.recycle()

            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                rendered.compress(Bitmap.CompressFormat.JPEG, 97, out)
            }
            rendered.recycle()

            writeExif(context, uri, shotInfo)
        } catch (e: Exception) {
            Log.e("ImageSaver", "processExistingPhoto failed", e)
        }
    }

    private fun writeExif(context: Context, uri: Uri, info: ProRenderEngine.ShotInfo) {
        try {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                exif.setAttribute(ExifInterface.TAG_ARTIST,    ProRenderEngine.OWNER_NAME)
                exif.setAttribute(ExifInterface.TAG_COPYRIGHT, "© ${ProRenderEngine.OWNER_NAME} — ${ProRenderEngine.APP_NAME}")
                exif.setAttribute(ExifInterface.TAG_SOFTWARE,  "${ProRenderEngine.APP_NAME} v${ProRenderEngine.APP_VERSION}")
                exif.setAttribute(ExifInterface.TAG_MAKE,      ProRenderEngine.DEVICE_MODEL)
                exif.setAttribute(ExifInterface.TAG_MODEL,     info.sensorName)
                exif.setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, info.iso.toString())
                exif.setAttribute(ExifInterface.TAG_F_NUMBER,  info.aperture.toString())
                exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "${(info.focalLengthMm * 100).toInt()}/100")
                exif.setAttribute(ExifInterface.TAG_LENS_MODEL, info.lensName)
                val now = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date())
                exif.setAttribute(ExifInterface.TAG_DATETIME, now)
                exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, now)
                exif.saveAttributes()
            }
        } catch (e: Exception) {
            Log.e("ImageSaver", "EXIF failed", e)
        }
    }
}
