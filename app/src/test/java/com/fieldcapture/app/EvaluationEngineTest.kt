package com.fieldcapture.app.eval

import com.fieldcapture.app.data.model.CaptureType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvaluationEngineTest {

    @Test
    fun `computes per-field accuracy and worst failures`() {
        val w = 32
        val h = 32
        val sharpPixels = IntArray(w * h) { idx ->
            val x = idx % w
            val y = idx / w
            val inCenter = x in 6..25 && y in 6..25
            val v = if (inCenter && ((x + y) % 2 == 0)) 220 else if (inCenter) 60 else 140
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val darkPixels = IntArray(w * h) { 0xFF101010.toInt() }

        val samples = listOf(
            EvalSample(
                id = "s1",
                captureType = CaptureType.NAMEPLATE,
                isLowLight = false,
                pixels = sharpPixels,
                width = w,
                height = h,
                expectedQualityPass = true,
                ocrTextForEval = "RHEEM\nModel: PROG50-38N\nS/N: Q12345678\nMfg Date: 2020-05",
                expectedMake = "Rheem",
                expectedModel = "PROG50-38N",
                expectedSerial = "Q12345678",
                expectedManufactureDate = "2020-05",
            ),
            EvalSample(
                id = "s2",
                captureType = CaptureType.NAMEPLATE,
                isLowLight = true,
                pixels = darkPixels,
                width = w,
                height = h,
                expectedQualityPass = false,
                ocrTextForEval = "blurry unreadable text",
                expectedMake = "Bradford White",
                expectedModel = "RG250T6N",
                expectedSerial = "BW998877",
                expectedManufactureDate = "2018-01",
            ),
        )

        val summary = EvaluationEngine.evaluate(samples)
        assertEquals(2, summary.totalFrames)
        assertEquals(1.0f, summary.qualityGateAccuracy)
        assertEquals(0.5f, summary.makeAccuracy)
        assertTrue(summary.avgConfidenceCorrect > summary.avgConfidenceWrong)
        assertTrue(summary.worstFailures.isNotEmpty())
    }
}
