package com.example.network

import com.example.data.model.RemoteKey
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** Normal settings-menu navigation for the user's Samsung RU7100 (2019).
 * Stops at the Bluetooth speaker list, leaving device selection to the user.
 * Samsung's remote socket cannot read focus, so this requires validation on the TV.
 */
object SamsungBluetoothAudio {
    suspend fun open(sendKey: (RemoteKey) -> Boolean): Boolean {
        suspend fun press(key: RemoteKey, settleMs: Long = 200): Boolean {
            currentCoroutineContext().ensureActive()
            if (!sendKey(key)) return false
            delay(settleMs)
            return true
        }

        // Close the current overlay, then open the full Settings menu with KEY_MENU.
        if (!press(RemoteKey.EXIT, 300) || !press(RemoteKey.MENU, 1200)) return false

        // Return to the category column and its first item (Picture), then select Sound.
        repeat(3) { if (!press(RemoteKey.LEFT)) return false }
        repeat(8) { if (!press(RemoteKey.UP)) return false }
        if (!press(RemoteKey.DOWN) || !press(RemoteKey.RIGHT, 400)) return false

        // Sound Output is the first row. Open it, then reach Bluetooth Speaker List
        // below the TV/HDMI/optical outputs. End after opening the list, without pairing.
        repeat(6) { if (!press(RemoteKey.UP)) return false }
        if (!press(RemoteKey.ENTER, 500)) return false
        repeat(8) { if (!press(RemoteKey.DOWN)) return false }
        return press(RemoteKey.ENTER, 500)
    }
}
