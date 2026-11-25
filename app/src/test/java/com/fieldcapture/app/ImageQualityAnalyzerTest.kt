package com.fieldcapture.app.quality

import com.fieldcapture.app.data.model.QualityFailureReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageQualityAnalyzerTest {

    @Test
    fun `rejects dark frame`() {
        val w = 64
        val h = 64
        val pixels = IntArray(w * h) { 0xFF101010.toInt() }
        val result = ImageQualityAnalyzer.analyzePixels(pixels, w, h)
        assertFalse(result.passed)
        assertEquals(QualityFailureReason.TOO_DARK, result.failureReason)
    }

    @Test
    fun `rejects blurry uniform frame`() {
        val w = 64
        val h = 64
        // Smooth gradient: bright enough, but zero sharp edges -> low Laplacian variance
        val pixels = IntArray(w * h) { idx ->
            val x = idx % w
            val v = 90 + (x * 60 / w)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val result = ImageQualityAnalyzer.analyzePixels(pixels, w, h)
        assertFalse(result.passed)
        assertEquals(QualityFailureReason.BLURRY, result.failureReason)
    }

    @Test
    fun `accepts sharp well-lit centered frame`() {
        val w = 64
        val h = 64
        val pixels = IntArray(w * h) { idx ->
            val x = idx % w
            val y = idx / w
            // High-contrast checkerboard inside center 60% on mid-gray background
            val inCenter = x in 14..50 && y in 14..50
            val v = if (inCenter && ((x + y) % 2 == 0)) 220 else if (inCenter) 60 else 140
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val result = ImageQualityAnalyzer.analyzePixels(pixels, w, h)
        assertTrue(result.passed)
        assertEquals(QualityFailureReason.NONE, result.failureReason)
    }

    @Test
    fun `rejects bad framing when edges are only at border`() {
        val w = 64
        val h = 64
        val pixels = IntArray(w * h) { idx ->
            val x = idx % w
            val y = idx / w
            val inBorder = x < 6 || x >= w - 6 || y < 6 || y >= h - 6
            val v = if (inBorder && ((x + y) % 2 == 0)) 240 else if (inBorder) 40 else 130
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val result = ImageQualityAnalyzer.analyzePixels(pixels, w, h)
        assertFalse(result.passed)
        assertEquals(QualityFailureReason.BAD_FRAMING, result.failureReason)
    }
}
