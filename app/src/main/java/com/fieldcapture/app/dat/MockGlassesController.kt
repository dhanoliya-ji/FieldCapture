package com.fieldcapture.app.dat

import android.content.Context
import android.net.Uri
import android.util.Log
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import com.meta.wearable.dat.mockdevice.api.MockGlasses
import com.meta.wearable.dat.mockdevice.api.camera.CameraFacing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class MockGlassesUiState(
    val isEnabled: Boolean = false,
    val isPaired: Boolean = false,
    val isPoweredOn: Boolean = false,
    val isUnfolded: Boolean = false,
    val isDonned: Boolean = false,
    val cameraSourceLabel: String = "None",
    val hasCapturedImageSet: Boolean = false,
    val lastStatusMessage: String = "MockDeviceKit disabled",
)

/**
 * Controls [MockDeviceKit] and simulated [MockGlasses] hardware states,
 * including phone camera feed, recorded video feed, still image injection,
 * and simulated hardware drop-off for disconnect recovery testing.
 */
class MockGlassesController(context: Context) {

    companion object {
        private const val TAG = "MockGlassesController"
    }

    private val mockDeviceKit = MockDeviceKit.getInstance(context.applicationContext)
    private var mockGlasses: MockGlasses? = null

    private val _state = MutableStateFlow(MockGlassesUiState())
    val state: StateFlow<MockGlassesUiState> = _state.asStateFlow()

    fun enableAndPairReady(cameraFacing: CameraFacing = CameraFacing.BACK) {
        enable()
        if (mockGlasses == null) {
            pairGlasses {
                powerOn()
                unfold()
                don()
                setPhoneCameraFeed(cameraFacing)
            }
        } else {
            powerOn()
            unfold()
            don()
            setPhoneCameraFeed(cameraFacing)
        }
    }

    fun enable() {
        runCatching {
            mockDeviceKit.enable()
            _state.update { it.copy(isEnabled = true, lastStatusMessage = "MockDeviceKit enabled") }
        }.onFailure { e ->
            Log.e(TAG, "Failed to enable MockDeviceKit", e)
            _state.update { it.copy(lastStatusMessage = "Enable failed: ${e.message}") }
        }
    }

    fun disable() {
        runCatching {
            mockDeviceKit.disable()
            mockGlasses = null
            _state.value = MockGlassesUiState(isEnabled = false, lastStatusMessage = "MockDeviceKit disabled")
        }.onFailure { e ->
            Log.e(TAG, "Failed to disable MockDeviceKit", e)
        }
    }

    fun pairGlasses(onPaired: (() -> Unit)? = null) {
        if (!_state.value.isEnabled) enable()
        mockDeviceKit.pairGlasses(GlassesModel.RAYBAN_META).fold(
            onSuccess = { glasses ->
                mockGlasses = glasses
                _state.update {
                    it.copy(
                        isPaired = true,
                        lastStatusMessage = "Paired Ray-Ban Meta (Mock)",
                    )
                }
                onPaired?.invoke()
            },
            onFailure = { error, _ ->
                Log.e(TAG, "pairGlasses failed: ${error.description}")
                _state.update { it.copy(lastStatusMessage = "Pair failed: ${error.description}") }
            },
        )
    }

    fun unpairGlasses() {
        val glasses = mockGlasses ?: return
        runCatching {
            mockDeviceKit.unpairDevice(glasses)
            mockGlasses = null
            _state.update {
                it.copy(
                    isPaired = false,
                    isPoweredOn = false,
                    isUnfolded = false,
                    isDonned = false,
                    lastStatusMessage = "Unpaired mock glasses",
                )
            }
        }
    }

    fun powerOn() {
        val glasses = mockGlasses ?: return
        runCatching {
            glasses.powerOn()
            _state.update { it.copy(isPoweredOn = true, lastStatusMessage = "Glasses powered ON") }
        }
    }

    fun powerOff() {
        val glasses = mockGlasses ?: return
        runCatching {
            glasses.powerOff()
            _state.update {
                it.copy(
                    isPoweredOn = false,
                    isUnfolded = false,
                    isDonned = false,
                    lastStatusMessage = "Glasses powered OFF (drop-off simulated)",
                )
            }
        }
    }

    fun unfold() {
        val glasses = mockGlasses ?: return
        runCatching {
            glasses.unfold()
            _state.update { it.copy(isUnfolded = true, lastStatusMessage = "Hinges unfolded") }
        }
    }

    fun fold() {
        val glasses = mockGlasses ?: return
        runCatching {
            glasses.fold()
            _state.update {
                it.copy(isUnfolded = false, isDonned = false, lastStatusMessage = "Hinges folded")
            }
        }
    }

    fun don() {
        val glasses = mockGlasses ?: return
        runCatching {
            glasses.don()
            _state.update {
                it.copy(isDonned = true, isUnfolded = true, lastStatusMessage = "Glasses donned")
            }
        }
    }

    fun doff() {
        val glasses = mockGlasses ?: return
        runCatching {
            glasses.doff()
            _state.update { it.copy(isDonned = false, lastStatusMessage = "Glasses doffed") }
        }
    }

    fun setPhoneCameraFeed(facing: CameraFacing) {
        val glasses = mockGlasses ?: return
        runCatching {
            glasses.services.camera.setCameraFeed(facing)
            _state.update {
                it.copy(cameraSourceLabel = "Phone ${facing.name} Camera", lastStatusMessage = "Feed: Phone ${facing.name}")
            }
        }.onFailure { e ->
            Log.e(TAG, "setPhoneCameraFeed failed", e)
        }
    }

    fun setVideoFeedUri(uri: Uri) {
        val glasses = mockGlasses ?: return
        runCatching {
            glasses.services.camera.setCameraFeed(uri)
            _state.update {
                it.copy(cameraSourceLabel = "Video URI", lastStatusMessage = "Feed: ${uri.lastPathSegment}")
            }
        }.onFailure { e ->
            Log.e(TAG, "setVideoFeedUri failed", e)
        }
    }

    fun setCapturedImageUri(uri: Uri) {
        val glasses = mockGlasses ?: return
        runCatching {
            glasses.services.camera.setCapturedImage(uri)
            _state.update {
                it.copy(hasCapturedImageSet = true, lastStatusMessage = "Mock photo set: ${uri.lastPathSegment}")
            }
        }.onFailure { e ->
            Log.e(TAG, "setCapturedImageUri failed", e)
        }
    }

    fun simulateTap() {
        val glasses = mockGlasses ?: return
        runCatching {
            glasses.services.captouch.tap()
            _state.update { it.copy(lastStatusMessage = "Simulated CapTouch Tap (Pause/Resume)") }
        }
    }

    fun simulateTapAndHold() {
        val glasses = mockGlasses ?: return
        runCatching {
            glasses.services.captouch.tapAndHold()
            _state.update { it.copy(lastStatusMessage = "Simulated CapTouch Tap & Hold (Stop)") }
        }
    }
}
