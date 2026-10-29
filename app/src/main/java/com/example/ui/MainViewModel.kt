package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.controller.UnifiedTvController
import com.example.data.local.AppDatabase
import com.example.data.model.CommandLog
import com.example.data.model.ConnectionState
import com.example.data.model.RemoteKey
import com.example.data.model.TvAppShortcut
import com.example.data.model.TvDeviceEntity
import com.example.data.repository.TvDeviceRepository
import com.example.network.DiscoveredTv
import com.example.network.NsdScanState
import com.example.network.NsdTvDiscoveryService
import com.example.network.TvScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.data.model.DeviceType
import com.example.sleep.SleepTimerManager
import android.widget.Toast

import com.example.data.model.TvAppEntity
import com.example.data.repository.TvAppRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf

enum class RemoteMode(val label: String) {
    DPAD("D-Pad"),
    APPS("Apps"),
    NUMPAD("Numpad")
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getInstance(application)
    private val repository = TvDeviceRepository(database.tvDeviceDao())
    private val appRepository = TvAppRepository(database)
    private val scanner = TvScanner(application)
    val tokenStore = com.example.data.local.TvTokenStore.getInstance(application)
    private val sleepTimerManager = SleepTimerManager.getInstance(application)
    val sleepTimer = sleepTimerManager.timer
    val sleepTimerResult = sleepTimerManager.result
    val nsdDiscoveryService = com.example.network.NsdTvDiscoveryService(application)

    private val controller = UnifiedTvController(
        context = application,
        tokenStore = tokenStore,
        onTokenReceived = { deviceId, token ->
            viewModelScope.launch(Dispatchers.IO) {
                repository.updateAuthToken(deviceId, token)
                _activeDevice.value = _activeDevice.value?.let { current ->
                    if (current.id == deviceId) current.copy(authToken = token) else current
                }
            }
        },
        onDeviceMacResolved = { deviceId, mac ->
            viewModelScope.launch(Dispatchers.IO) {
                repository.updateDeviceMac(deviceId, mac)
                _activeDevice.value = _activeDevice.value?.let { current ->
                    if (current.id == deviceId) current.copy(macAddress = mac) else current
                }
            }
        }
    )

    val allDevices: StateFlow<List<TvDeviceEntity>> = repository.allDevices
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _activeDevice = MutableStateFlow<TvDeviceEntity?>(null)
    private val deletedDeviceIds = mutableSetOf<Long>()
    val activeDevice: StateFlow<TvDeviceEntity?> = _activeDevice.asStateFlow()

    val connectionState: StateFlow<ConnectionState> = controller.connectionState
    val recentLogs: StateFlow<List<CommandLog>> = controller.recentLogs
    val lastDispatchedKey: StateFlow<RemoteKey?> = controller.lastDispatchedKey
    val macVolume: StateFlow<com.example.network.MacVolume?> = controller.macVolume

    private val _isMacLoginDialogOpen = MutableStateFlow(false)
    val isMacLoginDialogOpen: StateFlow<Boolean> = _isMacLoginDialogOpen.asStateFlow()

    private val _isMacLoginInProgress = MutableStateFlow(false)
    val isMacLoginInProgress: StateFlow<Boolean> = _isMacLoginInProgress.asStateFlow()

    private val _macLoginError = MutableStateFlow<String?>(null)
    val macLoginError: StateFlow<String?> = _macLoginError.asStateFlow()

    private val _activeTab = MutableStateFlow(RemoteMode.DPAD)
    val activeTab: StateFlow<RemoteMode> = _activeTab.asStateFlow()

    private val _isDeviceManagerOpen = MutableStateFlow(false)
    val isDeviceManagerOpen: StateFlow<Boolean> = _isDeviceManagerOpen.asStateFlow()

    private val _isScanResultsScreenOpen = MutableStateFlow(false)
    val isScanResultsScreenOpen: StateFlow<Boolean> = _isScanResultsScreenOpen.asStateFlow()

    val nsdDiscoveredTvs: StateFlow<List<DiscoveredTv>> = nsdDiscoveryService.discoveredTvs
    val nsdScanState: StateFlow<NsdScanState> = nsdDiscoveryService.scanState
    val nsdServiceTypes: List<String>
        get() = nsdDiscoveryService.serviceTypesToScan

