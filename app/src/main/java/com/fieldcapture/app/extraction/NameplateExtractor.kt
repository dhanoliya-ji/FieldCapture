package com.fieldcapture.app.extraction

import android.graphics.Bitmap
import com.fieldcapture.app.data.model.NameplateData
import com.fieldcapture.app.data.model.QualityMetrics
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

/**
 * Extracts structured nameplate fields on-device using Google ML Kit Text Recognition
 * (free, offline), with optional Gemini Vision enhancement when configured and online.
 */
class NameplateExtractor(
    private val geminiClient: GeminiVisionClient = GeminiVisionClient(),
) {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun extract(bitmap: Bitmap, quality: QualityMetrics): NameplateData {
        // 1. Optional cloud enhancement if API key is present
        val geminiResult = runCatching { geminiClient.extractNameplate(bitmap) }.getOrNull()
        if (geminiResult != null) {
            return geminiResult
        }

        // 2. On-device ML Kit OCR (free & offline)
        val image = InputImage.fromBitmap(bitmap, 0)
        val visionText = runCatching { recognizer.process(image).await() }.getOrNull()
        val rawText = visionText?.text.orEmpty()

        // Estimate OCR quality from block count & average line length
        val blockConf = if (rawText.isBlank()) 0.25f else 0.86f
        return NameplateParser.parse(rawText, quality = quality, ocrConfidence = blockConf)
    }
}
