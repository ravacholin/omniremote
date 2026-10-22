package com.example.controller

import android.util.Log
import com.example.data.model.CommandLog
import com.example.data.model.ConnectionState
import com.example.data.model.DeviceType
import com.example.data.model.RemoteKey
import com.example.data.model.TvAppEntity
import com.example.data.model.TvAppShortcut
import com.example.data.model.TvDeviceEntity
import com.example.network.AndroidTvClient
import com.example.network.SamsungTvClient
import com.example.network.WakeOnLan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.content.Context
import com.example.data.local.TvTokenStore
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

class UnifiedTvController(
    private val context: Context? = null,
    private val tokenStore: TvTokenStore? = null,
    private val onTokenReceived: ((Long, String) -> Unit)? = null,
    private val onDeviceMacResolved: ((Long, String) -> Unit)? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _recentLogs = MutableStateFlow<List<CommandLog>>(emptyList())
    val recentLogs: StateFlow<List<CommandLog>> = _recentLogs.asStateFlow()

    private val _lastDispatchedKey = MutableStateFlow<RemoteKey?>(null)
    val lastDispatchedKey: StateFlow<RemoteKey?> = _lastDispatchedKey.asStateFlow()

    private var connectionJob: Job? = null
    private var pairingJob: Job? = null

    private var activeDevice: TvDeviceEntity? = null

    private val samsungClient = SamsungTvClient(
        tokenStore = tokenStore,
        onTokenReceived = { token ->
            activeDevice = activeDevice?.copy(authToken = token)
            activeDevice?.let { dev ->
                tokenStore?.saveSamsungToken(dev.ipAddress, token, dev.id, dev.macAddress)
                onTokenReceived?.invoke(dev.id, token)
            }
        },
        onConnectionChanged = { connected, error ->
            val eventDevice = activeDevice
            scope.launch {
                val dev = eventDevice ?: return@launch
                if (activeDevice?.id != dev.id || activeDevice?.ipAddress != dev.ipAddress) return@launch
                if (connected) {
                    _connectionState.value = ConnectionState.Connected(dev.name, dev.ipAddress)
                    resolveDeviceMac(dev)
                } else if (error != null) {
                    _connectionState.value = ConnectionState.Error(error)
                } else {
                    _connectionState.value = ConnectionState.Disconnected
                }
            }
        },
        onWaitingAuthPrompt = {
            scope.launch {
                val dev = activeDevice ?: return@launch
                _connectionState.value = ConnectionState.WaitingAuth(
                    dev.name,
                    "Permite OmniRemote en la pantalla de tu Samsung TV con el control original."
                )
            }
        }
    )

    private val androidClient = AndroidTvClient(
        context = context,
        tokenStore = tokenStore,
        onConnectionChanged = { connected, error ->
            val eventDevice = activeDevice
            scope.launch {
                val dev = eventDevice ?: return@launch
                if (activeDevice?.id != dev.id || activeDevice?.ipAddress != dev.ipAddress) return@launch
                if (connected) {
                    _connectionState.value = ConnectionState.Connected(dev.name, dev.ipAddress)
                    resolveDeviceMac(dev)
                } else if (error != null) {
                    _connectionState.value = ConnectionState.Error(error)
                } else {
                    _connectionState.value = ConnectionState.Disconnected
                }
            }
        },
        onPairingPromptNeeded = { ip ->
            scope.launch {
                val dev = activeDevice ?: return@launch
                if (dev.ipAddress != ip || dev.deviceType != DeviceType.ANDROID_TV) return@launch
                _connectionState.value = ConnectionState.WaitingAuth(
                    dev.name,
                    "Google TV requiere vinculación. Pulsa Vincular para solicitar el código en la TV."
                )
            }
        }
    )

    fun startAndroidTvPairing(onShowPinDialog: () -> Unit, onResult: (Boolean, String?) -> Unit) {
        val dev = activeDevice
        if (dev == null) {
            onResult(false, "No hay ningún dispositivo seleccionado para vincular.")
            return
        }
        val connection = connectionJob
        val previousPairing = pairingJob
        connection?.cancel()
        previousPairing?.cancel()
        androidClient.cancelPairing()
        pairingJob = scope.launch {
            previousPairing?.join()
            connection?.join()
            androidClient.startPairing(dev.ipAddress, onShowPinDialog, onResult)
        }
    }

    suspend fun submitAndroidTvPin(pin: String): Boolean {
        val success = androidClient.submitPairingPin(pin)
        if (success) {
            val dev = activeDevice
            if (dev != null) {
                if (androidClient.isConnected) {
                    _connectionState.value = ConnectionState.Connected(dev.name, dev.ipAddress)
                }
                addLog(dev.name, "Google TV / Android TV vinculado correctamente!", true)
            }
        }
        return success
    }

    fun isCurrentDeviceAndroidTv(): Boolean {
        return activeDevice?.deviceType == DeviceType.ANDROID_TV
    }

    fun isCurrentAndroidTvPaired(): Boolean {
        val dev = activeDevice ?: return false
        return if (dev.deviceType == DeviceType.ANDROID_TV) {
            androidClient.isPaired
        } else false
    }

    fun setActiveDevice(device: TvDeviceEntity) {
        // Resolve token from persistent store to ensure latest token is always present
        val effectiveDevice = if (device.deviceType == DeviceType.SAMSUNG_TIZEN) {
            val savedToken = tokenStore?.getSamsungToken(device.ipAddress, device.id, device.macAddress) ?: ""
            val tokenToUse = if (savedToken.isNotBlank()) savedToken else device.authToken
            if (tokenToUse.isNotBlank() && device.authToken != tokenToUse) {
                device.copy(authToken = tokenToUse)
            } else {
                device
            }
        } else {
            device
        }

        if (activeDevice?.id == effectiveDevice.id && activeDevice?.ipAddress == effectiveDevice.ipAddress &&
            _connectionState.value is ConnectionState.Connected) {
            activeDevice = effectiveDevice
            return
        }

        // Disconnect previous
        disconnectCurrent()

        activeDevice = effectiveDevice
        _connectionState.value = ConnectionState.Connecting(effectiveDevice.name, effectiveDevice.ipAddress)
        addLog(effectiveDevice.name, "Connecting to ${effectiveDevice.ipAddress} (${effectiveDevice.deviceType.shortName})...", true)

        when (effectiveDevice.deviceType) {
            DeviceType.SAMSUNG_TIZEN -> {
                samsungClient.connect(effectiveDevice.ipAddress, 8002, effectiveDevice.authToken)
            }
            DeviceType.ANDROID_TV -> {
                connectionJob = scope.launch {
                    val ok = androidClient.connect(effectiveDevice.ipAddress, effectiveDevice.port)
                    if (ok) addLog(effectiveDevice.name, "Conectado a ${effectiveDevice.name}", true)
                    // The client emits the specific connection or authorization error.
                }
            }
        }
    }

    fun reconnect() {
        activeDevice?.let { dev ->
            disconnectCurrent()
            setActiveDevice(dev)
        }
    }

    fun cancelAndroidTvPairing() {
        pairingJob?.cancel()
        androidClient.cancelPairing()
    }

    private fun disconnectCurrent() {
        connectionJob?.cancel()
        connectionJob = null
        pairingJob?.cancel()
        pairingJob = null
        activeDevice = null
        samsungClient.disconnect()
        androidClient.disconnect()
        _connectionState.value = ConnectionState.Disconnected
    }

    fun sendKey(key: RemoteKey) {
        val device = activeDevice ?: return
        _lastDispatchedKey.value = key

        scope.launch(Dispatchers.IO) {
            val success: Boolean = when (device.deviceType) {
                DeviceType.SAMSUNG_TIZEN -> {
                    samsungClient.sendKey(key)
                }
                DeviceType.ANDROID_TV -> {
                    androidClient.sendKey(key)
                }
            }

            val commandName = when (device.deviceType) {
                DeviceType.SAMSUNG_TIZEN -> key.samsungKey
                DeviceType.ANDROID_TV -> "KEYCODE_${key.name}"
            }
            addLog(device.name, "Sent $commandName (${key.label})", success)
        }
    }

    fun sendText(text: String) {
        val device = activeDevice ?: return
        scope.launch(Dispatchers.IO) {
            val success = when (device.deviceType) {
                DeviceType.SAMSUNG_TIZEN -> {
                    // For Samsung, send keys or websocket text payload
                    true
                }
                DeviceType.ANDROID_TV -> {
                    androidClient.sendText(text)
                }
            }
            addLog(device.name, "Sent text: \"$text\"", success)
        }
    }

    fun launchApp(shortcut: TvAppShortcut) {
        val device = activeDevice ?: return
        scope.launch(Dispatchers.IO) {
            val success = when (device.deviceType) {
                DeviceType.SAMSUNG_TIZEN -> {
                    samsungClient.launchApp(device.ipAddress, shortcut).isSuccess
                }
                DeviceType.ANDROID_TV -> {
                    androidClient.launchApp(shortcut)
                }
            }
            addLog(device.name, "Launched ${shortcut.title}", success)
        }
    }

    fun launchTvApp(app: TvAppEntity) {
        val device = activeDevice ?: return
        scope.launch(Dispatchers.IO) {
            val success = when (device.deviceType) {
                DeviceType.SAMSUNG_TIZEN -> {
                    samsungClient.launchAppById(device.ipAddress, app.appIdentifier).isSuccess
                }
                DeviceType.ANDROID_TV -> {
                    androidClient.launchAppByPackage(app.appIdentifier)
                }
            }
            addLog(device.name, "Launched ${app.appName}", success)
        }
    }

    fun sendWakeOnLan() {
        val device = activeDevice ?: return
        scope.launch(Dispatchers.IO) {
            addLog(device.name, "Enviando ráfaga Wake-on-LAN multi-vector a ${device.name}...", true)
            val result = WakeOnLan.powerOnTv(device, context = context)
            if (result.resolvedMac != null && device.macAddress.isBlank()) {
                onDeviceMacResolved?.invoke(device.id, result.resolvedMac)
            }
            addLog(device.name, result.message, result.success)
            // Delay and auto reconnect
            kotlinx.coroutines.delay(1800)
            reconnect()
        }
    }

    private fun resolveDeviceMac(device: TvDeviceEntity) {
        if (WakeOnLan.formatMacAddress(device.macAddress) != null) return
        scope.launch(Dispatchers.IO) {
            val mac = WakeOnLan.fetchMacFromTv(device.ipAddress, device.deviceType) ?: return@launch
            if (activeDevice?.id != device.id || activeDevice?.ipAddress != device.ipAddress) return@launch
            activeDevice = activeDevice?.copy(macAddress = mac)
            onDeviceMacResolved?.invoke(device.id, mac)
            if (device.deviceType == DeviceType.SAMSUNG_TIZEN) {
                val token = tokenStore?.getSamsungToken(device.ipAddress, device.id, mac).orEmpty()
                    .ifBlank { device.authToken }
                tokenStore?.saveSamsungToken(device.ipAddress, token, device.id, mac)
            }
            addLog(device.name, "Dirección MAC guardada para Wake-on-LAN: $mac", true)
        }
    }

    private fun addLog(deviceName: String, message: String, success: Boolean) {
        scope.launch(Dispatchers.Main) {
            val newLog = CommandLog(
                deviceName = deviceName,
                command = message,
                success = success
            )
            val updated = (listOf(newLog) + _recentLogs.value).take(15)
            _recentLogs.value = updated
        }
    }
}
