package com.fieldcapture.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ConfidenceField<T>(
    val value: T? = null,
    val confidence: Float = 0f,
    val source: String = "unknown",
)

@Serializable
enum class CaptureType {
    NAMEPLATE,
    DAMAGE,
}

@Serializable
enum class UploadStatus {
    PENDING,
    UPLOADING,
    SYNCED,
    FAILED,
}

@Serializable
enum class QualityFailureReason {
    NONE,
    BLURRY,
    TOO_DARK,
    OVEREXPOSED,
    BAD_FRAMING,
}

@Serializable
data class QualityMetrics(
    val passed: Boolean,
    val blurScore: Double,
    val meanLuminance: Double,
    val darkPixelRatio: Double,
    val brightPixelRatio: Double,
    val borderEdgeRatio: Double,
    val centerContrastRatio: Double,
    val failureReason: QualityFailureReason = QualityFailureReason.NONE,
    val retakePrompt: String? = null,
)

@Serializable
data class NameplateData(
    val make: ConfidenceField<String> = ConfidenceField(),
    val model: ConfidenceField<String> = ConfidenceField(),
    val serialNumber: ConfidenceField<String> = ConfidenceField(),
    val manufactureDate: ConfidenceField<String> = ConfidenceField(),
    val rawOcrText: String = "",
)

@Serializable
enum class SizeEstimationMethod {
    REFERENCE_SCALE,
    FOV_DISTANCE_PRIOR,
    MANUAL_TAPE,
}

@Serializable
data class SizeEstimate(
    val widthInches: Float,
    val heightInches: Float,
    val areaSqFt: Float,
    val method: SizeEstimationMethod,
    val confidence: Float,
    val referenceUsed: String = "",
    val tapeWidthInches: Float? = null,
    val tapeHeightInches: Float? = null,
)

@Serializable
data class DamageData(
    val room: ConfidenceField<String> = ConfidenceField(),
    val surface: ConfidenceField<String> = ConfidenceField(),
    val damageType: ConfidenceField<String> = ConfidenceField(),
    val roughSize: ConfidenceField<String> = ConfidenceField(),
    val sizeEstimate: SizeEstimate? = null,
    val summary: String = "",
)

@Serializable
data class CaptureEvidence(
    val id: String,
    val captureType: CaptureType,
    val timestampIso: String,
    val imagePath: String,
    val qualityMetrics: QualityMetrics,
    val nameplateData: NameplateData? = null,
    val damageData: DamageData? = null,
    val uploadStatus: UploadStatus = UploadStatus.PENDING,
    val retryCount: Int = 0,
    val forceAccepted: Boolean = false,
)

@Serializable
data class StructuredNote(
    val id: String,
    val timestampIso: String,
    val rawText: String,
    val location: ConfidenceField<String> = ConfidenceField(),
    val component: ConfidenceField<String> = ConfidenceField(),
    val condition: ConfidenceField<String> = ConfidenceField(),
    val severity: ConfidenceField<String> = ConfidenceField(),
    val actionHint: ConfidenceField<String> = ConfidenceField(),
)

@Serializable
data class EstimateLineItem(
    val code: String,
    val category: String,
    val description: String,
    val unit: String,
    val quantity: Float,
    val unitRateUsd: Float,
    val estimatedTotalUsd: Float,
    val confidence: Float,
    val rationale: String,
    val sourceIds: List<String> = emptyList(),
)

@Serializable
data class SessionTelemetry(
    val sdkVersion: String = "mwdat-1.1.0",
    val deviceMode: String = "MOCK_DEVICE_KIT",
    val audioSource: String = "PHONE_MIC_FALLBACK",
    val audioFallbackReason: String = "",
    val disconnectCount: Int = 0,
    val recoveryCount: Int = 0,
    val retakeCount: Int = 0,
)

@Serializable
data class JobRecord(
    val schemaVersion: String = "1.0.0",
    val jobId: String,
    val createdAtIso: String,
    val updatedAtIso: String,
    val status: String = "DRAFT",
    val sessionTelemetry: SessionTelemetry = SessionTelemetry(),
    val captures: List<CaptureEvidence> = emptyList(),
    val notes: List<StructuredNote> = emptyList(),
    val lineItems: List<EstimateLineItem> = emptyList(),
    val estimatedSubtotalUsd: Float = 0f,
    val pendingUploadCount: Int = 0,
)
