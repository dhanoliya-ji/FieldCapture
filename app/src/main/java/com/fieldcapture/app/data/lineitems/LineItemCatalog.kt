package com.fieldcapture.app.data.lineitems

data class LineItemDefinition(
    val code: String,
    val category: String,
    val description: String,
    val unit: String,
    val defaultUnitRateUsd: Float,
)

/**
 * 14 custom water-damage & mechanical field estimation line-item codes.
 */
object LineItemCatalog {
    val ALL: List<LineItemDefinition> = listOf(
        LineItemDefinition("DRW-01", "Drywall", "Remove and replace water-damaged drywall", "sq ft", 4.50f),
        LineItemDefinition("DRW-02", "Drywall", "Drywall patch, tape, and texture blend", "sq ft", 6.25f),
        LineItemDefinition("PNT-01", "Paint", "Seal stain-blocker primer and paint two coats", "sq ft", 2.80f),
        LineItemDefinition("INS-01", "Insulation", "Remove and replace wet batt insulation", "sq ft", 2.40f),
        LineItemDefinition("FLR-01", "Flooring", "Remove and replace water-damaged subfloor", "sq ft", 9.75f),
        LineItemDefinition("CAB-01", "Cabinetry", "Detach, dry, and reset base cabinet", "each", 185.00f),
        LineItemDefinition("BSB-01", "Trim", "Remove and replace swollen baseboard trim", "lin ft", 5.50f),
        LineItemDefinition("WTR-01", "Mitigation", "Water extraction and air mover/dehumidifier setup", "day", 145.00f),
        LineItemDefinition("MLD-01", "Remediation", "Apply antimicrobial treatment to affected framing", "sq ft", 1.95f),
        LineItemDefinition("PLM-01", "Plumbing", "Water heater inspection, pan, and T&P relief check", "each", 220.00f),
        LineItemDefinition("PLM-02", "Plumbing", "Remove and replace residential water heater", "each", 1450.00f),
        LineItemDefinition("PLM-03", "Plumbing", "Supply line / shutoff valve repair", "each", 195.00f),
        LineItemDefinition("CLG-01", "Ceiling", "Remove and replace sagged or stained ceiling drywall", "sq ft", 5.75f),
        LineItemDefinition("TST-01", "Inspection", "Moisture meter & thermal mapping check", "each", 95.00f),
    )

    val BY_CODE: Map<String, LineItemDefinition> = ALL.associateBy { it.code }
}
