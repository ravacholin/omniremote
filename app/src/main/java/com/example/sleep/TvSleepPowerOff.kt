package com.example.sleep

import android.content.Context
import com.example.data.local.TvTokenStore
import com.example.data.model.DeviceType
import com.example.data.model.RemoteKey
import com.example.data.model.TvDeviceEntity
import com.example.network.AndroidTvClient
import com.example.network.SamsungTvClient
import com.example.network.WakeOnLan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** A private connection to the saved target, independent of the TV currently shown in the UI. */
class TvSleepPowerOff(private val context: Context) {
    suspend fun powerOff(device: TvDeviceEntity, canSend: () -> Boolean = { true }): Boolean = withContext(Dispatchers.IO) {
        if (!canSend()) return@withContext false
        try {
            withTimeoutOrNull(30_000) {
                when (device.deviceType) {
                    DeviceType.ANDROID_TV -> androidPowerOff(device, canSend)
                    DeviceType.SAMSUNG_TIZEN -> samsungPowerOff(device, canSend)
                    DeviceType.MACOS -> false
                }
            } ?: false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun androidPowerOff(device: TvDeviceEntity, canSend: () -> Boolean): Boolean {
        val savedMac = WakeOnLan.formatMacAddress(device.macAddress)
        if (savedMac != null) {
            val observedMac = WakeOnLan.fetchMacFromTv(device.ipAddress, device.deviceType)
            if (observedMac != null && observedMac != savedMac) return false
        }
        // Discovery may have saved a Cast, ADB or pairing port. Use the same
        // Remote v2 control endpoint as the interactive remote, including for saved devices.
        val client = AndroidTvClient(context, TvTokenStore.getInstance(context))
        return try {
            client.connect(device.ipAddress, device.port, device.id) && client.powerOff(canSend)
        } finally {
            client.disconnect()
        }
    }

    private suspend fun samsungPowerOff(device: TvDeviceEntity, canSend: () -> Boolean): Boolean {
        val store = TvTokenStore.getInstance(context)
        val token = store.getSamsungToken(device.ipAddress, device.id, device.macAddress, device.authToken)
        if (token.isBlank()) return false
        val ready = CompletableDeferred<Boolean>()
        val client = SamsungTvClient(
            tokenStore = store,
            onConnectionChanged = { connected, error ->
                if (connected) ready.complete(true) else if (error != null) ready.complete(false)
            },
            onWaitingAuthPrompt = { ready.complete(false) }
        )
        return try {
            client.connect(device.ipAddress, 8002, token, device.id, device.macAddress)
            ready.await() && canSend() && client.sendKey(RemoteKey.POWER_OFF)
        } finally {
            // OkHttp drains queued messages before its graceful WebSocket close.
            client.disconnect()
        }
    }
}
