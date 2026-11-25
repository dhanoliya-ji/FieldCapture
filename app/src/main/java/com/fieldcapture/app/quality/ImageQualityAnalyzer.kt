package com.fieldcapture.app.quality

import android.graphics.Bitmap
import com.fieldcapture.app.data.model.QualityFailureReason
import com.fieldcapture.app.data.model.QualityMetrics
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * On-device image quality gate for wearable field captures.
 *
 * Calibrated thresholds:
 * - Blur (3x3 Laplacian variance on <=320px grayscale): < 110.0 -> BLURRY
 * - Low light (mean luminance < 48.0 OR dark pixel ratio [L < 35] > 0.72) -> TOO_DARK
 * - Overexposed (bright pixel ratio [L > 240] > 0.65) -> OVEREXPOSED
 * - Bad framing (outer 12% border edge share > 0.45 OR center contrast ratio < 0.18) -> BAD_FRAMING
 */
object ImageQualityAnalyzer {

    const val BLUR_MIN_VARIANCE = 110.0
    const val MIN_MEAN_LUMINANCE = 48.0
    const val MAX_DARK_PIXEL_RATIO = 0.72
    const val MAX_BRIGHT_PIXEL_RATIO = 0.65
    const val MAX_BORDER_EDGE_RATIO = 0.45
    const val MIN_CENTER_CONTRAST_RATIO = 0.18
    private const val ANALYSIS_MAX_DIM = 320

    fun analyze(bitmap: Bitmap): QualityMetrics {
        val scale = min(1f, ANALYSIS_MAX_DIM.toFloat() / max(bitmap.width, bitmap.height).coerceAtLeast(1))
        val w = max(8, (bitmap.width * scale).toInt())
        val h = max(8, (bitmap.height * scale).toInt())
        val scaled = if (w != bitmap.width || h != bitmap.height) {
            Bitmap.createScaledBitmap(bitmap, w, h, true)
        } else {
            bitmap
        }
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        if (scaled !== bitmap) scaled.recycle()
        return analyzePixels(pixels, w, h)
    }

    fun analyzePixels(argbPixels: IntArray, width: Int, height: Int): QualityMetrics {
        require(argbPixels.size == width * height) { "Pixel array size mismatch" }
        require(width >= 8 && height >= 8) { "Image too small for quality analysis" }

        val n = width * height
        val luma = DoubleArray(n)
        var sumLuma = 0.0
        var darkCount = 0
        var brightCount = 0

        for (i in 0 until n) {
            val c = argbPixels[i]
            val r = (c ushr 16) and 0xFF
            val g = (c ushr 8) and 0xFF
            val b = c and 0xFF
            val y = 0.299 * r + 0.587 * g + 0.114 * b
            luma[i] = y
            sumLuma += y
            if (y < 35.0) darkCount++
            if (y > 240.0) brightCount++
        }

        val meanLuminance = sumLuma / n
        val darkRatio = darkCount.toDouble() / n
        val brightRatio = brightCount.toDouble() / n

        // 3x3 Laplacian variance + gradient energy in center vs 12% outer border
        val borderX = max(1, (width * 0.12).toInt())
        val borderY = max(1, (height * 0.12).toInt())

        var lapSum = 0.0
        var lapSqSum = 0.0
        var lapCount = 0

        var borderEdgeSum = 0.0
        var totalEdgeSum = 0.0
        var centerMin = 255.0
        var centerMax = 0.0

        for (y in 1 until height - 1) {
            val rowOffset = y * width
            for (x in 1 until width - 1) {
                val idx = rowOffset + x
                val c = luma[idx]
                val lap = luma[idx - width] + luma[idx + width] + luma[idx - 1] + luma[idx + 1] - 4.0 * c
                lapSum += lap
                lapSqSum += lap * lap
                lapCount++

                val gx = abs(luma[idx + 1] - luma[idx - 1])
                val gy = abs(luma[idx + width] - luma[idx - width])
                val edge = gx + gy
                totalEdgeSum += edge

                val isBorder = x < borderX || x >= width - borderX || y < borderY || y >= height - borderY
                if (isBorder) {
                    borderEdgeSum += edge
                } else {
                    if (c < centerMin) centerMin = c
                    if (c > centerMax) centerMax = c
                }
            }
        }

        val lapMean = if (lapCount > 0) lapSum / lapCount else 0.0
        val blurVariance = if (lapCount > 0) (lapSqSum / lapCount) - (lapMean * lapMean) else 0.0
        val borderEdgeRatio = if (totalEdgeSum > 1.0) borderEdgeSum / totalEdgeSum else 0.0
        val centerContrastRatio = ((centerMax - centerMin) / 255.0).coerceIn(0.0, 1.0)

        val (failureReason, prompt) = when {
            meanLuminance < MIN_MEAN_LUMINANCE || darkRatio > MAX_DARK_PIXEL_RATIO ->
                QualityFailureReason.TOO_DARK to "Too dark. Add light or step closer and retake."
            brightRatio > MAX_BRIGHT_PIXEL_RATIO ->
                QualityFailureReason.OVEREXPOSED to "Glare or overexposed. Angle away from light and retake."
            blurVariance < BLUR_MIN_VARIANCE ->
                QualityFailureReason.BLURRY to "Too blurry. Hold steady for one second and retake."
            borderEdgeRatio > MAX_BORDER_EDGE_RATIO || centerContrastRatio < MIN_CENTER_CONTRAST_RATIO ->
                QualityFailureReason.BAD_FRAMING to "Bad framing. Center the target in view and retake."
            else ->
                QualityFailureReason.NONE to null
        }

        return QualityMetrics(
            passed = failureReason == QualityFailureReason.NONE,
            blurScore = blurVariance,
            meanLuminance = meanLuminance,
            darkPixelRatio = darkRatio,
            brightPixelRatio = brightRatio,
            borderEdgeRatio = borderEdgeRatio,
            centerContrastRatio = centerContrastRatio,
            failureReason = failureReason,
            retakePrompt = prompt,
        )
    }
}
