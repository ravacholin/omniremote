package com.example.controller

import android.content.Context
import com.example.BuildConfig
import com.example.data.local.AppDatabase
import com.example.data.local.TvTokenStore
import com.example.data.model.ConnectionState
import com.example.data.model.DeviceType
import com.example.data.model.RemoteKey
import com.example.data.model.TvDeviceEntity
import com.example.data.repository.TvDeviceRepository
import com.example.network.TvScanner
import com.example.network.WakeOnLan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/** One authorized transport shared by every screen and the notification service. */
class RemoteSession private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository = TvDeviceRepository(AppDatabase.getInstance(context).tvDeviceDao())
    val tokenStore = TvTokenStore.getInstance(context)
    internal val selectedDevice = MutableStateFlow<TvDeviceEntity?>(null)
    internal val playing = MutableStateFlow(true) // Estimated state, not telemetry from the TV.
    internal val powerStatus = MutableStateFlow<String?>(null)
    private val generation = AtomicLong()
    val targetGeneration: Long get() = generation.get()
    private var wakeJob: Job? = null

    val controller = UnifiedTvController(
        context = context,
        tokenStore = tokenStore,
        onTokenReceived = { id, token ->
            scope.launch {
                withContext(Dispatchers.IO) { repository.updateAuthToken(id, token) }
                selectedDevice.value?.takeIf { it.id == id }?.let {
                    selectedDevice.value = it.copy(authToken = token)
                }
            }
        },
        onDeviceMacResolved = { id, mac ->
            scope.launch {
                withContext(Dispatchers.IO) { repository.updateDeviceMac(id, mac) }
                selectedDevice.value?.takeIf { it.id == id }?.let {
                    selectedDevice.value = it.copy(macAddress = mac)
                }
            }
        }
    )

    fun selectDevice(device: TvDeviceEntity) {
        val old = selectedDevice.value
        if (old?.id != device.id || old.ipAddress != device.ipAddress || old.deviceType != device.deviceType) {
            cancelWake()
            generation.incrementAndGet()
            playing.value = true
        }
        controller.setActiveDevice(device)
        selectedDevice.value = device
    }

    fun clearDevice() {
        cancelWake()
        generation.incrementAndGet()
        selectedDevice.value = null
        controller.disconnectCurrent()
    }

    fun resumeConnection() {
        selectedDevice.value?.let { controller.resumeDevice(it) }
    }

    fun disconnectForBackground() {
        cancelWake()
        generation.incrementAndGet()
        controller.disconnectCurrent()
    }

    fun sendKey(key: RemoteKey) {
        if (key == RemoteKey.POWER) {
            powerToggle()
            return
        }
        // Do not save taps while disconnected and replay them against a later connection.
        if (controller.connectionState.value !is ConnectionState.Connected) return
        if (key == RemoteKey.PLAY_PAUSE) {
            if (controller.isCurrentDeviceMac()) controller.sendKey(key)
            else {
                val next = !playing.value
                playing.value = next
                controller.sendKey(if (next) RemoteKey.PLAY else RemoteKey.PAUSE)
            }
            return
        }
        when (key) {
            RemoteKey.PLAY -> playing.value = true
            RemoteKey.PAUSE, RemoteKey.STOP -> playing.value = false
            else -> Unit
        }
        controller.sendKey(key)
    }

    fun powerToggle(onMissingMac: ((TvDeviceEntity) -> Unit)? = null) {
        if (wakeJob?.isActive == true) return
        when (controller.connectionState.value) {
            is ConnectionState.Connected -> controller.sendKey(RemoteKey.POWER)
            is ConnectionState.Connecting, is ConnectionState.WaitingAuth -> Unit
            else -> powerOnTv(onMissingMac)
        }
    }

    fun powerOnTv(onMissingMac: ((TvDeviceEntity) -> Unit)? = null) {
        if (wakeJob?.isActive == true) return
        val device = selectedDevice.value ?: return
        val expected = generation.get()
        val samsungProbe = controller.prepareWakeRequest(device)
        wakeJob = scope.launch {
            powerStatus.value = "Enviando ráfaga Wake-on-LAN a ${device.name}..."
            val result = WakeOnLan.powerOnTv(device, TvScanner(context).getSubnetPrefix(), context)
            if (generation.get() != expected) return@launch
            if (result.resolvedMac != null && device.macAddress.isBlank()) {
                withContext(Dispatchers.IO) { repository.updateDeviceMac(device.id, result.resolvedMac) }
                if (generation.get() != expected) return@launch
                selectedDevice.value = selectedDevice.value?.copy(macAddress = result.resolvedMac)
            }
            if (!result.success) {
                controller.cancelWakeRequest(device)
                powerStatus.value = result.message
                if (WakeOnLan.formatMacAddress(device.macAddress) == null) onMissingMac?.invoke(device)
                return@launch
            }
            powerStatus.value = if (samsungProbe)
                "Prueba Samsung: WOL enviado. La conexión se intentará automáticamente a los ${BuildConfig.SAMSUNG_WAKE_RECONNECT_DELAY_MS / 1000} segundos."
            else result.message
            repeat(if (samsungProbe) 30 else 10) {
                delay(1500)
                if (generation.get() != expected) return@launch
                when (controller.connectionState.value) {
                    is ConnectionState.Connected, is ConnectionState.WaitingAuth -> {
                        powerStatus.value = null
                        return@launch
                    }
                    is ConnectionState.Connecting -> Unit
                    else -> {
                        if (samsungProbe) {
                            powerStatus.value = null
                            return@launch
                        }
                        controller.reconnect()
                    }
                }
            }
            delay(1500)
            powerStatus.value = null
        }
    }

    private fun cancelWake() {
        wakeJob?.cancel()
        wakeJob = null
        powerStatus.value = null
    }

    companion object {
        @Volatile private var instance: RemoteSession? = null
        fun getInstance(context: Context): RemoteSession = synchronized(this) {
            val app = context.applicationContext
            instance?.takeIf { it.context === app }
                ?: RemoteSession(app).also { instance = it }
        }
    }
}
