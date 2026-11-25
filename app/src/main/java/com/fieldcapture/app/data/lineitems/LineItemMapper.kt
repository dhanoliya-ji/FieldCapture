package com.fieldcapture.app.data.lineitems

import com.fieldcapture.app.data.model.CaptureEvidence
import com.fieldcapture.app.data.model.CaptureType
import com.fieldcapture.app.data.model.EstimateLineItem
import com.fieldcapture.app.data.model.StructuredNote
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Deterministic mapper from extracted field observations (nameplates, damage captures, notes)
 * to draft estimate line items from [LineItemCatalog].
 */
object LineItemMapper {

    fun mapToLineItems(
        captures: List<CaptureEvidence>,
        notes: List<StructuredNote>,
    ): List<EstimateLineItem> {
        val items = linkedMapOf<String, EstimateLineItem>()

        fun addOrMerge(
            code: String,
            qty: Float,
            confidence: Float,
            rationale: String,
            sourceId: String,
        ) {
            val def = LineItemCatalog.BY_CODE[code] ?: return
            val existing = items[code]
            val cleanQty = max(1f, (qty * 10f).roundToInt() / 10f)
            if (existing == null) {
                items[code] = EstimateLineItem(
                    code = def.code,
                    category = def.category,
                    description = def.description,
                    unit = def.unit,
                    quantity = cleanQty,
                    unitRateUsd = def.defaultUnitRateUsd,
                    estimatedTotalUsd = ((cleanQty * def.defaultUnitRateUsd) * 100f).roundToInt() / 100f,
                    confidence = confidence.coerceIn(0.1f, 0.99f),
                    rationale = rationale,
                    sourceIds = listOf(sourceId),
                )
            } else {
                val mergedQty = if (def.unit == "each" || def.unit == "day") {
                    max(existing.quantity, cleanQty)
                } else {
                    ((existing.quantity + cleanQty) * 10f).roundToInt() / 10f
                }
                items[code] = existing.copy(
                    quantity = mergedQty,
                    estimatedTotalUsd = ((mergedQty * def.defaultUnitRateUsd) * 100f).roundToInt() / 100f,
                    confidence = max(existing.confidence, confidence).coerceIn(0.1f, 0.99f),
                    rationale = "${existing.rationale}; $rationale",
                    sourceIds = (existing.sourceIds + sourceId).distinct(),
                )
            }
        }

        for (capture in captures) {
            when (capture.captureType) {
                CaptureType.NAMEPLATE -> {
                    val np = capture.nameplateData ?: continue
                    val avgConf = listOf(
                        np.make.confidence,
                        np.model.confidence,
                        np.serialNumber.confidence,
                    ).filter { it > 0f }.ifEmpty { listOf(0.5f) }.average().toFloat()

                    val raw = np.rawOcrText.lowercase()
                    val mfgYear = np.manufactureDate.value?.take(4)?.toIntOrNull()
                    val isOldUnit = mfgYear != null && mfgYear <= 2016

                    if (isOldUnit || raw.contains("leak") || raw.contains("rust")) {
                        addOrMerge(
                            code = "PLM-02",
                            qty = 1f,
                            confidence = (avgConf * 0.85f),
                            rationale = "Aging appliance (${np.make.value ?: "unit"} ${np.manufactureDate.value ?: ""}) flagged for replacement",
                            sourceId = capture.id,
                        )
                    } else {
                        addOrMerge(
                            code = "PLM-01",
                            qty = 1f,
                            confidence = avgConf,
                            rationale = "Appliance nameplate captured (${np.make.value ?: "unknown"} ${np.model.value ?: ""})",
                            sourceId = capture.id,
                        )
                    }
                }

                CaptureType.DAMAGE -> {
                    val dmg = capture.damageData ?: continue
                    val surface = dmg.surface.value?.lowercase().orEmpty()
                    val damageType = dmg.damageType.value?.lowercase().orEmpty()
                    val room = dmg.room.value ?: "area"
                    val areaSqFt = dmg.sizeEstimate?.areaSqFt?.coerceAtLeast(2f) ?: 8f
                    val conf = listOf(
                        dmg.room.confidence,
                        dmg.surface.confidence,
                        dmg.damageType.confidence,
                    ).filter { it > 0f }.ifEmpty { listOf(0.55f) }.average().toFloat()

                    when {
                        surface.contains("ceiling") -> {
                            addOrMerge("CLG-01", areaSqFt, conf, "Ceiling damage in $room", capture.id)
                            addOrMerge("INS-01", areaSqFt, conf * 0.85f, "Potential wet ceiling insulation in $room", capture.id)
                            addOrMerge("PNT-01", areaSqFt * 1.5f, conf * 0.9f, "Prime & paint ceiling in $room", capture.id)
                        }
                        surface.contains("floor") || surface.contains("subfloor") -> {
                            addOrMerge("FLR-01", areaSqFt, conf, "Floor/subfloor damage in $room", capture.id)
                            addOrMerge("MLD-01", areaSqFt, conf * 0.85f, "Antimicrobial treatment for subfloor in $room", capture.id)
                        }
                        else -> {
                            // Wall or general surface
                            val code = if (damageType.contains("hole") || damageType.contains("crumble") || areaSqFt >= 6f) {
                                "DRW-01"
                            } else {
                                "DRW-02"
                            }
                            addOrMerge(code, areaSqFt, conf, "${dmg.damageType.value ?: "Water"} damage on $surface in $room", capture.id)
                            addOrMerge("PNT-01", areaSqFt * 1.5f, conf * 0.9f, "Prime & paint $surface in $room", capture.id)
                            addOrMerge("BSB-01", max(4f, kotlin.math.sqrt(areaSqFt) * 1.5f), conf * 0.75f, "Baseboard check along $surface in $room", capture.id)
                        }
                    }

                    if (damageType.contains("mold") || damageType.contains("mildew") || damageType.contains("dark")) {
                        addOrMerge("MLD-01", areaSqFt, conf * 0.85f, "Possible microbial staining on $surface in $room", capture.id)
                    }
                    if (damageType.contains("water") || damageType.contains("stain") || damageType.contains("wet")) {
                        addOrMerge("WTR-01", 2f, conf * 0.85f, "Mitigation drying equipment for $room", capture.id)
                    }
                }
            }
        }

        for (note in notes) {
            val text = note.rawText.lowercase()
            val conf = note.condition.confidence.coerceAtLeast(0.6f)
            if (text.contains("subfloor") || text.contains("soft") || text.contains("spongy")) {
                addOrMerge("FLR-01", 12f, conf, "Voice note: '${note.rawText}'", note.id)
            }
            if (text.contains("cabinet") || text.contains("vanity") || text.contains("sink")) {
                addOrMerge("CAB-01", 1f, conf * 0.9f, "Voice note: '${note.rawText}'", note.id)
            }
            if (text.contains("leak") || text.contains("pipe") || text.contains("valve") || text.contains("supply")) {
                addOrMerge("PLM-03", 1f, conf * 0.85f, "Voice note: '${note.rawText}'", note.id)
            }
            if (text.contains("mold") || text.contains("musty") || text.contains("odor")) {
                addOrMerge("MLD-01", 12f, conf * 0.85f, "Voice note: '${note.rawText}'", note.id)
            }
            if (text.contains("moisture") || text.contains("wet") || text.contains("damp")) {
                addOrMerge("TST-01", 1f, conf, "Voice note: '${note.rawText}'", note.id)
            }
        }

        return items.values.toList()
    }
}
