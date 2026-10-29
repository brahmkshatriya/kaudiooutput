@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brahmkshatriya.kaudiooutput

import platform.AVFoundation.AVPlayer
import platform.AVFoundation.audioOutputDeviceUniqueID

/** Sets AVPlayer's output UID without changing the Mac's global default output. */
public fun AudioOutputs.bindAVPlayer(player: AVPlayer): AutoCloseable =
    bind { device -> player.audioOutputDeviceUniqueID = device?.id }
