package com.fieldcapture.app.voice

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

data class AudioRouteStatus(
    val usingGlassesMic: Boolean,
    val label: String,
    val reason: String,
)

/**
 * Inspects Android [AudioManager] for a connected Bluetooth HFP/SCO glasses microphone.
 * MockDeviceKit does not register a virtual Bluetooth SCO audio device in AudioManager,
 * so this explicitly detects that gap and falls back to the phone microphone.
 */
class AudioRouteDetector(context: Context) {
    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun detect(): AudioRouteStatus {
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val btMic = inputs.firstOrNull { device ->
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                device.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        }
        return if (btMic != null) {
            AudioRouteStatus(
                usingGlassesMic = true,
                label = "Glasses Mic (BT HFP)",
                reason = "Using Bluetooth SCO/HFP input: ${btMic.productName}",
            )
        } else {
            AudioRouteStatus(
                usingGlassesMic = false,
                label = "Phone Mic (Fallback)",
                reason = "No Bluetooth SCO/HFP glasses mic in AudioManager (MockDeviceKit simulates video/lifecycle only); using phone mic.",
            )
        }
    }
}
