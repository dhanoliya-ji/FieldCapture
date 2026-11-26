package com.fieldcapture.app.dat

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class WalkSessionLifecycle {
    IDLE,
    STARTING,
    STREAMING,
    PAUSED,
    RECOVERING,
    STOPPED,
    ERROR,
}

data class DatSessionUiState(
    val registrationState: RegistrationState = RegistrationState.UNAVAILABLE,
    val devices: List<DeviceIdentifier> = emptyList(),
    val hasActiveDevice: Boolean = false,
    val sessionState: DeviceSessionState = DeviceSessionState.STOPPED,
    val streamState: StreamState = StreamState.STOPPED,
    val lifecycle: WalkSessionLifecycle = WalkSessionLifecycle.IDLE,
    val hasReceivedFirstFrame: Boolean = false,
    val disconnectCount: Int = 0,
    val recoveryCount: Int = 0,
    val lastError: String? = null,
)

/**
 * Manages the Meta DAT 1.1.0 SDK lifecycle:
 * registration, permissions, DeviceSession start/pause/resume/stop,
 * camera streaming, photo capture, and automatic disconnect recovery.
 */
class DatSessionManager(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "DatSessionManager"
        private const val FRAME_RATE = 24
        private const val RECONNECT_POLL_MS = 1500L
    }

    val deviceSelector = AutoDeviceSelector()

    private val _uiState = MutableStateFlow(DatSessionUiState())
    val uiState: StateFlow<DatSessionUiState> = _uiState.asStateFlow()

    private val _previewBitmap = MutableStateFlow<Bitmap?>(null)
    val previewBitmap: StateFlow<Bitmap?> = _previewBitmap.asStateFlow()

    private var session: DeviceSession? = null
    private var camera: Camera? = null
    private var stream: Stream? = null

    private var wantsActiveSession = false
    private var isUserPaused = false

    private var sessionStateJob: Job? = null
    private var sessionErrorJob: Job? = null
    private var streamStateJob: Job? = null
    private var streamErrorJob: Job? = null
    private var videoJob: Job? = null
    private var recoveryJob: Job? = null
    private var monitoringStarted = false

    fun startMonitoring() {
        if (monitoringStarted) return
        monitoringStarted = true

        scope.launch {
            Wearables.registrationState.collect { reg ->
                _uiState.update { it.copy(registrationState = reg) }
            }
        }
        scope.launch {
            Wearables.devices.collect { set ->
                _uiState.update { it.copy(devices = set.toList()) }
            }
        }
        scope.launch {
            deviceSelector.activeDeviceFlow().collect { active ->
                val available = active != null
                _uiState.update { it.copy(hasActiveDevice = available) }
                if (available && wantsActiveSession && !isUserPaused && session == null) {
                    attemptRecoveryRestart()
                }
            }
        }
    }

    fun startRegistration(activity: Activity) {
        Wearables.startRegistration(activity)
    }

    fun startSession(requestCameraPermission: suspend (Permission) -> PermissionStatus) {
        wantsActiveSession = true
        isUserPaused = false
        recoveryJob?.cancel()
        if (session != null && _uiState.value.lifecycle == WalkSessionLifecycle.STREAMING) return

        _uiState.update { it.copy(lifecycle = WalkSessionLifecycle.STARTING, lastError = null) }

        Wearables.createSession(deviceSelector).fold(
            onSuccess = { created ->
                session = created
                observeSession(created, requestCameraPermission)
                created.start()
            },
            onFailure = { error, _ ->
                Log.e(TAG, "createSession failed: ${error.description}")
                _uiState.update {
                    it.copy(
                        lifecycle = WalkSessionLifecycle.ERROR,
                        lastError = error.description,
                    )
                }
            },
        )
    }

    fun pauseSession() {
        if (session == null) return
        isUserPaused = true
        _uiState.update { it.copy(lifecycle = WalkSessionLifecycle.PAUSED) }
        tearDownStreamOnly()
    }

    fun resumeSession(requestCameraPermission: suspend (Permission) -> PermissionStatus) {
        if (!wantsActiveSession) {
            startSession(requestCameraPermission)
            return
        }
        isUserPaused = false
        val current = session
        if (current != null && current.state.value == DeviceSessionState.STARTED) {
            scope.launch { ensurePermissionAndStartStream(current, requestCameraPermission) }
        } else {
            startSession(requestCameraPermission)
        }
    }

    fun stopSession() {
        wantsActiveSession = false
        isUserPaused = false
        recoveryJob?.cancel()
        recoveryJob = null
        tearDownStreamOnly()
        session?.stop()
        cleanupSessionRefs()
        _uiState.update {
            it.copy(
                sessionState = DeviceSessionState.STOPPED,
                streamState = StreamState.STOPPED,
                lifecycle = WalkSessionLifecycle.STOPPED,
                hasReceivedFirstFrame = false,
            )
        }
    }

    /**
     * Captures a still photo from the active DAT [Stream].
     */
    suspend fun capturePhoto(): Result<Bitmap> {
        val activeStream = stream
            ?: return Result.failure(IllegalStateException("Camera stream is not active"))

        var photoDataRef: PhotoData? = null
        var failureMsg: String? = null

        val captureResult = activeStream.capturePhoto()
        captureResult.fold(
            onSuccess = { photoData ->
                photoDataRef = photoData
            },
            onFailure = { error, _ ->
                failureMsg = error.description
            },
        )

        val decoded = photoDataRef?.let { photo ->
            withContext(Dispatchers.Default) { decodePhoto(photo) }
        }
        if (photoDataRef != null && decoded == null) {
            failureMsg = "Failed to decode photo bytes from glasses"
        }

        return if (decoded != null) {
            Result.success(decoded)
        } else {
            val fallback = _previewBitmap.value
            if (fallback != null) {
                Log.w(TAG, "capturePhoto() failed ($failureMsg); using latest live preview frame")
                Result.success(fallback.copy(Bitmap.Config.ARGB_8888, false))
            } else {
                Result.failure(IllegalStateException(failureMsg ?: "Unknown capture error"))
            }
        }
    }

    private fun observeSession(
        created: DeviceSession,
        requestCameraPermission: suspend (Permission) -> PermissionStatus,
    ) {
        sessionStateJob?.cancel()
        sessionStateJob = scope.launch {
            created.state.collect { state ->
                _uiState.update { it.copy(sessionState = state) }
                when (state) {
                    DeviceSessionState.STARTED -> {
                        if (!isUserPaused && stream == null) {
                            ensurePermissionAndStartStream(created, requestCameraPermission)
                        }
                    }
                    DeviceSessionState.STOPPED -> {
                        handleUnexpectedOrNormalStop(created, requestCameraPermission)
                    }
                    else -> Unit
                }
            }
        }

        sessionErrorJob?.cancel()
        sessionErrorJob = scope.launch {
            created.errors.collect { error ->
                Log.e(TAG, "Session error: ${error.description}")
                _uiState.update { it.copy(lastError = error.description) }
            }
        }
    }

    private suspend fun ensurePermissionAndStartStream(
        activeSession: DeviceSession,
        requestCameraPermission: suspend (Permission) -> PermissionStatus,
    ) {
        var granted = false
        Wearables.checkPermissionStatus(Permission.CAMERA)
            .onSuccess { status ->
                granted = status == PermissionStatus.Granted
            }
            .onFailure { error, _ ->
                _uiState.update { it.copy(lastError = error.description) }
            }

        if (!granted) {
            granted = requestCameraPermission(Permission.CAMERA) == PermissionStatus.Granted
        }

        if (!granted) {
            _uiState.update {
                it.copy(
                    lifecycle = WalkSessionLifecycle.ERROR,
                    lastError = "Wearable camera permission denied",
                )
            }
            return
        }

        StreamingService.start(context)
        activeSession.addCamera(
            StreamConfiguration(
                videoQuality = VideoQuality.MEDIUM,
                frameRate = FRAME_RATE,
                compressVideo = false,
            ),
        ).fold(
            onSuccess = { addedCamera ->
                camera = addedCamera
                val addedStream = addedCamera.stream
                stream = addedStream
                observeStream(addedStream)
                addedStream.start().onFailure { error, _ ->
                    Log.e(TAG, "stream.start() failed: ${error.description}")
                    _uiState.update { it.copy(lastError = error.description) }
                    tearDownStreamOnly()
                }
            },
            onFailure = { error, _ ->
                Log.e(TAG, "addCamera() failed: ${error.description}")
                StreamingService.stop(context)
                _uiState.update {
                    it.copy(
                        lifecycle = WalkSessionLifecycle.ERROR,
                        lastError = error.description,
                    )
                }
            },
        )
    }

    private fun observeStream(activeStream: Stream) {
        videoJob?.cancel()
        videoJob = scope.launch(Dispatchers.Default.limitedParallelism(1)) {
            activeStream.videoStream.collect { frame -> handleVideoFrame(frame) }
        }

        streamStateJob?.cancel()
        streamStateJob = scope.launch {
            activeStream.state.collect { st ->
                _uiState.update { state ->
                    val nextLifecycle = when {
                        isUserPaused -> WalkSessionLifecycle.PAUSED
                        st == StreamState.STREAMING -> WalkSessionLifecycle.STREAMING
                        st == StreamState.STARTING -> WalkSessionLifecycle.STARTING
                        wantsActiveSession && (st == StreamState.STOPPED || st == StreamState.CLOSED) ->
                            WalkSessionLifecycle.RECOVERING
                        else -> state.lifecycle
                    }
                    state.copy(streamState = st, lifecycle = nextLifecycle)
                }
            }
        }

        streamErrorJob?.cancel()
        streamErrorJob = scope.launch {
            activeStream.errorStream.collect { err ->
                Log.e(TAG, "Stream error: ${err.description}")
                _uiState.update { it.copy(lastError = err.description) }
            }
        }
    }

    private fun handleVideoFrame(videoFrame: VideoFrame) {
        if (videoFrame.isCompressed) return
        val width = videoFrame.width
        val height = videoFrame.height
        if (width <= 0 || height <= 0) return

        val buffer = videoFrame.buffer
        val dataSize = buffer.remaining()
        val expectedI420Size = width * height * 3 / 2
        if (dataSize < expectedI420Size) return

        val raw = ByteArray(dataSize)
        val pos = buffer.position()
        buffer.get(raw)
        buffer.position(pos)

        // Convert I420 (Y + U + V) to NV21 (Y + VU) for YuvImage -> Bitmap
        val frameSize = width * height
        val qFrameSize = frameSize / 4
        val nv21 = ByteArray(expectedI420Size)
        System.arraycopy(raw, 0, nv21, 0, frameSize)
        val uOffset = frameSize
        val vOffset = frameSize + qFrameSize
        var outIdx = frameSize
        for (i in 0 until qFrameSize) {
            nv21[outIdx++] = raw[vOffset + i]
            nv21[outIdx++] = raw[uOffset + i]
        }

        val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val baos = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, width, height), 80, baos)
        val jpegBytes = baos.toByteArray()
        val bmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        if (bmp != null) {
            _previewBitmap.value = bmp
            if (!_uiState.value.hasReceivedFirstFrame) {
                _uiState.update { it.copy(hasReceivedFirstFrame = true) }
            }
        }
    }

    private fun handleUnexpectedOrNormalStop(
        stoppedSession: DeviceSession,
        requestCameraPermission: suspend (Permission) -> PermissionStatus,
    ) {
        if (session !== stoppedSession) return
        tearDownStreamOnly()
        cleanupSessionRefs()

        if (wantsActiveSession && !isUserPaused) {
            // Unexpected disconnect (e.g., glasses powered off, folded, or BT dropped)
            _uiState.update {
                it.copy(
                    lifecycle = WalkSessionLifecycle.RECOVERING,
                    disconnectCount = it.disconnectCount + 1,
                    lastError = "Device disconnected — waiting to recover...",
                )
            }
            recoveryJob?.cancel()
            recoveryJob = scope.launch {
                while (wantsActiveSession && !isUserPaused && session == null) {
                    delay(RECONNECT_POLL_MS)
                    if (_uiState.value.hasActiveDevice) {
                        _uiState.update { it.copy(recoveryCount = it.recoveryCount + 1) }
                        startSession(requestCameraPermission)
                        break
                    }
                }
            }
        }
    }

    private fun attemptRecoveryRestart() {
        // Triggered when activeDeviceFlow() emits a device while we're in RECOVERING
        Log.d(TAG, "Active device returned while recovering")
    }

    private fun tearDownStreamOnly() {
        videoJob?.cancel()
        videoJob = null
        streamStateJob?.cancel()
        streamStateJob = null
        streamErrorJob?.cancel()
        streamErrorJob = null
        StreamingService.stop(context)
        runCatching { camera?.close() }
        camera = null
        stream = null
        _previewBitmap.value = null
        _uiState.update { it.copy(hasReceivedFirstFrame = false) }
    }

    private fun cleanupSessionRefs() {
        sessionStateJob?.cancel()
        sessionStateJob = null
        sessionErrorJob?.cancel()
        sessionErrorJob = null
        session = null
    }

    private fun decodePhoto(photo: PhotoData): Bitmap? = when (photo) {
        is PhotoData.Bitmap -> photo.bitmap
        is PhotoData.HEIC -> decodeHeicWithExif(photo.data)
    }

    private fun decodeHeicWithExif(data: ByteBuffer): Bitmap? {
        val dup = data.duplicate().apply { rewind() }
        val bytes = ByteArray(dup.remaining())
        dup.get(bytes)
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val orientation = runCatching {
            ByteArrayInputStream(bytes).use { input ->
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        }
        if (matrix.isIdentity) return raw
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true).also {
            raw.recycle()
        }
    }
}
