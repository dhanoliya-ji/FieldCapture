package com.fieldcapture.app.eval

import com.fieldcapture.app.data.model.CaptureType
import com.fieldcapture.app.data.model.QualityMetrics
import com.fieldcapture.app.extraction.MonocularSizeEstimator
import com.fieldcapture.app.extraction.NameplateParser
import com.fieldcapture.app.quality.ImageQualityAnalyzer
import kotlin.math.abs

data class EvalSample(
    val id: String,
    val captureType: CaptureType,
    val isLowLight: Boolean,
    val pixels: IntArray,
    val width: Int,
    val height: Int,
    val expectedQualityPass: Boolean,
    val ocrTextForEval: String = "",
    val expectedMake: String? = null,
    val expectedModel: String? = null,
    val expectedSerial: String? = null,
    val expectedManufactureDate: String? = null,
    val tapeWidthIn: Float? = null,
    val tapeHeightIn: Float? = null,
)

data class EvalFailureCase(
    val sampleId: String,
    val field: String,
    val expected: String,
    val predicted: String,
    val confidence: Float,
    val rootCause: String,
)

data class EvalReportSummary(
    val totalFrames: Int,
    val nameplateFrames: Int,
    val lowLightFrames: Int,
    val qualityGateAccuracy: Float,
    val makeAccuracy: Float,
    val modelAccuracy: Float,
    val serialAccuracy: Float,
    val manufactureDateAccuracy: Float,
    val avgConfidenceCorrect: Float,
    val avgConfidenceWrong: Float,
    val meanSizeErrorPercent: Float?,
    val worstFailures: List<EvalFailureCase>,
)

object EvaluationEngine {

    fun evaluate(samples: List<EvalSample>): EvalReportSummary {
        var qualityCorrect = 0
        var npTotal = 0
        var makeOk = 0
        var modelOk = 0
        var serialOk = 0
        var dateOk = 0

        val correctConfs = mutableListOf<Float>()
        val wrongConfs = mutableListOf<Float>()
        val sizeErrors = mutableListOf<Float>()
        val failures = mutableListOf<EvalFailureCase>()

        for (s in samples) {
            val q: QualityMetrics = ImageQualityAnalyzer.analyzePixels(s.pixels, s.width, s.height)
            if (q.passed == s.expectedQualityPass) {
                qualityCorrect++
            } else {
                failures += EvalFailureCase(
                    sampleId = s.id,
                    field = "quality_gate",
                    expected = if (s.expectedQualityPass) "PASS" else "FAIL",
                    predicted = if (q.passed) "PASS" else "FAIL(${q.failureReason})",
                    confidence = 1.0f,
                    rootCause = "Blur=${"%.1f".format(q.blurScore)}, Luma=${"%.1f".format(q.meanLuminance)}, BorderEdge=${"%.2f".format(q.borderEdgeRatio)}",
                )
            }

            if (s.captureType == CaptureType.NAMEPLATE) {
                npTotal++
                val parsed = NameplateParser.parse(s.ocrTextForEval, quality = q, ocrConfidence = 0.85f)

                fun checkField(fieldName: String, expected: String?, predicted: String?, conf: Float): Boolean {
                    val match = expected.orEmpty().equals(predicted.orEmpty(), ignoreCase = true)
                    if (match) {
                        correctConfs += conf
                    } else {
                        wrongConfs += conf
                        failures += EvalFailureCase(
                            sampleId = s.id,
                            field = fieldName,
                            expected = expected ?: "<null>",
                            predicted = predicted ?: "<null>",
                            confidence = conf,
                            rootCause = when {
                                !q.passed -> "Degraded frame (${q.failureReason})"
                                predicted == null -> "Label/regex missed in OCR text"
                                else -> "OCR/token mismatch"
                            },
                        )
                    }
                    return match
                }

                if (checkField("make", s.expectedMake, parsed.make.value, parsed.make.confidence)) makeOk++
                if (checkField("model", s.expectedModel, parsed.model.value, parsed.model.confidence)) modelOk++
                if (checkField("serialNumber", s.expectedSerial, parsed.serialNumber.value, parsed.serialNumber.confidence)) serialOk++
                if (checkField("manufactureDate", s.expectedManufactureDate, parsed.manufactureDate.value, parsed.manufactureDate.confidence)) dateOk++
            }

            if (s.tapeWidthIn != null && s.tapeHeightIn != null) {
                val est = MonocularSizeEstimator.estimateFromPixels(s.pixels, s.width, s.height)
                sizeErrors += MonocularSizeEstimator.computeAreaErrorPercent(est.areaSqFt, s.tapeWidthIn, s.tapeHeightIn)
            }
        }

        val npDenom = npTotal.coerceAtLeast(1).toFloat()
        val worst5 = failures
            .sortedByDescending { abs(it.confidence) }
            .take(5)

        return EvalReportSummary(
            totalFrames = samples.size,
            nameplateFrames = npTotal,
            lowLightFrames = samples.count { it.isLowLight },
            qualityGateAccuracy = if (samples.isEmpty()) 0f else qualityCorrect.toFloat() / samples.size,
            makeAccuracy = makeOk / npDenom,
            modelAccuracy = modelOk / npDenom,
            serialAccuracy = serialOk / npDenom,
            manufactureDateAccuracy = dateOk / npDenom,
            avgConfidenceCorrect = if (correctConfs.isEmpty()) 0f else correctConfs.average().toFloat(),
            avgConfidenceWrong = if (wrongConfs.isEmpty()) 0f else wrongConfs.average().toFloat(),
            meanSizeErrorPercent = if (sizeErrors.isEmpty()) null else sizeErrors.average().toFloat(),
            worstFailures = worst5,
        )
    }
}
