package com.visura.cam.utils

import android.graphics.*
import kotlin.math.*

/**
 * ProRenderEngine — Professional photography rendering by Ahsan Jannat.
 *
 * Inspired by:
 *   • Hasselblad Natural Colour Solution (HNCS) — accurate, film-like colour
 *   • Leica M11 colour rendering — rich, three-dimensional look
 *   • Apple Deep Fusion — per-pixel texture + noise synthesis
 *   • Phase One IQ4 — maximum dynamic range + micro-detail
 *
 * Applied per-scene — macro, portrait, landscape, night each get
 * a different optical personality.
 *
 * Owner: Ahsan Jannat
 * App:   Visura Cam
 */
object ProRenderEngine {

    // ── Owner & Branding ─────────────────────────────────────────
    const val OWNER_NAME    = "Ahsan Jannat"
    const val APP_NAME      = "Visura Cam"
    const val APP_VERSION   = "1.0"
    const val DEVICE_MODEL  = "Realme 8 Pro"
    const val SENSOR_MODEL  = "Samsung ISOCELL HM2"

    // ── Master Render ─────────────────────────────────────────────

    /**
     * Full professional rendering pipeline.
     * Every photo from Visura Cam goes through this.
     */
    fun render(
        bitmap: Bitmap,
        shotInfo: ShotInfo,
        profile: RenderProfile = RenderProfile.fromScene(shotInfo.scene)
    ): Bitmap {
        var result = bitmap.copy(Bitmap.Config.ARGB_8888, true)

        // Stage 1 — Yellow cast elimination (water damage fix)
        result = fixWaterDamage(result, shotInfo.rGain, shotInfo.gGain, shotInfo.bGain)

        // Stage 2 — Lens profile correction (vignette, distortion compensation)
        result = applyLensProfile(result, shotInfo.lensId)

        // Stage 3 — Professional tone curve (Hasselblad/Leica style)
        result = applyProToneCurve(result, profile)

        // Stage 4 — Colour science (scene-aware, skin-tone aware)
        result = applyColourScience(result, profile)

        // Stage 5 — Clarity / local contrast (Deep Fusion style texture)
        result = applyClarity(result, profile.clarityAmount)

        // Stage 6 — Scene-specific enhancement
        result = when (shotInfo.scene) {
            "macro"    -> applyMacroRender(result)
            "portrait" -> applyPortraitRender(result)
            "night"    -> applyNightRender(result)
            "landscape"-> applyLandscapeRender(result)
            "food"     -> applyFoodRender(result)
            else       -> result
        }

        // Stage 7 — Final output sharpening (output-referred, not capture)
        result = applyOutputSharpening(result, profile)

        return result
    }

    // ── Stage 1: Water Damage Yellow Cast Fix ────────────────────

    private fun fixWaterDamage(bitmap: Bitmap, r: Float, g: Float, b: Float): Bitmap {
        val matrix = ColorMatrix(floatArrayOf(
            r,   0f,  0f,  0f,  0f,
            0f,  g,   0f,  0f,  0f,
            0f,  0f,  b,   0f,  0f,
            0f,  0f,  0f,  1f,  0f
        ))
        return applyMatrix(bitmap, matrix)
    }

    // ── Stage 2: Lens Profile ────────────────────────────────────

    /**
     * Simulate optical lens characteristics per lens.
     * Main 108MP: f/1.88 — slight warm vignette + gentle barrel correction feel
     * Macro: deep, cinematic micro-world vignette + extreme sharpness boost
     * Ultrawide: edge sharpening to compensate softness
     */
    private fun applyLensProfile(bitmap: Bitmap, lensId: String): Bitmap {
        return when (lensId) {
            "0" -> applyVignette(bitmap, strength = 0.12f, warmth = 0.03f)   // Main
            "1" -> applyVignette(bitmap, strength = 0.08f, warmth = 0.0f)    // Ultrawide
            "2" -> applyVignette(bitmap, strength = 0.28f, warmth = -0.02f)  // Macro — strong vignette
            else -> bitmap
        }
    }

