package com.fieldcapture.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fieldcapture.app.data.model.CaptureType
import com.fieldcapture.app.data.model.JobRecord
import com.fieldcapture.app.extraction.MonocularSizeEstimator

@Composable
fun JobSummaryScreen(
    job: JobRecord,
    lastExportPath: String?,
    onExportJson: () -> Unit,
    onNewJob: () -> Unit,
    onTapeComparison: (captureId: String, tapeW: Float, tapeH: Float) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Job #${job.jobId}", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Captures: ${job.captures.size} | Notes: ${job.notes.size} | Line Items: ${job.lineItems.size}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Draft Subtotal: $${"%.2f".format(job.estimatedSubtotalUsd)}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    Text(
                        "Disconnects: ${job.sessionTelemetry.disconnectCount} | Recoveries: ${job.sessionTelemetry.recoveryCount} | Retakes: ${job.sessionTelemetry.retakeCount}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = onExportJson, modifier = Modifier.weight(1f)) {
                            Text("Export JSON")
                        }
                        OutlinedButton(onClick = onNewJob, modifier = Modifier.weight(1f)) {
                            Text("New Job")
                        }
                    }
                    if (lastExportPath != null) {
                        Text("Saved: $lastExportPath", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        if (job.captures.isEmpty() && job.notes.isEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("No captures yet", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Go to Walkthrough -> tap Start -> use Nameplate, Damage, or Note (or speak hands-free) to populate this job record and draft estimate.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        if (job.lineItems.isNotEmpty()) {
            item { Text("Draft Estimate Line Items", style = MaterialTheme.typography.titleMedium) }
            items(job.lineItems, key = { it.code }) { item ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${item.code} • ${item.category}", style = MaterialTheme.typography.labelLarge)
                            Text("$${"%.2f".format(item.estimatedTotalUsd)}", color = MaterialTheme.colorScheme.secondary)
                        }
                        Text(item.description, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Qty: ${item.quantity} ${item.unit} @ $${item.unitRateUsd} | Conf: ${(item.confidence * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(item.rationale, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        if (job.captures.isNotEmpty()) {
            item { Text("Captured Evidence (${job.captures.size})", style = MaterialTheme.typography.titleMedium) }
            items(job.captures, key = { it.id }) { cap ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${cap.captureType} #${cap.id}", style = MaterialTheme.typography.labelLarge)
                            Text("Upload: ${cap.uploadStatus}", style = MaterialTheme.typography.labelMedium)
                        }
                        Text(
                            "Quality: ${if (cap.qualityMetrics.passed) "PASS" else "FORCED (${cap.qualityMetrics.failureReason})"} | Blur=${"%.0f".format(cap.qualityMetrics.blurScore)} Luma=${"%.0f".format(cap.qualityMetrics.meanLuminance)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (cap.captureType == CaptureType.NAMEPLATE && cap.nameplateData != null) {
                            val np = cap.nameplateData
                            Text("Make: ${np.make.value ?: "—"} (${(np.make.confidence * 100).toInt()}%)")
                            Text("Model: ${np.model.value ?: "—"} (${(np.model.confidence * 100).toInt()}%)")
                            Text("Serial: ${np.serialNumber.value ?: "—"} (${(np.serialNumber.confidence * 100).toInt()}%)")
                            Text("Mfg Date: ${np.manufactureDate.value ?: "—"} (${(np.manufactureDate.confidence * 100).toInt()}%)")
                        }
                        if (cap.captureType == CaptureType.DAMAGE && cap.damageData != null) {
                            val dmg = cap.damageData
                            Text("Room: ${dmg.room.value} (${(dmg.room.confidence * 100).toInt()}%) | Surface: ${dmg.surface.value} (${(dmg.surface.confidence * 100).toInt()}%)")
                            Text("Type: ${dmg.damageType.value} (${(dmg.damageType.confidence * 100).toInt()}%)")
                            val sz = dmg.sizeEstimate
                            if (sz != null) {
                                Text("Est. Size: ${sz.widthInches}\" x ${sz.heightInches}\" (${sz.areaSqFt} sq ft) [${sz.method}]")
                                var tapeW by remember(cap.id) { mutableStateOf(sz.tapeWidthInches?.toString() ?: "") }
                                var tapeH by remember(cap.id) { mutableStateOf(sz.tapeHeightInches?.toString() ?: "") }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OutlinedTextField(
                                        value = tapeW,
                                        onValueChange = { tapeW = it },
                                        label = { Text("Tape W\"") },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true,
                                    )
                                    OutlinedTextField(
                                        value = tapeH,
                                        onValueChange = { tapeH = it },
                                        label = { Text("Tape H\"") },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true,
                                    )
                                    Button(
                                        onClick = {
                                            val w = tapeW.toFloatOrNull()
                                            val h = tapeH.toFloatOrNull()
                                            if (w != null && h != null && w > 0f && h > 0f) {
                                                onTapeComparison(cap.id, w, h)
                                            }
                                        },
                                    ) { Text("Compare") }
                                }
                                if (sz.tapeWidthInches != null && sz.tapeHeightInches != null) {
                                    val err = MonocularSizeEstimator.computeAreaErrorPercent(
                                        sz.areaSqFt,
                                        sz.tapeWidthInches,
                                        sz.tapeHeightInches,
                                    )
                                    Text(
                                        "Tape Ground Truth: ${sz.tapeWidthInches}\" x ${sz.tapeHeightInches}\" -> Area Error: ${"%.1f".format(err)}%",
                                        color = MaterialTheme.colorScheme.secondary,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (job.notes.isNotEmpty()) {
            item { Text("Structured Notes (${job.notes.size})", style = MaterialTheme.typography.titleMedium) }
            items(job.notes, key = { it.id }) { note ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("\"${note.rawText}\"", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Component: ${note.component.value ?: "—"} | Condition: ${note.condition.value ?: "—"} | Severity: ${note.severity.value ?: "—"}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text("Action: ${note.actionHint.value ?: "—"}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
