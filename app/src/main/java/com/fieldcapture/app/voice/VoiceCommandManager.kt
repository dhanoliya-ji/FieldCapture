package com.fieldcapture.app.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class VoiceUiState(
    val isListening: Boolean = false,
    val audioRoute: AudioRouteStatus = AudioRouteStatus(
        usingGlassesMic = false,
        label = "Phone Mic (Fallback)",
        reason = "Not initialized",
    ),
    val lastTranscript: String = "",
    val lastError: String? = null,
)

/**
 * Hands-free continuous voice command listener backed by Android [SpeechRecognizer]
 * with automatic restart and explicit [AudioRouteDetector] fallback reporting.
 */
class VoiceCommandManager(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "VoiceCommandManager"
        private const val RESTART_DELAY_MS = 350L
    }

    private val routeDetector = AudioRouteDetector(context)
    private var speechRecognizer: SpeechRecognizer? = null
    private var restartJob: Job? = null
    private var wantsListening = false

    private val _state = MutableStateFlow(VoiceUiState(audioRoute = routeDetector.detect()))
    val state: StateFlow<VoiceUiState> = _state.asStateFlow()

    private val _commands = MutableSharedFlow<VoiceCommand>(extraBufferCapacity = 16)
    val commands: SharedFlow<VoiceCommand> = _commands.asSharedFlow()

    fun refreshAudioRoute() {
        _state.update { it.copy(audioRoute = routeDetector.detect()) }
    }

    fun startListening() {
        refreshAudioRoute()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            _state.update { it.copy(lastError = "RECORD_AUDIO permission required for voice commands") }
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _state.update {
                it.copy(lastError = "SpeechRecognizer unavailable on this device/emulator; use quick command bar")
            }
            return
        }
        wantsListening = true
        scope.launch(Dispatchers.Main) {
            beginRecognizerSession()
        }
    }

    fun stopListening() {
        wantsListening = false
        restartJob?.cancel()
        restartJob = null
        scope.launch(Dispatchers.Main) {
            runCatching { speechRecognizer?.stopListening() }
            runCatching { speechRecognizer?.destroy() }
            speechRecognizer = null
            _state.update { it.copy(isListening = false) }
        }
    }

    fun submitTranscript(transcript: String) {
        _state.update { it.copy(lastTranscript = transcript, lastError = null) }
        val cmd = VoiceCommandParser.parse(transcript)
        _commands.tryEmit(cmd)
    }

    private fun beginRecognizerSession() {
        if (!wantsListening) return
        runCatching { speechRecognizer?.destroy() }

        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer = recognizer
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                _state.update { it.copy(isListening = true, lastError = null) }
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> null
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mic permission denied"
                    else -> "Speech error ($error)"
                }
                if (msg != null) {
                    Log.w(TAG, msg)
                    _state.update { it.copy(lastError = msg) }
                }
                scheduleRestart()
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val top = matches?.firstOrNull().orEmpty()
                if (top.isNotBlank()) {
                    submitTranscript(top)
                }
                scheduleRestart()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!partial.isNullOrBlank()) {
                    _state.update { it.copy(lastTranscript = partial) }
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        recognizer.startListening(intent)
    }

    private fun scheduleRestart() {
        if (!wantsListening) {
            _state.update { it.copy(isListening = false) }
            return
        }
        restartJob?.cancel()
        restartJob = scope.launch(Dispatchers.Main) {
            delay(RESTART_DELAY_MS)
            if (wantsListening) {
                beginRecognizerSession()
            }
        }
    }

    fun destroy() {
        stopListening()
    }
}