    private val _discoveredTvs = MutableStateFlow<List<DiscoveredTv>>(emptyList())
    val discoveredTvs: StateFlow<List<DiscoveredTv>> = _discoveredTvs.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _scanProgress = MutableStateFlow(Pair(0, 254))
    val scanProgress: StateFlow<Pair<Int, Int>> = _scanProgress.asStateFlow()

    private val _scanStatusMessage = MutableStateFlow("")
    val scanStatusMessage: StateFlow<String> = _scanStatusMessage.asStateFlow()

    val localSubnetPrefix: String
        get() = scanner.getSubnetPrefix()

    val localIpAddress: String?
        get() = scanner.getLocalIpAddress()

    // Haptic Feedback Settings
    private val _isHapticEnabled = MutableStateFlow(com.example.ui.components.HapticUtil.isHapticEnabled(application))
    val isHapticEnabled: StateFlow<Boolean> = _isHapticEnabled.asStateFlow()

    // Playback State: tracks whether TV is currently playing or paused
    // Defaults to true (active playback) so pressing the button pauses playback
    private val _isPlaying = MutableStateFlow(true)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    // Android TV Pairing State
    private val _isAndroidTvPairingDialogOpen = MutableStateFlow(false)
    val isAndroidTvPairingDialogOpen: StateFlow<Boolean> = _isAndroidTvPairingDialogOpen.asStateFlow()

    private val _isPairingInProgress = MutableStateFlow(false)
    val isPairingInProgress: StateFlow<Boolean> = _isPairingInProgress.asStateFlow()

    private val _pairingErrorMessage = MutableStateFlow<String?>(null)
    val pairingErrorMessage: StateFlow<String?> = _pairingErrorMessage.asStateFlow()

    private val _pairingSuccessMessage = MutableStateFlow<String?>(null)
    val pairingSuccessMessage: StateFlow<String?> = _pairingSuccessMessage.asStateFlow()

    private val _powerOnStatusMessage = MutableStateFlow<String?>(null)
    val powerOnStatusMessage: StateFlow<String?> = _powerOnStatusMessage.asStateFlow()

    private val _showMacConfigDialog = MutableStateFlow(false)
    val showMacConfigDialog: StateFlow<Boolean> = _showMacConfigDialog.asStateFlow()

    private val _macConfigDevice = MutableStateFlow<TvDeviceEntity?>(null)
    val macConfigDevice: StateFlow<TvDeviceEntity?> = _macConfigDevice.asStateFlow()

    val isCurrentDeviceAndroidTv: Boolean
        get() = controller.isCurrentDeviceAndroidTv()

    val isCurrentAndroidTvPaired: Boolean
        get() = controller.isCurrentAndroidTvPaired()

    val isAndroidTvPaired: Boolean
        get() = isCurrentAndroidTvPaired

    fun setHapticEnabled(enabled: Boolean) {
        com.example.ui.components.HapticUtil.setHapticEnabled(getApplication(), enabled)
        _isHapticEnabled.value = enabled
    }

    fun toggleHapticEnabled() {
        setHapticEnabled(!_isHapticEnabled.value)
    }

    fun clearSavedTokens() {
        tokenStore.clearAllTokens()
    }

