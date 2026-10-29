package dev.brahmkshatriya.kaudiooutput

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun decodeNativeSnapshot(value: String): AudioOutputState {
    val root = Json.parseToJsonElement(value).jsonObject
    fun text(key: String): String? = root[key]?.jsonPrimitive?.contentOrNull
    return AudioOutputState(
        devices = root["devices"]?.jsonArray.orEmpty().map { item ->
            val device = item.jsonObject
            AudioOutputDevice(
                device.getValue("id").jsonPrimitive.content,
                device.getValue("name").jsonPrimitive.content,
                AudioOutputKind.entries.firstOrNull { it.name == device["kind"]?.jsonPrimitive?.content } ?: AudioOutputKind.Other,
            )
        },
        selectedDeviceId = text("selected"),
        defaultDeviceId = text("default"),
        selection = AudioOutputSelection.entries.firstOrNull { it.name == text("selection") } ?: AudioOutputSelection.Unavailable,
        ready = text("ready") == "true",
        error = text("error"),
    )
}
