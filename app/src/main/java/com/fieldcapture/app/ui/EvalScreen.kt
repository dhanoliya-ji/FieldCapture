package com.fieldcapture.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fieldcapture.app.eval.EvalReportSummary
import com.fieldcapture.app.eval.RealImageEvalRunner
import kotlinx.coroutines.launch

@Composable
fun EvalScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val evalDirPath = remember { RealImageEvalRunner.evalDirectory(context).absolutePath }

    var isRunning by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf<EvalReportSummary?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("40+ Frame Evaluation Runner", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Push your captured .jpg/.png frames and labels.json to:\n$evalDirPath",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(
                        onClick = {
                            if (isRunning) return@Button
                            isRunning = true
                            errorMsg = null
                            scope.launch {
                                val res = RealImageEvalRunner.runFromDirectory(context)
                                res.onSuccess { summary = it }
                                    .onFailure { errorMsg = it.message }
                                isRunning = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isRunning,
                    ) {
                        Text(if (isRunning) "Running ML Kit + Quality Eval..." else "Run Evaluation on Real Captures")
                    }
                    if (errorMsg != null) {
                        Text(errorMsg!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        summary?.let { s ->
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Results (${s.totalFrames} frames)", style = MaterialTheme.typography.titleMedium)
                        Text("Nameplates: ${s.nameplateFrames} | Low-light: ${s.lowLightFrames}")
                        Text("Quality Gate Accuracy: ${"%.1f".format(s.qualityGateAccuracy * 100)}%")
                        Text("Make: ${"%.1f".format(s.makeAccuracy * 100)}% | Model: ${"%.1f".format(s.modelAccuracy * 100)}%")
                        Text("Serial: ${"%.1f".format(s.serialAccuracy * 100)}% | Mfg Date: ${"%.1f".format(s.manufactureDateAccuracy * 100)}%")
                        Text(
                            "Confidence Calibration — Correct: ${"%.2f".format(s.avgConfidenceCorrect)} vs Wrong: ${"%.2f".format(s.avgConfidenceWrong)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        s.meanSizeErrorPercent?.let {
                            Text("Mean Monocular Area Error: ${"%.1f".format(it)}%", color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
            }

            if (s.worstFailures.isNotEmpty()) {
                item { Text("Top 5 Worst Failures", style = MaterialTheme.typography.titleMedium) }
                itemsIndexed(s.worstFailures) { idx, f ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("${idx + 1}. ${f.sampleId} [${f.field}]", style = MaterialTheme.typography.labelLarge)
                            Text("Expected: ${f.expected} | Got: ${f.predicted} (conf=${"%.2f".format(f.confidence)})", style = MaterialTheme.typography.bodySmall)
                            Text("Cause: ${f.rootCause}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}
