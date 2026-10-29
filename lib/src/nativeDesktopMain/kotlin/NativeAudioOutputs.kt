@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brahmkshatriya.kaudiooutput

import dev.brahmkshatriya.kaudiooutput.interop.*
import kotlinx.cinterop.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

public actual fun AudioOutputs.Companion.desktop(scope: CoroutineScope): AudioOutputs =
    AudioOutputs(scope, NativeAudioOutputBackend(scope))

private class NativeAudioOutputBackend(scope: CoroutineScope) : AudioOutputBackend {
    private val mutable = MutableStateFlow(AudioOutputState())
    override val state: StateFlow<AudioOutputState> = mutable.asStateFlow()
    private val updates = Channel<String>(Channel.CONFLATED)
    private val reference = StableRef.create(updates)
    private var handle = kao_create(staticCFunction(::onAudioOutputChanged), reference.asCPointer())
    private val observer: Job

    init {
        if (handle == null) {
            mutable.value = AudioOutputState(ready = true, error = "Could not connect to the audio output service")
        } else {
            kao_snapshot(handle)?.let { json ->
                try { publish(json.toKString()) } finally { kao_free(json) }
            }
        }
        observer = scope.launch { for (json in updates) publish(json) }
    }

    private fun publish(json: String) {
        mutable.value = try { decodeNativeSnapshot(json) } catch (error: Exception) {
            mutable.value.copy(error = error.message ?: "Invalid audio device state")
        }
    }

    override fun select(deviceId: String?) {
        check(handle != null && kao_select(handle, deviceId) != 0) { "Could not select the audio device" }
        // The platform callback is queued; publish the accepted selection before returning.
        kao_snapshot(handle)?.let { json ->
            try { publish(json.toKString()) } finally { kao_free(json) }
        }
    }

    override fun showSystemPicker(): Boolean = handle != null && kao_show_picker(handle) != 0

    override fun close() {
        handle?.let(::kao_destroy); handle = null
        observer.cancel(); updates.close(); reference.dispose()
    }
}

private fun onAudioOutputChanged(user: COpaquePointer?, json: CPointer<ByteVar>?) {
    if (json != null) user?.asStableRef<Channel<String>>()?.get()?.trySend(json.toKString())
}
