@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brahmkshatriya.kaudiooutput

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.AVFAudio.*
import platform.AVKit.AVRoutePickerView
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue

public fun AudioOutputs.Companion.ios(scope: CoroutineScope): AudioOutputs = AudioOutputs(scope, IosAudioOutputBackend())

/** Embed this visible native control in your UI; iOS owns the list and route selection. */
public fun AudioOutputs.createRoutePickerView(): AVRoutePickerView {
    check(backend is IosAudioOutputBackend) { "Use AudioOutputs.ios(scope)" }
    return AVRoutePickerView(frame = CGRectMake(0.0, 0.0, 44.0, 44.0)).apply { prioritizesVideoDevices = false }
}

private class IosAudioOutputBackend : AudioOutputBackend {
    private val session = AVAudioSession.sharedInstance()
    private val mutable = MutableStateFlow(AudioOutputState(selection = AudioOutputSelection.SystemPicker))
    override val state: StateFlow<AudioOutputState> = mutable.asStateFlow()
    private val center = NSNotificationCenter.defaultCenter
    private val observer = center.addObserverForName(AVAudioSessionRouteChangeNotification, session, NSOperationQueue.mainQueue) { refresh() }

    init { refresh() }

    private fun refresh() {
        val outputs = session.currentRoute.outputs.filterIsInstance<AVAudioSessionPortDescription>()
        mutable.value = AudioOutputState(
            devices = outputs.map { port ->
                AudioOutputDevice(port.UID, port.portName, when (port.portType) {
                    AVAudioSessionPortBuiltInSpeaker, AVAudioSessionPortBuiltInReceiver -> AudioOutputKind.Speaker
                    AVAudioSessionPortHeadphones -> AudioOutputKind.Headphones
                    AVAudioSessionPortBluetoothA2DP, AVAudioSessionPortBluetoothHFP, AVAudioSessionPortBluetoothLE -> AudioOutputKind.Bluetooth
                    AVAudioSessionPortUSBAudio -> AudioOutputKind.Usb
                    AVAudioSessionPortAirPlay -> AudioOutputKind.AirPlay
                    AVAudioSessionPortHDMI -> AudioOutputKind.Hdmi
                    else -> AudioOutputKind.Other
                })
            }, selectedDeviceId = outputs.firstOrNull()?.UID,
            selection = AudioOutputSelection.SystemPicker, ready = true,
        )
    }

    override fun select(deviceId: String?) { error("iOS requires the visible AVRoutePickerView for output selection") }
    override fun close() { center.removeObserver(observer) }
}

public actual fun AudioOutputs.Companion.desktop(scope: CoroutineScope): AudioOutputs = ios(scope)
