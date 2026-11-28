package com.example.sleep

import android.content.Context
import com.example.controller.TargetRemoteConnection
import com.example.data.local.TvTokenStore
import com.example.data.model.DeviceType
import com.example.data.model.RemoteKey
import com.example.data.model.TvDeviceEntity
import com.example.network.AndroidTvProtocol
import com.example.network.WakeOnLan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** A private connection to the saved target, independent of the TV currently shown in the UI. */
class TvSleepPowerOff(private val context: Context) {
    internal enum class Outcome { VERIFIED, SENT, UNCERTAIN, UNREACHABLE, AUTHORIZATION_REQUIRED, IDENTITY_CHANGED, TARGET_CHANGED, UNSUPPORTED }

    suspend fun powerOff(device: TvDeviceEntity, canSend: () -> Boolean = { true }): Boolean =
        when (execute(device, canSend)) {
            Outcome.VERIFIED, Outcome.SENT -> true
            else -> false
        }

    internal suspend fun execute(
        device: TvDeviceEntity,
        canSend: () -> Boolean,
        isSavedTarget: suspend () -> Boolean = { true }
    ): Outcome = withContext(Dispatchers.IO) {
        try {
            withTimeoutOrNull(30_000) {
                withPowerOffLock(device.id) {
                    if (!canSend() || device.deviceType == DeviceType.MACOS) return@withPowerOffLock Outcome.UNCERTAIN
                    if (device.deviceType == DeviceType.ANDROID_TV) {
                        val savedMac = WakeOnLan.formatMacAddress(device.macAddress)
                        val observedMac = savedMac?.let { WakeOnLan.fetchMacFromTv(device.ipAddress, device.deviceType) }
                        if (observedMac != null && observedMac != savedMac) return@withPowerOffLock Outcome.IDENTITY_CHANGED
                    }
                    val target = TargetRemoteConnection(context, TvTokenStore.getInstance(context), device)
                    target.use { ready ->
                        when (ready) {
                            TargetRemoteConnection.Readiness.UNREACHABLE -> Outcome.UNREACHABLE
                            TargetRemoteConnection.Readiness.AUTHORIZATION_REQUIRED -> Outcome.AUTHORIZATION_REQUIRED
                            TargetRemoteConnection.Readiness.READY -> when {
                                !isSavedTarget() -> Outcome.TARGET_CHANGED
                                !canSend() -> Outcome.UNCERTAIN
                                device.deviceType == DeviceType.ANDROID_TV -> when {
                                    target.android.observedPower() == null &&
                                        target.android.supportsFeature(AndroidTvProtocol.FEATURE_POWER) == false &&
                                        !target.android.canPowerOff() -> Outcome.UNSUPPORTED
                                    target.android.powerOff(canSend) -> Outcome.VERIFIED
                                    else -> Outcome.UNCERTAIN
                                }
                                target.samsung.sendKey(RemoteKey.POWER_OFF) -> Outcome.SENT
                                else -> Outcome.UNCERTAIN
                            }
                        }
                    }
                }
            } ?: Outcome.UNCERTAIN
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Outcome.UNCERTAIN
        }
    }

    private companion object {
        class PowerOffLock(val mutex: Mutex = Mutex(), var users: Int = 0)
        val locks = mutableMapOf<Long, PowerOffLock>()

        /** Serializes Sleep expiry and explicit power-off for this target only. */
        suspend fun <T> withPowerOffLock(deviceId: Long, operation: suspend () -> T): T {
            val entry = synchronized(locks) {
                locks.getOrPut(deviceId) { PowerOffLock() }.also { it.users++ }
            }
            try {
                return entry.mutex.withLock { operation() }
            } finally {
                synchronized(locks) {
                    if (--entry.users == 0) locks.remove(deviceId)
                }
            }
        }
    }
}
