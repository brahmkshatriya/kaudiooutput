package dev.brahmkshatriya.kaudiooutput

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AudioOutputsTest {
    private val speaker = AudioOutputDevice("speaker", "Speaker", AudioOutputKind.Speaker)
    private val usb = AudioOutputDevice("usb", "USB DAC", AudioOutputKind.Usb)
    private class Backend(devices: List<AudioOutputDevice>) : AudioOutputBackend {
        override val state = MutableStateFlow(AudioOutputState(devices, selection = AudioOutputSelection.Direct, ready = true))
        var closed = false
        override fun select(deviceId: String?) { state.value = state.value.copy(selectedDeviceId = deviceId) }
        override fun close() { closed = true }
    }

    @Test fun bothPlayersFollowSelectionAndDisconnectedOutputFallsBack() = runTest {
        val backend = Backend(listOf(speaker, usb))
        val outputs = AudioOutputs(this, backend)
        val first = mutableListOf<String?>()
        val second = mutableListOf<String?>()
        val firstBinding = outputs.bind { first += it?.id }
        outputs.select("usb")
        runCurrent()
        val secondBinding = outputs.bind { second += it?.id }
        assertEquals(listOf(null, "usb"), first)
        assertEquals(listOf<String?>("usb"), second)
        backend.state.value = backend.state.value.copy(devices = listOf(speaker), selectedDeviceId = null)
        runCurrent()
        assertEquals(null, first.last())
        assertEquals(null, second.last())
        firstBinding.close()
        outputs.select("speaker")
        runCurrent()
        assertEquals(null, first.last())
        assertEquals("speaker", second.last())
        secondBinding.close(); outputs.close()
        assertTrue(backend.closed)
        assertFailsWith<IllegalStateException> { outputs.select(null) }
    }

    @Test fun rejectedDeviceDoesNotChangeSelection() = runTest {
        val backend = Backend(listOf(speaker))
        val outputs = AudioOutputs(this, backend)
        assertFailsWith<IllegalArgumentException> { outputs.select("missing") }
        assertEquals(null, outputs.state.value.selectedDeviceId)
        outputs.close()
    }

    @Test fun bindingFailureDoesNotCancelOtherPlayerBindings() = runTest {
        val backend = Backend(listOf(speaker))
        val outputs = AudioOutputs(this, backend)
        val failures = mutableListOf<Throwable>()
        val errors = backgroundScope.launch { outputs.errors.collect { failures += it } }
        runCurrent()
        val broken = outputs.bind { if (it != null) error("Player rejected the device") }
        val values = mutableListOf<String?>()
        val working = outputs.bind { values += it?.id }
        outputs.select("speaker"); runCurrent()
        assertEquals("speaker", values.last())
        assertEquals("Player rejected the device", failures.single().message)
        broken.close(); working.close(); errors.cancel(); outputs.close()
    }

    @Test fun nativeSnapshotHandlesUnicodeAndMissingDefault() {
        val state = decodeNativeSnapshot("""{"devices":[{"id":"dac","name":"耳机 \"USB\"","kind":"Usb"}],"selected":"dac","selection":"Direct","ready":true}""")
        assertEquals("耳机 \"USB\"", state.selectedDevice?.name)
        assertEquals(AudioOutputKind.Usb, state.selectedDevice?.kind)
        assertTrue(state.ready)
    }
}
