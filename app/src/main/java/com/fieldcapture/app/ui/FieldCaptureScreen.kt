package com.fieldcapture.app.ui

import android.app.Activity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fieldcapture.app.dat.WalkSessionLifecycle
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState

@Composable
fun FieldCaptureAppRoot(
    viewModel: FieldCaptureViewModel,
    onRequestWearablePermission: suspend (Permission) -> PermissionStatus,
) {
    val state by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    var showMockSheet by remember { mutableStateOf(false) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = {},
                    label = { Text("Walkthrough") },
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = {},
                    label = { Text("Job (${state.jobRecord.captures.size})") },
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = {},
                    label = { Text("Eval") },
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (selectedTab) {
                0 -> WalkthroughTab(
                    state = state,
                    viewModel = viewModel,
                    onOpenMockSheet = { showMockSheet = true },
                    onRequestWearablePermission = onRequestWearablePermission,
                )
                1 -> JobSummaryScreen(
                    job = state.jobRecord,
                    lastExportPath = state.lastExportPath,
                    onExportJson = viewModel::exportJobJson,
                    onNewJob = viewModel::startNewJob,
                    onTapeComparison = viewModel::updateTapeMeasureComparison,
                )
                2 -> EvalScreen()
            }
        }
    }

    if (showMockSheet) {
        MockGlassesSheet(
            state = state.mock,
            onDismiss = { showMockSheet = false },
            onQuickStart = viewModel::quickStartMockGlasses,
            onPowerOn = viewModel::mockPowerOn,
            onPowerOff = viewModel::mockPowerOff,
            onUnfold = viewModel::mockUnfold,
            onFold = viewModel::mockFold,
            onDon = viewModel::mockDon,
            onDoff = viewModel::mockDoff,
            onPhoneCamera = viewModel::mockSetPhoneCamera,
            onVideoUri = viewModel::mockSetVideoUri,
            onImageUri = viewModel::mockSetCapturedImageUri,
            onTap = viewModel::mockTap,
            onTapAndHold = viewModel::mockTapAndHold,
        )
    }
}

@Composable
private fun WalkthroughTab(
    state: FieldCaptureUiState,
    viewModel: FieldCaptureViewModel,
    onOpenMockSheet: () -> Unit,
    onRequestWearablePermission: suspend (Permission) -> PermissionStatus,
) {
    val activity = LocalContext.current as? Activity
    val previewBmp by viewModel.previewBitmap.collectAsState()
    var commandText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Top status bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("FieldCapture • DAT 1.1", style = MaterialTheme.typography.titleLarge)
            OutlinedButton(onClick = onOpenMockSheet) {
                Text("Mock Glasses")
            }
        }

        // Telemetry chips
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            AssistChip(
                onClick = {},
                label = { Text("Session: ${state.dat.lifecycle.name}") },
            )
            AssistChip(
                onClick = viewModel::toggleSimulateOffline,
                label = {
                    val net = if (state.simulateOffline || !state.isOnline) "OFFLINE" else "ONLINE"
                    Text("$net • Queue:${state.pendingUploadCount}")
                },
            )
        }

        Text(
            "Audio: ${state.voice.audioRoute.label} — ${state.voice.audioRoute.reason}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        )

        // Live preview rendered directly as ImageBitmap from uncompressed DAT VideoFrames
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .background(Color.Black, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = previewBmp
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Live Glasses Camera Preview",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text(
                    when (state.dat.lifecycle) {
                        WalkSessionLifecycle.RECOVERING -> "Device disconnected — recovering session..."
                        WalkSessionLifecycle.PAUSED -> "Session paused"
                        WalkSessionLifecycle.STARTING -> "Starting camera stream..."
                        else -> "Tap 'Start' to auto-pair Mock Glasses & stream"
                    },
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        // Banner
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Text(
                text = state.statusBanner,
                modifier = Modifier.fillMaxWidth().padding(10.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        // Retake prompt card if quality check failed
        state.pendingRetake?.let { retake ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.2f))) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Quality Gate: ${retake.quality.failureReason}", style = MaterialTheme.typography.titleSmall)
                    Text(retake.quality.retakePrompt ?: "Please retake", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Blur=${"%.1f".format(retake.quality.blurScore)} (min 110) | Luma=${"%.1f".format(retake.quality.meanLuminance)} (min 48)",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = viewModel::dismissPendingRetake, modifier = Modifier.weight(1f)) {
                            Text("Retake")
                        }
                        OutlinedButton(onClick = viewModel::forceAcceptPendingRetake, modifier = Modifier.weight(1f)) {
                            Text("Keep Anyway")
                        }
                    }
                }
            }
        }

        // Session lifecycle buttons
        if (state.dat.registrationState != RegistrationState.REGISTERED && !state.mock.isEnabled) {
            Button(
                onClick = { activity?.let { viewModel.startRegistration(it) } },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Register App with Meta AI")
            }
        }

        val lifecycle = state.dat.lifecycle
        val canStart = lifecycle == WalkSessionLifecycle.IDLE ||
            lifecycle == WalkSessionLifecycle.STOPPED ||
            lifecycle == WalkSessionLifecycle.ERROR
        val canPauseOrResume = lifecycle == WalkSessionLifecycle.STREAMING ||
            lifecycle == WalkSessionLifecycle.PAUSED
        val canStop = lifecycle == WalkSessionLifecycle.STREAMING ||
            lifecycle == WalkSessionLifecycle.PAUSED ||
            lifecycle == WalkSessionLifecycle.STARTING ||
            lifecycle == WalkSessionLifecycle.RECOVERING

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { viewModel.startWalkthroughSession(onRequestWearablePermission) },
                enabled = canStart,
                modifier = Modifier.weight(1f),
            ) { Text("Start") }
            OutlinedButton(
                onClick = {
                    if (lifecycle == WalkSessionLifecycle.PAUSED) {
                        viewModel.resumeWalkthroughSession(onRequestWearablePermission)
                    } else {
                        viewModel.pauseWalkthroughSession()
                    }
                },
                enabled = canPauseOrResume,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (lifecycle == WalkSessionLifecycle.PAUSED) "Resume" else "Pause")
            }
            Button(
                onClick = viewModel::stopWalkthroughSession,
                enabled = canStop,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.weight(1f),
            ) { Text("Stop") }
        }

        // Hands-free voice listening + quick test commands
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = viewModel::toggleVoiceListening,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (state.voice.isListening) "Listening (Tap to Mute)" else "Start Hands-Free Mic")
            }
            OutlinedButton(onClick = viewModel::syncNow) {
                Text("Sync Queue")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { viewModel.submitVoiceCommandText("capture nameplate") },
                modifier = Modifier.weight(1f),
            ) { Text("Nameplate") }
            OutlinedButton(
                onClick = { viewModel.submitVoiceCommandText("capture damage kitchen north wall") },
                modifier = Modifier.weight(1f),
            ) { Text("Damage") }
            OutlinedButton(
                onClick = { viewModel.submitVoiceCommandText("note: subfloor soft by the cabinet") },
                modifier = Modifier.weight(1f),
            ) { Text("Note") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = commandText,
                onValueChange = { commandText = it },
                label = { Text("Simulate voice command...") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            Button(
                onClick = {
                    if (commandText.isNotBlank()) {
                        viewModel.submitVoiceCommandText(commandText)
                        commandText = ""
                    }
                },
            ) { Text("Run") }
        }
    }
}
