package com.fieldcapture.app.eval

import android.content.Context
import android.graphics.BitmapFactory
import com.fieldcapture.app.data.model.CaptureType
import com.fieldcapture.app.extraction.MonocularSizeEstimator
import com.fieldcapture.app.extraction.NameplateExtractor
import com.fieldcapture.app.quality.ImageQualityAnalyzer
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class EvalLabelItem(
    val filename: String,
    val captureType: String = "NAMEPLATE", // NAMEPLATE or DAMAGE
    val isLowLight: Boolean = false,
    val expectedQualityPass: Boolean = true,
    val expectedMake: String? = null,
    val expectedModel: String? = null,
    val expectedSerial: String? = null,
    val expectedManufactureDate: String? = null,
    val tapeWidthIn: Float? = null,
    val tapeHeightIn: Float? = null,
)

/**
 * Runs real on-device evaluation (ImageQualityAnalyzer + ML Kit OCR + NameplateParser +
 * MonocularSizeEstimator) against real captured images placed in:
 * /sdcard/Android/data/com.fieldcapture.app/files/eval/
 * accompanied by labels.json (List<EvalLabelItem>).
 */
object RealImageEvalRunner {

    private val json = Json { ignoreUnknownKeys = true }

    fun evalDirectory(context: Context): File {
        return File(context.getExternalFilesDir(null), "eval").apply { mkdirs() }
    }

    suspend fun runFromDirectory(context: Context): Result<EvalReportSummary> = withContext(Dispatchers.IO) {
        val dir = evalDirectory(context)
        val labelsFile = File(dir, "labels.json")
        if (!labelsFile.exists()) {
            return@withContext Result.failure(
                IllegalStateException("Missing labels.json in ${dir.absolutePath}"),
            )
        }

        val labels = runCatching {
            json.decodeFromString<List<EvalLabelItem>>(labelsFile.readText())
        }.getOrElse { e ->
            return@withContext Result.failure(IllegalStateException("Invalid labels.json: ${e.message}"))
        }

        if (labels.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("labels.json is empty"))
        }

        val extractor = NameplateExtractor()
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
        var processed = 0

        for (item in labels) {
            val imgFile = File(dir, item.filename)
            val bitmap = if (imgFile.exists()) BitmapFactory.decodeFile(imgFile.absolutePath) else null
            if (bitmap == null) {
                failures += EvalFailureCase(
                    sampleId = item.filename,
                    field = "file_load",
                    expected = "valid image",
                    predicted = "missing/unreadable",
                    confidence = 1.0f,
                    rootCause = "File not found in ${dir.absolutePath}",
                )
                continue
            }
            processed++
            val q = ImageQualityAnalyzer.analyze(bitmap)
            if (q.passed == item.expectedQualityPass) {
                qualityCorrect++
            } else {
                failures += EvalFailureCase(
                    sampleId = item.filename,
                    field = "quality_gate",
                    expected = if (item.expectedQualityPass) "PASS" else "FAIL",
                    predicted = if (q.passed) "PASS" else "FAIL(${q.failureReason})",
                    confidence = 1.0f,
                    rootCause = "Blur=${"%.1f".format(q.blurScore)}, Luma=${"%.1f".format(q.meanLuminance)}, BorderEdge=${"%.2f".format(q.borderEdgeRatio)}",
                )
            }

            val type = runCatching { CaptureType.valueOf(item.captureType.uppercase()) }
                .getOrDefault(CaptureType.NAMEPLATE)

            if (type == CaptureType.NAMEPLATE) {
                npTotal++
                val np = extractor.extract(bitmap, q)

                fun check(field: String, expected: String?, predicted: String?, conf: Float): Boolean {
                    val ok = expected.orEmpty().equals(predicted.orEmpty(), ignoreCase = true)
                    if (ok) {
                        correctConfs += conf
                    } else {
                        wrongConfs += conf
                        failures += EvalFailureCase(
                            sampleId = item.filename,
                            field = field,
                            expected = expected ?: "<null>",
                            predicted = predicted ?: "<null>",
                            confidence = conf,
                            rootCause = when {
                                !q.passed -> "Degraded frame (${q.failureReason})"
                                np.rawOcrText.isBlank() -> "ML Kit OCR returned empty text"
                                predicted == null -> "Parser missed field in OCR text"
                                else -> "OCR character/token mismatch"
                            },
                        )
                    }
                    return ok
                }

                if (check("make", item.expectedMake, np.make.value, np.make.confidence)) makeOk++
                if (check("model", item.expectedModel, np.model.value, np.model.confidence)) modelOk++
                if (check("serialNumber", item.expectedSerial, np.serialNumber.value, np.serialNumber.confidence)) serialOk++
                if (check("manufactureDate", item.expectedManufactureDate, np.manufactureDate.value, np.manufactureDate.confidence)) dateOk++
            }

            if (item.tapeWidthIn != null && item.tapeHeightIn != null) {
                val est = MonocularSizeEstimator.estimateFromBitmap(bitmap)
                sizeErrors += MonocularSizeEstimator.computeAreaErrorPercent(
                    est.areaSqFt,
                    item.tapeWidthIn,
                    item.tapeHeightIn,
                )
            }
            bitmap.recycle()
        }

