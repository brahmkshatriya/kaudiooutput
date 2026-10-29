package dev.brahmkshatriya.kaudiooutput

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

public enum class AudioOutputKind { Speaker, Headphones, Bluetooth, Usb, Hdmi, AirPlay, Virtual, Other }

public data class AudioOutputDevice(
    public val id: String,
    public val name: String,
    public val kind: AudioOutputKind = AudioOutputKind.Other,
) {
    init { require(id.isNotBlank() && name.isNotBlank()) }
}

/** Direct selection is a preference applied to bound players, or to this process on Linux. */
public enum class AudioOutputSelection { Direct, SystemPicker, Unavailable }

public data class AudioOutputState(
    public val devices: List<AudioOutputDevice> = emptyList(),
    /** Null follows the system output. A preference is not a guarantee of the physical route. */
    public val selectedDeviceId: String? = null,
    public val defaultDeviceId: String? = null,
    public val selection: AudioOutputSelection = AudioOutputSelection.Unavailable,
    public val ready: Boolean = false,
    public val error: String? = null,
) {
    public val selectedDevice: AudioOutputDevice? get() = devices.firstOrNull { it.id == selectedDeviceId }
}

/** Backend SPI for custom devices, transports, and player integrations. Publish state safely from platform callbacks. */
public interface AudioOutputBackend : AutoCloseable {
    public val state: StateFlow<AudioOutputState>
    public fun select(deviceId: String?)
    public fun showSystemPicker(): Boolean = false
}

/** Receives null for the system default. Run on the scope dispatcher passed to [AudioOutputs]. */
public fun interface AudioOutputBinding {
    public fun apply(device: AudioOutputDevice?)
}

/**
 * Own one instance per playback session. Closing a binding never closes its player.
 * Device discovery does not activate an audio session or start playback.
 * Call control methods and close bindings on the playback scope's dispatcher.
 */
public class AudioOutputs(
    scope: CoroutineScope,
    public val backend: AudioOutputBackend,
) : AutoCloseable {
    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val bindingScope = CoroutineScope(scope.coroutineContext + job)
    private val failures = MutableSharedFlow<Throwable>(extraBufferCapacity = 16)
    private var closed = false
    public val state: StateFlow<AudioOutputState> get() = backend.state
    public val errors: SharedFlow<Throwable> = failures.asSharedFlow()

    init {
        bindingScope.launch {
            state.map { it.error }.distinctUntilChanged().filterNotNull().collect {
                failures.emit(IllegalStateException(it))
            }
        }
    }

    public fun select(deviceId: String?) {
        check(!closed) { "Audio outputs are closed" }
        check(state.value.selection == AudioOutputSelection.Direct) { "This platform requires its system output picker" }
        require(deviceId == null || state.value.devices.any { it.id == deviceId }) { "The audio device is no longer available" }
        backend.select(deviceId)
    }

    /** The first callback is immediate; subsequent updates use the supplied scope dispatcher. */
    public fun bind(binding: AudioOutputBinding): AutoCloseable {
        check(!closed) { "Audio outputs are closed" }
        val observer = bindingScope.launch(start = CoroutineStart.UNDISPATCHED) {
            state.map { it.selectedDevice }.distinctUntilChanged().collect { device ->
                try { binding.apply(device) } catch (error: Exception) { failures.emit(error) }
            }
        }
        return AutoCloseable { observer.cancel() }
    }

    /** Returns false when a platform embeds a native picker view instead, as on iOS. */
    public fun showSystemPicker(): Boolean {
        check(!closed) { "Audio outputs are closed" }
        return backend.showSystemPicker()
    }

    override fun close() {
        if (closed) return
        closed = true
        bindingScope.cancel()
        backend.close()
    }

    public companion object
}

/** Uses Core Audio on macOS, PulseAudio/PipeWire on Linux, and the Windows output picker. */
public expect fun AudioOutputs.Companion.desktop(scope: CoroutineScope): AudioOutputs