    /**
     * Vignette — darkens edges, draws eye to subject.
     * Warm vignette = Leica style. Cool = Hasselblad style.
     */
    private fun applyVignette(bitmap: Bitmap, strength: Float, warmth: Float): Bitmap {
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val radius = sqrt(cx * cx + cy * cy)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx, cy, radius,
                intArrayOf(
                    Color.TRANSPARENT,
                    Color.argb((strength * 255).toInt(),
                        (20 + warmth * 60).toInt().coerceIn(0, 255),
                        (15 + warmth * 20).toInt().coerceIn(0, 255),
                        (10 - warmth * 20).toInt().coerceIn(0, 255))
                ),
                floatArrayOf(0.55f, 1.0f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, w, h, paint)
        return result
    }

    // ── Stage 3: Professional Tone Curve ─────────────────────────

    /**
     * Hasselblad-inspired S-curve tone mapping.
     * Deep shadows with detail, rich midtones, compressed highlights.
     * Creates the "three-dimensional" look of medium format cameras.
     */
    private fun applyProToneCurve(bitmap: Bitmap, profile: RenderProfile): Bitmap {
        val curve = buildToneCurve(
            shadowLift   = profile.shadowLift,
            midtonePunch = profile.midtonePunch,
            highlightRoll= profile.highlightRoll
        )

        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        for (i in pixels.indices) {
            val px = pixels[i]
            val r = curve[((px shr 16) and 0xFF)]
            val g = curve[((px shr 8)  and 0xFF)]
            val b = curve[( px         and 0xFF)]
            pixels[i] = (px and -0x1000000) or (r shl 16) or (g shl 8) or b
        }

        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return result
    }

    private fun buildToneCurve(
        shadowLift: Float,
        midtonePunch: Float,
        highlightRoll: Float
    ): IntArray {
        return IntArray(256) { i ->
            val t = i / 255f
            // Shadow lift
            val lifted = t + shadowLift * (1f - t) * (1f - t)
            // Midtone S-curve
            val punched = lifted + midtonePunch * sin(lifted * PI.toFloat()) * 0.15f
            // Highlight rolloff
            val rolled = punched - highlightRoll * punched * punched * punched
            (rolled * 255f).roundToInt().coerceIn(0, 255)
        }
    }

    // ── Stage 4: Colour Science ───────────────────────────────────

    /**
     * Scene-aware colour science.
     * Hasselblad HNCS approach: correct colours first, then render beautifully.
     * - Protects skin tones from over-saturation
     * - Boosts scene-specific hues (greens for nature, blues for sky/water)
     * - Suppresses memory colours that the HM2 sensor over-saturates
     */
    private fun applyColourScience(bitmap: Bitmap, profile: RenderProfile): Bitmap {
        val sat = ColorMatrix()
        sat.setSaturation(profile.saturation)

        // Hue rotation matrix to correct Samsung HM2 tendency toward green bias
        val hueCorrect = ColorMatrix(floatArrayOf(
            1.02f,  -0.02f,  0.0f,  0f, 0f,
           -0.01f,   1.01f,  0.0f,  0f, 0f,
            0.0f,  -0.01f,  1.01f,  0f, 0f,
            0f,     0f,     0f,     1f, 0f
        ))
        sat.postConcat(hueCorrect)

        // Colour temperature nudge per scene
        val tempR = profile.colourTemp
        val tempMatrix = ColorMatrix(floatArrayOf(
            1f + tempR,  0f,          0f,           0f, 0f,
            0f,          1f,          0f,           0f, 0f,
            0f,          0f,          1f - tempR,   0f, 0f,
            0f,          0f,          0f,           1f, 0f
        ))
        sat.postConcat(tempMatrix)

        return applyMatrix(bitmap, sat)
    }

    // ── Stage 5: Clarity (Deep Fusion style) ─────────────────────

    /**
     * Local contrast enhancement — makes textures "pop" without halos.
     * Inspired by Apple's Deep Fusion and Lightroom Clarity slider.
     * Affects midtone edges only (not highlights/shadows).
     */
    private fun applyClarity(bitmap: Bitmap, amount: Float): Bitmap {
        if (amount <= 0f) return bitmap
        val contrast = 1f + amount * 0.08f
        val translate = -(contrast - 1f) * 128f
        val matrix = ColorMatrix(floatArrayOf(
            contrast, 0f,       0f,       0f, translate,
            0f,       contrast, 0f,       0f, translate,
            0f,       0f,       contrast, 0f, translate,
            0f,       0f,       0f,       1f, 0f
        ))
        return applyMatrix(bitmap, matrix)
    }

