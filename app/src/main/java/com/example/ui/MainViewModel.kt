package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppDatabase
import com.example.data.model.CommandLog
import com.example.data.model.ConnectionState
import com.example.data.model.RemoteKey
import com.example.data.model.TvAppShortcut
import com.example.data.model.hasSameControlEndpoint
import com.example.data.model.TvDeviceEntity
import com.example.data.repository.TvDeviceRepository
import com.example.network.DiscoveredTv
import com.example.network.NsdScanState
import com.example.network.NsdTvDiscoveryService
import com.example.network.TvScanner
import com.example.network.AndroidDiagnosticEnvironment
import com.example.network.ConnectionDiagnosticRunner
import com.example.network.DiagnosticAction
import com.example.network.DiagnosticEnvironment
import com.example.ui.components.ConnectionDiagnosisUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
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
class MainViewModel @JvmOverloads constructor(
    application: Application,
    private val scanner: TvScanner = TvScanner(application)
) : AndroidViewModel(application) {

    private val database = AppDatabase.getInstance(application)
    private val repository = TvDeviceRepository(database.tvDeviceDao())
    private val appRepository = TvAppRepository(database)
    val tokenStore = com.example.data.local.TvTokenStore.getInstance(application)
    private val sleepTimerManager = SleepTimerManager.getInstance(application)
    val sleepTimer = sleepTimerManager.timer
    val sleepTimerResult = sleepTimerManager.result
    val nsdDiscoveryService = com.example.network.NsdTvDiscoveryService(application)

    private val session = com.example.controller.RemoteSession.getInstance(application)
    val macLinks = com.example.share.MacLinksState(application, session, viewModelScope)
    private val controller = session.controller

    val allDevices: StateFlow<List<TvDeviceEntity>> = repository.allDevices
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _activeDevice = session.selectedDevice
    private val deletedDeviceIds = mutableSetOf<Long>()
    val activeDevice: StateFlow<TvDeviceEntity?> = _activeDevice.asStateFlow()

    val connectionState: StateFlow<ConnectionState> = controller.connectionState
    val recentLogs: StateFlow<List<CommandLog>> = controller.recentLogs
    val lastDispatchedKey: StateFlow<RemoteKey?> = controller.lastDispatchedKey
    val adbSetup = controller.adbSetup
    fun submitAdbSetup(port: String) = controller.submitAdbSetup(port)
    fun dismissAdbSetup() = controller.dismissAdbSetup()
    fun forgetAdbSetup() { adbSetup.value?.device?.id?.let(controller::forgetAdb) }
    fun configureBrightnessAdb(device: TvDeviceEntity) {
        if (device.id in deletedDeviceIds || device.deviceType != DeviceType.ANDROID_TV) return
        selectDevice(device)
        setDeviceManagerOpen(false)
        openBrightness(configureAdb = true)
    }
    val macVolume: StateFlow<com.example.network.MacVolume?> = controller.macVolume
    val macMedia = session.macMedia.state
    fun selectMacMedia(id: String?) = session.macMedia.select(id)
    fun refreshMacMedia() {
        if (controller.isCurrentDeviceMac() && connectionState.value is ConnectionState.Connected) session.macMedia.refresh()
    }
    fun revealMacMedia() {
        if (controller.isCurrentDeviceMac() && connectionState.value is ConnectionState.Connected) session.macMedia.reveal()
    }

    private val diagnosticEnvironment: DiagnosticEnvironment = AndroidDiagnosticEnvironment(application, scanner, tokenStore) { connectionState.value }
    private val _connectionDiagnosis = MutableStateFlow<ConnectionDiagnosisUiState?>(null)
    val connectionDiagnosis = _connectionDiagnosis.asStateFlow()
    private var diagnosticJob: Job? = null

    private var macLoginJob: Job? = null
    private var macLoginGeneration = 0L

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

    private var networkScanJob: Job? = null
    private val networkScanLock = Any()
    private var networkScanGeneration = 0L

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
    private val _isPlaying = session.playing
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

    private val _powerOnStatusMessage = session.powerStatus
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
        if (!device.hasSameControlEndpoint(_activeDevice.value)) {
            closeConnectionDiagnosis()
            closeMacLogin()
        }
        session.selectDevice(device)
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
            appRepository.toggleFavorite(app.id)
        }
    }

    fun launchTvApp(app: TvAppEntity) {
        if (app.deviceId != _activeDevice.value?.id) return
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
        if (!isOpen) cancelNetworkScan()
        _isDeviceManagerOpen.value = isOpen
    }

    /**
     * Unified Key Dispatcher:
     * - Keeps playback state in sync for PLAY, PAUSE, STOP, and PLAY_PAUSE
     * - Routes POWER through powerToggle (Wake-on-LAN + key dispatch)
     */
    fun openBrightness(configureAdb: Boolean = false) {
        val device = _activeDevice.value ?: return
        val navigationGeneration = controller.automaticNavigationGeneration
        viewModelScope.launch {
            val dimmer = if (device.deviceType == DeviceType.ANDROID_TV) {
                appRepository.getAppsForDevice(device.id).first().firstOrNull { app ->
                    app.appIdentifier == com.example.controller.AndroidAdbBrightness.DIMMER_PACKAGE ||
                    app.appName.filter { it.isLetterOrDigit() }
                        .startsWith("tvdimmer", ignoreCase = true)
                }
            } else null
            if (_activeDevice.value?.id == device.id && _activeDevice.value?.ipAddress == device.ipAddress) {
                controller.openBrightness(dimmer, configureAdb, navigationGeneration)
            }
        }
    }

    fun sendKey(key: RemoteKey) = session.sendKey(key)

    fun togglePlayPause() = session.sendKey(RemoteKey.PLAY_PAUSE)

    fun pressPlay() = session.sendKey(RemoteKey.PLAY)

    fun pressPause() = session.sendKey(RemoteKey.PAUSE)

    fun pressStop() = session.sendKey(RemoteKey.STOP)

    fun powerToggle() = session.powerToggle { openMacConfigDialog(it) }

    /** False asks the UI to offer Android's exact-alarm permission; no timer is changed. */
    fun cycleSleepTimer(expectedDeviceId: Long? = _activeDevice.value?.id): Boolean {
        val device = sleepDevice(expectedDeviceId) ?: return true
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

    private fun sleepDevice(expectedDeviceId: Long?) = _activeDevice.value?.takeIf {
        it.id == expectedDeviceId && it.id !in deletedDeviceIds && it.deviceType != DeviceType.MACOS
    }

    fun scheduleSleepFor(deviceId: Long, minutes: Int): Boolean {
        val device = sleepDevice(deviceId) ?: return true
        return changeSleepTimer { sleepTimerManager.scheduleFor(device, minutes) }
    }

    fun scheduleSleepAt(deviceId: Long, hour: Int, minute: Int): Boolean {
        val device = sleepDevice(deviceId) ?: return true
        return changeSleepTimer { sleepTimerManager.scheduleAt(device, hour, minute) }
    }

    fun extendSleepTimer(token: String): Boolean = changeSleepTimer { sleepTimerManager.extend(token) }

    private fun changeSleepTimer(action: () -> com.example.sleep.SleepTimerState?): Boolean = try {
        val timer = action()
        val message = if (timer == null) "Sleep ya venció o cambió; no se modificó."
            else "Sleep · ${timer.deviceName}: quedan ${com.example.ui.components.formatSleepCountdown(timer.remainingSeconds())}"
        Toast.makeText(getApplication(), message, Toast.LENGTH_SHORT).show()
        true
    } catch (_: SecurityException) {
        false
    } catch (_: Exception) {
        Toast.makeText(getApplication(), "No se pudo programar Sleep. Reintentá.", Toast.LENGTH_SHORT).show()
        true
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
    fun powerOnTv() = session.powerOnTv { openMacConfigDialog(it) }

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
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.updateDeviceMac(device.id, cleanMac) }
            _activeDevice.value?.takeIf { it.id == device.id && it.ipAddress == device.ipAddress }?.let {
                session.selectDevice(it.copy(macAddress = cleanMac))
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
        if (device.deviceType == DeviceType.SAMSUNG_TIZEN && _activeDevice.value?.id == device.id &&
            _activeDevice.value?.ipAddress == device.ipAddress) {
            powerOnTv()
            return
        }
        controller.recordWakeRequest(device)
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
        macLoginGeneration++
        macLoginJob?.cancel()
        macLoginJob = null
        _isMacLoginInProgress.value = false
        _isMacLoginDialogOpen.value = false
        _macLoginError.value = null
    }

    fun submitMacLogin(username: String, password: String) {
        if (_isMacLoginInProgress.value) return
        if (username.isBlank() || password.isEmpty()) {
            _macLoginError.value = "Completá el usuario y la contraseña de la Mac."
            return
        }
        _isMacLoginInProgress.value = true
        _macLoginError.value = null
        val expected = ++macLoginGeneration
        macLoginJob = viewModelScope.launch {
            try {
                val result = controller.loginMac(username, password)
                if (expected != macLoginGeneration) return@launch
                when (result) {
                    is com.example.network.MacResult.Ok -> _isMacLoginDialogOpen.value = false
                    is com.example.network.MacResult.LoginNeeded -> _macLoginError.value = result.message
                    is com.example.network.MacResult.Failed -> _macLoginError.value = result.message
                }
            } finally {
                if (expected == macLoginGeneration) _isMacLoginInProgress.value = false
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
        viewModelScope.launch {
            val updatedDevice = withContext(Dispatchers.IO) {
                val id = repository.saveDevice(device)
                repository.getDeviceById(id)
            }
            if (updatedDevice != null) {
                selectDevice(updatedDevice)
                // The saved TLS identity is tried first; a changed DHCP IP does not
                // mean the TV needs pairing again. The connection reports auth errors.
            }
        }
    }

    fun deleteDevice(device: TvDeviceEntity) {
        controller.forgetAdb(device.id)
        val wasActive = _activeDevice.value?.id == device.id
        macLinks.onDeviceDeleted(device.id)
        if (_activeDevice.value?.id == device.id) closeConnectionDiagnosis()
        deletedDeviceIds += device.id
        // Invalidate unsent Mac links before suspending for the database deletion.
        if (wasActive && device.deviceType == DeviceType.MACOS) {
            closeMacLogin()
            session.clearDevice()
        }
        sleepTimerManager.cancelForDevice(device.id)
        viewModelScope.launch {
            val remaining = withContext(Dispatchers.IO) {
                repository.deleteDevice(device)
                if (device.deviceType == DeviceType.MACOS) tokenStore.clearMacLogin(device.id)
                repository.getDefaultDevice()
            }
            if (_activeDevice.value?.id == device.id || (wasActive && _activeDevice.value == null)) {
                if (remaining != null) {
                    selectDevice(remaining)
                } else {
                    session.clearDevice()
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
        val expected = synchronized(networkScanLock) { ++networkScanGeneration }
        _isScanning.value = true
        _scanProgress.value = Pair(0, 254)
        val prefix = customSubnet?.trim() ?: localSubnetPrefix
        _scanStatusMessage.value = "Buscando Samsung y Android TV en $prefix*…"
        networkScanJob = viewModelScope.launch {
            try {
                val results = scanner.scanLocalNetwork(customSubnet = customSubnet,
                    onProgress = { current, total ->
                        synchronized(networkScanLock) {
                            if (expected == networkScanGeneration) _scanProgress.value = Pair(current, total)
                        }
                    })
                ensureActive()
                if (expected != networkScanGeneration) return@launch
                _discoveredTvs.value = results
                _scanStatusMessage.value = if (results.isNotEmpty()) {
                    "Se encontraron ${results.size} TV(s)."
                } else "No se encontraron TVs en $prefix*. Revisá que estén encendidas y en la misma red Wi-Fi."
            } catch (cancelled: CancellationException) {
                if (expected == networkScanGeneration) _scanStatusMessage.value = "Búsqueda cancelada."
                throw cancelled
            } catch (error: Exception) {
                if (expected == networkScanGeneration) _scanStatusMessage.value =
                    if (error is IllegalArgumentException) "Subred inválida. Usá tres números entre 0 y 255, por ejemplo 192.168.1."
                    else "No se pudo completar la búsqueda. Revisá la conexión y volvé a intentar."
            } finally {
                if (expected == networkScanGeneration) _isScanning.value = false
            }
        }
    }

    private fun cancelNetworkScan() {
        synchronized(networkScanLock) { networkScanGeneration++ }
        networkScanJob?.cancel()
        networkScanJob = null
        if (_isScanning.value) _scanStatusMessage.value = "Búsqueda cancelada."
        _isScanning.value = false
    }

    fun retryConnection() {
        controller.reconnect()
    }

    fun diagnoseConnection() {
        val device = _activeDevice.value ?: return
        diagnosticJob?.cancel()
        _connectionDiagnosis.value = ConnectionDiagnosisUiState(device.name)
        diagnosticJob = viewModelScope.launch {
            try {
                val report = withContext(Dispatchers.IO) {
                    ConnectionDiagnosticRunner(diagnosticEnvironment).run(device) { stage ->
                        if (diagnosisTargets(device)) _connectionDiagnosis.value =
                            _connectionDiagnosis.value?.copy(stage = stage)
                    }
                }
                ensureActive()
                if (diagnosisTargets(device)) _connectionDiagnosis.value =
                    ConnectionDiagnosisUiState(device.name, isRunning = false, report = report)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (diagnosisTargets(device)) _connectionDiagnosis.value = ConnectionDiagnosisUiState(
                    device.name, isRunning = false,
                    message = "No pudimos completar las comprobaciones. Verificá la red local y volvé a intentar. La vinculación se conserva.")
            }
        }
    }

    fun closeConnectionDiagnosis() {
        diagnosticJob?.cancel()
        diagnosticJob = null
        _connectionDiagnosis.value = null
    }

    private fun diagnosisTargets(device: TvDeviceEntity): Boolean =
        _connectionDiagnosis.value != null && device.id !in deletedDeviceIds &&
            _activeDevice.value?.id == device.id && _activeDevice.value?.ipAddress == device.ipAddress

    fun performDiagnosticAction(action: DiagnosticAction) {
        val ui = _connectionDiagnosis.value ?: return
        val report = ui.report ?: return
        val device = _activeDevice.value ?: return
        if (ui.isRunning || action !in report.actions || device.id != report.deviceId ||
            device.ipAddress != report.originalAddress || !diagnosisTargets(device)) return
        val currentState = connectionState.value
        if (currentState is ConnectionState.Connected && action != DiagnosticAction.MANAGE_DEVICE &&
            action != DiagnosticAction.NETWORK_SETTINGS) {
            closeConnectionDiagnosis()
            return
        }
        if ((action == DiagnosticAction.AUTHORIZE && currentState !is ConnectionState.WaitingAuth) ||
            (action in setOf(DiagnosticAction.RECONNECT, DiagnosticAction.WAKE, DiagnosticAction.USE_NEW_ADDRESS) && (currentState is ConnectionState.WaitingAuth ||
                currentState is ConnectionState.Connecting))) {
            diagnoseConnection()
            return
        }
        when (action) {
            DiagnosticAction.NETWORK_SETTINGS -> Unit // Handled by the screen, which owns activity launches.
            DiagnosticAction.RECONNECT -> { closeConnectionDiagnosis(); retryConnection() }
            DiagnosticAction.MANAGE_DEVICE -> { closeConnectionDiagnosis(); setDeviceManagerOpen(true) }
            DiagnosticAction.WAKE -> { closeConnectionDiagnosis(); powerOnTv() }
            DiagnosticAction.AUTHORIZE -> when (device.deviceType) {
                DeviceType.ANDROID_TV -> { closeConnectionDiagnosis(); startAndroidTvPairing() }
                DeviceType.MACOS -> { closeConnectionDiagnosis(); openMacLogin() }
                DeviceType.SAMSUNG_TIZEN -> {
                    // Keep the socket that owns the TV's pending permission prompt.
                    _connectionDiagnosis.value = ui.copy(message =
                        "Permití OmniRemote en la pantalla de tu Samsung con el control original. Si está bloqueado, revisá la lista de dispositivos permitidos de la TV.")
                }
            }
            DiagnosticAction.USE_NEW_ADDRESS -> {
                val newAddress = report.newAddress ?: return
                _connectionDiagnosis.value = ui.copy(isRunning = true, stage = "Confirmando la identidad y actualizando la IP…")
                diagnosticJob = viewModelScope.launch {
                    try {
                        val savedMac = com.example.network.WakeOnLan.formatMacAddress(device.macAddress)
                        val observedMac = diagnosticEnvironment.hardwareAddress(device.copy(ipAddress = newAddress))
                            ?.let(com.example.network.WakeOnLan::formatMacAddress)
                        ensureActive()
                        if (!diagnosisTargets(device)) return@launch
                        if (savedMac == null || observedMac != savedMac) {
                            _connectionDiagnosis.value = ui.copy(report = report.copy(actions = listOf(DiagnosticAction.MANAGE_DEVICE)),
                                message = "La identidad ya no pudo confirmarse en la nueva IP. No cambiamos el equipo; volvé a comprobar.")
                            return@launch
                        }
                        val updated = withContext(Dispatchers.IO) {
                            if (repository.updateAddressIfUnchanged(device.id, device.ipAddress, newAddress))
                                repository.getDeviceById(device.id) else null
                        }
                        ensureActive()
                        if (!diagnosisTargets(device)) return@launch
                        if (updated != null) {
                            // Stable ID preserves Samsung tokens, Android TLS identity and Mac SSH login.
                            closeConnectionDiagnosis()
                            selectDevice(updated)
                        } else {
                            _connectionDiagnosis.value = ui.copy(report = report.copy(actions = listOf(DiagnosticAction.MANAGE_DEVICE)),
                                message = "El equipo guardado cambió o esa IP ya está en uso. No se aplicó la actualización; revisá los equipos.")
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        if (diagnosisTargets(device)) _connectionDiagnosis.value = ui.copy(
                            message = "No pudimos actualizar la dirección. La vinculación guardada se conserva.")
                    }
                }
            }
        }
    }

    fun onAppForeground() {
        if (sleepTimerManager.timer.value != null && !sleepTimerManager.canScheduleExact) {
            sleepTimerManager.cancel()
            sleepTimerManager.reportResult("Sleep cancelado: habilitá Alarmas y recordatorios para volver a programarlo")
        }
        session.resumeConnection()
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
        cancelNetworkScan()
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
    override fun onCleared() {
        cancelNetworkScan()
        closeMacLogin()
        nsdDiscoveryService.stopDiscovery()
        super.onCleared()
    }

}
