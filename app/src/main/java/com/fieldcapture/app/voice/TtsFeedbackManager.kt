package com.fieldcapture.app.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class TtsFeedbackManager(context: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                ready = true
            }
        }
    }

    fun speak(message: String) {
        if (ready) {
            tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "fieldcapture_${System.currentTimeMillis()}")
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}
