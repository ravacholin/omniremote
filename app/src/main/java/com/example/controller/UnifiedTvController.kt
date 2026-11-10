package com.example.controller

import com.example.BuildConfig
import android.util.Log
import com.example.data.model.CommandLog
import com.example.data.model.ConnectionState
import com.example.data.model.DeviceType
import com.example.data.model.RemoteKey
import com.example.data.model.TvAppEntity
import com.example.data.model.TvAppShortcut
import com.example.data.model.TvDeviceEntity
import com.example.network.AndroidTvClient
import com.example.network.MacResult
import com.example.network.MacScript
import com.example.network.MacScripts
import com.example.network.MacSshClient
import com.example.network.MacVolume
import com.example.network.SamsungTvClient
import com.example.network.SamsungConnectionDiagnostics
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class UnifiedTvController(
    private val context: Context? = null,
    private val tokenStore: TvTokenStore? = null,
    private val onTokenReceived: ((Long, String) -> Unit)? = null,
    private val onDeviceMacResolved: ((Long, String) -> Unit)? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val navigationGeneration = AtomicLong()
    val automaticNavigationGeneration: Long get() = navigationGeneration.get()

    private val dimmerLaunch: AndroidTvDimmerLaunch = AndroidTvDimmerLaunch(scope, { pkg, allowed ->
        var storeRequested = false
        val sent = androidClient.launchAppByPackage(pkg,
            "https://play.google.com/store/apps/details?id=$pkg", canSend = allowed) { storeRequested = true }
        sent && storeRequested
    }, { allowed -> androidClient.sendKey(RemoteKey.ENTER, allowed) })

    private val adbBrightness = context?.let { appContext ->
        AndroidAdbBrightness(scope, com.example.data.local.AdbSettingsStore(appContext), { host, port, pkg ->
            val identity = withContext(Dispatchers.IO) { com.example.network.AdbIdentity.load(appContext) }
            com.example.network.AdbTcpClient(identity).launch(host, port, pkg)
        }) { device, message, success -> addLog(device.name, message, success) }
    }
    val adbSetup: StateFlow<AdbSetupState?> = adbBrightness?.setup ?: MutableStateFlow(null)
    fun submitAdbSetup(port: String) { adbBrightness?.submit(port) }
    fun dismissAdbSetup() { adbBrightness?.cancel() }
    fun forgetAdb(deviceId: Long) { adbBrightness?.forget(deviceId) }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _recentLogs = MutableStateFlow<List<CommandLog>>(emptyList())
    val recentLogs: StateFlow<List<CommandLog>> = _recentLogs.asStateFlow()

    private val _lastDispatchedKey = MutableStateFlow<RemoteKey?>(null)
    val lastDispatchedKey: StateFlow<RemoteKey?> = _lastDispatchedKey.asStateFlow()

    private val _macVolume = MutableStateFlow<MacVolume?>(null)
    val macVolume: StateFlow<MacVolume?> = _macVolume.asStateFlow()

    private var connectionJob: Job? = null
    private var pairingJob: Job? = null
    private var bluetoothSettingsJob: Job? = null

    private var activeDevice: TvDeviceEntity? = null
    // A TV that is already on often refuses the very first socket after the app starts
    // (Wi-Fi still waking up, or the TV still holding the previous session).
    private var connectRetriesLeft = 0
    private val samsungDiagnostics = context?.let { SamsungConnectionDiagnostics(it) }
    private val samsungUiGeneration = AtomicLong()
    private val commandGeneration = AtomicLong()

    private val samsungClient = SamsungTvClient(
        tokenStore = tokenStore,
        diagnostics = samsungDiagnostics,
        onTokenReceived = { token ->
            activeDevice?.let { dev ->
                // The client already persisted this event. A separate Sleep session
                // may have renewed it again before this callback runs.
                val latest = tokenStore?.getSamsungToken(dev.ipAddress, dev.id, dev.macAddress, token) ?: token
                activeDevice = dev.copy(authToken = latest)
                onTokenReceived?.invoke(dev.id, latest)
            }
        },
        onConnectionChanged = { connected, error ->
            val eventDevice = activeDevice
            val eventGeneration = samsungUiGeneration.get()
            scope.launch {
                if (eventGeneration != samsungUiGeneration.get()) return@launch
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
            val eventDevice = activeDevice
            val eventGeneration = samsungUiGeneration.get()
            scope.launch {
                if (eventGeneration != samsungUiGeneration.get()) return@launch
                val dev = eventDevice ?: return@launch
                if (activeDevice?.id != dev.id || activeDevice?.ipAddress != dev.ipAddress) return@launch
                _connectionState.value = ConnectionState.WaitingAuth(
                    dev.name,
                    "Permite OmniRemote en la pantalla de tu Samsung TV con el control original."
                )
            }
        }
    )

    private val macClient = MacSshClient(tokenStore)
    // Held volume keys repeat faster than a Mac round trip; drop repeats while one is running.
    private val macVolumeKeyBusy = AtomicBoolean(false)
    // Slider drags only need the latest level.
    private val macVolumeRequests = Channel<Int>(Channel.CONFLATED)

    init {
        scope.launch(Dispatchers.IO) {
            for (level in macVolumeRequests) {
                val device = activeDevice?.takeIf { it.deviceType == DeviceType.MACOS } ?: continue
                runMacScript(device, MacScripts.setVolume(level), reportsVolume = true)
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
                if (!connected) cancelAutomaticNavigation()
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
            scope.launch { cancelAutomaticNavigation() }
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

    fun isCurrentDeviceMac(): Boolean = activeDevice?.deviceType == DeviceType.MACOS

    /** Media commands share the authorized connection, with guards checked while waiting too. */
    suspend fun runMacMediaScript(script: MacScript, canSend: () -> Boolean): MacResult {
        val device = activeDevice?.takeIf { it.deviceType == DeviceType.MACOS }
            ?: return MacResult.Failed("No hay ninguna Mac seleccionada.")
        val expected = commandGeneration.get()
        val caller = currentCoroutineContext()
        val result = withContext(Dispatchers.IO) {
            macClient.run(script) {
                caller.isActive && canSend() && commandGeneration.get() == expected &&
                    activeDevice?.id == device.id && activeDevice?.ipAddress == device.ipAddress
            }
        }
        if (caller.isActive && canSend() && commandGeneration.get() == expected && result is MacResult.LoginNeeded) {
            _connectionState.value = ConnectionState.WaitingAuth(device.name, result.message)
        }
        return result
    }

    /** First login to a Mac; the password authorizes a key and is not stored. */
    suspend fun loginMac(username: String, password: String): MacResult {
        val dev = activeDevice?.takeIf { it.deviceType == DeviceType.MACOS }
            ?: return MacResult.Failed("Seleccioná primero tu Mac.")
        connectionJob?.cancel()
        val result = withContext(Dispatchers.IO) {
            macClient.login(dev.id, dev.ipAddress, dev.port, username, password)
        }
        handleMacConnectResult(dev, result)
        return result
    }

    /** Bind both the selected device and transport generation before crossing to the IO thread. */
    suspend fun openMacLink(deviceId: Long, url: String): MacResult {
        val device = activeDevice?.takeIf { it.id == deviceId && it.deviceType == DeviceType.MACOS }
            ?: return MacResult.Failed("Seleccioná el Mac de este enlace.")
        if (_connectionState.value !is ConnectionState.Connected) return MacResult.Failed("Conectá el Mac antes de abrir el enlace.")
        val script = try { MacScripts.openLink(url) } catch (_: IllegalArgumentException) {
            return MacResult.Failed("El enlace no es válido.")
        }
        val expected = commandGeneration.get()
        val result = withContext(Dispatchers.IO) {
            macClient.run(script) {
                commandGeneration.get() == expected && activeDevice?.id == deviceId &&
                    activeDevice?.ipAddress == device.ipAddress && activeDevice?.deviceType == DeviceType.MACOS
            }
        }
        if (commandGeneration.get() == expected && activeDevice?.id == deviceId) {
            if (result is MacResult.LoginNeeded) _connectionState.value = ConnectionState.WaitingAuth(device.name, result.message)
            addLog(device.name, if (result is MacResult.Ok) "Enlace abierto en el Mac" else "No se pudo abrir el enlace", result is MacResult.Ok)
        }
        return result
    }

    fun setMacVolume(level: Int) {
        if (activeDevice?.deviceType != DeviceType.MACOS) return
        _macVolume.value = _macVolume.value?.copy(level = level, muted = false)
        macVolumeRequests.trySend(level)
    }

    private fun handleMacConnectResult(dev: TvDeviceEntity, result: MacResult) {
        if (activeDevice?.id != dev.id || activeDevice?.ipAddress != dev.ipAddress) return
        when (result) {
            is MacResult.Ok -> {
                _connectionState.value = ConnectionState.Connected(dev.name, dev.ipAddress)
                addLog(dev.name, "Conectado a ${dev.name}", true)
                resolveDeviceMac(dev)
                scope.launch(Dispatchers.IO) { runMacScript(dev, MacScripts.readVolume, reportsVolume = true) }
            }
            is MacResult.LoginNeeded -> _connectionState.value = ConnectionState.WaitingAuth(dev.name, result.message)
            is MacResult.Failed -> if (!retryInitialConnection(dev)) _connectionState.value = ConnectionState.Error(result.message)
        }
    }

    /** Runs [script] on the Mac; returns whether it succeeded and logs why when it did not. */
    private fun runMacScript(device: TvDeviceEntity, script: MacScript, reportsVolume: Boolean,
        expectedGeneration: Long = commandGeneration.get()): Boolean {
        val result = macClient.run(script) {
            commandGeneration.get() == expectedGeneration && activeDevice?.id == device.id &&
                activeDevice?.ipAddress == device.ipAddress
        }
        if (activeDevice?.id != device.id) return false
        return when (result) {
            is MacResult.Ok -> {
                if (reportsVolume) MacScripts.parseVolume(result.output)?.let { _macVolume.value = it }
                if (result.output == MacScripts.NO_PLAYER) {
                    addLog(device.name, "Nada reproduciendo. Abrí Música o Spotify, o dale Accesibilidad a sshd-keygen-wrapper en la Mac.", false)
                    false
                } else true
            }
            is MacResult.LoginNeeded -> {
                _connectionState.value = ConnectionState.WaitingAuth(device.name, result.message)
                false
            }
            is MacResult.Failed -> {
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
        synchronized(samsungClient) { setActiveDeviceLocked(device) }
    }

    private fun setActiveDeviceLocked(device: TvDeviceEntity) {
        // Resolve token from persistent store to ensure latest token is always present
        val effectiveDevice = if (device.deviceType == DeviceType.SAMSUNG_TIZEN) {
            val tokenToUse = tokenStore?.getSamsungToken(device.ipAddress, device.id, device.macAddress, device.authToken)
                ?: device.authToken
            device.copy(authToken = tokenToUse)
        } else {
            device
        }

        val state = _connectionState.value
        val preserveSession = state is ConnectionState.Connected ||
            (effectiveDevice.deviceType == DeviceType.SAMSUNG_TIZEN &&
                (samsungClient.hasPendingConnection(effectiveDevice.ipAddress) ||
                    state is ConnectionState.Connecting || state is ConnectionState.WaitingAuth))
        if (activeDevice?.id == effectiveDevice.id && activeDevice?.ipAddress == effectiveDevice.ipAddress &&
            activeDevice?.deviceType == effectiveDevice.deviceType && preserveSession) {
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
                // Re-read on every attempt: a retry must use a token accepted since the last one.
                val token = tokenStore?.getSamsungToken(device.ipAddress, device.id, device.macAddress, device.authToken)
                    ?: device.authToken
                samsungClient.connect(device.ipAddress, 8002, token, device.id, device.macAddress)
            }
            DeviceType.ANDROID_TV -> {
                connectionJob = scope.launch {
                    val ok = androidClient.connect(device.ipAddress, device.port, device.id)
                    if (ok) addLog(device.name, "Conectado a ${device.name}", true)
                    // The client emits the specific connection or authorization error.
                }
            }
            DeviceType.MACOS -> {
                connectionJob = scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        macClient.connect(device.id, device.ipAddress, device.port)
                    }
                    handleMacConnectResult(device, result)
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

    /** Recover both transport errors and normal standby closes on foreground. */
    fun reconnectIfFailed() {
        activeDevice?.takeIf { it.deviceType == DeviceType.SAMSUNG_TIZEN }?.let {
            samsungDiagnostics?.record(it.ipAddress, 0, "app_foreground", samsungClient.token,
                "state=${_connectionState.value.javaClass.simpleName}")
        }
        if (_connectionState.value is ConnectionState.Error ||
            (_connectionState.value is ConnectionState.Disconnected && activeDevice != null)) reconnect()
    }

    /** Resume after a background-only disconnect without cancelling an ongoing PIN exchange. */
    fun resumeDevice(device: TvDeviceEntity) {
        synchronized(samsungClient) {
            val current = activeDevice
            if (current?.id == device.id && current.ipAddress == device.ipAddress && current.deviceType == device.deviceType) {
                reconnectIfFailed()
            } else {
                if (device.deviceType == DeviceType.SAMSUNG_TIZEN) {
                    val credential = tokenStore?.getSamsungToken(device.ipAddress, device.id, device.macAddress, device.authToken)
                        ?: device.authToken
                    samsungDiagnostics?.record(device.ipAddress, 0, "app_foreground", credential,
                        "state=${_connectionState.value.javaClass.simpleName}")
                }
                setActiveDeviceLocked(device)
            }
        }
    }

    fun reconnect() {
        synchronized(samsungClient) { reconnectLocked() }
    }

    private fun reconnectLocked() {
        activeDevice?.let { dev ->
            val state = _connectionState.value
            if (dev.deviceType == DeviceType.SAMSUNG_TIZEN &&
                (samsungClient.hasPendingConnection(dev.ipAddress) ||
                    state is ConnectionState.Connecting || state is ConnectionState.WaitingAuth)) return
            disconnectCurrent()
            setActiveDevice(dev)
        }
    }

    fun cancelAndroidTvPairing() {
        pairingJob?.cancel()
        androidClient.cancelPairing()
    }

    fun disconnectCurrent() {
        adbBrightness?.cancel()
        commandGeneration.incrementAndGet()
        samsungUiGeneration.incrementAndGet()
        cancelAutomaticNavigation()
        connectionJob?.cancel()
        connectionJob = null
        connectRetriesLeft = 0
        pairingJob?.cancel()
        pairingJob = null
        activeDevice = null
        samsungClient.disconnect()
        androidClient.disconnect()
        macClient.disconnect()
        _macVolume.value = null
        _connectionState.value = ConnectionState.Disconnected
    }

    fun sendKey(key: RemoteKey) {
        cancelAutomaticNavigation()
        val device = activeDevice ?: return
        val expectedGeneration = commandGeneration.get()
        _lastDispatchedKey.value = key

        if (device.deviceType == DeviceType.MACOS) {
            sendMacKey(device, key, expectedGeneration)
            return
        }

        scope.launch(Dispatchers.IO) {
            val success: Boolean = when (device.deviceType) {
                DeviceType.SAMSUNG_TIZEN -> {
                    synchronized(samsungClient) {
                        commandGeneration.get() == expectedGeneration && samsungClient.sendKey(key)
                    }
                }
                DeviceType.ANDROID_TV -> {
                    androidClient.sendKey(key) { commandGeneration.get() == expectedGeneration }
                }
                DeviceType.MACOS -> false
            }

            val commandName = when (device.deviceType) {
                DeviceType.SAMSUNG_TIZEN -> key.samsungKey
                DeviceType.ANDROID_TV -> "KEYCODE_${key.name}"
                DeviceType.MACOS -> key.name
            }
            addLog(device.name, "Sent $commandName (${key.label})", success)
        }
    }

    private fun sendMacKey(device: TvDeviceEntity, key: RemoteKey, expectedGeneration: Long) {
        val script = MacScripts.forKey(key) ?: run {
            addLog(device.name, "${key.label} no está disponible en la Mac.", false)
            return
        }
        val volumeKey = MacScripts.reportsVolume(key)
        if (volumeKey && !macVolumeKeyBusy.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                if (runMacScript(device, script, reportsVolume = volumeKey, expectedGeneration = expectedGeneration)) addLog(device.name, "Mac: ${key.label}", true)
            } finally {
                if (volumeKey) macVolumeKeyBusy.set(false)
            }
        }
    }

    fun sendText(text: String) {
        cancelAutomaticNavigation()
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
                DeviceType.MACOS -> false
            }
            addLog(device.name, "Sent text: \"$text\"", success)
        }
    }

    private fun cancelAutomaticNavigation() {
        navigationGeneration.incrementAndGet()
        dimmerLaunch.cancel()
        bluetoothSettingsJob?.cancel()
        bluetoothSettingsJob = null
    }

    fun openBrightness(
        dimmer: TvAppEntity?,
        configureAdb: Boolean = false,
        expectedNavigationGeneration: Long = automaticNavigationGeneration
    ) {
        if (expectedNavigationGeneration != automaticNavigationGeneration) return
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
        if (!configureAdb && dimmerLaunch.isRunning) return
        cancelAutomaticNavigation()
        if (configureAdb) {
            adbBrightness?.configure(device, packageName)
            return
        }
        adbBrightness?.cancel()
        if (!androidClient.isConnected) {
            addLog(device.name, "Conecta el FlowBox antes de abrir TVDimmer.", false)
            return
        }
        val expectedGeneration = commandGeneration.get()
        dimmerLaunch.open(packageName, {
            commandGeneration.get() == expectedGeneration && androidClient.isConnected &&
                activeDevice?.id == device.id && activeDevice?.ipAddress == device.ipAddress
        }) { sent ->
            addLog(device.name, if (sent) "TVDimmer: enviada la selección de Abrir."
                else "No se pudo enviar la apertura de TVDimmer.", sent)
        }
    }

    fun launchApp(shortcut: TvAppShortcut) {
        cancelAutomaticNavigation()
        val device = activeDevice ?: return
        scope.launch(Dispatchers.IO) {
            val success = when (device.deviceType) {
                DeviceType.SAMSUNG_TIZEN -> {
                    samsungClient.launchApp(device.ipAddress, shortcut).isSuccess
                }
                DeviceType.ANDROID_TV -> {
                    androidClient.launchApp(shortcut)
                }
                DeviceType.MACOS -> false
            }
            addLog(device.name, "Launched ${shortcut.title}", success)
        }
    }

    fun launchTvApp(app: TvAppEntity) {
        val device = activeDevice ?: return
        if (device.deviceType == DeviceType.ANDROID_TV &&
            (app.appIdentifier == AndroidAdbBrightness.DIMMER_PACKAGE ||
                app.appName.filter { it.isLetterOrDigit() }.startsWith("tvdimmer", ignoreCase = true))) {
            if (app.deviceId == device.id) openBrightness(app)
            return
        }
        cancelAutomaticNavigation()
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
                DeviceType.MACOS -> false
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
        recordWakeRequest()
        scope.launch(Dispatchers.IO) {
            addLog(device.name, "Enviando ráfaga Wake-on-LAN multi-vector a ${device.name}...", true)
            val result = WakeOnLan.powerOnTv(device, context = context)
            if (result.resolvedMac != null && device.macAddress.isBlank()) {
                onDeviceMacResolved?.invoke(device.id, result.resolvedMac)
            }
            addLog(device.name, result.message, result.success)
            // Delay and auto reconnect, unless the TV is connected or showing its permission prompt.
            kotlinx.coroutines.delay(1800)
            val state = _connectionState.value
            if (state !is ConnectionState.Connected && state !is ConnectionState.WaitingAuth &&
                state !is ConnectionState.Connecting) reconnect()
        }
    }

    fun recordWakeRequest(device: TvDeviceEntity? = activeDevice) {
        device?.takeIf { it.deviceType == DeviceType.SAMSUNG_TIZEN }?.let {
            // WOL sends no authorization token, including when another TV is active.
            samsungDiagnostics?.record(it.ipAddress, 0, "wake_requested", "")
        }
    }

    /** Diagnostic variant: explicitly serialize the selected Samsung's WOL and
     * connection, instead of requiring a separate Android shortcut to test timing. */
    fun prepareWakeRequest(device: TvDeviceEntity): Boolean = synchronized(samsungClient) {
        recordWakeRequest(device)
        if (device.deviceType != DeviceType.SAMSUNG_TIZEN || activeDevice?.id != device.id ||
            activeDevice?.ipAddress != device.ipAddress || samsungClient.isConnected) return@synchronized false
        samsungUiGeneration.incrementAndGet()
        connectionJob?.cancel()
        connectionJob = null
        connectRetriesLeft = 0
        val credential = tokenStore?.getSamsungToken(device.ipAddress, device.id, device.macAddress, device.authToken)
            ?: device.authToken
        val waiting = samsungClient.connectAfterWake(device.ipAddress, credential, device.id, device.macAddress,
            BuildConfig.SAMSUNG_WAKE_RECONNECT_DELAY_MS)
        if (waiting) _connectionState.value = ConnectionState.Connecting(device.name, device.ipAddress)
        waiting
    }

    fun cancelWakeRequest(device: TvDeviceEntity) {
        synchronized(samsungClient) {
            if (activeDevice?.id == device.id && activeDevice?.ipAddress == device.ipAddress &&
                samsungClient.cancelWakeConnection()) _connectionState.value = ConnectionState.Disconnected
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
                val token = samsungClient.token.ifBlank { activeDevice?.authToken.orEmpty() }
                tokenStore?.bindSamsungIdentity(device.ipAddress, device.id, mac, token)
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
