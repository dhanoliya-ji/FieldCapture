package com.fieldcapture.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fieldcapture.app.dat.MockGlassesUiState
import com.meta.wearable.dat.mockdevice.api.camera.CameraFacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MockGlassesSheet(
    state: MockGlassesUiState,
    onDismiss: () -> Unit,
    onQuickStart: () -> Unit,
    onPowerOn: () -> Unit,
    onPowerOff: () -> Unit,
    onUnfold: () -> Unit,
    onFold: () -> Unit,
    onDon: () -> Unit,
    onDoff: () -> Unit,
    onPhoneCamera: (CameraFacing) -> Unit,
    onVideoUri: (Uri) -> Unit,
    onImageUri: (Uri) -> Unit,
    onTap: () -> Unit,
    onTapAndHold: () -> Unit,
) {
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onVideoUri(uri)
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onImageUri(uri)
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Mock Device Kit (Ray-Ban Meta)", style = MaterialTheme.typography.titleLarge)
            Text(
                "Status: ${state.lastStatusMessage} | Feed: ${state.cameraSourceLabel}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            )

            Button(onClick = onQuickStart, modifier = Modifier.fillMaxWidth()) {
                Text("1-Tap Enable, Pair, Unfold, Don & Back Camera")
            }

            Text("Hardware Lifecycle & Drop-Off Simulation", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onPowerOn, modifier = Modifier.weight(1f)) { Text("Power On") }
                Button(
                    onClick = onPowerOff,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Drop-off (Off)") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onUnfold, modifier = Modifier.weight(1f)) { Text("Unfold") }
                OutlinedButton(onClick = onFold, modifier = Modifier.weight(1f)) { Text("Fold") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onDon, modifier = Modifier.weight(1f)) { Text("Don") }
                OutlinedButton(onClick = onDoff, modifier = Modifier.weight(1f)) { Text("Doff") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onTap, modifier = Modifier.weight(1f)) { Text("CapTouch Tap") }
                OutlinedButton(onClick = onTapAndHold, modifier = Modifier.weight(1f)) { Text("Tap & Hold") }
            }

            Text("Camera Feed & Capture Source", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { onPhoneCamera(CameraFacing.BACK) }, modifier = Modifier.weight(1f)) {
                    Text("Phone Back Cam")
                }
                OutlinedButton(onClick = { onPhoneCamera(CameraFacing.FRONT) }, modifier = Modifier.weight(1f)) {
                    Text("Phone Front Cam")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { videoPicker.launch("video/*") }, modifier = Modifier.weight(1f)) {
                    Text("Pick Video Feed")
                }
                OutlinedButton(onClick = { imagePicker.launch("image/*") }, modifier = Modifier.weight(1f)) {
                    Text("Pick Mock Photo")
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}
