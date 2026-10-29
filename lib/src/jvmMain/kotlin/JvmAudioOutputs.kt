package dev.brahmkshatriya.kaudiooutput

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.file.Files

public actual fun AudioOutputs.Companion.desktop(scope: CoroutineScope): AudioOutputs = AudioOutputs(scope, JvmAudioOutputBackend(scope))

private interface Bridge : Library {
    interface Changed : Callback { fun invoke(user: Pointer?, json: Pointer?) }
    fun kao_create(changed: Changed, user: Pointer?): Pointer?
    fun kao_destroy(context: Pointer)
    fun kao_snapshot(context: Pointer): Pointer?
    fun kao_select(context: Pointer, id: String?): Int
    fun kao_show_picker(context: Pointer): Int
    fun kao_free(memory: Pointer)
}

private fun loadBridge(): Bridge {
    val os = System.getProperty("os.name").lowercase().let { when { it.startsWith("windows") -> "windows"; it.startsWith("mac") -> "macos"; else -> "linux" } }
    val arch = if (System.getProperty("os.arch") in setOf("arm64", "aarch64")) "aarch64" else "x86_64"
    val name = when (os) { "windows" -> "kaudiooutput.dll"; "macos" -> "libkaudiooutput.dylib"; else -> "libkaudiooutput.so" }
    val resource = Bridge::class.java.getResourceAsStream("/native/$os-$arch/$name")
        ?: error("No KAudioOutput bridge packaged for $os-$arch")
    val file = Files.createTempFile("kaudiooutput-", name.substringAfterLast('.').let { ".$it" }).toFile()
    file.deleteOnExit()
    resource.use { input -> file.outputStream().use(input::copyTo) }
    return Native.load(file.absolutePath, Bridge::class.java)
}

private class JvmAudioOutputBackend(scope: CoroutineScope) : AudioOutputBackend {
    private val mutable = MutableStateFlow(AudioOutputState())
    override val state: StateFlow<AudioOutputState> = mutable.asStateFlow()
    private val updates = Channel<String>(Channel.CONFLATED)
    private val callback = object : Bridge.Changed {
        override fun invoke(user: Pointer?, json: Pointer?) { json?.getString(0, "UTF-8")?.let { updates.trySend(it) } }
    }
    private var bridge: Bridge? = null
    private var handle: Pointer? = null
    private val observer = scope.launch { for (json in updates) publish(json) }

    init {
        try {
            bridge = loadBridge()
            handle = checkNotNull(bridge!!.kao_create(callback, null)) { "Could not connect to the audio output service" }
            snapshot()
        } catch (error: Throwable) {
            mutable.value = AudioOutputState(ready = true, error = error.message ?: "Could not load the native audio output service")
        }
    }

    private fun publish(json: String) {
        mutable.value = try { decodeNativeSnapshot(json) } catch (error: Exception) { mutable.value.copy(error = error.message) }
    }
    private fun snapshot() {
        val native = bridge ?: return
        val context = handle ?: return
        native.kao_snapshot(context)?.let { value ->
            try { publish(value.getString(0, "UTF-8")) } finally { native.kao_free(value) }
        }
    }
    override fun select(deviceId: String?) {
        val context = checkNotNull(handle) { "Audio output service is unavailable" }
        check(bridge!!.kao_select(context, deviceId) != 0) { "Could not select the audio device" }
        snapshot()
    }
    override fun showSystemPicker(): Boolean = handle?.let { bridge!!.kao_show_picker(it) != 0 } ?: false
    override fun close() {
        handle?.let { bridge?.kao_destroy(it) }; handle = null
        observer.cancel(); updates.close()
    }
}
