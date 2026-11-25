package com.fieldcapture.app.extraction

import com.fieldcapture.app.data.model.SizeEstimationMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MonocularSizeEstimatorTest {

    @Test
    fun `estimates size accurately when reference pixels-per-inch is known`() {
        val w = 200
        val h = 200
        val pixelsPerInch = 5f // 40" x 40" frame
        // Draw a 20" x 10" dark brown stain in the center (100px x 50px)
        val pixels = IntArray(w * h) { idx ->
            val x = idx % w
            val y = idx / w
            val inStain = x in 50 until 150 && y in 75 until 125
            if (inStain) 0xFF6B4C2A.toInt() else 0xFFE8E8E8.toInt()
        }

        val est = MonocularSizeEstimator.estimateFromPixels(pixels, w, h, pixelsPerInchOverride = pixelsPerInch)
        assertEquals(SizeEstimationMethod.REFERENCE_SCALE, est.method)
        assertTrue(est.widthInches in 18f..22f)
        assertTrue(est.heightInches in 8f..12f)
        val err = MonocularSizeEstimator.computeAreaErrorPercent(est.areaSqFt, 20f, 10f)
        assertTrue("Expected < 15% error, got $err%", err < 15f)
    }
}
