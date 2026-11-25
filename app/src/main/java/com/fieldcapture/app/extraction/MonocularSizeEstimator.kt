package com.fieldcapture.app.extraction

import android.graphics.Bitmap
import com.fieldcapture.app.data.model.SizeEstimate
import com.fieldcapture.app.data.model.SizeEstimationMethod
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * Stretch Goal: Monocular damage size estimation without a depth sensor.
 *
 * Estimates damaged area in inches and sq ft by segmenting the anomalous/stained region
 * in the center inspection zone and projecting pixel dimensions using either:
 * 1) A known reference scale (pixels-per-inch from a visible reference object), or
 * 2) Ray-Ban Meta 12MP camera FoV (~82 deg H / ~64 deg V) at a typical inspection distance (1.2 m).
 */
object MonocularSizeEstimator {

    private const val RAYBAN_META_HFOV_RAD = 82.0 * Math.PI / 180.0
    private const val DEFAULT_STANDOFF_INCHES = 47.24f // 1.2 meters
    private const val ANALYSIS_MAX_DIM = 240

    fun estimateFromBitmap(
        bitmap: Bitmap,
        pixelsPerInchOverride: Float? = null,
        assumedDistanceInches: Float = DEFAULT_STANDOFF_INCHES,
    ): SizeEstimate {
        val scale = min(1f, ANALYSIS_MAX_DIM.toFloat() / max(bitmap.width, bitmap.height).coerceAtLeast(1))
        val w = max(16, (bitmap.width * scale).toInt())
        val h = max(16, (bitmap.height * scale).toInt())
        val scaled = if (w != bitmap.width || h != bitmap.height) {
            Bitmap.createScaledBitmap(bitmap, w, h, true)
        } else {
            bitmap
        }
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        if (scaled !== bitmap) scaled.recycle()
        return estimateFromPixels(pixels, w, h, pixelsPerInchOverride, assumedDistanceInches)
    }

    fun estimateFromPixels(
        argbPixels: IntArray,
        width: Int,
        height: Int,
        pixelsPerInchOverride: Float? = null,
        assumedDistanceInches: Float = DEFAULT_STANDOFF_INCHES,
    ): SizeEstimate {
        require(argbPixels.size == width * height)
        val n = width * height
        val luma = DoubleArray(n)
        val warmth = DoubleArray(n) // R - B proxy for yellow/brown water stains

        for (i in 0 until n) {
            val c = argbPixels[i]
            val r = (c ushr 16) and 0xFF
            val g = (c ushr 8) and 0xFF
            val b = c and 0xFF
            luma[i] = 0.299 * r + 0.587 * g + 0.114 * b
            warmth[i] = (r - b).toDouble()
        }

        // Background baseline from outer 10% ring
        var bgLumaSum = 0.0
        var bgWarmthSum = 0.0
        var bgCount = 0
        val borderX = max(1, (width * 0.10).toInt())
        val borderY = max(1, (height * 0.10).toInt())
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (x < borderX || x >= width - borderX || y < borderY || y >= height - borderY) {
                    val idx = y * width + x
                    bgLumaSum += luma[idx]
                    bgWarmthSum += warmth[idx]
                    bgCount++
                }
            }
        }
        val bgLuma = if (bgCount > 0) bgLumaSum / bgCount else 128.0
        val bgWarmth = if (bgCount > 0) bgWarmthSum / bgCount else 0.0

        // Segment anomalous pixels inside center 85%
        val minXBound = (width * 0.075).toInt()
        val maxXBound = (width * 0.925).toInt()
        val minYBound = (height * 0.075).toInt()
        val maxYBound = (height * 0.925).toInt()

        var minX = width
        var maxX = 0
        var minY = height
        var maxY = 0
        var hitCount = 0

        for (y in minYBound until maxYBound) {
            for (x in minXBound until maxXBound) {
                val idx = y * width + x
                val lumaDiff = abs(luma[idx] - bgLuma)
                val warmthDiff = warmth[idx] - bgWarmth
                if (lumaDiff > 26.0 || warmthDiff > 22.0) {
                    hitCount++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        val bboxWidthPx: Float
        val bboxHeightPx: Float
        val segConfidence: Float

        if (hitCount < 16 || maxX <= minX || maxY <= minY) {
            // Fallback bounding box when uniform surface has weak contrast
            bboxWidthPx = width * 0.35f
            bboxHeightPx = height * 0.30f
            segConfidence = 0.32f
        } else {
            bboxWidthPx = (maxX - minX).toFloat()
            bboxHeightPx = (maxY - minY).toFloat()
            val fillRatio = hitCount.toFloat() / (bboxWidthPx * bboxHeightPx)
            segConfidence = (0.45f + 0.35f * fillRatio.coerceIn(0f, 1f)).coerceIn(0.35f, 0.80f)
        }

        val (pxPerInch, method, refLabel, methodConf) = if (pixelsPerInchOverride != null && pixelsPerInchOverride > 0f) {
            Quad(pixelsPerInchOverride, SizeEstimationMethod.REFERENCE_SCALE, "Reference scale (${pixelsPerInchOverride} px/in)", 0.88f)
        } else {
            // Horizontal span at assumed distance: 2 * D * tan(HFOV/2)
            val spanInches = (2.0 * assumedDistanceInches * tan(RAYBAN_META_HFOV_RAD / 2.0)).toFloat()
            val ppi = width / spanInches
            Quad(ppi, SizeEstimationMethod.FOV_DISTANCE_PRIOR, "82° HFOV @ ${assumedDistanceInches.roundToInt()} in", 0.56f)
        }

        val widthIn = ((bboxWidthPx / pxPerInch) * 10f).roundToInt() / 10f
        val heightIn = ((bboxHeightPx / pxPerInch) * 10f).roundToInt() / 10f
        val areaSqFt = (((widthIn * heightIn) / 144f) * 100f).roundToInt() / 100f

        return SizeEstimate(
            widthInches = widthIn.coerceAtLeast(1f),
            heightInches = heightIn.coerceAtLeast(1f),
            areaSqFt = areaSqFt.coerceAtLeast(0.1f),
            method = method,
            confidence = ((segConfidence + methodConf) / 2f).coerceIn(0.2f, 0.92f),
            referenceUsed = refLabel,
        )
    }

    fun computeAreaErrorPercent(estimatedSqFt: Float, tapeWidthIn: Float, tapeHeightIn: Float): Float {
        val actualSqFt = (tapeWidthIn * tapeHeightIn) / 144f
        if (actualSqFt <= 0f) return 0f
        return (abs(estimatedSqFt - actualSqFt) / actualSqFt) * 100f
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
}
