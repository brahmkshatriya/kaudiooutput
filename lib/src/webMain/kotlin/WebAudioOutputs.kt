package dev.brahmkshatriya.kaudiooutput

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Web output routing is not provided by this module. It never requests microphone access. */
public fun AudioOutputs.Companion.unsupported(scope: CoroutineScope): AudioOutputs = AudioOutputs(scope,
    object : AudioOutputBackend {
        override val state: StateFlow<AudioOutputState> = MutableStateFlow(AudioOutputState(ready = true))
        override fun select(deviceId: String?) { error("Use the browser's output selection API") }
        override fun close() = Unit
    })

public actual fun AudioOutputs.Companion.desktop(scope: CoroutineScope): AudioOutputs = unsupported(scope)
