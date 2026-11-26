package com.example.controller

import android.content.Context
import com.example.data.local.TvTokenStore
import com.example.data.model.DeviceType
import com.example.data.model.TvDeviceEntity
import com.example.network.AndroidTvClient
import com.example.network.MacResult
import com.example.network.MacSshClient
import com.example.network.SamsungTvClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Short-lived control session for a saved target, independent of the selected UI session. */
internal class TargetRemoteConnection(
    context: Context,
    private val store: TvTokenStore,
    val device: TvDeviceEntity
) {
    enum class Readiness { READY, UNREACHABLE, AUTHORIZATION_REQUIRED }

    private val ready = CompletableDeferred<Readiness>()
    private var androidAuthRequired = false
    private val androidClient = lazy {
        AndroidTvClient(context, store, onPairingPromptNeeded = { androidAuthRequired = true })
    }
    private val samsungClient = lazy {
        SamsungTvClient(
            tokenStore = store,
            onConnectionChanged = { connected, _ ->
                ready.complete(if (connected) Readiness.READY else Readiness.UNREACHABLE)
            },
            onWaitingAuthPrompt = { ready.complete(Readiness.AUTHORIZATION_REQUIRED) }
        )
    }
    private val macClient = lazy { MacSshClient(store) }
    val android get() = androidClient.value
    val samsung get() = samsungClient.value
    val mac get() = macClient.value

    suspend fun <T> use(block: suspend (Readiness) -> T): T = coroutineScope {
        // Close a socket from another coroutine when a blocking read/write is cancelled.
        val closer = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                if (androidClient.isInitialized()) android.disconnect()
                if (samsungClient.isInitialized()) samsung.disconnect()
                if (macClient.isInitialized()) mac.disconnect()
            }
        }
        try {
            block(connect())
        } finally {
            closer.cancelAndJoin()
        }
    }

    private suspend fun connect(): Readiness = when (device.deviceType) {
        DeviceType.ANDROID_TV -> {
            if (android.connect(device.ipAddress, device.port, device.id)) Readiness.READY
            else if (androidAuthRequired) Readiness.AUTHORIZATION_REQUIRED else Readiness.UNREACHABLE
        }
        DeviceType.SAMSUNG_TIZEN -> {
            val token = store.getSamsungToken(device.ipAddress, device.id, device.macAddress, device.authToken)
            if (token.isBlank()) Readiness.AUTHORIZATION_REQUIRED else {
                samsung.connect(device.ipAddress, device.port, token, device.id, device.macAddress)
                ready.await()
            }
        }
        DeviceType.MACOS -> when (mac.connect(device.id, device.ipAddress, device.port)) {
            is MacResult.Ok -> Readiness.READY
            is MacResult.LoginNeeded -> Readiness.AUTHORIZATION_REQUIRED
            is MacResult.Failed -> Readiness.UNREACHABLE
        }
    }
}