    // TV Apps State Management
    private val _selectedCategory = MutableStateFlow("All")
    val selectedCategory: StateFlow<String> = _selectedCategory.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Apps specifically for the currently active TV device
    val activeTvApps: StateFlow<List<TvAppEntity>> = _activeDevice
        .flatMapLatest { dev ->
            if (dev == null) flowOf(emptyList()) else appRepository.getAppsForDevice(dev.id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Filtered apps based on Category and Search
    val filteredTvApps: StateFlow<List<TvAppEntity>> = combine(
        activeTvApps,
        _selectedCategory,
        _searchQuery
    ) { apps, category, query ->
        apps.filter { app ->
            val matchesCategory = when (category) {
                "All" -> true
                "Favorites" -> app.isFavorite
                else -> app.category.equals(category, ignoreCase = true)
            }
            val matchesQuery = query.isBlank() ||
                    app.appName.contains(query, ignoreCase = true) ||
                    app.category.contains(query, ignoreCase = true)
            matchesCategory && matchesQuery
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            controller.connectionState.collect { state ->
                if (state is ConnectionState.WaitingAuth && controller.isCurrentDeviceAndroidTv() &&
                    !_isAndroidTvPairingDialogOpen.value) startAndroidTvPairing()
                if (state is ConnectionState.WaitingAuth && controller.isCurrentDeviceMac() &&
                    !_isMacLoginDialogOpen.value && !_isMacLoginInProgress.value) openMacLogin()
            }
        }
        viewModelScope.launch {
            repository.cleanDemoDevices()
        }

        viewModelScope.launch {
            allDevices.collect { devices ->
                if (devices.isNotEmpty()) {
                    devices.forEach { dev ->
                        appRepository.ensureDefaultAppsForDevice(dev.id, dev.deviceType)
                    }

                    if (_activeDevice.value == null) {
                        val defaultDev = devices.firstOrNull { it.isDefault } ?: devices.first()
                        selectDevice(defaultDev)
                    }
                }
            }
        }
    }

    fun selectDevice(device: TvDeviceEntity) {
        // An older device-list emission can finish app initialization after deletion.
        if (device.id in deletedDeviceIds) return
        _activeDevice.value = device
        controller.setActiveDevice(device)
        viewModelScope.launch(Dispatchers.IO) {
            repository.markLastConnected(device.id)
            appRepository.ensureDefaultAppsForDevice(device.id, device.deviceType)
        }
    }

    fun setSelectedCategory(category: String) {
        _selectedCategory.value = category
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun toggleFavoriteApp(app: TvAppEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            appRepository.toggleFavorite(app.id, app.isFavorite)
        }
    }

    fun launchTvApp(app: TvAppEntity) {
        controller.launchTvApp(app)
        viewModelScope.launch(Dispatchers.IO) {
            appRepository.recordLaunch(app.id)
        }
    }

    fun addCustomApp(
        appName: String,
        appIdentifier: String,
        category: String,
        colorHex: Long,
        iconKey: String,
        isFavorite: Boolean
    ) {
        val device = _activeDevice.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val newApp = TvAppEntity(
                deviceId = device.id,
                appName = appName,
                appIdentifier = appIdentifier,
                category = category,
                colorHex = colorHex,
                iconKey = iconKey,
                isFavorite = isFavorite
            )
            appRepository.saveApp(newApp)
        }
    }

    fun deleteTvApp(app: TvAppEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            appRepository.deleteApp(app)
        }
    }

    fun setActiveTab(mode: RemoteMode) {
        _activeTab.value = mode
    }

    fun setDeviceManagerOpen(isOpen: Boolean) {
        _isDeviceManagerOpen.value = isOpen
    }

    /**
     * Unified Key Dispatcher:
     * - Keeps playback state in sync for PLAY, PAUSE, STOP, and PLAY_PAUSE
     * - Routes POWER through powerToggle (Wake-on-LAN + key dispatch)
     */
    fun openBrightness() {
        val device = _activeDevice.value ?: return
        viewModelScope.launch {
            val dimmer = if (device.deviceType == DeviceType.ANDROID_TV) {
                appRepository.getAppsForDevice(device.id).first().firstOrNull { app ->
                    app.appName.filter { it.isLetterOrDigit() }
                        .startsWith("tvdimmer", ignoreCase = true)
                }
            } else null
            if (_activeDevice.value?.id == device.id) controller.openBrightness(dimmer)
        }
    }

    fun sendKey(key: RemoteKey) {
        // The Mac toggles whatever is playing; a guessed play/pause state would drift.
        if (key == RemoteKey.PLAY_PAUSE && controller.isCurrentDeviceMac()) {
            controller.sendKey(key)
            return
        }
        when (key) {
            RemoteKey.PLAY -> _isPlaying.value = true
            RemoteKey.PAUSE, RemoteKey.STOP -> _isPlaying.value = false
            RemoteKey.PLAY_PAUSE -> {
                togglePlayPause()
                return
            }
            RemoteKey.POWER -> {
                powerToggle()
                return
            }
            else -> {}
        }
        controller.sendKey(key)
    }

    /**
     * Toggles playback between Play and Pause.
     * If currently playing -> sends RemoteKey.PAUSE and updates state to paused.
     * If currently paused -> sends RemoteKey.PLAY and updates state to playing.
     */
    fun togglePlayPause() {
        val currentlyPlaying = _isPlaying.value
        if (currentlyPlaying) {
            _isPlaying.value = false
            controller.sendKey(RemoteKey.PAUSE)
        } else {
            _isPlaying.value = true
            controller.sendKey(RemoteKey.PLAY)
        }
    }

