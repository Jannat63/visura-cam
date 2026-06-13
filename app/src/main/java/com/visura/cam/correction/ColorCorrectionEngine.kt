package com.visura.cam.correction

import javax.inject.Inject
import javax.inject.Singleton

/**
 * ColorCorrectionEngine — Yellow cast correction profiles for AJ Cam.
 * Water damage fix: reduction applied in AIRenderEngine post-processing.
 * Owner: Ahsan Jannat — AJ Cam
 */
@Singleton
class ColorCorrectionEngine @Inject constructor(
    private val calibrationStore: CalibrationDataStore
) {
    companion object {
        // Gentle correction — applied in post-processing, NOT on live preview
        // This avoids the green cast from disabling sensor AWB
        const val DEFAULT_R_GAIN = 0.88f   // Reduce red (water damage yellow fix)
        const val DEFAULT_G_GAIN = 0.95f   // Slightly reduce green
        const val DEFAULT_B_GAIN = 1.10f   // Boost blue

        // Lens IDs
        const val LENS_MAIN_108MP = "0"
        const val LENS_ULTRAWIDE  = "1"
        const val LENS_MACRO      = "2"
        const val LENS_DEPTH      = "3"
        const val LENS_SELFIE     = "FRONT"
    }

    private val lensProfiles = mutableMapOf(
        LENS_MAIN_108MP to CorrectionProfile(DEFAULT_R_GAIN, DEFAULT_G_GAIN, DEFAULT_B_GAIN),
        LENS_ULTRAWIDE  to CorrectionProfile(0.90f, 0.96f, 1.08f),
        LENS_MACRO      to CorrectionProfile(0.89f, 0.95f, 1.09f),
        LENS_DEPTH      to CorrectionProfile(1.0f,  1.0f,  1.0f),
        LENS_SELFIE     to CorrectionProfile(1.0f,  1.0f,  1.0f)
    )

    fun getProfile(lensId: String): CorrectionProfile =
        lensProfiles[lensId] ?: lensProfiles[LENS_MAIN_108MP]!!

    fun toggleCorrection(lensId: String, enabled: Boolean) {
        lensProfiles[lensId]?.enabled = enabled
    }

    fun updateProfile(lensId: String, rGain: Float, gGain: Float, bGain: Float) {
        lensProfiles[lensId] = CorrectionProfile(rGain, gGain, bGain)
        calibrationStore.save(lensId, rGain, gGain, bGain)
    }

    fun calibrateFromWhiteReference(pixels: IntArray, lensId: String): CorrectionProfile {
        var rSum = 0L; var gSum = 0L; var bSum = 0L
        pixels.forEach { px ->
            rSum += (px shr 16) and 0xFF
            gSum += (px shr 8)  and 0xFF
            bSum +=  px         and 0xFF
        }
        val count = pixels.size.toFloat()
        val rAvg = rSum / count; val gAvg = gSum / count; val bAvg = bSum / count
        val maxAvg = maxOf(rAvg, gAvg, bAvg)
        val profile = CorrectionProfile(
            (maxAvg / rAvg).toFloat().coerceIn(0.5f, 2.0f),
            (maxAvg / gAvg).toFloat().coerceIn(0.5f, 2.0f),
            (maxAvg / bAvg).toFloat().coerceIn(0.5f, 2.0f)
        )
        lensProfiles[lensId] = profile
        calibrationStore.save(lensId, profile.rGain, profile.gEvenGain, profile.bGain)
        return profile
    }
}

data class CorrectionProfile(
    val rGain: Float,
    val gEvenGain: Float,
    val bGain: Float,
    var enabled: Boolean = true
)