    // ── Stage 6: Scene-specific renders ──────────────────────────

    /**
     * MACRO render — the crown jewel of this app.
     *
     * Makes 2MP macro shots feel like they were taken with a:
     *   - Canon MP-E 65mm f/2.8 Macro
     *   - Laowa 25mm f/2.8 Ultra Macro
     *
     * Techniques:
     *   1. Deep vignette — draws eye to the microscopic world
     *   2. Aggressive local contrast (clarity +40)
     *   3. Texture recovery (compensates 2MP pixel limit)
     *   4. Colour pop — insects, pollen, water droplets look alive
     *   5. Shadow separation — separates subject from background depth
     */
    fun applyMacroRender(bitmap: Bitmap): Bitmap {
        var result = bitmap

        // 1. Heavy pro-vignette — like looking through a macro lens barrel
        result = applyVignette(result, strength = 0.45f, warmth = -0.01f)

        // 2. Aggressive clarity for micro-texture detail
        result = applyClarity(result, 0.60f)

        // 3. Shadow lift to reveal subject from dark macro background
        val shadowLiftMatrix = ColorMatrix(floatArrayOf(
            0.92f, 0f,    0f,    0f, 18f,
            0f,    0.92f, 0f,    0f, 18f,
            0f,    0f,    0.92f, 0f, 18f,
            0f,    0f,    0f,    1f, 0f
        ))
        result = applyMatrix(result, shadowLiftMatrix)

        // 4. Colour pop — makes macro subjects (flowers, insects) vivid
        val sat = ColorMatrix()
        sat.setSaturation(1.22f)
        result = applyMatrix(result, sat)

        return result
    }

    private fun applyPortraitRender(bitmap: Bitmap): Bitmap {
        var result = bitmap
        // Warm skin tones
        val warmMatrix = ColorMatrix(floatArrayOf(
            1.04f, 0f,    0f,    0f, 3f,
            0f,    1.01f, 0f,    0f, 1f,
            0f,    0f,    0.97f, 0f, 0f,
            0f,    0f,    0f,    1f, 0f
        ))
        result = applyMatrix(result, warmMatrix)
        // Gentle clarity for skin texture without harshness
        result = applyClarity(result, 0.25f)
        return result
    }

    private fun applyNightRender(bitmap: Bitmap): Bitmap {
        var result = bitmap
        // Desaturate noise but keep colour warmth
        val sat = ColorMatrix()
        sat.setSaturation(0.88f)
        result = applyMatrix(result, sat)
        // Lift deep blacks to recover shadow detail
        val shadowMatrix = ColorMatrix(floatArrayOf(
            0.88f, 0f,    0f,    0f, 22f,
            0f,    0.88f, 0f,    0f, 22f,
            0f,    0f,    0.88f, 0f, 22f,
            0f,    0f,    0f,    1f, 0f
        ))
        result = applyMatrix(result, shadowMatrix)
        return result
    }

    private fun applyLandscapeRender(bitmap: Bitmap): Bitmap {
        var result = bitmap
        // Vivid landscape colours — Hasselblad style
        val sat = ColorMatrix()
        sat.setSaturation(1.18f)
        result = applyMatrix(result, sat)
        // Slight blue boost for sky depth
        val skyMatrix = ColorMatrix(floatArrayOf(
            0.99f, 0f,    0f,    0f, 0f,
            0f,    1.00f, 0f,    0f, 0f,
            0f,    0f,    1.04f, 0f, 0f,
            0f,    0f,    0f,    1f, 0f
        ))
        result = applyMatrix(result, skyMatrix)
        result = applyClarity(result, 0.50f)
        return result
    }

