package com.visura.cam.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * AIRenderEngine — Intelligent per-image analysis + professional rendering.
 * Owner: Ahsan Jannat — AJ Cam
 */
object AIRenderEngine {

    fun render(bitmap: Bitmap, scene: String, lensId: String): Bitmap {
        if (bitmap.isRecycled) return bitmap

        val analysis = analyzeImage(bitmap)

        var result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        result = autoExposure(result, analysis)
        result = smartWhiteBalance(result, analysis)
        result = proToneMap(result, scene, analysis)
        result = smartDetail(result, scene)
        result = colourGrade(result, scene, lensId)
        return result
    }

    data class ImageAnalysis(
        val avgBrightness: Float,
        val avgR: Float,
        val avgG: Float,
        val avgB: Float,
        val isUnderexposed: Boolean,
        val isOverexposed: Boolean,
        val colorCast: ColorCast,
        val highlightClipping: Float,
        val shadowCrushing: Float
    ) {
        enum class ColorCast { NONE, WARM_YELLOW, COOL_BLUE, GREEN, MAGENTA }
    }

    private fun analyzeImage(bitmap: Bitmap): ImageAnalysis {
        val step = 8
        var rSum = 0L; var gSum = 0L; var bSum = 0L; var count = 0
        var highlight = 0; var shadow = 0

        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val px = bitmap.getPixel(x, y)
                val r = (px shr 16) and 0xFF
                val g = (px shr 8)  and 0xFF
                val b =  px         and 0xFF
                rSum += r; gSum += g; bSum += b; count++
                val lum = (r * 77 + g * 150 + b * 29) shr 8
                if (lum > 245) highlight++
                if (lum < 10) shadow++
                x += step
            }
            y += step
        }
        if (count == 0) count = 1

        val avgR = rSum.toFloat() / count
        val avgG = gSum.toFloat() / count
        val avgB = bSum.toFloat() / count
        val avg = (avgR + avgG + avgB) / 3f

        val rDiff = avgR - avg; val gDiff = avgG - avg; val bDiff = avgB - avg
        val colorCast = when {
            rDiff > 12 && gDiff > 6 -> ImageAnalysis.ColorCast.WARM_YELLOW
            bDiff > 12              -> ImageAnalysis.ColorCast.COOL_BLUE
            gDiff > 10              -> ImageAnalysis.ColorCast.GREEN
            rDiff > 8 && bDiff > 8  -> ImageAnalysis.ColorCast.MAGENTA
            else                    -> ImageAnalysis.ColorCast.NONE
        }

        val hlPct = highlight.toFloat() / count
        val shPct = shadow.toFloat() / count

        return ImageAnalysis(
            avgBrightness     = avg,
            avgR              = avgR,
            avgG              = avgG,
            avgB              = avgB,
            isUnderexposed    = avg < 80f,
            isOverexposed     = avg > 200f || hlPct > 0.05f,
            colorCast         = colorCast,
            highlightClipping = hlPct,
            shadowCrushing    = shPct
        )
    }

    private fun autoExposure(bmp: Bitmap, a: ImageAnalysis): Bitmap {
        if (!a.isUnderexposed && !a.isOverexposed) return bmp
        val targetBrightness = 118f
        val scale = (targetBrightness / a.avgBrightness.coerceAtLeast(1f)).coerceIn(0.6f, 1.8f)
        if (abs(scale - 1f) < 0.05f) return bmp
        return applyMatrix(bmp, ColorMatrix(floatArrayOf(
            scale, 0f,    0f,    0f, 0f,
            0f,    scale, 0f,    0f, 0f,
            0f,    0f,    scale, 0f, 0f,
            0f,    0f,    0f,    1f, 0f
        )))
    }

    private fun smartWhiteBalance(bmp: Bitmap, a: ImageAnalysis): Bitmap {
        val rGain: Float; val gGain: Float; val bGain: Float
        when (a.colorCast) {
            ImageAnalysis.ColorCast.WARM_YELLOW -> {
                val target = a.avgG
                rGain = (target / a.avgR.coerceAtLeast(1f)).coerceIn(0.75f, 1.10f)
                gGain = 1.0f
                bGain = (target / a.avgB.coerceAtLeast(1f)).coerceIn(0.90f, 1.30f)
            }
            ImageAnalysis.ColorCast.COOL_BLUE -> {
                val target = a.avgG
                rGain = (target / a.avgR.coerceAtLeast(1f)).coerceIn(0.90f, 1.20f)
                gGain = 1.0f
                bGain = (target / a.avgB.coerceAtLeast(1f)).coerceIn(0.80f, 1.0f)
            }
            ImageAnalysis.ColorCast.GREEN -> {
                val target = (a.avgR + a.avgB) / 2f
                rGain = 1.0f
                gGain = (target / a.avgG.coerceAtLeast(1f)).coerceIn(0.80f, 1.0f)
                bGain = 1.0f
            }
            else -> return bmp
        }
        return applyMatrix(bmp, ColorMatrix(floatArrayOf(
            rGain, 0f,    0f,    0f, 0f,
            0f,    gGain, 0f,    0f, 0f,
            0f,    0f,    bGain, 0f, 0f,
            0f,    0f,    0f,    1f, 0f
        )))
    }

    private fun proToneMap(bmp: Bitmap, scene: String, a: ImageAnalysis): Bitmap {
        val shadowLift = when {
            a.isUnderexposed  -> 0.22f
            scene == "night"  -> 0.18f
            scene == "macro"  -> 0.12f
            else              -> 0.07f
        }
        val hlRoll = when {
            a.isOverexposed              -> 0.22f
            a.highlightClipping > 0.02f  -> 0.18f
            scene == "sunset"            -> 0.16f
            else                         -> 0.08f
        }
        val punch = when (scene) {
            "macro"     -> 0.22f
            "landscape" -> 0.18f
            "portrait"  -> 0.10f
            "night"     -> 0.05f
            else        -> 0.12f
        }

        val lut = IntArray(256) { i ->
            val t = i / 255f
            val oneMinusT = 1f - t
            val lifted  = t + shadowLift * oneMinusT * oneMinusT * oneMinusT
            val punched = lifted + punch * sin(lifted * PI.toFloat()) * 0.18f
            val rolled  = punched - hlRoll * punched * punched * punched
            (rolled * 255f).toInt().coerceIn(0, 255)
        }

        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        for (i in pixels.indices) {
            val px = pixels[i]
            pixels[i] = (px and -0x1000000) or
                (lut[(px shr 16) and 0xFF] shl 16) or
                (lut[(px shr 8)  and 0xFF] shl 8)  or
                 lut[ px         and 0xFF]
        }
        val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return out
    }

    private fun smartDetail(bmp: Bitmap, scene: String): Bitmap {
        val strength = when (scene) {
            "macro"     -> 0.80f
            "landscape" -> 0.55f
            "portrait"  -> 0.25f
            "night"     -> 0.15f
            else        -> 0.40f
        }
        val vigStrength = when (scene) {
            "macro"     -> 0.50f
            "portrait"  -> 0.20f
            "landscape" -> 0.15f
            else        -> 0.12f
        }
        var result = applyVignette(bmp, vigStrength)
        val s = strength * 0.06f
        result = applyMatrix(result, ColorMatrix(floatArrayOf(
            1f+s, 0f,   0f,   0f, -s * 128f,
            0f,   1f+s, 0f,   0f, -s * 128f,
            0f,   0f,   1f+s, 0f, -s * 128f,
            0f,   0f,   0f,   1f, 0f
        )))
        return result
    }

    private fun colourGrade(bmp: Bitmap, scene: String, lensId: String): Bitmap {
        val sat = when (scene) {
            "macro"     -> 1.30f
            "landscape" -> 1.22f
            "sunset"    -> 1.28f
            "food"      -> 1.18f
            "portrait"  -> 1.08f
            "night"     -> 0.88f
            else        -> 1.10f
        }
        val satMatrix = ColorMatrix().apply { setSaturation(sat) }
        val hm2Fix = ColorMatrix(floatArrayOf(
             1.02f, -0.01f, 0.00f, 0f, 0f,
            -0.01f,  1.01f, 0.00f, 0f, 0f,
             0.00f, -0.01f, 1.01f, 0f, 0f,
             0f,     0f,    0f,    1f, 0f
        ))
        satMatrix.postConcat(hm2Fix)

        val tR: Float; val tB: Float
        when (scene) {
            "portrait", "food"   -> { tR = 0.04f; tB = -0.02f }
            "landscape", "macro" -> { tR = -0.01f; tB = 0.02f }
            "sunset"             -> { tR = 0.08f; tB = -0.05f }
            "night"              -> { tR = 0.02f; tB = -0.01f }
            else                 -> { tR = 0.01f; tB = 0.00f }
        }
        satMatrix.postConcat(ColorMatrix(floatArrayOf(
            1f+tR, 0f, 0f,    0f, 0f,
            0f,    1f, 0f,    0f, 0f,
            0f,    0f, 1f+tB, 0f, 0f,
            0f,    0f, 0f,    1f, 0f
        )))
        return applyMatrix(bmp, satMatrix)
    }

    private fun applyVignette(bmp: Bitmap, strength: Float): Bitmap {
        val result = bmp.copy(Bitmap.Config.ARGB_8888, true)
        val cx = bmp.width / 2f; val cy = bmp.height / 2f
        val radius = sqrt(cx * cx + cy * cy)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx, cy, radius,
                intArrayOf(Color.TRANSPARENT, Color.argb((strength * 255).toInt(), 0, 0, 0)),
                floatArrayOf(0.50f, 1.0f),
                Shader.TileMode.CLAMP
            )
        }
        Canvas(result).drawRect(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat(), paint)
        return result
    }

    private fun applyMatrix(bmp: Bitmap, matrix: ColorMatrix): Bitmap {
        val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(bmp, 0f, 0f, Paint().apply {
            colorFilter = ColorMatrixColorFilter(matrix)
        })
        return out
    }
}
