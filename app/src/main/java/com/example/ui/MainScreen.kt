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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.example.ui.components.RemoteAppsGrid
import com.example.ui.components.RemoteDpad
import com.example.ui.components.RemoteNumpad
import com.example.ui.components.RemoteTouchpad
import com.example.ui.components.VirtualTrackpad
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
    val isAndroidTvPaired = viewModel.isAndroidTvPaired

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
    val brandAccent = currentType.brandColor

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
                screenWidth < 360.dp -> 10.dp
                screenWidth < 480.dp -> 14.dp
                else -> 18.dp
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
                verticalArrangement = Arrangement.spacedBy(if (isShortScreen) 8.dp else 10.dp)
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

                // Power-On Status Banner (Wake-on-LAN in progress)
                powerOnStatusMessage?.let { status ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(2.dp))
                            .border(1.dp, RemoteBorderStrong, RoundedCornerShape(2.dp)),
                        color = Color(0xFF0F0F0F)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                color = Color.White,
                                strokeWidth = 1.5.dp
                            )
                            Text(
                                text = status,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                            )
                        }
                    }
                }

                // Connection State Banner (Auth prompt / Error / No TV)
                when (val state = connectionState) {
                    is ConnectionState.WaitingAuth -> {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(2.dp))
                                .border(1.dp, Color(0xFF555555), RoundedCornerShape(2.dp)),
                            color = Color(0xFF0F0F0F)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Autoriza la conexión en tu TV",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                            color = TextSecondary,
                                            fontWeight = FontWeight.Bold
                                        )
                                    )
                                    Text(
                                        text = state.message,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = TextPrimary,
                                            fontSize = 11.sp
                                        )
                                    )
                                }
                                if (activeDevice?.deviceType == DeviceType.ANDROID_TV) {
                                    Button(
                                        onClick = { viewModel.startAndroidTvPairing() },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color.White,
                                            contentColor = Color.Black
                                        ),
                                        shape = RoundedCornerShape(2.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                                    ) {
                                        Text("CÓDIGO PIN", fontSize = 10.sp, fontWeight = FontWeight.Black)
                                    }
                                }
                            }
                        }
                    }
                    is ConnectionState.Error -> {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(2.dp))
                                .border(1.dp, Color(0xFF444444), RoundedCornerShape(2.dp)),
                            color = Color(0xFF0D0D0D)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "No se pudo conectar",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                            color = TextMuted,
                                            fontWeight = FontWeight.Bold
                                        )
                                    )
                                    Text(
                                        text = state.message,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = TextSecondary,
                                            fontSize = 11.sp
                                        ),
                                        maxLines = 1
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Button(
                                        onClick = { viewModel.powerOnTv() },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color(0xFF222222),
                                            contentColor = Color.White
                                        ),
                                        shape = RoundedCornerShape(2.dp),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF444444)),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Text("ENCENDER", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                    if (activeDevice?.deviceType == DeviceType.ANDROID_TV) {
                                        Button(
                                            onClick = { viewModel.startAndroidTvPairing() },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = Color.White,
                                                contentColor = Color.Black
                                            ),
                                            shape = RoundedCornerShape(2.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Text("VINCULAR", fontSize = 10.sp, fontWeight = FontWeight.Black)
                                        }
                                    }
                                    Button(
                                        onClick = { viewModel.retryConnection() },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color(0xFF181818),
                                            contentColor = Color.White
                                        ),
                                        shape = RoundedCornerShape(2.dp),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF333333)),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Text("REINTENTAR", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                    is ConnectionState.Disconnected -> {
                        if (activeDevice == null) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(2.dp))
                                    .border(1.dp, RemoteBorderStrong, RoundedCornerShape(2.dp))
                                    .clickable { viewModel.openScanResultsScreen() },
                                color = Color(0xFF0E0E0E)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text(
                                            text = "Tu mando empieza aquí",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                                color = TextSecondary,
                                                fontWeight = FontWeight.Bold
                                            )
                                        )
                                        Text(
                                            text = "Busca una TV en tu red Wi-Fi",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                color = TextMuted,
                                                fontSize = 11.sp
                                            )
                                        )
                                    }
                                    Text(
                                        text = "BUSCAR →",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                            color = Color.White,
                                            fontWeight = FontWeight.Black,
                                            fontSize = 11.sp
                                        )
                                    )
                                }
                            }
                        } else {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(2.dp))
                                    .border(1.dp, RemoteBorder, RoundedCornerShape(2.dp)),
                                color = Color(0xFF0A0A0A)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "STANDBY // ${activeDevice?.name?.uppercase()}",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                                color = TextMuted,
                                                fontWeight = FontWeight.Bold
                                            )
                                        )
                                        Text(
                                            text = "Dispositivo en reposo",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                color = TextSecondary,
                                                fontSize = 11.sp
                                            )
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Button(
                                            onClick = { viewModel.powerOnTv() },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = Color.White,
                                                contentColor = Color.Black
                                            ),
                                            shape = RoundedCornerShape(2.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Text("ENCENDER", fontSize = 10.sp, fontWeight = FontWeight.Black)
                                        }
                                        Button(
                                            onClick = { viewModel.retryConnection() },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = Color(0xFF1C1C1C),
                                                contentColor = Color.White
                                            ),
                                            shape = RoundedCornerShape(2.dp),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF333333)),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Text("CONECTAR", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    else -> {}
                }

                // Android TV 1-Touch Pairing Prompt
                if (activeDevice?.deviceType == DeviceType.ANDROID_TV && !isAndroidTvPaired && connectionState !is ConnectionState.WaitingAuth) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(2.dp))
                            .border(1.dp, Color(0xFF444444), RoundedCornerShape(2.dp))
                            .clickable { viewModel.startAndroidTvPairing() },
                        color = Color(0xFF0F0F0F)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Key, contentDescription = "Pair", tint = TextPrimary, modifier = Modifier.size(16.dp))
                                Column {
                                    Text(
                                        text = "Vincular Google TV",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                            color = TextSecondary,
                                            fontWeight = FontWeight.Bold
                                        )
                                    )
                                    Text(
                                        text = "Vincular con el código PIN en pantalla",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = TextPrimary,
                                            fontSize = 11.sp
                                        )
                                    )
                                }
                            }
                            Button(
                                onClick = { viewModel.startAndroidTvPairing() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.White,
                                    contentColor = Color.Black
                                ),
                                shape = RoundedCornerShape(2.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 3.dp)
                            ) {
                                Text("VINCULAR", fontSize = 10.sp, fontWeight = FontWeight.Black)
                            }
                        }
                    }
                }

                // Remote Navigation Mode Switcher Pills (D-Pad, Trackpad, Apps, Numpad)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0C0C0C), RoundedCornerShape(2.dp))
                        .border(1.dp, RemoteBorderStrong, RoundedCornerShape(2.dp))
                        .padding(3.dp)
                        .selectableGroup(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RemoteModeTabButton(
                        mode = RemoteMode.DPAD,
                        icon = RemoteIcons.Dpad,
                        isSelected = activeTab == RemoteMode.DPAD,
                        brandAccent = brandAccent,
                        onClick = {
                            HapticUtil.triggerKeyHaptic(context)
                            viewModel.setActiveTab(RemoteMode.DPAD)
                        },
                        modifier = Modifier.weight(1f)
                    )
                    RemoteModeTabButton(
                        mode = RemoteMode.TOUCHPAD,
                        icon = RemoteIcons.Touchpad,
                        isSelected = activeTab == RemoteMode.TOUCHPAD,
                        brandAccent = brandAccent,
                        onClick = {
                            HapticUtil.triggerKeyHaptic(context)
                            viewModel.setActiveTab(RemoteMode.TOUCHPAD)
                        },
                        modifier = Modifier.weight(1f)
                    )
                    RemoteModeTabButton(
                        mode = RemoteMode.APPS,
                        icon = RemoteIcons.Apps,
                        isSelected = activeTab == RemoteMode.APPS,
                        brandAccent = brandAccent,
                        onClick = {
                            HapticUtil.triggerKeyHaptic(context)
                            viewModel.setActiveTab(RemoteMode.APPS)
                        },
                        modifier = Modifier.weight(1f)
                    )
                    RemoteModeTabButton(
                        mode = RemoteMode.NUMPAD,
                        icon = RemoteIcons.Keyboard,
                        isSelected = activeTab == RemoteMode.NUMPAD,
                        brandAccent = brandAccent,
                        onClick = {
                            HapticUtil.triggerKeyHaptic(context)
                            viewModel.setActiveTab(RemoteMode.NUMPAD)
                        },
                        modifier = Modifier.weight(1f)
                    )
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
                        RemoteMode.TOUCHPAD -> {
                            VirtualTrackpad(
                                deviceType = currentType,
                                onNavigationCommand = { command ->
                                    viewModel.sendKey(command.remoteKey)
                                },
                                onKeyPress = { key ->
                                    viewModel.sendKey(key)
                                },
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

                // Real-time Command Feedback Bar anchored cleanly at bottom
                val latestLog = recentLogs.firstOrNull()
                if (latestLog != null) Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("command_feedback_bar"),
                    color = Color(0xFF0C0C0C),
                    shape = RoundedCornerShape(2.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, RemoteBorderStrong)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(5.dp)
                                    .background(
                                        if (latestLog.success) Color.White else Color(0xFFA1A1AA),
                                        CircleShape
                                    )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = latestLog.command,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    color = TextPrimary,
                                    fontSize = 10.sp,
                                    letterSpacing = 0.5.sp
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
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

@Composable
fun RemoteModeTabButton(
    mode: RemoteMode,
    icon: ImageVector,
    isSelected: Boolean,
    brandAccent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(52.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(if (isSelected) Color.White else Color.Transparent)
            .border(
                width = if (isSelected) 1.dp else 0.dp,
                color = if (isSelected) Color.White else Color.Transparent,
                shape = RoundedCornerShape(2.dp)
            )
            .selectable(selected = isSelected, role = Role.Tab, onClick = onClick)
            .testTag("tab_${mode.name.lowercase()}"),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = when (mode) {
                RemoteMode.DPAD -> "Mando"
                RemoteMode.TOUCHPAD -> "Panel táctil"
                RemoteMode.APPS -> "Aplicaciones"
                RemoteMode.NUMPAD -> "Teclado"
            },
            tint = if (isSelected) Color.Black else TextSecondary,
            modifier = Modifier.size(24.dp)
        )
    }
}
