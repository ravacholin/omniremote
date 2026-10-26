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
import com.example.network.ComputerVolume
import com.example.network.MacPlatform
import com.example.network.SshCommand
import com.example.network.SshComputerClient
import com.example.network.SshResult
import com.example.network.WindowsPlatform
import com.example.network.SamsungTvClient
import com.example.network.SamsungBluetoothAudio
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

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

    private val _computerVolume = MutableStateFlow<ComputerVolume?>(null)
    val computerVolume: StateFlow<ComputerVolume?> = _computerVolume.asStateFlow()

    private var connectionJob: Job? = null
    private var pairingJob: Job? = null
    private var bluetoothSettingsJob: Job? = null

    private var activeDevice: TvDeviceEntity? = null
    // A TV that is already on often refuses the very first socket after the app starts
    // (Wi-Fi still waking up, or the TV still holding the previous session).
    private var connectRetriesLeft = 0

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
                    if (!retryInitialConnection(dev)) _connectionState.value = ConnectionState.Error(error)
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

    private val computerClients = mapOf(
        DeviceType.MACOS to SshComputerClient(tokenStore, MacPlatform),
        DeviceType.WINDOWS to SshComputerClient(tokenStore, WindowsPlatform)
    )
    // Held volume keys repeat faster than an SSH round trip; drop repeats while one is running.
    private val volumeKeyBusy = AtomicBoolean(false)
    // Slider drags only need the latest level.
    private val volumeRequests = Channel<Int>(Channel.CONFLATED)

    init {
        scope.launch(Dispatchers.IO) {
            for (level in volumeRequests) {
                val device = activeDevice?.takeIf { it.deviceType.isComputer } ?: continue
                val client = computerClients.getValue(device.deviceType)
                runComputerCommand(device, client.platform.setVolume(level), reportsVolume = true)
            }
        }
    }

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
                    if (!retryInitialConnection(dev)) _connectionState.value = ConnectionState.Error(error)
                } else {
                    _connectionState.value = ConnectionState.Disconnected
                }
            }
        },
        onCommandRejected = { message ->
            val dev = activeDevice
            if (dev?.deviceType == DeviceType.ANDROID_TV) addLog(dev.name, message, false)
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

    fun isCurrentDeviceComputer(): Boolean = activeDevice?.deviceType?.isComputer == true

    /** First login to a Mac or PC; the password authorizes a key and is not stored. */
    suspend fun loginComputer(username: String, password: String): SshResult {
        val dev = activeDevice?.takeIf { it.deviceType.isComputer }
            ?: return SshResult.Failed("Seleccioná primero la computadora.")
        connectionJob?.cancel()
        val result = withContext(Dispatchers.IO) {
            computerClients.getValue(dev.deviceType).login(dev.id, dev.ipAddress, dev.port, username, password)
        }
        handleComputerConnectResult(dev, result)
        return result
    }

    fun setComputerVolume(level: Int) {
        if (activeDevice?.deviceType?.isComputer != true) return
        _computerVolume.value = _computerVolume.value?.copy(level = level, muted = false)
        volumeRequests.trySend(level)
    }

    private fun handleComputerConnectResult(dev: TvDeviceEntity, result: SshResult) {
        if (activeDevice?.id != dev.id || activeDevice?.ipAddress != dev.ipAddress) return
        when (result) {
            is SshResult.Ok -> {
                _connectionState.value = ConnectionState.Connected(dev.name, dev.ipAddress)
                addLog(dev.name, "Conectado a ${dev.name}", true)
                resolveDeviceMac(dev)
                val readVolume = computerClients.getValue(dev.deviceType).platform.readVolume
                scope.launch(Dispatchers.IO) { runComputerCommand(dev, readVolume, reportsVolume = true) }
            }
            is SshResult.LoginNeeded -> _connectionState.value = ConnectionState.WaitingAuth(dev.name, result.message)
            is SshResult.Failed -> if (!retryInitialConnection(dev)) _connectionState.value = ConnectionState.Error(result.message)
        }
    }

    /** Runs [command] on the Mac or PC; returns whether it succeeded and logs why when it did not. */
    private fun runComputerCommand(device: TvDeviceEntity, command: SshCommand, reportsVolume: Boolean): Boolean {
        val client = computerClients.getValue(device.deviceType)
        val result = client.run(command)
        if (activeDevice?.id != device.id) return false
        return when (result) {
            is SshResult.Ok -> {
                if (reportsVolume) ComputerVolume.parse(result.output)?.let { _computerVolume.value = it }
                val idle = client.platform.idleMessage(result.output)
                if (idle != null) {
                    addLog(device.name, idle, false)
                    false
                } else true
            }
            is SshResult.LoginNeeded -> {
                _connectionState.value = ConnectionState.WaitingAuth(device.name, result.message)
                false
            }
            is SshResult.Failed -> {
                addLog(device.name, result.message, false)
                false
            }
        }
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

        connectRetriesLeft = CONNECT_RETRY_DELAYS_MS.size
        startConnection(effectiveDevice)
    }

    private fun startConnection(device: TvDeviceEntity) {
        when (device.deviceType) {
            DeviceType.SAMSUNG_TIZEN -> {
                samsungClient.connect(device.ipAddress, 8002, device.authToken)
            }
            DeviceType.ANDROID_TV -> {
                connectionJob = scope.launch {
                    val ok = androidClient.connect(device.ipAddress, device.port)
                    if (ok) addLog(device.name, "Conectado a ${device.name}", true)
                    // The client emits the specific connection or authorization error.
                }
            }
            DeviceType.MACOS, DeviceType.WINDOWS -> {
                connectionJob = scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        computerClients.getValue(device.deviceType).connect(device.id, device.ipAddress, device.port)
                    }
                    handleComputerConnectResult(device, result)
                }
            }
        }
    }

    /** Retries a failed first connection silently; returns false once the error should be shown. */
    private fun retryInitialConnection(device: TvDeviceEntity): Boolean {
        if (_connectionState.value !is ConnectionState.Connecting || connectRetriesLeft <= 0) return false
        val wait = CONNECT_RETRY_DELAYS_MS[CONNECT_RETRY_DELAYS_MS.size - connectRetriesLeft]
        connectRetriesLeft--
        connectionJob?.cancel()
        connectionJob = scope.launch {
            delay(wait)
            if (activeDevice?.id != device.id || activeDevice?.ipAddress != device.ipAddress) return@launch
            if (_connectionState.value !is ConnectionState.Connecting) return@launch
            startConnection(activeDevice ?: return@launch)
        }
        return true
    }

    /** Reconnects when the app returns to the foreground after a connection error. */
    fun reconnectIfFailed() {
        if (_connectionState.value is ConnectionState.Error) reconnect()
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

    fun disconnectCurrent() {
        cancelBluetoothNavigation()
        connectionJob?.cancel()
        connectionJob = null
        connectRetriesLeft = 0
        pairingJob?.cancel()
        pairingJob = null
        activeDevice = null
        samsungClient.disconnect()
        androidClient.disconnect()
        computerClients.values.forEach { it.disconnect() }
        _computerVolume.value = null
        _connectionState.value = ConnectionState.Disconnected
    }

    fun sendKey(key: RemoteKey) {
        cancelBluetoothNavigation()
        val device = activeDevice ?: return
        _lastDispatchedKey.value = key

        if (device.deviceType.isComputer) {
            sendComputerKey(device, key)
            return
        }

        scope.launch(Dispatchers.IO) {
            val success: Boolean = when (device.deviceType) {
                DeviceType.SAMSUNG_TIZEN -> {
                    samsungClient.sendKey(key)
                }
                DeviceType.ANDROID_TV -> {
                    androidClient.sendKey(key)
                }
                DeviceType.MACOS, DeviceType.WINDOWS -> false
            }

            val commandName = when (device.deviceType) {
                DeviceType.SAMSUNG_TIZEN -> key.samsungKey
                DeviceType.ANDROID_TV -> "KEYCODE_${key.name}"
                DeviceType.MACOS, DeviceType.WINDOWS -> key.name
            }
            addLog(device.name, "Sent $commandName (${key.label})", success)
        }
    }

    private fun sendComputerKey(device: TvDeviceEntity, key: RemoteKey) {
        val platform = computerClients.getValue(device.deviceType).platform
        val command = platform.forKey(key) ?: run {
            addLog(device.name, "${key.label} no está disponible en ${platform.displayName}.", false)
            return
        }
        val volumeKey = platform.reportsVolume(key)
        if (volumeKey && !volumeKeyBusy.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                if (runComputerCommand(device, command, reportsVolume = volumeKey)) {
                    addLog(device.name, "${device.deviceType.shortName}: ${key.label}", true)
                }
            } finally {
                if (volumeKey) volumeKeyBusy.set(false)
            }
        }
    }

    fun sendText(text: String) {
        cancelBluetoothNavigation()
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
                DeviceType.MACOS, DeviceType.WINDOWS -> false
            }
            addLog(device.name, "Sent text: \"$text\"", success)
        }
    }

    private fun cancelBluetoothNavigation() {
        bluetoothSettingsJob?.cancel()
        bluetoothSettingsJob = null
    }

    fun openBrightness(dimmer: TvAppEntity?) {
        val device = activeDevice ?: return
        if (device.deviceType == DeviceType.SAMSUNG_TIZEN) {
            if (bluetoothSettingsJob?.isActive == true) return
            bluetoothSettingsJob = scope.launch(Dispatchers.IO) {
                val sent = SamsungBluetoothAudio.open { key ->
                    val current = activeDevice
                    current?.id == device.id && current.ipAddress == device.ipAddress &&
                        current.deviceType == DeviceType.SAMSUNG_TIZEN &&
                        samsungClient.isConnected && samsungClient.sendKey(key)
                }
                addLog(device.name, "Abrir Sonido > Salida de sonido > Lista de altavoces Bluetooth (RU7100)", sent)
            }
            return
        }
        if (dimmer == null || dimmer.deviceId != device.id) {
            addLog(device.name, "Add TVDimmer in Apps with its Android package name to enable Brightness.", false)
            return
        }
        val packageName = dimmer.appIdentifier.trim()
        if (!packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))) {
            addLog(device.name, "TVDimmer needs a valid Android package name in Apps.", false)
            return
        }
        // Use the same checked launch and on-TV Open guidance as the Apps grid.
        launchTvApp(dimmer)
    }

    fun launchApp(shortcut: TvAppShortcut) {
        cancelBluetoothNavigation()
        val device = activeDevice ?: return
        scope.launch(Dispatchers.IO) {
            val success = when (device.deviceType) {
                DeviceType.SAMSUNG_TIZEN -> {
                    samsungClient.launchApp(device.ipAddress, shortcut).isSuccess
                }
                DeviceType.ANDROID_TV -> {
                    androidClient.launchApp(shortcut)
                }
                DeviceType.MACOS, DeviceType.WINDOWS -> false
            }
            addLog(device.name, "Launched ${shortcut.title}", success)
        }
    }

    fun launchTvApp(app: TvAppEntity) {
        cancelBluetoothNavigation()
        val device = activeDevice ?: return
        scope.launch(Dispatchers.IO) {
            var launchError: String? = null
            var storeOpened = false
            val success = when (device.deviceType) {
                DeviceType.SAMSUNG_TIZEN -> {
                    val result = samsungClient.launchAppById(device.ipAddress, app.appIdentifier)
                    launchError = result.exceptionOrNull()?.message
                    result.isSuccess
                }
                DeviceType.ANDROID_TV -> androidClient.launchAppByPackage(app.appIdentifier, app.deepLinkUrl) { storeOpened = true }
                DeviceType.MACOS, DeviceType.WINDOWS -> false
            }
            val message = when {
                storeOpened -> "${app.appName}: selecciona Abrir en la pantalla de la TV."
                success -> "Abrir ${app.appName}"
                else -> launchError ?: "La TV no pudo abrir ${app.appName}. El mando sigue disponible."
            }
            addLog(device.name, message, success)
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

    private companion object {
        val CONNECT_RETRY_DELAYS_MS = longArrayOf(800, 2000)
    }
}