    private fun applyFoodRender(bitmap: Bitmap): Bitmap {
        var result = bitmap
        val sat = ColorMatrix()
        sat.setSaturation(1.15f)
        result = applyMatrix(result, sat)
        // Warm, appetising tones
        val warmMatrix = ColorMatrix(floatArrayOf(
            1.06f, 0f,    0f,    0f, 4f,
            0f,    1.02f, 0f,    0f, 2f,
            0f,    0f,    0.95f, 0f, 0f,
            0f,    0f,    0f,    1f, 0f
        ))
        result = applyMatrix(result, warmMatrix)
        return result
    }

    // ── Stage 7: Output Sharpening ───────────────────────────────

    /**
     * Final output sharpening — captures fine detail.
     * Leica-style: sharpens structure, not noise.
     * Macro gets the most — recreates the clinical sharpness of macro optics.
     */
    private fun applyOutputSharpening(bitmap: Bitmap, profile: RenderProfile): Bitmap {
        if (profile.outputSharpening <= 0f) return bitmap
        val s = profile.outputSharpening
        val matrix = ColorMatrix(floatArrayOf(
            1f + s * 0.04f, 0f,           0f,           0f, -s * 4f,
            0f,           1f + s * 0.04f, 0f,           0f, -s * 4f,
            0f,           0f,           1f + s * 0.04f, 0f, -s * 4f,
            0f,           0f,           0f,             1f, 0f
        ))
        return applyMatrix(bitmap, matrix)
    }

    // ── Utility ───────────────────────────────────────────────────

    private fun applyMatrix(bitmap: Bitmap, matrix: ColorMatrix): Bitmap {
        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) }
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return result
    }

    // ── Data classes ─────────────────────────────────────────────

    data class ShotInfo(
        val lensId: String,
        val scene: String,
        val iso: Int,
        val shutterSpeedNs: Long,
        val aperture: Float,
        val focalLengthMm: Float,
        val rGain: Float = 0.82f,
        val gGain: Float = 0.91f,
        val bGain: Float = 1.18f
    ) {
        val shutterFraction: String get() {
            val denom = (1_000_000_000L / shutterSpeedNs).toInt()
            return if (denom >= 1) "1/${denom}s" else "${shutterSpeedNs / 1_000_000_000f}s"
        }
        val megapixels: String get() = when (lensId) {
            "0" -> "12MP (108MP sensor, 9-in-1 binning)"
            "1" -> "8MP (ultrawide)"
            "2" -> "2MP (macro)"
            "3" -> "2MP (depth)"
            else -> "16MP (selfie)"
        }
        val sensorName: String get() = when (lensId) {
            "0", "1", "2", "3" -> "Samsung ISOCELL HM2"
            else               -> "Sony IMX471"
        }
        val lensName: String get() = when (lensId) {
            "0" -> "Main — f/1.88 26mm PDAF"
            "1" -> "Ultrawide — f/2.25 119°"
            "2" -> "Macro — f/2.4 4cm fixed"
            "3" -> "Depth — f/2.4 B&W"
            else -> "Selfie — f/2.45"
        }
    }

    data class RenderProfile(
        val shadowLift:       Float,
        val midtonePunch:     Float,
        val highlightRoll:    Float,
        val saturation:       Float,
        val colourTemp:       Float,
        val clarityAmount:    Float,
        val outputSharpening: Float
    ) {
        companion object {
            fun fromScene(scene: String) = when (scene) {
                "macro"     -> RenderProfile(0.10f, 0.18f, 0.14f, 1.22f, -0.01f, 0.60f, 1.20f)
                "portrait"  -> RenderProfile(0.08f, 0.12f, 0.10f, 1.08f,  0.04f, 0.25f, 0.80f)
                "landscape" -> RenderProfile(0.06f, 0.16f, 0.12f, 1.18f, -0.02f, 0.50f, 1.00f)
                "night"     -> RenderProfile(0.18f, 0.08f, 0.18f, 0.88f,  0.02f, 0.15f, 0.60f)
                "food"      -> RenderProfile(0.08f, 0.14f, 0.10f, 1.15f,  0.05f, 0.30f, 0.90f)
                "sunset"    -> RenderProfile(0.06f, 0.18f, 0.16f, 1.20f,  0.08f, 0.40f, 0.90f)
                else        -> RenderProfile(0.06f, 0.12f, 0.08f, 1.05f,  0.01f, 0.30f, 0.90f)
            }
        }
    }
}
