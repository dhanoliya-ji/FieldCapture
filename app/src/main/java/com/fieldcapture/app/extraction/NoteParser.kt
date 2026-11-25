package com.fieldcapture.app.extraction

import com.fieldcapture.app.data.model.ConfidenceField
import com.fieldcapture.app.data.model.StructuredNote
import java.time.Instant
import java.util.UUID

/**
 * Parses free-text voice notes (e.g., "subfloor soft by the cabinet") into structured fields
 * with calibrated confidence scores.
 */
object NoteParser {

    private val COMPONENTS = listOf(
        "subfloor", "drywall", "ceiling", "wall", "baseboard", "cabinet",
        "vanity", "sink", "water heater", "pipe", "valve", "supply line",
        "insulation", "joist", "flooring", "tile", "carpet", "pan",
    )

    private val CONDITIONS = listOf(
        "soft", "spongy", "wet", "damp", "soaked", "stained", "sagging",
        "warped", "swollen", "moldy", "mildew", "rusted", "corroded",
        "leaking", "dripping", "cracked", "crumbling", "peeling",
    )

    private val LOCATION_PREPOSITIONS = listOf(
        "by the ", "near the ", "under the ", "behind the ", "above the ",
        "next to the ", "around the ", "in the ", "at the ",
    )

    fun parse(rawText: String, id: String = UUID.randomUUID().toString()): StructuredNote {
        val cleaned = rawText.trim()
        val lower = cleaned.lowercase()

        val component = COMPONENTS.firstOrNull { lower.contains(it) }
        val condition = CONDITIONS.firstOrNull { lower.contains(it) }

        val location = LOCATION_PREPOSITIONS.firstNotNullOfOrNull { prep ->
            val idx = lower.indexOf(prep)
            if (idx >= 0) cleaned.substring(idx).trim() else null
        }

        val severity = when {
            listOf("soft", "spongy", "sagging", "moldy", "leaking", "soaked", "crumbling").any { lower.contains(it) } ->
                ConfidenceField("HIGH", 0.88f, "keyword_severity")
            listOf("wet", "damp", "stained", "swollen", "warped", "rusted").any { lower.contains(it) } ->
                ConfidenceField("MODERATE", 0.82f, "keyword_severity")
            condition != null ->
                ConfidenceField("LOW", 0.65f, "keyword_severity")
            else ->
                ConfidenceField("UNKNOWN", 0.30f, "fallback")
        }

        val actionHint = when {
            component == "subfloor" && (condition == "soft" || condition == "spongy") ->
                "Probe moisture & framing; likely remove/replace subfloor"
            lower.contains("leak") || lower.contains("drip") ->
                "Isolate supply valve and inspect plumbing connection"
            lower.contains("mold") || lower.contains("musty") ->
                "Perform containment & antimicrobial treatment"
            component != null && condition != null ->
                "Inspect $component ($condition) and verify with moisture meter"
            else ->
                "Review field note during estimate finalization"
        }

        return StructuredNote(
            id = id,
            timestampIso = Instant.now().toString(),
            rawText = cleaned,
            location = if (location != null) {
                ConfidenceField(location, 0.84f, "preposition_phrase")
            } else {
                ConfidenceField(null, 0.25f, "not_specified")
            },
            component = if (component != null) {
                ConfidenceField(component, 0.90f, "component_dict")
            } else {
                ConfidenceField(null, 0.25f, "not_specified")
            },
            condition = if (condition != null) {
                ConfidenceField(condition, 0.88f, "condition_dict")
            } else {
                ConfidenceField(null, 0.30f, "not_specified")
            },
            severity = severity,
            actionHint = ConfidenceField(actionHint, 0.78f, "rule_engine"),
        )
    }
}
