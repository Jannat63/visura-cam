package com.visura.cam.utils

import androidx.exifinterface.media.ExifInterface
import com.visura.cam.utils.ProRenderEngine.ShotInfo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ExifWriter — Embeds professional metadata in every Visura Cam photo.
 *
 * Every photo carries:
 *   • Owner: Ahsan Jannat
 *   • App:   Visura Cam
 *   • Full technical data: ISO, shutter, aperture, lens, sensor
 *   • GPS (if available)
 *   • Camera fingerprint for batch correction recognition
 *
 * Opens the saved JPEG file and writes EXIF tags using ExifInterface.
 */
object ExifWriter {

    private val dateFormatter = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)

    /**
     * Write all EXIF metadata to a saved JPEG file.
     * Call this after ImageSaver saves the photo to disk.
     */
    fun write(file: File, shotInfo: ShotInfo, latitude: Double? = null, longitude: Double? = null) {
        if (!file.exists()) return
        try {
            val exif = ExifInterface(file.absolutePath)
            writeOwnership(exif)
            writeCameraInfo(exif, shotInfo)
            writeExposureInfo(exif, shotInfo)
            writeLensInfo(exif, shotInfo)
            writeDateTime(exif)
            latitude?.let { lat -> longitude?.let { lon -> writeGPS(exif, lat, lon) } }
            exif.saveAttributes()
        } catch (_: Exception) { }
    }

    // ── Ownership & Branding ──────────────────────────────────────

    private fun writeOwnership(exif: ExifInterface) {
        exif.setAttribute(ExifInterface.TAG_ARTIST, ProRenderEngine.OWNER_NAME)
        exif.setAttribute(ExifInterface.TAG_COPYRIGHT,
            "© ${ProRenderEngine.OWNER_NAME} — ${ProRenderEngine.APP_NAME}")
        exif.setAttribute(ExifInterface.TAG_SOFTWARE,
            "${ProRenderEngine.APP_NAME} v${ProRenderEngine.APP_VERSION} by ${ProRenderEngine.OWNER_NAME}")
        exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION,
            "Shot with ${ProRenderEngine.APP_NAME} by ${ProRenderEngine.OWNER_NAME}")
    }

    // ── Camera & Sensor Info ──────────────────────────────────────

    private fun writeCameraInfo(exif: ExifInterface, shotInfo: ShotInfo) {
        exif.setAttribute(ExifInterface.TAG_MAKE,
            "${ProRenderEngine.DEVICE_MODEL} — ${ProRenderEngine.APP_NAME}")
        exif.setAttribute(ExifInterface.TAG_MODEL, shotInfo.sensorName)
        exif.setAttribute(ExifInterface.TAG_CAMERA_OWNER_NAME, ProRenderEngine.OWNER_NAME)
    }

    // ── Exposure Data (what photographers care about) ─────────────

    private fun writeExposureInfo(exif: ExifInterface, shotInfo: ShotInfo) {
        // ISO
        exif.setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, shotInfo.iso.toString())
        exif.setAttribute(ExifInterface.TAG_SENSITIVITY_TYPE, "1") // Standard Output Sensitivity

        // Shutter speed as rational (numerator/denominator)
        val shutterSec = shotInfo.shutterSpeedNs / 1_000_000_000.0
        if (shutterSec < 1.0) {
            val denom = (1.0 / shutterSec).roundToInt()
            exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, "1/$denom")
        } else {
            exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, "$shutterSec/1")
        }

        // Aperture as APEX value
        exif.setAttribute(ExifInterface.TAG_F_NUMBER, shotInfo.aperture.toString())
        val apertureApex = (2.0 * log2(shotInfo.aperture.toDouble()))
        exif.setAttribute(ExifInterface.TAG_APERTURE_VALUE,
            "${(apertureApex * 100).toInt()}/100")

        // Exposure mode
        exif.setAttribute(ExifInterface.TAG_EXPOSURE_MODE, "0")  // Auto
        exif.setAttribute(ExifInterface.TAG_WHITE_BALANCE, "0")  // Auto (we override it)
        exif.setAttribute(ExifInterface.TAG_METERING_MODE, "5")  // Pattern (multi-zone)
    }

    // ── Lens Info ─────────────────────────────────────────────────

    private fun writeLensInfo(exif: ExifInterface, shotInfo: ShotInfo) {
        exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH,
            "${(shotInfo.focalLengthMm * 100).toInt()}/100")

        // 35mm equivalent focal length
        val focalLength35mm = (shotInfo.focalLengthMm * 6.3f).toInt()  // HM2 crop factor ~6.3
        exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, focalLength35mm.toString())

        exif.setAttribute(ExifInterface.TAG_LENS_MODEL, shotInfo.lensName)
        exif.setAttribute(ExifInterface.TAG_LENS_MAKE, ProRenderEngine.DEVICE_MODEL)

        // Scene type
        exif.setAttribute(ExifInterface.TAG_SCENE_TYPE, "1")  // Directly photographed
        exif.setAttribute(ExifInterface.TAG_CUSTOM_RENDERED,
            if (shotInfo.scene == "macro") "1" else "0")  // Custom rendered for macro
    }

    // ── Date/Time ─────────────────────────────────────────────────

    private fun writeDateTime(exif: ExifInterface) {
        val now = dateFormatter.format(Date())
        exif.setAttribute(ExifInterface.TAG_DATETIME, now)
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, now)
        exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, now)
    }

    // ── GPS ───────────────────────────────────────────────────────

    private fun writeGPS(exif: ExifInterface, lat: Double, lon: Double) {
        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, if (lat >= 0) "N" else "S")
        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, decimalToDMS(Math.abs(lat)))
        exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, if (lon >= 0) "E" else "W")
        exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, decimalToDMS(Math.abs(lon)))
    }

    private fun decimalToDMS(decimal: Double): String {
        val degrees = decimal.toInt()
        val minutesF = (decimal - degrees) * 60
        val minutes = minutesF.toInt()
        val seconds = (minutesF - minutes) * 60
        return "$degrees/1,$minutes/1,${(seconds * 1000).toInt()}/1000"
    }

    private fun Double.roundToInt() = kotlin.math.round(this).toInt()
    private fun log2(x: Double) = kotlin.math.ln(x) / kotlin.math.ln(2.0)
}
