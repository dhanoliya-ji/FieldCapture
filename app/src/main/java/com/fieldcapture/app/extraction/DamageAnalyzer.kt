package com.fieldcapture.app.extraction

import android.graphics.Bitmap
import com.fieldcapture.app.data.model.ConfidenceField
import com.fieldcapture.app.data.model.DamageData
import com.fieldcapture.app.data.model.QualityMetrics

/**
 * Extracts structured damage observations from a capture bitmap + voice-command room/surface.
 * Uses optional Gemini Vision when configured, otherwise deterministic visual + size heuristics.
 */
class DamageAnalyzer(
    private val geminiClient: GeminiVisionClient = GeminiVisionClient(),
) {
    suspend fun analyze(
        bitmap: Bitmap,
        voiceRoom: String,
        voiceSurface: String,
        quality: QualityMetrics,
    ): DamageData {
        val sizeEstimate = MonocularSizeEstimator.estimateFromBitmap(bitmap)

        val geminiDamage = runCatching {
            geminiClient.analyzeDamage(bitmap, voiceRoom, voiceSurface)
        }.getOrNull()

        if (geminiDamage != null) {
            return geminiDamage.copy(sizeEstimate = sizeEstimate)
        }

        val qFactor = if (quality.passed) 1.0f else 0.6f
        val detectedType = inferDamageTypeFromBitmap(bitmap)

        val roomConf = if (voiceRoom != "unspecified") 0.92f else 0.25f
        val surfaceConf = if (voiceSurface != "unspecified") 0.90f else 0.25f
        val roughSizeText = "${sizeEstimate.widthInches}\" x ${sizeEstimate.heightInches}\" (~${sizeEstimate.areaSqFt} sq ft)"

        return DamageData(
            room = ConfidenceField(voiceRoom, roomConf, "voice_command"),
            surface = ConfidenceField(voiceSurface, surfaceConf, "voice_command"),
            damageType = ConfidenceField(detectedType.first, detectedType.second * qFactor, "on_device_color_texture"),
            roughSize = ConfidenceField(roughSizeText, sizeEstimate.confidence, sizeEstimate.method.name),
            sizeEstimate = sizeEstimate,
            summary = "${detectedType.first} on $voiceSurface in $voiceRoom ($roughSizeText)",
        )
    }

    private fun inferDamageTypeFromBitmap(bitmap: Bitmap): Pair<String, Float> {
        val w = minOf(64, bitmap.width)
        val h = minOf(64, bitmap.height)
        val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        if (scaled !== bitmap) scaled.recycle()

        var warmPixels = 0
        var veryDarkPixels = 0
        for (c in pixels) {
            val r = (c ushr 16) and 0xFF
            val g = (c ushr 8) and 0xFF
            val b = c and 0xFF
            val luma = 0.299 * r + 0.587 * g + 0.114 * b
            if (r - b > 22 && luma in 55.0..210.0) warmPixels++
            if (luma < 50.0) veryDarkPixels++
        }
        val warmRatio = warmPixels.toFloat() / pixels.size
        val darkRatio = veryDarkPixels.toFloat() / pixels.size

        return when {
            darkRatio > 0.22f && warmRatio < 0.15f -> "potential_mold_or_dark_staining" to 0.72f
            warmRatio > 0.18f -> "water_stain_discoloration" to 0.78f
            else -> "moisture_or_surface_damage" to 0.64f
        }
    }
}
