package com.visura.cam.correction

import javax.inject.Inject
import javax.inject.Singleton

/**
 * ColorCorrectionEngine — Yellow cast correction profiles for AJ Cam.
 * Water damage fix: applied in AIRenderEngine post-processing.
 * Owner: Ahsan Jannat — AJ Cam
 */
@Singleton
class ColorCorrectionEngine @Inject constructor(
    private val calibrationStore: CalibrationDataStore
) {
    companion object {
        const val DEFAULT_R_GAIN = 0.88f
        const val DEFAULT_G_GAIN = 0.95f
        const val DEFAULT_B_GAIN = 1.10f

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
}

data class CorrectionProfile(
    val rGain: Float,
    val gEvenGain: Float,
    val bGain: Float,
    var enabled: Boolean = true
)
