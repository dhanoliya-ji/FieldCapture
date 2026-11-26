package com.fieldcapture.app.ui

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fieldcapture.app.data.lineitems.LineItemMapper
import com.fieldcapture.app.data.local.FieldCaptureDatabase
import com.fieldcapture.app.data.model.CaptureEvidence
import com.fieldcapture.app.data.model.CaptureType
import com.fieldcapture.app.data.model.JobRecord
import com.fieldcapture.app.data.model.QualityMetrics
import com.fieldcapture.app.data.model.SessionTelemetry
import com.fieldcapture.app.data.model.StructuredNote
import com.fieldcapture.app.data.model.UploadStatus
import com.fieldcapture.app.data.sync.ConnectivityMonitor
import com.fieldcapture.app.data.sync.UploadRepository
import com.fieldcapture.app.dat.DatSessionManager
import com.fieldcapture.app.dat.DatSessionUiState
import com.fieldcapture.app.dat.MockGlassesController
import com.fieldcapture.app.dat.MockGlassesUiState
import com.fieldcapture.app.extraction.DamageAnalyzer
import com.fieldcapture.app.extraction.MonocularSizeEstimator
import com.fieldcapture.app.extraction.NameplateExtractor
import com.fieldcapture.app.extraction.NoteParser
import com.fieldcapture.app.quality.ImageQualityAnalyzer
import com.fieldcapture.app.voice.TtsFeedbackManager
import com.fieldcapture.app.voice.VoiceCommand
import com.fieldcapture.app.voice.VoiceCommandManager
import com.fieldcapture.app.voice.VoiceUiState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.mockdevice.api.camera.CameraFacing
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PendingRetakeState(
    val captureType: CaptureType,
    val bitmap: Bitmap,
    val quality: QualityMetrics,
    val room: String = "unspecified",
    val surface: String = "unspecified",
)

data class FieldCaptureUiState(
    val dat: DatSessionUiState = DatSessionUiState(),
    val mock: MockGlassesUiState = MockGlassesUiState(),
    val voice: VoiceUiState = VoiceUiState(),
    val isOnline: Boolean = true,
    val simulateOffline: Boolean = false,
    val pendingUploadCount: Int = 0,
    val jobRecord: JobRecord = JobRecord(
        jobId = UUID.randomUUID().toString().take(8),
        createdAtIso = Instant.now().toString(),
        updatedAtIso = Instant.now().toString(),
    ),
    val pendingRetake: PendingRetakeState? = null,
    val statusBanner: String = "Ready. Enable Mock Glasses or connect Meta glasses.",
    val lastExportPath: String? = null,
    val isProcessingCapture: Boolean = false,
)

class FieldCaptureViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "FieldCaptureViewModel"
    }

    private val db = FieldCaptureDatabase(application)
    private val uploadRepo = UploadRepository(application, db)
    private val connectivityMonitor = ConnectivityMonitor(application)
    private val datManager = DatSessionManager(application, viewModelScope)
    private val mockController = MockGlassesController(application)
    private val voiceManager = VoiceCommandManager(application, viewModelScope)
    private val tts = TtsFeedbackManager(application)

    private val nameplateExtractor = NameplateExtractor()
    private val damageAnalyzer = DamageAnalyzer()

    private var retakeCount = 0

    private val _uiState = MutableStateFlow(
        FieldCaptureUiState(
            jobRecord = db.loadLatestJob() ?: JobRecord(
                jobId = UUID.randomUUID().toString().take(8),
                createdAtIso = Instant.now().toString(),
                updatedAtIso = Instant.now().toString(),
            ),
            simulateOffline = uploadRepo.simulateOffline,
            pendingUploadCount = uploadRepo.pendingCount.value,
        ),
    )
    val uiState: StateFlow<FieldCaptureUiState> = _uiState.asStateFlow()
    val previewBitmap: StateFlow<Bitmap?> = datManager.previewBitmap

    init {
        connectivityMonitor.start()
        datManager.startMonitoring()

        viewModelScope.launch {
            datManager.uiState.collect { dat ->
                _uiState.update { state ->
                    state.copy(
                        dat = dat,
                        statusBanner = dat.lastError ?: state.statusBanner,
                        jobRecord = state.jobRecord.copy(
                            sessionTelemetry = state.jobRecord.sessionTelemetry.copy(
                                disconnectCount = dat.disconnectCount,
                                recoveryCount = dat.recoveryCount,
                            ),
                        ),
                    )
                }
            }
        }
        viewModelScope.launch {
            mockController.state.collect { mock ->
                _uiState.update { it.copy(mock = mock) }
            }
        }
        viewModelScope.launch {
            voiceManager.state.collect { voice ->
                _uiState.update { state ->
                    state.copy(
                        voice = voice,
                        jobRecord = state.jobRecord.copy(
                            sessionTelemetry = state.jobRecord.sessionTelemetry.copy(
                                audioSource = if (voice.audioRoute.usingGlassesMic) "GLASSES_BT_HFP" else "PHONE_MIC_FALLBACK",
                                audioFallbackReason = voice.audioRoute.reason,
                            ),
                        ),
                    )
                }
            }
        }
        viewModelScope.launch {
            connectivityMonitor.isOnline.collect { online ->
                _uiState.update { it.copy(isOnline = online) }
                if (online && !uploadRepo.simulateOffline) {
                    syncNow()
                }
            }
        }
        viewModelScope.launch {
            uploadRepo.pendingCount.collect { count ->
                _uiState.update { it.copy(pendingUploadCount = count) }
            }
        }
        viewModelScope.launch {
            voiceManager.commands.collect { cmd ->
                handleVoiceCommand(cmd)
            }
        }
    }

    fun startRegistration(activity: Activity) = datManager.startRegistration(activity)

    fun startWalkthroughSession(requestWearablePerm: suspend (Permission) -> PermissionStatus) {
        if (!_uiState.value.mock.isEnabled && !_uiState.value.dat.hasActiveDevice) {
            quickStartMockGlasses()
            viewModelScope.launch {
                kotlinx.coroutines.delay(500)
                datManager.startSession(requestWearablePerm)
            }
            return
        }
        datManager.startSession(requestWearablePerm)
    }

    fun pauseWalkthroughSession() = datManager.pauseSession()

    fun resumeWalkthroughSession(requestWearablePerm: suspend (Permission) -> PermissionStatus) {
        datManager.resumeSession(requestWearablePerm)
    }

    fun stopWalkthroughSession() = datManager.stopSession()

    // MockDeviceKit controls
    fun quickStartMockGlasses() {
        mockController.enableAndPairReady(CameraFacing.BACK)
        _uiState.update { it.copy(statusBanner = "Mock Ray-Ban Meta paired & ready (Back Camera)") }
    }

    fun mockPowerOn() = mockController.powerOn()
    fun mockPowerOff() = mockController.powerOff()
    fun mockUnfold() = mockController.unfold()
    fun mockFold() = mockController.fold()
    fun mockDon() = mockController.don()
    fun mockDoff() = mockController.doff()
    fun mockSetPhoneCamera(facing: CameraFacing) = mockController.setPhoneCameraFeed(facing)
    fun mockSetVideoUri(uri: Uri) = mockController.setVideoFeedUri(uri)
    fun mockSetCapturedImageUri(uri: Uri) = mockController.setCapturedImageUri(uri)
    fun mockTap() = mockController.simulateTap()
    fun mockTapAndHold() = mockController.simulateTapAndHold()

    // Voice controls
    fun toggleVoiceListening() {
        if (_uiState.value.voice.isListening) {
            voiceManager.stopListening()
        } else {
            voiceManager.startListening()
        }
    }

    fun submitVoiceCommandText(text: String) {
        voiceManager.submitTranscript(text)
    }

    private fun handleVoiceCommand(cmd: VoiceCommand) {
        when (cmd) {
            is VoiceCommand.CaptureNameplate -> {
                _uiState.update { it.copy(statusBanner = "Voice: capture nameplate") }
                triggerCapture(CaptureType.NAMEPLATE, "utility", "water heater")
            }
            is VoiceCommand.CaptureDamage -> {
                _uiState.update { it.copy(statusBanner = "Voice: capture damage (${cmd.room}, ${cmd.surface})") }
                triggerCapture(CaptureType.DAMAGE, cmd.room, cmd.surface)
            }
            is VoiceCommand.AddNote -> {
                val note = NoteParser.parse(cmd.text)
                appendNote(note)
                tts.speak("Note saved")
                _uiState.update { it.copy(statusBanner = "Note saved: ${cmd.text}") }
            }
            is VoiceCommand.Unknown -> {
                _uiState.update { it.copy(statusBanner = "Unrecognized voice command: \"${cmd.rawTranscript}\"") }
            }
        }
    }

    fun triggerCapture(type: CaptureType, room: String, surface: String) {
        if (_uiState.value.isProcessingCapture) return
        _uiState.update { it.copy(isProcessingCapture = true, pendingRetake = null) }

        viewModelScope.launch {
            val photoResult = datManager.capturePhoto()
            photoResult.fold(
                onSuccess = { bitmap ->
                    val quality = withContext(Dispatchers.Default) {
                        ImageQualityAnalyzer.analyze(bitmap)
                    }
                    if (!quality.passed) {
                        retakeCount++
                        val prompt = quality.retakePrompt ?: "Quality check failed, please retake."
                        tts.speak(prompt)
                        _uiState.update {
                            it.copy(
                                isProcessingCapture = false,
                                pendingRetake = PendingRetakeState(type, bitmap, quality, room, surface),
                                statusBanner = "Retake needed: $prompt",
                            )
                        }
                    } else {
                        finalizeAcceptedCapture(type, bitmap, quality, room, surface, forceAccepted = false)
                    }
                },
                onFailure = { err ->
                    Log.e(TAG, "Capture failed", err)
                    _uiState.update {
                        it.copy(
                            isProcessingCapture = false,
                            statusBanner = "Capture failed: ${err.message}",
                        )
                    }
                },
            )
        }
    }

    fun forceAcceptPendingRetake() {
        val pending = _uiState.value.pendingRetake ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(pendingRetake = null, isProcessingCapture = true) }
            finalizeAcceptedCapture(
                pending.captureType,
                pending.bitmap,
                pending.quality,
                pending.room,
                pending.surface,
                forceAccepted = true,
            )
        }
    }

    fun dismissPendingRetake() {
        _uiState.update { it.copy(pendingRetake = null, statusBanner = "Ready for retake") }
    }

    private suspend fun finalizeAcceptedCapture(
        type: CaptureType,
        bitmap: Bitmap,
        quality: QualityMetrics,
        room: String,
        surface: String,
        forceAccepted: Boolean,
    ) {
        val captureId = UUID.randomUUID().toString().take(8)
        val imagePath = withContext(Dispatchers.IO) { saveBitmapToDisk(captureId, bitmap) }

        val nameplateData = if (type == CaptureType.NAMEPLATE) {
            nameplateExtractor.extract(bitmap, quality)
        } else null

        val damageData = if (type == CaptureType.DAMAGE) {
            damageAnalyzer.analyze(bitmap, room, surface, quality)
        } else null

        val evidence = CaptureEvidence(
            id = captureId,
            captureType = type,
            timestampIso = Instant.now().toString(),
            imagePath = imagePath,
            qualityMetrics = quality,
            nameplateData = nameplateData,
            damageData = damageData,
            uploadStatus = UploadStatus.PENDING,
            forceAccepted = forceAccepted,
        )

        val currentJob = _uiState.value.jobRecord
        val updatedCaptures = currentJob.captures + evidence
        val updatedLineItems = LineItemMapper.mapToLineItems(updatedCaptures, currentJob.notes)
        val subtotal = ((updatedLineItems.sumOf { it.estimatedTotalUsd.toDouble() }) * 100.0).roundToInt() / 100f

        val updatedJob = currentJob.copy(
            updatedAtIso = Instant.now().toString(),
            sessionTelemetry = currentJob.sessionTelemetry.copy(retakeCount = retakeCount),
            captures = updatedCaptures,
            lineItems = updatedLineItems,
            estimatedSubtotalUsd = subtotal,
            pendingUploadCount = uploadRepo.pendingCount.value + 1,
        )

        withContext(Dispatchers.IO) {
            db.saveJob(updatedJob)
            uploadRepo.enqueueCapture(updatedJob.jobId, evidence)
        }

        val spoken = if (type == CaptureType.NAMEPLATE) {
            "Captured nameplate"
        } else {
            "Captured $room $surface damage"
        }
        tts.speak(spoken)

        _uiState.update {
            it.copy(
                isProcessingCapture = false,
                jobRecord = updatedJob,
                statusBanner = "$spoken (saved & queued)",
            )
        }

        if (_uiState.value.isOnline && !uploadRepo.simulateOffline) {
            syncNow()
        }
    }

    private fun appendNote(note: StructuredNote) {
        val currentJob = _uiState.value.jobRecord
        val updatedNotes = currentJob.notes + note
        val updatedLineItems = LineItemMapper.mapToLineItems(currentJob.captures, updatedNotes)
        val subtotal = ((updatedLineItems.sumOf { it.estimatedTotalUsd.toDouble() }) * 100.0).roundToInt() / 100f
        val updatedJob = currentJob.copy(
            updatedAtIso = Instant.now().toString(),
            notes = updatedNotes,
            lineItems = updatedLineItems,
            estimatedSubtotalUsd = subtotal,
        )
        viewModelScope.launch(Dispatchers.IO) { db.saveJob(updatedJob) }
        _uiState.update { it.copy(jobRecord = updatedJob) }
    }

    fun updateTapeMeasureComparison(captureId: String, tapeWidthIn: Float, tapeHeightIn: Float) {
        val job = _uiState.value.jobRecord
        val updatedCaptures = job.captures.map { cap ->
            if (cap.id == captureId && cap.damageData?.sizeEstimate != null) {
                cap.copy(
                    damageData = cap.damageData.copy(
                        sizeEstimate = cap.damageData.sizeEstimate.copy(
                            tapeWidthInches = tapeWidthIn,
                            tapeHeightInches = tapeHeightIn,
                        ),
                    ),
                )
            } else cap
        }
        val updatedJob = job.copy(captures = updatedCaptures, updatedAtIso = Instant.now().toString())
        viewModelScope.launch(Dispatchers.IO) { db.saveJob(updatedJob) }
        _uiState.update { it.copy(jobRecord = updatedJob) }
    }

    fun toggleSimulateOffline() {
        val next = !_uiState.value.simulateOffline
        uploadRepo.simulateOffline = next
        _uiState.update {
            it.copy(
                simulateOffline = next,
                statusBanner = if (next) "Offline simulation ON — captures will queue locally" else "Online mode restored — syncing queue...",
            )
        }
        if (!next && _uiState.value.isOnline) {
            syncNow()
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            val synced = uploadRepo.flushPending(_uiState.value.isOnline)
            if (synced > 0) {
                val job = _uiState.value.jobRecord
                val updatedCaptures = job.captures.map { cap ->
                    if (cap.uploadStatus != UploadStatus.SYNCED) cap.copy(uploadStatus = UploadStatus.SYNCED) else cap
                }
                val updatedJob = job.copy(
                    captures = updatedCaptures,
                    pendingUploadCount = uploadRepo.pendingCount.value,
                )
                withContext(Dispatchers.IO) { db.saveJob(updatedJob) }
                _uiState.update {
                    it.copy(
                        jobRecord = updatedJob,
                        statusBanner = "Synced $synced queued capture(s)",
                    )
                }
            }
        }
    }

    fun exportJobJson() {
        viewModelScope.launch(Dispatchers.IO) {
            val file = uploadRepo.exportJobJson(_uiState.value.jobRecord)
            _uiState.update {
                it.copy(
                    lastExportPath = file.absolutePath,
                    statusBanner = "Exported JSON to ${file.name}",
                )
            }
        }
    }

    fun startNewJob() {
        val fresh = JobRecord(
            jobId = UUID.randomUUID().toString().take(8),
            createdAtIso = Instant.now().toString(),
            updatedAtIso = Instant.now().toString(),
        )
        retakeCount = 0
        viewModelScope.launch(Dispatchers.IO) { db.saveJob(fresh) }
        _uiState.update { it.copy(jobRecord = fresh, statusBanner = "Started new job #${fresh.jobId}") }
    }

    private fun saveBitmapToDisk(captureId: String, bitmap: Bitmap): String {
        val dir = File(getApplication<Application>().filesDir, "captures").apply { mkdirs() }
        val file = File(dir, "cap_$captureId.jpg")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        return file.absolutePath
    }

    override fun onCleared() {
        super.onCleared()
        connectivityMonitor.stop()
        voiceManager.destroy()
        tts.shutdown()
        datManager.stopSession()
    }
}
