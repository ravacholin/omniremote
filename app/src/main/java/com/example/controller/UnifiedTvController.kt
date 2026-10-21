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

class UnifiedTvController(
    private val context: Context? = null,
    private val tokenStore: TvTokenStore? = null,
    private val onTokenReceived: ((Long, String) -> Unit)? = null,
    private val onDeviceMacResolved: ((Long, String) -> Unit)? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pairingJob: kotlinx.coroutines.Job? = null
    private var connectionJob: kotlinx.coroutines.Job? = null
    private var pairingSubmitJob: kotlinx.coroutines.Job? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _recentLogs = MutableStateFlow<List<CommandLog>>(emptyList())
    val recentLogs: StateFlow<List<CommandLog>> = _recentLogs.asStateFlow()

    private val _lastDispatchedKey = MutableStateFlow<RemoteKey?>(null)
    val lastDispatchedKey: StateFlow<RemoteKey?> = _lastDispatchedKey.asStateFlow()

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
            scope.launch {
                val dev = activeDevice ?: return@launch
                if (connected) {
                    _connectionState.value = ConnectionState.Connected(dev.name, dev.ipAddress)
                    // If device doesn't have a MAC address yet, resolve it right now from the active TV!
                    if (dev.macAddress.isBlank()) {
                        scope.launch(Dispatchers.IO) {
                            val mac = WakeOnLan.fetchMacFromTv(dev.ipAddress, dev.deviceType)
                            if (!mac.isNullOrBlank()) {
                                activeDevice = activeDevice?.copy(macAddress = mac)
                                onDeviceMacResolved?.invoke(dev.id, mac)
                                tokenStore?.saveSamsungToken(dev.ipAddress, dev.authToken, dev.id, mac)
                                addLog(dev.name, "Dirección MAC guardada para Wake-on-LAN: $mac", true)
                            }
                        }
                    }
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
            val source = activeDevice
            scope.launch {
                val dev = source ?: return@launch
                if (activeDevice?.id != dev.id) return@launch
                if (connected) {
                    _connectionState.value = ConnectionState.Connected(dev.name, dev.ipAddress)
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
                if (dev.ipAddress != ip) return@launch
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
        val previous = pairingJob
        val previousSubmission = pairingSubmitJob
        val previousConnection = connectionJob
        previousConnection?.cancel()
        previousSubmission?.cancel()
        previous?.cancel()
        androidClient.cancelPairing()
        pairingJob = scope.launch {
            previous?.join()
            previousSubmission?.join()
            previousConnection?.join()
            if (activeDevice?.id != dev.id) return@launch
            androidClient.startPairing(dev.ipAddress, onShowPinDialog, onResult)
        }
    }

    fun cancelAndroidTvPairing() {
        pairingSubmitJob?.cancel()
        pairingJob?.cancel()
        androidClient.cancelPairing()
    }

    fun androidTvPairingError(): String? = androidClient.lastPairingError

    suspend fun submitAndroidTvPin(pin: String): Boolean {
        val device = activeDevice ?: return false
        val job = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]
        pairingSubmitJob = job
        try {
            val success = androidClient.submitPairingPin(pin)
            if (activeDevice?.id != device.id) return false
            if (success) {
                _connectionState.value = ConnectionState.Connected(device.name, device.ipAddress)
                addLog(device.name, "Google TV / Android TV vinculado correctamente!", true)
            }
            return success
        } finally {
            if (pairingSubmitJob === job) pairingSubmitJob = null
        }
    }

    fun isCurrentDeviceAndroidTv(): Boolean {
        return activeDevice?.deviceType == DeviceType.ANDROID_TV
    }

    fun isCurrentAndroidTvPaired(): Boolean {
        val dev = activeDevice ?: return false
        return if (dev.deviceType == DeviceType.ANDROID_TV) {
            tokenStore?.isAndroidTvPaired(dev.ipAddress) ?: androidClient.isPaired
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
                val previous = connectionJob
                connectionJob = scope.launch {
                    previous?.join()
                    androidClient.connect(effectiveDevice.ipAddress, effectiveDevice.port)
                    // Connection and pairing state come from the validated protocol callbacks.
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

    private fun disconnectCurrent() {
        connectionJob?.cancel()
        cancelAndroidTvPairing()
        samsungClient.disconnect()
        androidClient.disconnect()
        _connectionState.value = ConnectionState.Disconnected
    }

    fun sendKey(key: RemoteKey) {
        val device = activeDevice ?: return
        _lastDispatchedKey.value = key

        scope.launch(Dispatchers.IO) {
            // If the command is POWER, always broadcast Wake-on-LAN and Network Wakeup so standby TVs turn ON immediately
            if (key == RemoteKey.POWER) {
                WakeOnLan.powerOnTv(device, context = context)
            }

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
