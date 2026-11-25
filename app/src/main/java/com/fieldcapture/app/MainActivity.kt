package com.fieldcapture.app

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.activity.viewModels
import com.fieldcapture.app.ui.FieldCaptureAppRoot
import com.fieldcapture.app.ui.FieldCaptureTheme
import com.fieldcapture.app.ui.FieldCaptureViewModel
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MainActivity : ComponentActivity() {

    private val viewModel: FieldCaptureViewModel by viewModels()

    private val androidPermissionLauncher =
        registerForActivityResult(RequestMultiplePermissions()) { _ -> }

    private val permissionMutex = Mutex()
    private var wearablePermContinuation: CancellableContinuation<PermissionStatus>? = null

    private val wearablePermissionLauncher =
        registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
            val status = result.getOrDefault(PermissionStatus.Denied)
            wearablePermContinuation?.resume(status)
            wearablePermContinuation = null
        }

    suspend fun requestWearablesPermission(permission: Permission): PermissionStatus {
        return permissionMutex.withLock {
            suspendCancellableCoroutine { cont ->
                wearablePermContinuation = cont
                cont.invokeOnCancellation { wearablePermContinuation = null }
                wearablePermissionLauncher.launch(permission)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FieldCaptureTheme {
                FieldCaptureAppRoot(
                    viewModel = viewModel,
                    onRequestWearablePermission = ::requestWearablesPermission,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val perms = buildList {
            add(Manifest.permission.BLUETOOTH)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            add(Manifest.permission.INTERNET)
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.CAMERA)
        }.toTypedArray()
        androidPermissionLauncher.launch(perms)
    }
}
