package com.fieldcapture.app.extraction

import android.graphics.Bitmap
import android.util.Base64
import com.fieldcapture.app.BuildConfig
import com.fieldcapture.app.data.model.ConfidenceField
import com.fieldcapture.app.data.model.DamageData
import com.fieldcapture.app.data.model.NameplateData
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Optional free-tier Gemini 2.5 Flash Vision client.
 * Returns null immediately if no API key is configured, keeping the app 100% functional offline.
 */
class GeminiVisionClient(
    private val apiKeyProvider: () -> String = { BuildConfig.GEMINI_API_KEY },
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    fun isConfigured(): Boolean = apiKeyProvider().isNotBlank()

    suspend fun extractNameplate(bitmap: Bitmap): NameplateData? {
        val key = apiKeyProvider().trim()
        if (key.isEmpty()) return null

        val prompt = """
            Extract appliance nameplate fields from this image as strict JSON:
            {"make": {"value": str|null, "confidence": 0..1},
             "model": {"value": str|null, "confidence": 0..1},
             "serial_number": {"value": str|null, "confidence": 0..1},
             "manufacture_date": {"value": str|null, "confidence": 0..1}}
        """.trimIndent()

        val text = callGeminiVision(key, bitmap, prompt) ?: return null
        val obj = parseJsonObject(text) ?: return null

        return NameplateData(
            make = obj.toConfidenceField("make", "gemini_flash"),
            model = obj.toConfidenceField("model", "gemini_flash"),
            serialNumber = obj.toConfidenceField("serial_number", "gemini_flash"),
            manufactureDate = obj.toConfidenceField("manufacture_date", "gemini_flash"),
            rawOcrText = text,
        )
    }

    suspend fun analyzeDamage(bitmap: Bitmap, room: String, surface: String): DamageData? {
        val key = apiKeyProvider().trim()
        if (key.isEmpty()) return null

        val prompt = """
            Analyze water/property damage in this photo. Tech stated room="$room", surface="$surface".
            Return strict JSON:
            {"damage_type": {"value": str, "confidence": 0..1},
             "rough_size": {"value": str, "confidence": 0..1},
             "summary": str}
        """.trimIndent()

        val text = callGeminiVision(key, bitmap, prompt) ?: return null
        val obj = parseJsonObject(text) ?: return null

        return DamageData(
            room = ConfidenceField(room, if (room != "unspecified") 0.92f else 0.3f, "voice_command"),
            surface = ConfidenceField(surface, if (surface != "unspecified") 0.90f else 0.3f, "voice_command"),
            damageType = obj.toConfidenceField("damage_type", "gemini_flash"),
            roughSize = obj.toConfidenceField("rough_size", "gemini_flash"),
            summary = obj["summary"]?.jsonPrimitive?.content ?: "$room $surface damage",
        )
    }

    private suspend fun callGeminiVision(apiKey: String, bitmap: Bitmap, prompt: String): String? =
        withContext(Dispatchers.IO) {
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)
            val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

            val payload = """
                {
                  "contents": [{
                    "parts": [
                      {"text": ${Json.encodeToString(kotlinx.serialization.serializer<String>(), prompt)}},
                      {"inline_data": {"mime_type": "image/jpeg", "data": "$b64"}}
                    ]
                  }],
                  "generationConfig": {"responseMimeType": "application/json", "temperature": 0.1}
                }
            """.trimIndent()

            val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"
            val req = Request.Builder()
                .url(url)
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string() ?: return@withContext null
                val root = json.parseToJsonElement(body).jsonObject
                root["candidates"]?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("content")?.jsonObject
                    ?.get("parts")?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("text")?.jsonPrimitive?.content
            }
        }

    private fun parseJsonObject(raw: String): JsonObject? = runCatching {
        val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        json.parseToJsonElement(cleaned).jsonObject
    }.getOrNull()

    private fun JsonObject.toConfidenceField(key: String, source: String): ConfidenceField<String> {
        val child = this[key]?.jsonObject ?: return ConfidenceField(null, 0f, source)
        val valStr = child["value"]?.jsonPrimitive?.content?.takeIf { it != "null" && it.isNotBlank() }
        val conf = child["confidence"]?.jsonPrimitive?.floatOrNull ?: if (valStr != null) 0.8f else 0.1f
        return ConfidenceField(valStr, conf.coerceIn(0f, 1f), source)
    }
}