        val npDenom = npTotal.coerceAtLeast(1).toFloat()
        val summary = EvalReportSummary(
            totalFrames = processed,
            nameplateFrames = npTotal,
            lowLightFrames = labels.count { it.isLowLight },
            qualityGateAccuracy = if (processed == 0) 0f else qualityCorrect.toFloat() / processed,
            makeAccuracy = makeOk / npDenom,
            modelAccuracy = modelOk / npDenom,
            serialAccuracy = serialOk / npDenom,
            manufactureDateAccuracy = dateOk / npDenom,
            avgConfidenceCorrect = if (correctConfs.isEmpty()) 0f else correctConfs.average().toFloat(),
            avgConfidenceWrong = if (wrongConfs.isEmpty()) 0f else wrongConfs.average().toFloat(),
            meanSizeErrorPercent = if (sizeErrors.isEmpty()) null else sizeErrors.average().toFloat(),
            worstFailures = failures.sortedByDescending { abs(it.confidence) }.take(5),
        )

        File(dir, "eval_report_output.txt").writeText(formatSummaryText(summary))
        Result.success(summary)
    }

    private fun formatSummaryText(s: EvalReportSummary): String = buildString {
        appendLine("Total Frames: ${s.totalFrames}")
        appendLine("Nameplate Frames: ${s.nameplateFrames}")
        appendLine("Low-Light Frames: ${s.lowLightFrames}")
        appendLine("Quality Gate Accuracy: ${"%.1f".format(s.qualityGateAccuracy * 100)}%")
        appendLine("Make Accuracy: ${"%.1f".format(s.makeAccuracy * 100)}%")
        appendLine("Model Accuracy: ${"%.1f".format(s.modelAccuracy * 100)}%")
        appendLine("Serial Accuracy: ${"%.1f".format(s.serialAccuracy * 100)}%")
        appendLine("Mfg Date Accuracy: ${"%.1f".format(s.manufactureDateAccuracy * 100)}%")
        appendLine("Avg Confidence (Correct): ${"%.2f".format(s.avgConfidenceCorrect)}")
        appendLine("Avg Confidence (Wrong): ${"%.2f".format(s.avgConfidenceWrong)}")
        s.meanSizeErrorPercent?.let { appendLine("Mean Size Error: ${"%.1f".format(it)}%") }
        appendLine("\nTop 5 Worst Failures:")
        s.worstFailures.forEachIndexed { idx, f ->
            appendLine("${idx + 1}. ${f.sampleId} [${f.field}] expected=${f.expected} got=${f.predicted} (conf=${"%.2f".format(f.confidence)}) -> ${f.rootCause}")
        }
    }
}
