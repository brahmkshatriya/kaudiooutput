# KAudioOutput

[![Maven Central](https://img.shields.io/maven-central/v/dev.brahmkshatriya.kaudiooutput/lib?label=Maven%20Central)](https://central.sonatype.com/artifact/dev.brahmkshatriya.kaudiooutput/lib)
[![Publish](https://github.com/brahmkshatriya/kaudiooutput/actions/workflows/publish.yml/badge.svg)](https://github.com/brahmkshatriya/kaudiooutput/actions/workflows/publish.yml)

Let listeners choose where their audio plays. KAudioOutput provides audio device discovery, output selection, and native route pickers for Kotlin Multiplatform apps. It works with your existing player and UI, without requiring Compose.

## Installation

Add `mavenCentral()` and `google()` to your dependency repositories, then add the library to your shared module:

```kotlin
commonMain.dependencies {
    implementation("dev.brahmkshatriya.kaudiooutput:lib:0.1.1")
}
```

## Getting started

Create one manager for your playback session. Choose a factory in platform code, then pass the resulting `AudioOutputs` into your shared code:

```kotlin
import dev.brahmkshatriya.kaudiooutput.*

// Android
val outputs = AudioOutputs.android(applicationContext, playbackScope)

// Desktop: Kotlin/Native or JVM
val outputs = AudioOutputs.desktop(playbackScope)

// iOS, including simulators
val outputs = AudioOutputs.ios(playbackScope)
```

Use a scope with the dispatcher your player requires. Collect `state` to keep your output picker up to date as devices connect or disconnect:

```kotlin
playbackScope.launch {
    outputs.state.collect { state ->
        // state.devices: available devices, with id, name, and kind
        // state.selectedDeviceId: the selected preference
        // state.ready: discovery has finished initializing
        // state.selection: Direct, SystemPicker, or Unavailable
    }
}
```

When `state.selection` is `Direct`, show your own device list and select an output by ID:

```kotlin
outputs.select(device.id)
outputs.select(null) // Follow the system default again.
```

When it is `SystemPicker`, use the platform's picker described below. With `Unavailable`, hide the selection control or explain that output selection is handled outside your app. A selected device is a routing preference; the operating system decides the physical route.

## Connect your player

### Android

Bind the manager to your player's preferred-device setter. ExoPlayer, MediaPlayer, and AudioTrack can use the same adapter:

```kotlin
val binding = outputs.bindAndroidDevice(exoPlayer::setPreferredAudioDevice)
```

The library uses the application context and observes connected media outputs. It does not require Bluetooth discovery permissions.

### Desktop

On Linux, selection routes this process's PulseAudio streams, including new streams created after selection. PipeWire works through its PulseAudio compatibility server. No player binding is needed. Install your distribution's PulseAudio client runtime (`libpulse0` on Ubuntu/Debian).

On macOS Kotlin/Native, bind each AVPlayer:

```kotlin
val binding = outputs.bindAVPlayer(avPlayer)
```

For macOS JVM or another player with its own output API, use a custom binding. Device IDs on macOS are Core Audio device UIDs:

```kotlin
val binding = outputs.bind { device ->
    player.setOutputDevice(device?.id) // Adapt to your player's API; null means system default.
}
```

On Windows, offer a button that opens the system's per-app audio output settings:

```kotlin
outputs.showSystemPicker()
```

The JVM release includes native bridges for Linux x64/ARM64, Windows x64, and macOS x64/ARM64.

### iOS

Embed the native route picker in UIKit, or wrap it in Compose's `UIKitView`:

```kotlin
val routePicker = outputs.createRoutePickerView()
```

The visible `AVRoutePickerView` lets iOS present the available routes and handle selection. `state.devices` describes the current route, rather than every possible destination. Configure your app's audio session and background playback as usual. `showSystemPicker()` returns `false` on iOS; embed the view instead.

## Platform support

| Platform | Output selection |
| --- | --- |
| Android API 24+ | Connected media devices, applied to bound players |
| Linux x64 / ARM64 | Per-process routing through PulseAudio or PipeWire's PulseAudio server |
| macOS x64 / ARM64 | Core Audio devices, applied through AVPlayer or a custom player binding |
| Windows x64 | Native per-app audio output settings |
| iOS device / simulators | Native route picker and current-route updates |
| JVM | The host platform's desktop behavior |
| JavaScript / WasmJS | Reports `Unavailable` |

Use `state.selection` to choose the appropriate UI instead of checking operating-system names. Creating a manager does not start playback or activate an audio session.

## Lifecycle and errors

Keep one binding per player, including any standby or crossfade player. The first binding callback runs immediately; later callbacks use the supplied scope's dispatcher. Call control methods and close bindings on that dispatcher too.

Collect `outputs.errors` to show discovery or player-binding failures:

```kotlin
playbackScope.launch {
    outputs.errors.collect { error ->
        showError(error.message ?: "Couldn't change the audio output")
    }
}
```

Before releasing a player, close its binding. Close the manager when the playback session ends:

```kotlin
binding.close()
outputs.close()
```

Closing a binding or manager leaves your player and the supplied scope under your app's ownership. If a selected device disconnects, the built-in direct-selection backends fall back to system routing.

## Custom backends

For a different device system or transport, implement `AudioOutputBackend` and pass it to `AudioOutputs(playbackScope, backend)`. Publish device changes through the backend's `StateFlow<AudioOutputState>` and implement selection and cleanup. Your existing UI and player bindings can keep using the same consumer API.

## License

[Apache License 2.0](LICENSE).
