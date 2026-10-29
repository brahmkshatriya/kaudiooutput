package dev.brahmkshatriya.kaudiooutput

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Only the application context is retained. No Bluetooth discovery permission is needed. */
public fun AudioOutputs.Companion.android(context: Context, scope: CoroutineScope): AudioOutputs =
    AudioOutputs(scope, AndroidAudioOutputBackend(context.applicationContext))

/** Adapts AudioTrack, MediaPlayer, ExoPlayer, or any other Android media output. */
public fun AudioOutputs.bindAndroidDevice(setDevice: (AudioDeviceInfo?) -> Unit): AutoCloseable {
    val android = backend as? AndroidAudioOutputBackend ?: error("Not an Android audio output manager")
    return bind { device -> setDevice(device?.let { android.device(it.id) }) }
}

private class AndroidAudioOutputBackend(context: Context) : AudioOutputBackend {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val mutable = MutableStateFlow(AudioOutputState())
    override val state: StateFlow<AudioOutputState> = mutable.asStateFlow()
    private var devices = emptyList<AudioDeviceInfo>()
    private var closed = false
    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }

    init {
        refresh()
        audio.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
    }

    @Synchronized fun device(id: String): AudioDeviceInfo? = devices.firstOrNull { it.id.toString() == id }

    @Synchronized private fun refresh() {
        if (closed) return
        devices = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.type in MediaDeviceTypes }
        val selected = mutable.value.selectedDeviceId?.takeIf { id -> devices.any { it.id.toString() == id } }
        mutable.value = AudioOutputState(
            devices.map { device ->
                AudioOutputDevice(device.id.toString(),
                    if (device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) "This device" else device.productName.toString().ifBlank { "Audio device" },
                    device.kind())
            }, selectedDeviceId = selected, selection = AudioOutputSelection.Direct, ready = true,
        )
    }

    @Synchronized override fun select(deviceId: String?) {
        check(!closed)
        require(deviceId == null || device(deviceId) != null) { "The audio device is no longer connected" }
        mutable.value = mutable.value.copy(selectedDeviceId = deviceId)
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        audio.unregisterAudioDeviceCallback(callback)
    }
}

private fun AudioDeviceInfo.kind(): AudioOutputKind = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioOutputKind.Speaker
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> AudioOutputKind.Headphones
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
    AudioDeviceInfo.TYPE_BLE_BROADCAST, AudioDeviceInfo.TYPE_HEARING_AID -> AudioOutputKind.Bluetooth
    AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> AudioOutputKind.Usb
    AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC -> AudioOutputKind.Hdmi
    else -> AudioOutputKind.Other
}

private val MediaDeviceTypes = setOf(
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
    AudioDeviceInfo.TYPE_BLE_BROADCAST, AudioDeviceInfo.TYPE_HEARING_AID, AudioDeviceInfo.TYPE_USB_DEVICE,
    AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_HDMI,
    AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC, AudioDeviceInfo.TYPE_LINE_ANALOG,
    AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_AUX_LINE, AudioDeviceInfo.TYPE_DOCK,
)

public actual fun AudioOutputs.Companion.desktop(scope: CoroutineScope): AudioOutputs =
    error("Use AudioOutputs.android(context, scope) on Android")
