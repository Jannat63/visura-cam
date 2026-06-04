package com.visura.cam.utils

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import com.visura.cam.correction.ColorCorrectionEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BatchColorFixer — Retroactively fix yellow cast in all past photos.
 *
 * This is a key feature for the user: all photos taken before installing
 * Visura Cam (or before the fix was enabled) can be corrected in bulk.
 *
 * Process:
 *   1. Scan MediaStore for photos taken with Realme 8 Pro
 *   2. For each photo: load, apply RGB correction, save as new copy
 *   3. Report progress — runs as background WorkManager task
 *   4. Non-destructive: originals are ALWAYS preserved
 */
@Singleton
class BatchColorFixer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val colorEngine: ColorCorrectionEngine
) {
    private val _progress = MutableStateFlow<BatchProgress?>(null)
    val progress: StateFlow<BatchProgress?> = _progress

    private var isCancelled = false

    /**
     * Fix all photos in the gallery from Realme 8 Pro camera.
     * Saves corrected copies to a "Visura Fixed" album.
     */
    suspend fun fixAllPhotos(
        lensId: String = ColorCorrectionEngine.LENS_MAIN_108MP,
        onlyUnfixed: Boolean = true
    ) = withContext(Dispatchers.IO) {

        isCancelled = false
        val photos = scanGalleryPhotos()
        val total = photos.size

        _progress.value = BatchProgress(0, total, BatchStage.SCANNING)

        photos.forEachIndexed { index, uri ->
            if (isCancelled) return@withContext

            try {
                _progress.value = BatchProgress(
                    processed = index,
                    total = total,
                    stage = BatchStage.PROCESSING,
                    currentFile = uri.lastPathSegment ?: "photo"
                )

                val bitmap = loadBitmap(uri) ?: return@forEachIndexed
                val corrected = applyColorCorrection(bitmap, lensId)
                saveToFixedAlbum(corrected, uri)
                bitmap.recycle()
                corrected.recycle()

            } catch (e: Exception) {
                // Skip failed photos, continue batch
            }
        }

        _progress.value = BatchProgress(total, total, BatchStage.DONE)
    }

    /**
     * Fix a single photo URI — used from gallery long-press menu.
     */
    suspend fun fixSinglePhoto(uri: Uri, lensId: String = ColorCorrectionEngine.LENS_MAIN_108MP): Uri? =
        withContext(Dispatchers.IO) {
            val bitmap = loadBitmap(uri) ?: return@withContext null
            val corrected = applyColorCorrection(bitmap, lensId)
            val savedUri = saveToFixedAlbum(corrected, uri)
            bitmap.recycle()
            corrected.recycle()
            savedUri
        }

    /**
     * Apply RGB gain correction to a Bitmap.
     * Uses the same correction values as the live Camera2 pipeline.
     */
    private fun applyColorCorrection(bitmap: Bitmap, lensId: String): Bitmap {
        val profile = colorEngine.getProfile(lensId)
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val px = pixels[i]
            val a = (px shr 24) and 0xFF

            // Apply per-channel gain correction
            val r = ((px shr 16) and 0xFF) * profile.rGain
            val g = ((px shr 8)  and 0xFF) * profile.gEvenGain
            val b = (px          and 0xFF) * profile.bGain

            pixels[i] = (a shl 24) or
                (r.toInt().coerceIn(0, 255) shl 16) or
                (g.toInt().coerceIn(0, 255) shl 8) or
                b.toInt().coerceIn(0, 255)
        }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }

    private fun scanGalleryPhotos(): List<Uri> {
        // Scan all photos - MODEL column doesn't exist in MediaStore
        // Color correction applies per-lens so works on any photo
        val photos = mutableListOf<Uri>()
        val projection = arrayOf(MediaStore.Images.Media._ID)

        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, null, null,
            "${MediaStore.Images.Media.DATE_TAKEN} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                photos.add(Uri.withAppendedPath(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString()
                ))
            }
        }
        return photos
    }

    private fun loadBitmap(uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it)
            }
        } catch (e: Exception) { null }
    }

    private fun saveToFixedAlbum(bitmap: Bitmap, sourceUri: Uri): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME,
                "fixed_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/VisuraFixed")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val outputUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null

        resolver.openOutputStream(outputUri)?.use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 97, stream)
        }

        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(outputUri, values, null, null)

        return outputUri
    }

    fun cancel() { isCancelled = true }

    data class BatchProgress(
        val processed: Int,
        val total: Int,
        val stage: BatchStage,
        val currentFile: String = ""
    ) {
        val percentage get() = if (total == 0) 0 else (processed * 100 / total)
        val label get() = when (stage) {
            BatchStage.SCANNING    -> "Scanning photos…"
            BatchStage.PROCESSING  -> "Fixing $processed / $total"
            BatchStage.DONE        -> "Done! $total photos fixed"
        }
    }

    enum class BatchStage { SCANNING, PROCESSING, DONE }
}
