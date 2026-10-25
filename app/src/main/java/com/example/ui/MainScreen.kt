package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import com.example.data.model.ConnectionState
import com.example.data.model.DeviceType
import com.example.data.model.RemoteKey
import com.example.ui.components.AndroidTvPairingDialog
import com.example.ui.components.DeviceManagerDialog
import com.example.ui.components.DeviceSelectorBar
import com.example.ui.components.RemoteIcons
import com.example.ui.components.HapticUtil
import com.example.ui.components.GridGap
import com.example.ui.components.MonoLabel
import com.example.ui.components.Notice
import com.example.ui.components.NoticeAction
import com.example.ui.components.RemoteAppsGrid
import com.example.ui.components.RemoteDpad
import com.example.ui.components.RemoteNumpad
import com.example.ui.screens.ScanResultsScreen
import com.example.ui.theme.*
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val allDevices by viewModel.allDevices.collectAsStateWithLifecycle()
    val activeDevice by viewModel.activeDevice.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val activeTab by viewModel.activeTab.collectAsStateWithLifecycle()
    val isDeviceManagerOpen by viewModel.isDeviceManagerOpen.collectAsStateWithLifecycle()
    val recentLogs by viewModel.recentLogs.collectAsStateWithLifecycle()
    val discoveredTvs by viewModel.discoveredTvs.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()

    val activeTvApps by viewModel.activeTvApps.collectAsStateWithLifecycle()
    val filteredTvApps by viewModel.filteredTvApps.collectAsStateWithLifecycle()
    val selectedCategory by viewModel.selectedCategory.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val scanProgress by viewModel.scanProgress.collectAsStateWithLifecycle()
    val scanStatusMessage by viewModel.scanStatusMessage.collectAsStateWithLifecycle()

    val isScanResultsScreenOpen by viewModel.isScanResultsScreenOpen.collectAsStateWithLifecycle()
    val nsdDiscoveredTvs by viewModel.nsdDiscoveredTvs.collectAsStateWithLifecycle()
    val nsdScanState by viewModel.nsdScanState.collectAsStateWithLifecycle()
    val isHapticEnabled by viewModel.isHapticEnabled.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val isAndroidTvPairingDialogOpen by viewModel.isAndroidTvPairingDialogOpen.collectAsStateWithLifecycle()
    val isPairingInProgress by viewModel.isPairingInProgress.collectAsStateWithLifecycle()
    val pairingErrorMessage by viewModel.pairingErrorMessage.collectAsStateWithLifecycle()
    val pairingSuccessMessage by viewModel.pairingSuccessMessage.collectAsStateWithLifecycle()
    val powerOnStatusMessage by viewModel.powerOnStatusMessage.collectAsStateWithLifecycle()
    val showMacConfigDialog by viewModel.showMacConfigDialog.collectAsStateWithLifecycle()
    val macConfigDevice by viewModel.macConfigDevice.collectAsStateWithLifecycle()

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    if (isScanResultsScreenOpen) {
        ScanResultsScreen(
            discoveredTvs = nsdDiscoveredTvs,
            scanState = nsdScanState,
            localIp = viewModel.localIpAddress,
            serviceTypes = viewModel.nsdServiceTypes,
            onBackClick = { viewModel.closeScanResultsScreen() },
            onStartScan = { viewModel.startNsdDiscovery() },
            onStopScan = { viewModel.stopNsdDiscovery() },
            onConnectTv = { tv ->
                HapticUtil.triggerKeyHaptic(context)
                viewModel.connectDiscoveredTv(tv)
            },
            onDirectIpClick = {
                viewModel.closeScanResultsScreen()
                viewModel.setDeviceManagerOpen(true)
            }
        )
        return
    }

    // Back handler: Close sheet if open, or return to D-Pad if in sub-mode
    BackHandler(enabled = isDeviceManagerOpen || activeTab != RemoteMode.DPAD) {
        if (isDeviceManagerOpen) {
            viewModel.setDeviceManagerOpen(false)
        } else {
            viewModel.setActiveTab(RemoteMode.DPAD)
        }
    }

    val currentType = activeDevice?.deviceType ?: DeviceType.SAMSUNG_TIZEN

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(RemoteDarkBg)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        containerColor = RemoteDarkBg
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            val screenHeight = maxHeight
            val screenWidth = maxWidth

            // Responsive horizontal padding based on phone/tablet screen width
            val contentHorizontalPadding = when {
                screenWidth < 360.dp -> 14.dp
                screenWidth < 480.dp -> 20.dp
                else -> 24.dp
            }

            // Compact mode if screen is unusually short (e.g. landscape or small split-screen window)
            val isShortScreen = screenHeight < 560.dp

            val scrollModifier = if (isShortScreen) {
                Modifier.verticalScroll(rememberScrollState())
            } else {
                Modifier
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 560.dp)
                    .then(scrollModifier)
                    .padding(horizontal = contentHorizontalPadding, vertical = if (isShortScreen) 4.dp else 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(if (isShortScreen) 10.dp else 16.dp)
            ) {
                // Header: Device Selector Bar with seamless switcher & power button
                DeviceSelectorBar(
                    activeDevice = activeDevice,
                    allDevices = allDevices,
                    connectionState = connectionState,
                    isHapticEnabled = isHapticEnabled,
                    onToggleHaptic = { viewModel.toggleHapticEnabled() },
                    onSelectDevice = { dev ->
                        HapticUtil.triggerKeyHaptic(context)
                        viewModel.selectDevice(dev)
                    },
                    onOpenManager = {
                        HapticUtil.triggerKeyHaptic(context)
                        viewModel.setDeviceManagerOpen(true)
                    },
                    onOpenScan = {
                        HapticUtil.triggerKeyHaptic(context)
                        viewModel.openScanResultsScreen()
                    },
                    onPowerClick = {
                        HapticUtil.triggerKeyHaptic(context)
                        viewModel.powerToggle()
                    }
                )

                // Power-On Status (Wake-on-LAN in progress)
                powerOnStatusMessage?.let { status ->
                    Notice(
                        label = "Encendiendo",
                        message = status,
                        leading = {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                color = Paper,
                                strokeWidth = 1.dp
                            )
                        }
                    )
                }

                // Only WaitingAuth requests a PIN. A cached pairing flag can be stale
                // while credentials are restored or the control session reconnects.
                val isAndroidTv = activeDevice?.deviceType == DeviceType.ANDROID_TV
                when (val state = connectionState) {
                    is ConnectionState.WaitingAuth -> Notice(
                        label = "Autorizá en tu TV",
                        message = state.message,
                        actions = if (isAndroidTv) {
                            listOf(NoticeAction("PIN", primary = true) { viewModel.startAndroidTvPairing() })
                        } else emptyList()
                    )
                    is ConnectionState.Error -> Notice(
                        label = "Sin conexión",
                        message = state.message,
                        actions = buildList {
                            add(NoticeAction("Encender") { viewModel.powerOnTv() })
                            if (isAndroidTv) add(NoticeAction("Vincular") { viewModel.startAndroidTvPairing() })
                            add(NoticeAction("Reintentar", primary = !isAndroidTv) { viewModel.retryConnection() })
                        }
                    )
                    is ConnectionState.Disconnected -> if (activeDevice == null) {
                        Notice(
                            label = "Empezá acá",
                            message = "Buscá una TV en tu red Wi-Fi",
                            actions = listOf(NoticeAction("Buscar", primary = true) { viewModel.openScanResultsScreen() }),
                            onClick = { viewModel.openScanResultsScreen() }
                        )
                    } else {
                        Notice(
                            label = "En reposo",
                            message = activeDevice?.name ?: "",
                            actions = listOf(
                                NoticeAction("Encender", primary = true) { viewModel.powerOnTv() },
                                NoticeAction("Conectar") { viewModel.retryConnection() }
                            )
                        )
                    }
                    else -> {}
                }

                // Navigation and gestures share the D-pad; apps and numpad have their own tabs.
                Column(modifier = Modifier.fillMaxWidth()) {
                    RemoteModeTabs(
                        activeTab = activeTab,
                        onSelect = { mode ->
                            HapticUtil.triggerKeyHaptic(context)
                            viewModel.setActiveTab(mode)
                        }
                    )
                    HorizontalDivider(color = Hairline, thickness = GridGap)
                }

                // Main Interactive Remote Body: dynamically fills the remaining screen height
                val bodyModifier = if (isShortScreen) {
                    Modifier.fillMaxWidth()
                } else {
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                }

                AnimatedContent(
                    targetState = activeTab,
                    transitionSpec = {
                        (fadeIn() + slideInVertically { it / 8 }) togetherWith (fadeOut() + slideOutVertically { -it / 8 })
                    },
                    label = "remote_tab_content",
                    modifier = bodyModifier
                ) { tab ->
                    when (tab) {
                        RemoteMode.DPAD -> {
                            RemoteDpad(
                                deviceType = currentType,
                                onKeyPress = { key -> viewModel.sendKey(key) },
                                onBrightnessClick = { viewModel.openBrightness() },
                                isPlaying = isPlaying,
                                onTogglePlayPause = { viewModel.togglePlayPause() },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        RemoteMode.APPS -> {
                            RemoteAppsGrid(
                                deviceType = currentType,
                                deviceName = activeDevice?.name ?: currentType.title,
                                apps = filteredTvApps,
                                allAppsCount = activeTvApps.size,
                                favoritesCount = activeTvApps.count { it.isFavorite },
                                selectedCategory = selectedCategory,
                                searchQuery = searchQuery,
                                onCategorySelected = { viewModel.setSelectedCategory(it) },
                                onSearchQueryChanged = { viewModel.setSearchQuery(it) },
                                onAppLaunch = { app -> viewModel.launchTvApp(app) },
                                onToggleFavorite = { app -> viewModel.toggleFavoriteApp(app) },
                                onDeleteApp = { app -> viewModel.deleteTvApp(app) },
                                onAddNewApp = { name, id, category, colorHex, iconKey, isFav ->
                                    viewModel.addCustomApp(name, id, category, colorHex, iconKey, isFav)
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        RemoteMode.NUMPAD -> {
                            RemoteNumpad(
                                deviceType = currentType,
                                onKeyPress = { key -> viewModel.sendKey(key) },
                                onSendText = { text -> viewModel.sendText(text) },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }

                // Last command, set as a single typographic line.
                val latestLog = recentLogs.firstOrNull()
                if (latestLog != null) Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("command_feedback_bar"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(4.dp)
                            .background(if (latestLog.success) Paper else Graphite)
                    )
                    MonoLabel(
                        text = latestLog.command,
                        modifier = Modifier.weight(1f),
                        color = if (latestLog.success) TextSecondary else TextMuted
                    )
                }
            }
        }
    }

    // Modal Device Manager Sheet
    DeviceManagerDialog(
        isOpen = isDeviceManagerOpen,
        sheetState = sheetState,
        allDevices = allDevices,
        activeDeviceId = activeDevice?.id,
        discoveredList = discoveredTvs,
        isScanning = isScanning,
        scanProgress = scanProgress,
        scanStatusMessage = scanStatusMessage,
        detectedSubnet = viewModel.localSubnetPrefix,
        isHapticEnabled = isHapticEnabled,
        onToggleHaptic = { enabled -> viewModel.setHapticEnabled(enabled) },
        onClearSavedTokens = { viewModel.clearSavedTokens() },
        onDismiss = { viewModel.setDeviceManagerOpen(false) },
        onSelectDevice = { dev ->
            HapticUtil.triggerKeyHaptic(context)
            viewModel.selectDevice(dev)
        },
        onSaveDevice = { dev ->
            HapticUtil.triggerKeyHaptic(context)
            viewModel.saveDevice(dev)
        },
        onDeleteDevice = { dev ->
            HapticUtil.triggerKeyHaptic(context)
            viewModel.deleteDevice(dev)
        },
        onSetDefault = { id ->
            HapticUtil.triggerKeyHaptic(context)
            viewModel.setDefaultDevice(id)
        },
        onStartScan = { customSubnet ->
            viewModel.startNetworkScan(customSubnet)
        },
        onTestPing = { ip, port, callback ->
            viewModel.testPing(ip, port, callback)
        },
        onSendWakeOnLan = { dev ->
            viewModel.sendWakeOnLan(dev)
        },
        onOpenNsdScanScreen = {
            viewModel.openScanResultsScreen()
        },
        onEditMac = { dev ->
            viewModel.openMacConfigDialog(dev)
        },
        onStartPairing = {
            viewModel.startAndroidTvPairing()
        }
    )

    // Google TV / Android TV Mutual TLS Pairing Dialog
    AndroidTvPairingDialog(
        isOpen = isAndroidTvPairingDialogOpen,
        deviceName = activeDevice?.name ?: "Google TV",
        deviceIp = activeDevice?.ipAddress ?: "",
        isLoading = isPairingInProgress,
        errorMessage = pairingErrorMessage,
        successMessage = pairingSuccessMessage,
        onSubmitPin = { pin -> viewModel.submitAndroidTvPin(pin) },
        onRetry = { viewModel.startAndroidTvPairing() },
        onDismiss = { viewModel.closeAndroidTvPairingDialog() }
    )

    // Wake-on-LAN MAC Address Configuration Dialog
    if (showMacConfigDialog && macConfigDevice != null) {
        com.example.ui.components.MacAddressConfigDialog(
            device = macConfigDevice!!,
            onDismiss = { viewModel.closeMacConfigDialog() },
            onSaveMac = { dev, mac, andPowerOn ->
                viewModel.updateDeviceMac(dev, mac, andPowerOn)
            },
            onDetectMac = { dev ->
                viewModel.detectMacForDevice(dev)
            }
        )
    }
}

/** Mode tabs: tap one, or slide a finger along the row to switch to the tab under it. */
@Composable
fun RemoteModeTabs(
    activeTab: RemoteMode,
    onSelect: (RemoteMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentTab by rememberUpdatedState(activeTab)
    val currentOnSelect by rememberUpdatedState(onSelect)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectableGroup()
            .testTag("remote_mode_tabs")
            .pointerInput(Unit) {
                // Drag events can arrive before recomposition updates currentTab.
                var lastTab = currentTab
                fun selectAt(x: Float) {
                    val modes = RemoteMode.entries
                    val mode = modes[(x / size.width * modes.size).toInt().coerceIn(0, modes.lastIndex)]
                    if (mode != lastTab) {
                        lastTab = mode
                        currentOnSelect(mode)
                    }
                }
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        lastTab = currentTab
                        selectAt(offset.x)
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        selectAt(change.position.x)
                    }
                )
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        RemoteMode.entries.forEachIndexed { index, mode ->
            RemoteModeTabButton(
                mode = mode,
                index = index + 1,
                isSelected = activeTab == mode,
                onClick = { if (mode != activeTab) onSelect(mode) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun RemoteModeTabButton(
    mode: RemoteMode,
    index: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val title = when (mode) {
        RemoteMode.DPAD -> "Mando"
        RemoteMode.APPS -> "Apps"
        RemoteMode.NUMPAD -> "Teclado"
    }
    Box(
        modifier = modifier
            .height(48.dp)
            .selectable(selected = isSelected, role = Role.Tab, onClick = onClick)
            .testTag("tab_${mode.name.lowercase()}"),
        contentAlignment = Alignment.BottomStart
    ) {
        Row(
            modifier = Modifier.align(Alignment.CenterStart),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MonoLabel("%02d".format(index), color = if (isSelected) TextSecondary else Graphite)
            MonoLabel(title, color = if (isSelected) TextPrimary else TextMuted)
        }
        if (isSelected) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(Paper)
            )
        }
    }
}
