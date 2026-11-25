package com.fieldcapture.app.extraction

import com.fieldcapture.app.data.model.QualityMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NameplateParserTest {

    private val goodQuality = QualityMetrics(
        passed = true,
        blurScore = 400.0,
        meanLuminance = 130.0,
        darkPixelRatio = 0.05,
        brightPixelRatio = 0.02,
        borderEdgeRatio = 0.15,
        centerContrastRatio = 0.70,
    )

    @Test
    fun `extracts standard water heater nameplate fields`() {
        val ocr = """
            RHEEM MANUFACTURING CO.
            Model No: PROG50-38N RH67
            Serial No: Q421912345
            Mfg Date: 10/2019
        """.trimIndent()

        val data = NameplateParser.parse(ocr, quality = goodQuality, ocrConfidence = 0.90f)
        assertEquals("Rheem", data.make.value)
        assertEquals("PROG50-38N", data.model.value)
        assertEquals("Q421912345", data.serialNumber.value)
        assertEquals("2019-10", data.manufactureDate.value)
        assertTrue(data.make.confidence > 0.7f)
        assertTrue(data.serialNumber.confidence > 0.7f)
    }

    @Test
    fun `lowers confidence when quality gate failed`() {
        val badQuality = goodQuality.copy(passed = false)
        val ocr = "BRADFORD WHITE\nS/N: BW12345678\nM/N: RG250T6N"
        val goodRes = NameplateParser.parse(ocr, quality = goodQuality, ocrConfidence = 0.90f)
        val badRes = NameplateParser.parse(ocr, quality = badQuality, ocrConfidence = 0.90f)
        assertTrue(badRes.make.confidence < goodRes.make.confidence)
        assertTrue(badRes.serialNumber.confidence < goodRes.serialNumber.confidence)
    }
}