    fun pressPlay() {
        _isPlaying.value = true
        controller.sendKey(RemoteKey.PLAY)
    }

    fun pressPause() {
        _isPlaying.value = false
        controller.sendKey(RemoteKey.PAUSE)
    }

    fun pressStop() {
        _isPlaying.value = false
        controller.sendKey(RemoteKey.STOP)
    }

    /**
     * Smart Power Toggle:
     * - If TV is connected: sends POWER command to put TV to sleep/standby.
     * - If TV is disconnected or in error: executes multi-vector Wake-on-LAN power-on sequence
     *   (Broadcast + Subnet + Unicast + Google Cast wake + ADB wake) and begins auto-reconnecting!
     */
    fun powerToggle() {
        val device = _activeDevice.value ?: return
        val isConnected = controller.connectionState.value is ConnectionState.Connected
        if (!isConnected) {
            powerOnTv()
        } else {
            controller.sendKey(RemoteKey.POWER)
        }
    }

    /** False asks the UI to offer Android's exact-alarm permission; no timer is changed. */
    fun cycleSleepTimer(): Boolean {
        val device = _activeDevice.value?.takeIf { it.deviceType != DeviceType.MACOS } ?: return true
        return try {
            val timer = sleepTimerManager.advance(device)
            Toast.makeText(getApplication(), timer?.let { "Sleep: ${it.minutes} minutos · ${it.deviceName}" }
                ?: "Sleep: OFF", Toast.LENGTH_SHORT).show()
            true
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            Toast.makeText(getApplication(), "No se pudo programar Sleep. Reintentá.", Toast.LENGTH_SHORT).show()
            true
        }
    }

    fun cancelSleepTimer() {
        sleepTimerManager.cancel()
        Toast.makeText(getApplication(), "Sleep: OFF", Toast.LENGTH_SHORT).show()
    }

    fun dismissSleepTimerResult() = sleepTimerManager.reportResult(null)

    /**
     * Direct Multi-Vector Power On for linked TVs:
     * Broadcasts WoL packets across global broadcast, subnet broadcast, and direct unicast to the TV IP,
     * fires manufacturer-specific wake vectors (Cast wake-on-cast, Samsung HTTP wake, Sony IRCC),
     * and automatically polls to connect as the TV boots up!
     */
    fun powerOnTv() {
        val device = _activeDevice.value ?: return
        viewModelScope.launch {
            _powerOnStatusMessage.value = "Enviando ráfaga Wake-on-LAN a ${device.name}..."
            val result = com.example.network.WakeOnLan.powerOnTv(device, localSubnetPrefix, getApplication())
            if (result.resolvedMac != null && device.macAddress.isBlank()) {
                val updated = device.copy(macAddress = result.resolvedMac)
                repository.saveDevice(updated)
                _activeDevice.value = updated
            }
            if (!result.success) {
                // TV cannot wake because MAC address is missing: open prompt immediately!
                _powerOnStatusMessage.value = result.message
                if (com.example.network.WakeOnLan.formatMacAddress(device.macAddress) == null) {
                    openMacConfigDialog(device)
                }
                return@launch
            }

            _powerOnStatusMessage.value = result.message

            // Polling loop to auto-connect once TV hardware powers up network interfaces
            repeat(10) {
                delay(1500)
                if (_activeDevice.value?.id != device.id) return@launch
                when (controller.connectionState.value) {
                    is ConnectionState.Connected -> {
                        _powerOnStatusMessage.value = null
                        return@launch
                    }
                    // Reconnecting would close the socket the TV prompt belongs to; the token
                    // the user grants arrives on it, otherwise the TV asks again next time.
                    is ConnectionState.WaitingAuth -> {
                        _powerOnStatusMessage.value = null
                        return@launch
                    }
                    is ConnectionState.Connecting -> Unit
                    else -> controller.reconnect()
                }
            }
            delay(1500)
            _powerOnStatusMessage.value = null
        }
    }

    fun openMacConfigDialog(device: TvDeviceEntity) {
        _macConfigDevice.value = device
        _showMacConfigDialog.value = true
    }

    fun closeMacConfigDialog() {
        _showMacConfigDialog.value = false
        _macConfigDevice.value = null
    }

