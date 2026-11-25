package com.fieldcapture.app.data.lineitems

import com.fieldcapture.app.data.model.CaptureEvidence
import com.fieldcapture.app.data.model.CaptureType
import com.fieldcapture.app.data.model.ConfidenceField
import com.fieldcapture.app.data.model.DamageData
import com.fieldcapture.app.data.model.NameplateData
import com.fieldcapture.app.data.model.QualityMetrics
import com.fieldcapture.app.data.model.SizeEstimate
import com.fieldcapture.app.data.model.SizeEstimationMethod
import com.fieldcapture.app.extraction.NoteParser
import org.junit.Assert.assertTrue
import org.junit.Test

class LineItemMapperTest {

    private val okQuality = QualityMetrics(true, 300.0, 120.0, 0.1, 0.01, 0.15, 0.6)

    @Test
    fun `catalog has between 10 and 15 custom line items`() {
        assertTrue(LineItemCatalog.ALL.size in 10..15)
    }

    @Test
    fun `maps nameplate damage and note to appropriate codes`() {
        val nameplate = CaptureEvidence(
            id = "np1",
            captureType = CaptureType.NAMEPLATE,
            timestampIso = "2026-01-01T00:00:00Z",
            imagePath = "/tmp/np1.jpg",
            qualityMetrics = okQuality,
            nameplateData = NameplateData(
                make = ConfidenceField("Rheem", 0.9f, "test"),
                model = ConfidenceField("PROG50", 0.9f, "test"),
                serialNumber = ConfidenceField("SN123", 0.9f, "test"),
                manufactureDate = ConfidenceField("2014-05", 0.85f, "test"),
            ),
        )
        val damage = CaptureEvidence(
            id = "dmg1",
            captureType = CaptureType.DAMAGE,
            timestampIso = "2026-01-01T00:01:00Z",
            imagePath = "/tmp/dmg1.jpg",
            qualityMetrics = okQuality,
            damageData = DamageData(
                room = ConfidenceField("kitchen", 0.9f, "voice"),
                surface = ConfidenceField("north wall", 0.9f, "voice"),
                damageType = ConfidenceField("water_stain_discoloration", 0.8f, "vision"),
                sizeEstimate = SizeEstimate(24f, 18f, 3.0f, SizeEstimationMethod.FOV_DISTANCE_PRIOR, 0.6f),
            ),
        )
        val note = NoteParser.parse("subfloor soft by the cabinet", id = "n1")

        val items = LineItemMapper.mapToLineItems(listOf(nameplate, damage), listOf(note))
        val codes = items.map { it.code }.toSet()

        assertTrue("PLM-02" in codes) // old water heater -> replacement
        assertTrue("DRW-02" in codes || "DRW-01" in codes)
        assertTrue("FLR-01" in codes)
        assertTrue("CAB-01" in codes)
    }
}
