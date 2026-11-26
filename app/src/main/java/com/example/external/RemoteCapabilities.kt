package com.example.external

import com.example.data.model.DeviceType
import com.example.data.model.RemoteKey
import com.example.network.MacScripts

internal fun supportedKeys(type: DeviceType): List<RemoteKey> = RemoteKey.entries.filter { key ->
    // TVs use the guarded explicit power operations, never a raw POWER toggle.
    key != RemoteKey.POWER && key != RemoteKey.POWER_OFF &&
        (type != DeviceType.MACOS || MacScripts.forKey(key) != null)
}

internal fun supportedCommands(type: DeviceType, hasDimmer: Boolean, hasAdb: Boolean): Set<Command> = buildSet {
    addAll(listOf(Command.GET_STATE, Command.KEY, Command.POWER_ON, Command.POWER_OFF))
    when (type) {
        DeviceType.MACOS -> addAll(listOf(Command.SET_VOLUME, Command.SET_MUTE, Command.MAC_OPEN_LINK,
            Command.MAC_MEDIA_LIST, Command.MAC_MEDIA_STATE, Command.MAC_MEDIA_REVEAL, Command.MAC_MEDIA_KEY))
        DeviceType.ANDROID_TV, DeviceType.SAMSUNG_TIZEN -> {
            addAll(listOf(Command.TEXT, Command.OPEN_APP, Command.SLEEP_FOR, Command.SLEEP_AT,
                Command.SLEEP_CANCEL, Command.SLEEP_EXTEND))
            if (type == DeviceType.SAMSUNG_TIZEN) add(Command.OPEN_BLUETOOTH_AUDIO)
            if (type == DeviceType.ANDROID_TV) {
                addAll(listOf(Command.SET_VOLUME, Command.SET_MUTE))
                if (hasDimmer) {
                    add(Command.OPEN_BRIGHTNESS)
                    if (hasAdb) add(Command.OPEN_BRIGHTNESS_ADB)
                }
            }
        }
    }
}