    fun updateDeviceMac(device: TvDeviceEntity, newMac: String, andPowerOn: Boolean = true) {
        val cleanMac = com.example.network.WakeOnLan.formatMacAddress(newMac) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateDeviceMac(device.id, cleanMac)
            val updated = device.copy(macAddress = cleanMac)
            if (_activeDevice.value?.id == device.id) {
                _activeDevice.value = updated
                controller.setActiveDevice(updated)
            }
            closeMacConfigDialog()
            if (andPowerOn) {
                powerOnTv()
            }
        }
    }

    suspend fun detectMacForDevice(device: TvDeviceEntity): String? {
        return com.example.network.WakeOnLan.fetchMacFromTv(device.ipAddress, device.deviceType)
    }

    fun sendWakeOnLan(device: TvDeviceEntity) {
        viewModelScope.launch {
            _powerOnStatusMessage.value = "Enviando ráfaga Wake-on-LAN a ${device.name}..."
            val result = com.example.network.WakeOnLan.powerOnTv(device, localSubnetPrefix, getApplication())
            if (result.resolvedMac != null && device.macAddress.isBlank()) {
                val updated = device.copy(macAddress = result.resolvedMac)
                repository.saveDevice(updated)
                if (_activeDevice.value?.id == device.id) {
                    _activeDevice.value = updated
                }
            }
            _powerOnStatusMessage.value = result.message
            if (!result.success && device.macAddress.isBlank()) {
                openMacConfigDialog(device)
            } else {
                delay(3000)
                _powerOnStatusMessage.value = null
            }
        }
    }

    private var pairingSubmitJob: kotlinx.coroutines.Job? = null

    fun startAndroidTvPairing() {
        pairingSubmitJob?.cancel()
        val dev = _activeDevice.value
        if (dev == null) {
            _pairingErrorMessage.value = "Selecciona primero tu Google TV para vincular."
            _isAndroidTvPairingDialogOpen.value = true
            return
        }
        _pairingErrorMessage.value = null
        _pairingSuccessMessage.value = null
        _isPairingInProgress.value = true
        _isAndroidTvPairingDialogOpen.value = true
        controller.startAndroidTvPairing(
            onShowPinDialog = {
                _isPairingInProgress.value = false
                _isAndroidTvPairingDialogOpen.value = true
            },
            onResult = { success, errorMsg ->
                _isPairingInProgress.value = false
                if (success) {
                    _pairingSuccessMessage.value = "Google TV / Android TV vinculado correctamente!"
                    viewModelScope.launch {
                        delay(1200)
                        _isAndroidTvPairingDialogOpen.value = false
                    }
                } else {
                    _pairingErrorMessage.value = errorMsg ?: "Error de vinculación. Verifica que la TV esté encendida y en la misma red Wi-Fi."
                    _isAndroidTvPairingDialogOpen.value = true
                }
            }
        )
    }

    fun submitAndroidTvPin(pin: String) {
        if (!pin.trim().matches(Regex("[0-9a-fA-F]{6}"))) {
            _pairingErrorMessage.value = "Introduce los 6 caracteres (0–9 y A–F) que aparecen en la TV"
            return
        }
        _isPairingInProgress.value = true
        _pairingErrorMessage.value = null
        pairingSubmitJob = viewModelScope.launch {
            val ok = controller.submitAndroidTvPin(pin)
            _isPairingInProgress.value = false
            if (ok) {
                _pairingSuccessMessage.value = "Google TV / Android TV vinculado correctamente!"
                delay(1200)
                _isAndroidTvPairingDialogOpen.value = false
            } else {
                _pairingErrorMessage.value = "Código inválido o tiempo de espera agotado. Verifica el código en pantalla y vuelve a intentar."
            }
        }
    }

    fun closeAndroidTvPairingDialog() {
        pairingSubmitJob?.cancel()
        controller.cancelAndroidTvPairing()
        _isPairingInProgress.value = false
        _isAndroidTvPairingDialogOpen.value = false
        _pairingErrorMessage.value = null
        _pairingSuccessMessage.value = null
    }

    fun openMacLogin() {
        _macLoginError.value = (controller.connectionState.value as? ConnectionState.WaitingAuth)?.message
        _isMacLoginDialogOpen.value = true
    }

    fun closeMacLogin() {
        _isMacLoginDialogOpen.value = false
        _macLoginError.value = null
    }

    fun submitMacLogin(username: String, password: String) {
        if (username.isBlank() || password.isEmpty()) {
            _macLoginError.value = "Completá el usuario y la contraseña de la Mac."
            return
        }
        _isMacLoginInProgress.value = true
        _macLoginError.value = null
        viewModelScope.launch {
            val result = controller.loginMac(username, password)
            _isMacLoginInProgress.value = false
            when (result) {
                is com.example.network.MacResult.Ok -> _isMacLoginDialogOpen.value = false
                is com.example.network.MacResult.LoginNeeded -> _macLoginError.value = result.message
                is com.example.network.MacResult.Failed -> _macLoginError.value = result.message
            }
        }
    }

    fun setMacVolume(level: Int) {
        controller.setMacVolume(level)
    }

    fun sendText(text: String) {
        controller.sendText(text)
    }

    fun launchApp(shortcut: TvAppShortcut) {
        controller.launchApp(shortcut)
    }

    fun saveDevice(device: TvDeviceEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val id = repository.saveDevice(device)
            val updatedDevice = repository.getDeviceById(id)
            if (updatedDevice != null) {
                selectDevice(updatedDevice)
                // The saved TLS identity is tried first; a changed DHCP IP does not
                // mean the TV needs pairing again. The connection reports auth errors.
            }
        }
    }

    fun deleteDevice(device: TvDeviceEntity) {
        deletedDeviceIds += device.id
        sleepTimerManager.cancelForDevice(device.id)
        viewModelScope.launch {
            val remaining = withContext(Dispatchers.IO) {
                repository.deleteDevice(device)
                if (device.deviceType == DeviceType.MACOS) tokenStore.clearMacLogin(device.id)
                repository.getDefaultDevice()
            }
            if (_activeDevice.value?.id == device.id) {
                if (remaining != null) {
                    selectDevice(remaining)
                } else {
                    controller.disconnectCurrent()
                    _activeDevice.value = null
                }
            }
        }
    }

    fun setDefaultDevice(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.setDefaultDevice(id)
        }
    }

    fun startNetworkScan(customSubnet: String? = null) {
        if (_isScanning.value) return
        _isScanning.value = true
        _scanProgress.value = Pair(0, 254)
        val prefix = customSubnet?.trim() ?: localSubnetPrefix
        _scanStatusMessage.value = "Scanning $prefix* for Samsung and Android TVs..."

        viewModelScope.launch {
            val results = scanner.scanLocalNetwork(
                customSubnet = customSubnet,
                onProgress = { current, total ->
                    _scanProgress.value = Pair(current, total)
                }
            )
            _discoveredTvs.value = results
            _isScanning.value = false
            _scanStatusMessage.value = if (results.isNotEmpty()) {
                "Found ${results.size} TV(s) on network!"
            } else {
                "Scan complete. 0 TVs detected on $prefix*. Make sure TV is turned ON, on the same Wi-Fi, or enter its IP directly."
            }
        }
    }

    fun retryConnection() {
        controller.reconnect()
    }

    fun onAppForeground() {
        if (sleepTimerManager.timer.value != null && !sleepTimerManager.canScheduleExact) {
            sleepTimerManager.cancel()
            sleepTimerManager.reportResult("Sleep cancelado: habilitá Alarmas y recordatorios para volver a programarlo")
        }
        controller.reconnectIfFailed()
    }

    fun testPing(ip: String, port: Int, callback: (Boolean) -> Unit) {
        viewModelScope.launch {
            val result = scanner.testConnection(ip, port)
            callback(result)
        }
    }

    fun openScanResultsScreen() {
        _isScanResultsScreenOpen.value = true
        startNsdDiscovery()
    }

    fun closeScanResultsScreen() {
        _isScanResultsScreenOpen.value = false
        stopNsdDiscovery()
    }

    fun startNsdDiscovery() {
        nsdDiscoveryService.startDiscovery()
    }

    fun stopNsdDiscovery() {
        nsdDiscoveryService.stopDiscovery()
    }

    fun connectDiscoveredTv(tv: DiscoveredTv) {
        val existingToken = tokenStore.getSamsungToken(tv.ipAddress, macAddress = tv.macAddress)
        val newDev = TvDeviceEntity(
            name = tv.name,
            deviceType = tv.deviceType,
            ipAddress = tv.ipAddress,
            port = tv.port,
            room = "Living Room",
            macAddress = tv.macAddress,
            authToken = existingToken,
            isDemoMode = false
        )
        saveDevice(newDev)
        closeScanResultsScreen()
    }
}
