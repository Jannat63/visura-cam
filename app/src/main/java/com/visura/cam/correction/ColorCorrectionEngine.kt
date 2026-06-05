package com.visura.cam.correction

import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.RggbChannelVector
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ColorCorrectionEngine — Core fix for Realme 8 Pro water damage yellow cast.
 *
 * The Samsung HM2 sensor's ISP was damaged by water exposure, causing:
 *   - Red channel gain:   too HIGH  (~+20%)
 *   - Green channel gain: slightly HIGH (~+9%)
 *   - Blue channel gain:  too LOW   (~-15%)
 * Result: warm yellow-orange tint across all photos.
 *
 * Fix strategy:
 *   1. Override Camera2 AWB with manual RggbChannelVector gains
 *   2. Apply correction matrix to RAW capture pipeline
 *   3. Run OpenGL LUT shader on live preview
 *   4. Optional: TFLite model for per-frame adaptive correction
 */
@Singleton
class ColorCorrectionEngine @Inject constructor(
    private val calibrationStore: CalibrationDataStore
) {

    companion object {
        // Default correction for Realme 8 Pro water damage
        // These values cancel the yellow cast: reduce R/G, boost B
        // Gentle correction - applied in post-processing (ImageSaver), NOT on live preview
        // This avoids the green cast that happened when AWB was disabled on the sensor
        const val DEFAULT_R_GAIN = 0.88f   // Reduce red (water damage yellow fix)
        const val DEFAULT_G_GAIN = 0.95f   // Slightly reduce green
        const val DEFAULT_B_GAIN = 1.10f   // Boost blue gently

        // Lens IDs on Realme 8 Pro
        // Back cameras: 0=main, 1=ultrawide, 2=macro, 3=depth
        // Front camera: uses CameraCharacteristics.LENS_FACING_FRONT — queried at runtime
        const val LENS_MAIN_108MP  = "0"   // Samsung HM2
        const val LENS_ULTRAWIDE   = "1"   // 8MP ultrawide
        const val LENS_MACRO       = "2"   // 2MP macro
        const val LENS_DEPTH       = "3"   // 2MP B&W depth
        const val LENS_SELFIE      = "FRONT"  // resolved at runtime via facing
    }

    // Per-lens correction profiles — each lens may have different damage
    private val lensProfiles = mutableMapOf(
        LENS_MAIN_108MP to CorrectionProfile(DEFAULT_R_GAIN, DEFAULT_G_GAIN, DEFAULT_B_GAIN),
        LENS_ULTRAWIDE  to CorrectionProfile(0.88f, 0.94f, 1.10f),
        LENS_MACRO      to CorrectionProfile(0.85f, 0.92f, 1.15f),
        LENS_DEPTH      to CorrectionProfile(1.0f,  1.0f,  1.0f),   // B&W — no color fix needed
        LENS_SELFIE     to CorrectionProfile(1.0f,  1.0f,  1.0f),   // Sony IMX471 unaffected
    )

    // applyToCapture: CameraX handles AWB automatically
    // Yellow cast correction is applied in ImageSaver post-processing pipeline
    // This avoids the green cast from disabling sensor AWB
    fun applyToCapture(builder: android.hardware.camera2.CaptureRequest.Builder, lensId: String = LENS_MAIN_108MP) {
        // Intentionally empty - CameraX AWB handles live preview
        // Post-processing in ImageSaver applies the color correction after capture
    }

    /**
     * Generate OpenGL LUT texture data for real-time preview shader.
     * Returns a 64x64x64 RGB LUT as ByteArray for use in GLSurfaceView.
     */
    fun generatePreviewLUT(lensId: String = LENS_MAIN_108MP): ByteArray {
        val profile = lensProfiles[lensId] ?: lensProfiles[LENS_MAIN_108MP]!!
        val lutSize = 64
        val lut = ByteArray(lutSize * lutSize * lutSize * 3)
        var idx = 0
        for (b in 0 until lutSize) {
            for (g in 0 until lutSize) {
                for (r in 0 until lutSize) {
                    val rNorm = r / (lutSize - 1f)
                    val gNorm = g / (lutSize - 1f)
                    val bNorm = b / (lutSize - 1f)

                    // Apply gain correction in LUT
                    val rOut = (rNorm * profile.rGain).coerceIn(0f, 1f)
                    val gOut = (gNorm * profile.gEvenGain).coerceIn(0f, 1f)
                    val bOut = (bNorm * profile.bGain).coerceIn(0f, 1f)

                    lut[idx++] = (rOut * 255).toInt().toByte()
                    lut[idx++] = (gOut * 255).toInt().toByte()
                    lut[idx++] = (bOut * 255).toInt().toByte()
                }
            }
        }
        return lut
    }

    /**
     * Update correction profile for a specific lens.
     * Called from settings UI or auto-calibration.
     */
    fun updateProfile(lensId: String, rGain: Float, gGain: Float, bGain: Float) {
        lensProfiles[lensId] = CorrectionProfile(rGain, gGain, bGain)
        calibrationStore.save(lensId, rGain, gGain, bGain)
    }

    fun getProfile(lensId: String): CorrectionProfile =
        lensProfiles[lensId] ?: lensProfiles[LENS_MAIN_108MP]!!

    fun toggleCorrection(lensId: String, enabled: Boolean) {
        lensProfiles[lensId]?.enabled = enabled
    }

    /**
     * Auto-calibrate using a white/gray reference image.
     * User points at white paper → app calculates exact correction needed.
     */
    fun calibrateFromWhiteReference(pixels: IntArray, lensId: String): CorrectionProfile {
        var rSum = 0L; var gSum = 0L; var bSum = 0L
        pixels.forEach { px ->
            rSum += (px shr 16) and 0xFF
            gSum += (px shr 8)  and 0xFF
            bSum += px          and 0xFF
        }
        val count = pixels.size.toFloat()
        val rAvg = rSum / count
        val gAvg = gSum / count
        val bAvg = bSum / count

        // True gray = equal R, G, B. Calculate gains to achieve balance.
        val maxAvg = maxOf(rAvg, gAvg, bAvg)
        val correctedR = (maxAvg / rAvg).toFloat().coerceIn(0.5f, 2.0f)
        val correctedG = (maxAvg / gAvg).toFloat().coerceIn(0.5f, 2.0f)
        val correctedB = (maxAvg / bAvg).toFloat().coerceIn(0.5f, 2.0f)

        val profile = CorrectionProfile(correctedR, correctedG, correctedB)
        lensProfiles[lensId] = profile
        calibrationStore.save(lensId, correctedR, correctedG, correctedB)
        return profile
    }
}

data class CorrectionProfile(
    val rGain: Float,
    val gEvenGain: Float,
    val bGain: Float,
    val gOddGain: Float = gEvenGain,   // Samsung HM2: both G channels usually same
    var enabled: Boolean = true
) {
    // 3x3 color correction matrix — identity with gain applied on diagonal
    val colorTransformMatrix: android.hardware.camera2.params.ColorSpaceTransform
        get() {
            val elements = intArrayOf(
                (rGain * 128).toInt(), 128,  0, 128,  0, 128,
                0, 128, (gEvenGain * 128).toInt(), 128, 0, 128,
                0, 128,  0, 128, (bGain * 128).toInt(), 128
            )
            return android.hardware.camera2.params.ColorSpaceTransform(elements)
        }
}
