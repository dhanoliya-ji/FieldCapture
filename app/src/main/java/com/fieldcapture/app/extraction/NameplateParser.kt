package com.fieldcapture.app.extraction

import com.fieldcapture.app.data.model.ConfidenceField
import com.fieldcapture.app.data.model.NameplateData
import com.fieldcapture.app.data.model.QualityMetrics

/**
 * Deterministic parser for appliance/water-heater nameplate OCR text.
 * Produces per-field confidence scores calibrated by label match strength and image quality.
 */
object NameplateParser {

    private val KNOWN_MAKES = listOf(
        "BRADFORD WHITE", "A.O. SMITH", "AO SMITH", "RHEEM", "RUUD", "STATE",
        "WHIRLPOOL", "KENMORE", "RICHMOND", "RINNAI", "NAVIEN", "BOSCH",
        "CARRIER", "TRANE", "LENNOX", "GOODMAN", "YORK", "GENERAL ELECTRIC", "GE",
        "AMERICAN STANDARD", "HONEYWELL", "NORITZ", "TAKAGI", "ECO-SMART",
    )

    private val MODEL_REGEX = Regex(
        """(?i)(?:model(?:\s*(?:no\.?|number|#))?|mod\.?|m/n|cat\.?\s*no\.?)\s*[:#\-]?\s*([A-Z0-9][A-Z0-9\-/]{3,22})"""
    )

    private val SERIAL_REGEX = Regex(
        """(?i)(?:serial(?:\s*(?:no\.?|number|#))?|ser\.?(?:\s*no\.?)?|s/n|sn)\s*[:#\-]?\s*([A-Z0-9]{5,20})"""
    )

    private val MFG_DATE_LABELED_REGEX = Regex(
        """(?i)(?:mfg\.?\s*date|mfr\.?\s*date|date\s*(?:of\s*)?mfg\.?|dom|date)\s*[:#\-]?\s*(\d{4}[-/]\d{1,2}(?:[-/]\d{1,2})?|\d{1,2}[-/]\d{4}|(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*[\s\-/,]+\d{4})"""
    )

    private val GENERIC_DATE_REGEX = Regex(
        """\b((?:19|20)\d{2}[-/](?:0?[1-9]|1[0-2])(?:[-/](?:0?[1-9]|[12]\d|3[01]))?|(?:0?[1-9]|1[0-2])[-/](?:19|20)\d{2})\b"""
    )

    fun parse(
        rawText: String,
        quality: QualityMetrics? = null,
        ocrConfidence: Float = 0.85f,
    ): NameplateData {
        val qFactor = when {
            quality == null -> 1.0f
            quality.passed -> 1.0f
            else -> 0.55f
        }
        val baseOcr = ocrConfidence.coerceIn(0.2f, 0.98f)
        val lines = rawText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val upperText = rawText.uppercase()

        // 1. Make
        val matchedMake = KNOWN_MAKES.firstOrNull { upperText.contains(it) }
        val makeField = if (matchedMake != null) {
            ConfidenceField(
                value = matchedMake.split(" ").joinToString(" ") { w ->
                    w.lowercase().replaceFirstChar { c -> c.uppercase() }
                },
                confidence = (0.94f * baseOcr * qFactor).coerceIn(0.1f, 0.99f),
                source = "ocr+brand_dict",
            )
        } else {
            val mfrLine = lines.firstOrNull { it.contains("mfg", true) || it.contains("corp", true) || it.contains("inc", true) }
            if (mfrLine != null) {
                ConfidenceField(
                    value = mfrLine.take(36),
                    confidence = (0.52f * baseOcr * qFactor).coerceIn(0.1f, 0.99f),
                    source = "ocr_header_heuristic",
                )
            } else {
                ConfidenceField<String>(value = null, confidence = 0.0f, source = "not_found")
            }
        }

        // 2. Model
        val modelMatch = MODEL_REGEX.find(rawText)
        val modelField = if (modelMatch != null) {
            ConfidenceField(
                value = modelMatch.groupValues[1].uppercase(),
                confidence = (0.92f * baseOcr * qFactor).coerceIn(0.1f, 0.99f),
                source = "ocr+label_regex",
            )
        } else {
            // Heuristic: alphanumeric token with both letters and digits and hyphen, length 6..18
            val candidate = lines
                .flatMap { it.split(Regex("\\s+")) }
                .firstOrNull { tok ->
                    tok.length in 6..18 &&
                        tok.any { it.isLetter() } &&
                        tok.any { it.isDigit() } &&
                        tok.contains("-")
                }
            if (candidate != null) {
                ConfidenceField(
                    value = candidate.uppercase(),
                    confidence = (0.55f * baseOcr * qFactor).coerceIn(0.1f, 0.99f),
                    source = "ocr_token_heuristic",
                )
            } else {
                ConfidenceField<String>(value = null, confidence = 0.0f, source = "not_found")
            }
        }

        // 3. Serial Number
        val serialMatch = SERIAL_REGEX.find(rawText)
        val serialField = if (serialMatch != null) {
            ConfidenceField(
                value = serialMatch.groupValues[1].uppercase(),
                confidence = (0.91f * baseOcr * qFactor).coerceIn(0.1f, 0.99f),
                source = "ocr+label_regex",
            )
        } else {
            val candidate = lines
                .flatMap { it.split(Regex("\\s+")) }
                .firstOrNull { tok ->
                    tok.length in 8..16 &&
                        tok.all { it.isLetterOrDigit() } &&
                        tok.count { it.isDigit() } >= 5 &&
                        tok.uppercase() != modelField.value
                }
            if (candidate != null) {
                ConfidenceField(
                    value = candidate.uppercase(),
                    confidence = (0.48f * baseOcr * qFactor).coerceIn(0.1f, 0.99f),
                    source = "ocr_token_heuristic",
                )
            } else {
                ConfidenceField<String>(value = null, confidence = 0.0f, source = "not_found")
            }
        }

        // 4. Manufacture Date
        val labeledDate = MFG_DATE_LABELED_REGEX.find(rawText)
        val genericDate = GENERIC_DATE_REGEX.find(rawText)
        val dateField = when {
            labeledDate != null -> ConfidenceField(
                value = normalizeDate(labeledDate.groupValues[1]),
                confidence = (0.90f * baseOcr * qFactor).coerceIn(0.1f, 0.99f),
                source = "ocr+label_regex",
            )
            genericDate != null -> ConfidenceField(
                value = normalizeDate(genericDate.groupValues[1]),
                confidence = (0.65f * baseOcr * qFactor).coerceIn(0.1f, 0.99f),
                source = "ocr_date_regex",
            )
            else -> ConfidenceField<String>(value = null, confidence = 0.0f, source = "not_found")
        }

        return NameplateData(
            make = makeField,
            model = modelField,
            serialNumber = serialField,
            manufactureDate = dateField,
            rawOcrText = rawText.trim(),
        )
    }

    private fun normalizeDate(raw: String): String {
        val trimmed = raw.trim()
        // Convert MM/YYYY or MM-YYYY -> YYYY-MM
        val mmYyyy = Regex("""^(\d{1,2})[-/](\d{4})$""").find(trimmed)
        if (mmYyyy != null) {
            val m = mmYyyy.groupValues[1].padStart(2, '0')
            val y = mmYyyy.groupValues[2]
            return "$y-$m"
        }
        return trimmed.replace('/', '-')
    }
}
