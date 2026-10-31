package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.DeviceType
import com.example.data.model.TvDeviceEntity
import com.example.network.DiscoveredTv
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceManagerDialog(
    isOpen: Boolean,
    sheetState: SheetState,
    allDevices: List<TvDeviceEntity>,
    activeDeviceId: Long?,
    discoveredList: List<DiscoveredTv>,
    isScanning: Boolean,
    scanProgress: Pair<Int, Int>,
    scanStatusMessage: String,
    detectedSubnet: String,
    isHapticEnabled: Boolean = true,
    onToggleHaptic: (Boolean) -> Unit = {},
    onClearSavedTokens: () -> Unit = {},
    onCopySamsungDiagnostics: () -> Unit = {},
    onDismiss: () -> Unit,
    onSelectDevice: (TvDeviceEntity) -> Unit,
    onSaveDevice: (TvDeviceEntity) -> Unit,
    onDeleteDevice: (TvDeviceEntity) -> Unit,
    onSetDefault: (Long) -> Unit,
    onStartScan: (customSubnet: String?) -> Unit,
    onTestPing: (String, Int, (Boolean) -> Unit) -> Unit,
    onSendWakeOnLan: (TvDeviceEntity) -> Unit,
    onOpenNsdScanScreen: () -> Unit = {},
    onEditMac: (TvDeviceEntity) -> Unit = {},
    onStartPairing: (() -> Unit)? = null
) {
    if (!isOpen) return
    val context = LocalContext.current

    // Default to Scan tab (tab 1) if no devices, otherwise My TVs (tab 0)
    var selectedTab by remember { mutableIntStateOf(if (allDevices.isEmpty()) 1 else 0) }

    // Direct Connect Form states
    var formName by remember { mutableStateOf("My Smart TV") }
    var formType by remember { mutableStateOf(DeviceType.SAMSUNG_TIZEN) }
    var formIp by remember { mutableStateOf(detectedSubnet) }
    var formMac by remember { mutableStateOf("") }
    var formPort by remember { mutableStateOf("8002") }
    var formRoom by remember { mutableStateOf("Living Room") }
    var pingStatus by remember { mutableStateOf<String?>(null) }
    var isPinging by remember { mutableStateOf(false) }

    // Subnet for scanning
    var subnetInput by remember { mutableStateOf(detectedSubnet) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = RemoteDarkBg,
        tonalElevation = 8.dp,
        modifier = Modifier.testTag("device_manager_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Tv,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "TV Connection Manager",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    )
                }

                IconButton(onClick = onDismiss, modifier = Modifier.testTag("close_manager_button")) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Navigation Tabs
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = RemoteCardBg,
                contentColor = TextPrimary,
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = MaterialTheme.colorScheme.primary
                    )
                },
                modifier = Modifier
                    .clip(Sharp)
                    .border(1.dp, RemoteBorder, Sharp)
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("My TVs (${allDevices.size})", fontSize = 11.sp, maxLines = 1) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = {
                        selectedTab = 1
                        if (discoveredList.isEmpty() && !isScanning) {
                            onStartScan(subnetInput)
                        }
                    },
                    text = { Text("Scan", fontSize = 11.sp, maxLines = 1) }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("Direct IP", fontSize = 11.sp, maxLines = 1) }
                )
                Tab(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    text = { Text("Settings", fontSize = 11.sp, maxLines = 1) }
                )
                Tab(
                    selected = selectedTab == 4,
                    onClick = { selectedTab = 4 },
                    text = { Text("Guide", fontSize = 11.sp, maxLines = 1) }
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            when (selectedTab) {
                0 -> {
                    // TAB 0: Saved Devices List
                    if (allDevices.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tv,
                                contentDescription = null,
                                tint = TextMuted,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "No Connected TVs",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Scan your local Wi-Fi or enter your TV's IP address directly to connect.",
                                style = MaterialTheme.typography.bodySmall.copy(color = TextMuted),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 24.dp)
                            )
                            Spacer(modifier = Modifier.height(20.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = {
                                        selectedTab = 1
                                        onStartScan(subnetInput)
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                    shape = Sharp
                                ) {
                                    Icon(Icons.Default.Search, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Scan Wi-Fi", color = Color.Black, fontWeight = FontWeight.Bold)
                                }
                                OutlinedButton(
                                    onClick = { selectedTab = 2 },
                                    shape = Sharp
                                ) {
                                    Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Connect by IP")
                                }
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(allDevices) { device ->
                                val isActive = device.id == activeDeviceId
                                TvDeviceCard(
                                    device = device,
                                    isActive = isActive,
                                    onSelect = {
                                        onSelectDevice(device)
                                        onDismiss()
                                    },
                                    onSetDefault = { onSetDefault(device.id) },
                                    onDelete = { onDeleteDevice(device) },
                                    onWakeOnLan = { onSendWakeOnLan(device) },
                                    onEditMac = { onEditMac(device) },
                                    onPair = {
                                        onSelectDevice(device)
                                        onDismiss()
                                        onStartPairing?.invoke()
                                    }
                                )
                            }

                            item {
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            selectedTab = 1
                                            onStartScan(subnetInput)
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                        shape = Sharp
                                    ) {
                                        Icon(Icons.Default.Search, contentDescription = null, tint = Color.Black)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Scan Network", color = Color.Black, fontWeight = FontWeight.Bold)
                                    }

                                    OutlinedButton(
                                        onClick = { selectedTab = 2 },
                                        modifier = Modifier.weight(1f),
                                        shape = Sharp
                                    ) {
                                        Icon(Icons.Default.Add, contentDescription = null)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Connect by IP")
                                    }
                                }
                            }
                        }
                    }
                }

                1 -> {
                    // TAB 1: Real Wi-Fi Network Scanner
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // NsdManager Full Scanner Shortcut Banner
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(Sharp)
                                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), Sharp)
                                .clickable {
                                    onDismiss()
                                    onOpenNsdScanScreen()
                                }
                                .testTag("open_nsd_screen_from_dialog"),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(38.dp)
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), CircleShape)
                                            .border(1.dp, MaterialTheme.colorScheme.primary, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Radar,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = "Full mDNS Network TV Scanner",
                                            style = MaterialTheme.typography.titleSmall.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = TextPrimary
                                            )
                                        )
                                        Text(
                                            text = "Continuous NsdManager discovery for Samsung & Android TVs",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                color = TextMuted,
                                                fontSize = 11.sp
                                            )
                                        )
                                    }
                                }
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        // Subnet Controls & Rescan
                        Surface(
                            color = RemoteCardBg,
                            shape = Sharp,
                            border = androidx.compose.foundation.BorderStroke(1.dp, RemoteBorder)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "SUBNET ADDRESS",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = TextMuted,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp
                                    )
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedTextField(
                                        value = subnetInput,
                                        onValueChange = { subnetInput = it },
                                        placeholder = { Text("e.g. 192.168.1.") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                        colors = formTextFieldColors()
                                    )
                                    Button(
                                        onClick = { onStartScan(subnetInput) },
                                        enabled = !isScanning,
                                        shape = Sharp,
                                        modifier = Modifier
                                            .height(52.dp)
                                            .testTag("rescan_network_button")
                                    ) {
                                        if (isScanning) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                strokeWidth = 2.dp,
                                                color = Color.Black
                                            )
                                        } else {
                                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Scan")
                                        }
                                    }
                                }

                                if (isScanning) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    LinearProgressIndicator(
                                        progress = {
                                            if (scanProgress.second > 0) scanProgress.first.toFloat() / scanProgress.second else 0f
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Scanning ${scanProgress.first} of ${scanProgress.second} IPs...",
                                        style = MaterialTheme.typography.bodySmall.copy(color = TextMuted, fontSize = 11.sp)
                                    )
                                } else if (scanStatusMessage.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = scanStatusMessage,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = if (discoveredList.isNotEmpty()) StatusGreen else TextMuted,
                                            fontSize = 11.sp
                                        )
                                    )
                                }
                            }
                        }

                        // Discovered Real Devices List
                        if (discoveredList.isEmpty() && !isScanning) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = "0 Smart TVs Detected",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary
                                    )
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Ensure your Samsung TV or Android TV is switched ON and on Wi-Fi subnet $subnetInput. You can also connect directly by entering its IP address.",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = TextMuted,
                                        fontSize = 12.sp,
                                        lineHeight = 17.sp
                                    ),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 20.dp)
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                                Button(
                                    onClick = {
                                        formIp = subnetInput
                                        selectedTab = 2
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                    shape = Sharp
                                ) {
                                    Text("Enter TV IP Directly", color = Color.Black, fontWeight = FontWeight.Bold)
                                }
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                items(discoveredList) { discovered ->
                                    Surface(
                                        color = RemoteCardBg,
                                        shape = Sharp,
                                        border = androidx.compose.foundation.BorderStroke(1.dp, RemoteBorder)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(14.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        text = discovered.name,
                                                        style = MaterialTheme.typography.titleMedium.copy(
                                                            color = TextPrimary,
                                                            fontWeight = FontWeight.Bold,
                                                            fontSize = 15.sp
                                                        )
                                                    )
                                                }
                                                Text(
                                                    text = "${discovered.deviceType.shortName} • ${discovered.ipAddress}:${discovered.port}",
                                                    style = MaterialTheme.typography.bodySmall.copy(
                                                        color = discovered.deviceType.brandColor,
                                                        fontSize = 12.sp
                                                    )
                                                )
                                                if (discovered.modelInfo.isNotBlank()) {
                                                    Text(
                                                        text = discovered.modelInfo,
                                                        style = MaterialTheme.typography.bodySmall.copy(
                                                            color = TextMuted,
                                                            fontSize = 11.sp
                                                        )
                                                    )
                                                }
                                            }

                                            Button(
                                                onClick = {
                                                    val newEntity = TvDeviceEntity(
                                                        name = discovered.name,
                                                        deviceType = discovered.deviceType,
                                                        ipAddress = discovered.ipAddress,
                                                        port = discovered.port,
                                                        room = "Living Room",
                                                        macAddress = discovered.macAddress,
                                                        isDemoMode = false
                                                    )
                                                    onSaveDevice(newEntity)
                                                    onDismiss()
                                                },
                                                shape = Sharp,
                                                colors = ButtonDefaults.buttonColors(containerColor = discovered.deviceType.brandColor),
                                                modifier = Modifier.testTag("connect_discovered_${discovered.ipAddress.replace(".", "_")}")
                                            ) {
                                                Text("Connect", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                2 -> {
                    // TAB 2: Direct IP Connection Form
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            Text(
                                text = "TV PLATFORM",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = TextMuted,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                FilterChip(
                                    selected = formType == DeviceType.SAMSUNG_TIZEN,
                                    onClick = {
                                        formType = DeviceType.SAMSUNG_TIZEN
                                        formPort = "8002"
                                    },
                                    label = { Text("Samsung Tizen") },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = DeviceType.SAMSUNG_TIZEN.brandColor.copy(alpha = 0.2f),
                                        selectedLabelColor = DeviceType.SAMSUNG_TIZEN.brandColor
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("select_samsung_type")
                                )
                                FilterChip(
                                    selected = formType == DeviceType.ANDROID_TV,
                                    onClick = {
                                        formType = DeviceType.ANDROID_TV
                                        formPort = "8008"
                                    },
                                    label = { Text("Google TV / Android TV") },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = DeviceType.ANDROID_TV.brandColor.copy(alpha = 0.2f),
                                        selectedLabelColor = DeviceType.ANDROID_TV.brandColor
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("select_android_type")
                                )
                                FilterChip(
                                    selected = formType == DeviceType.MACOS,
                                    onClick = {
                                        formType = DeviceType.MACOS
                                        formPort = DeviceType.MACOS.defaultPort.toString()
                                    },
                                    label = { Text("Mac") },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = DeviceType.MACOS.brandColor.copy(alpha = 0.2f),
                                        selectedLabelColor = DeviceType.MACOS.brandColor
                                    ),
                                    modifier = Modifier
                                        .weight(0.6f)
                                        .testTag("select_mac_type")
                                )
                            }
                        }

                        item {
                            OutlinedTextField(
                                value = formName,
                                onValueChange = { formName = it },
                                label = { Text("Friendly Name") },
                                placeholder = { Text("e.g. Living Room TV") },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("form_tv_name"),
                                colors = formTextFieldColors(),
                                singleLine = true
                            )
                        }

                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = formIp,
                                    onValueChange = { formIp = it },
                                    label = { Text("TV IP Address") },
                                    placeholder = { Text("192.168.1.100") },
                                    modifier = Modifier
                                        .weight(2.5f)
                                        .testTag("form_tv_ip"),
                                    colors = formTextFieldColors(),
                                    singleLine = true
                                )

                                OutlinedTextField(
                                    value = formPort,
                                    onValueChange = { formPort = it },
                                    label = { Text("Port") },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("form_tv_port"),
                                    colors = formTextFieldColors(),
                                    singleLine = true
                                )
                            }
                        }

                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = formRoom,
                                    onValueChange = { formRoom = it },
                                    label = { Text("Room") },
                                    placeholder = { Text("Living Room") },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("form_tv_room"),
                                    colors = formTextFieldColors(),
                                    singleLine = true
                                )

                                OutlinedTextField(
                                    value = formMac,
                                    onValueChange = { formMac = it },
                                    label = { Text("MAC Address (for Wake-on-LAN)") },
                                    placeholder = { Text("AA:BB:CC:DD:EE:FF") },
                                    modifier = Modifier
                                        .weight(1.5f)
                                        .testTag("form_tv_mac"),
                                    colors = formTextFieldColors(),
                                    singleLine = true
                                )
                            }
                        }

                        item {
                            // Test Ping & Check
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        if (formIp.isNotBlank()) {
                                            isPinging = true
                                            pingStatus = "Pinging $formIp:$formPort..."
                                            onTestPing(formIp.trim(), formPort.toIntOrNull() ?: formType.defaultPort) { success ->
                                                isPinging = false
                                                pingStatus = if (success) "Reachable! Ready to connect." else "Cannot reach $formIp:$formPort. Verify TV is turned ON."
                                            }
                                        }
                                    },
                                    modifier = Modifier.testTag("test_ping_button"),
                                    shape = Sharp
                                ) {
                                    if (isPinging) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    } else {
                                        Text("Test Ping")
                                    }
                                }

                                pingStatus?.let { status ->
                                    Text(
                                        text = status,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = if (status.contains("Reachable")) StatusGreen else PowerRed,
                                            fontSize = 11.sp
                                        )
                                    )
                                }
                            }
                        }

                        item {
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = {
                                    val cleanIp = formIp.trim()
                                    if (cleanIp.isNotBlank()) {
                                        val cleanMac = com.example.network.WakeOnLan.formatMacAddress(formMac) ?: formMac.trim().uppercase()
                                        val newTv = TvDeviceEntity(
                                            name = formName.ifBlank { "${formType.shortName} ($cleanIp)" },
                                            deviceType = formType,
                                            ipAddress = cleanIp,
                                            port = formPort.toIntOrNull() ?: formType.defaultPort,
                                            room = formRoom.ifBlank { "Living Room" },
                                            macAddress = cleanMac,
                                            isDemoMode = false
                                        )
                                        onSaveDevice(newTv)
                                        onDismiss()
                                    }
                                },
                                enabled = formIp.isNotBlank(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .testTag("save_device_button"),
                                colors = ButtonDefaults.buttonColors(containerColor = formType.brandColor),
                                shape = Sharp
                            ) {
                                Text("Connect to TV Now", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        }
                    }
                }

                3 -> {
                    // TAB 3: Settings & Preferences
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // Section: Android Quick Settings Panel Tiles
                        item {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(Sharp)
                                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), Sharp),
                                color = RemoteCardBg
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(38.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Tv,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Text(
                                                text = "Ajustes Rápidos de Android",
                                                style = MaterialTheme.typography.titleMedium.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    color = TextPrimary,
                                                    fontSize = 14.sp
                                                )
                                            )
                                            Text(
                                                text = "Accede al control desde la barra superior",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    color = StatusGreen,
                                                    fontWeight = FontWeight.SemiBold,
                                                    fontSize = 11.sp
                                                )
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = "Añade los botones de OmniRemote a tus accesos rápidos de Android. Podrás abrir el control remoto o encender la TV con Wake-on-LAN deslizando la barra superior desde cualquier pantalla.",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = TextSecondary,
                                            fontSize = 12.sp,
                                            lineHeight = 17.sp
                                        )
                                    )

                                    Spacer(modifier = Modifier.height(12.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = {
                                                com.example.util.QuickSettingsHelper.requestAddRemoteTile(context) { msg ->
                                                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                                                }
                                            },
                                            shape = Sharp,
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                            modifier = Modifier.weight(1f).testTag("add_qs_remote_tile_button")
                                        ) {
                                            Icon(Icons.Default.Tv, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Control TV", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                        }

                                        OutlinedButton(
                                            onClick = {
                                                com.example.util.QuickSettingsHelper.requestAddPowerTile(context) { msg ->
                                                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                                                }
                                            },
                                            shape = Sharp,
                                            modifier = Modifier.weight(1f).testTag("add_qs_power_tile_button")
                                        ) {
                                            Icon(Icons.Default.Bolt, contentDescription = null, tint = Paper, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Encender TV", fontSize = 11.sp)
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(10.dp))

                                    Surface(
                                        color = RemoteDarkBg.copy(alpha = 0.6f),
                                        shape = Sharp,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Text(
                                                text = "📱 Cómo agregarlo manualmente:",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    color = TextPrimary,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 11.sp
                                                )
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = "1. Desliza hacia abajo dos veces la barra de notificaciones.\n2. Toca el lápiz ✏️ de edición.\n3. Busca 'Control TV' o 'Encender TV' y arrástralo a la sección activa.",
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    color = TextMuted,
                                                    fontSize = 11.sp,
                                                    lineHeight = 15.sp
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Section: Remote Interaction & Haptics
                        item {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(Sharp)
                                    .border(1.dp, RemoteBorder, Sharp),
                                color = RemoteCardBg
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(42.dp)
                                                    .clip(CircleShape)
                                                    .background(
                                                        if (isHapticEnabled)
                                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                                        else
                                                            RemoteBtnDefault
                                                    ),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Vibration,
                                                    contentDescription = "Haptic Feedback",
                                                    tint = if (isHapticEnabled)
                                                        MaterialTheme.colorScheme.primary
                                                    else
                                                        TextMuted,
                                                    modifier = Modifier.size(22.dp)
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Column {
                                                Text(
                                                    text = "Haptic Feedback",
                                                    style = MaterialTheme.typography.titleMedium.copy(
                                                        fontWeight = FontWeight.Bold,
                                                        color = TextPrimary,
                                                        fontSize = 15.sp
                                                    )
                                                )
                                                Text(
                                                    text = if (isHapticEnabled) "Enabled • Physical confirmation on" else "Disabled • Silent touch",
                                                    style = MaterialTheme.typography.bodySmall.copy(
                                                        color = if (isHapticEnabled) StatusGreen else TextMuted,
                                                        fontWeight = FontWeight.Medium,
                                                        fontSize = 11.sp
                                                    )
                                                )
                                            }
                                        }

                                        Switch(
                                            checked = isHapticEnabled,
                                            onCheckedChange = { checked ->
                                                onToggleHaptic(checked)
                                            },
                                            colors = SwitchDefaults.colors(
                                                checkedThumbColor = Color.White,
                                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                                uncheckedThumbColor = TextMuted,
                                                uncheckedTrackColor = RemoteDarkBg
                                            ),
                                            modifier = Modifier.testTag("haptic_feedback_switch")
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = "Provides responsive physical confirmation via micro-vibration when tapping remote buttons, navigating the D-pad, and swiping on the virtual trackpad.",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = TextSecondary,
                                            fontSize = 12.sp,
                                            lineHeight = 17.sp
                                        )
                                    )

                                    if (isHapticEnabled) {
                                        Spacer(modifier = Modifier.height(12.dp))
                                        OutlinedButton(
                                            onClick = {
                                                HapticUtil.triggerConfirmationHaptic(context)
                                            },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .testTag("test_haptic_button"),
                                            shape = Sharp,
                                            colors = ButtonDefaults.outlinedButtonColors(
                                                contentColor = MaterialTheme.colorScheme.primary
                                            ),
                                            border = androidx.compose.foundation.BorderStroke(
                                                1.dp,
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                            )
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Vibration,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "Test Physical Confirmation Pulse",
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 12.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Section: Samsung Authorization & Token Persistence
                        item {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(Sharp)
                                    .border(1.dp, RemoteBorder, Sharp),
                                color = RemoteCardBg
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(CircleShape)
                                                .background(DeviceType.SAMSUNG_TIZEN.brandColor.copy(alpha = 0.15f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Security,
                                                contentDescription = null,
                                                tint = DeviceType.SAMSUNG_TIZEN.brandColor,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column {
                                            Text(
                                                text = "Samsung TV Token Persistence",
                                                style = MaterialTheme.typography.titleMedium.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    color = TextPrimary,
                                                    fontSize = 14.sp
                                                )
                                            )
                                            Text(
                                                text = "Credenciales guardadas por TV",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    color = StatusGreen,
                                                    fontWeight = FontWeight.SemiBold,
                                                    fontSize = 11.sp
                                                )
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = "La app reutiliza la autorización recibida de cada TV. Si vuelve a pedir permiso, copiá el diagnóstico de la conexión para comprobar qué ocurrió.",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = TextSecondary,
                                            fontSize = 12.sp,
                                            lineHeight = 17.sp
                                        )
                                    )

                                    Spacer(modifier = Modifier.height(12.dp))
                                    OutlinedButton(
                                        onClick = onCopySamsungDiagnostics,
                                        modifier = Modifier.fillMaxWidth().testTag("copy_samsung_diagnostics"),
                                        shape = Sharp
                                    ) {
                                        Text("Copiar diagnóstico Samsung", fontSize = 11.sp)
                                    }
                                    OutlinedButton(
                                        onClick = onClearSavedTokens,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("clear_tokens_button"),
                                        shape = Sharp,
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = TextSecondary
                                        ),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, RemoteBorder)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Reset Saved TV Authorization Tokens", fontSize = 11.sp)
                                    }
                                }
                            }
                        }

                        // Section: Android TV & Multi-protocol Compatibility
                        item {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(Sharp)
                                    .border(1.dp, RemoteBorder, Sharp),
                                color = RemoteCardBg
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(CircleShape)
                                                .background(DeviceType.ANDROID_TV.brandColor.copy(alpha = 0.15f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Tv,
                                                contentDescription = null,
                                                tint = DeviceType.ANDROID_TV.brandColor,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column {
                                            Text(
                                                text = "Android TV / Google TV Engine",
                                                style = MaterialTheme.typography.titleMedium.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    color = TextPrimary,
                                                    fontSize = 14.sp
                                                )
                                            )
                                            Text(
                                                text = "Multi-Protocol Enabled (Cast, Remote v2, IRCC, JointSPACE, ADB)",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    color = DeviceType.ANDROID_TV.brandColor,
                                                    fontWeight = FontWeight.SemiBold,
                                                    fontSize = 11.sp
                                                )
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = "OmniRemote connects to Android TV / Google TV via standard Google Cast (port 8008), Google TV Remote protocol v2 (port 6466), Sony BRAVIA IRCC-IP (port 80/8000), Philips JointSPACE (port 1925), and ADB (port 5555). No special TV developer settings required for standard navigation and media control.",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = TextSecondary,
                                            fontSize = 12.sp,
                                            lineHeight = 17.sp
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                4 -> {
                    // TAB 4: Setup Guide
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        item {
                            GuideCard(
                                title = "How to find Samsung TV IP",
                                brandColor = DeviceType.SAMSUNG_TIZEN.brandColor,
                                steps = listOf(
                                    "On your Samsung TV, open Settings ➔ General (or All Settings).",
                                    "Select Network ➔ Network Status ➔ IP Settings.",
                                    "Note the IP address (e.g. 192.168.1.55).",
                                    "Important: Go to Settings ➔ General ➔ External Device Manager ➔ Device Connect Manager and make sure 'Access Notification' is set to 'First Time Only'.",
                                    "When connecting, look at your TV screen and press 'Allow' with your physical remote."
                                )
                            )
                        }

                        item {
                            GuideCard(
                                title = "How to find Android TV / Google TV IP",
                                brandColor = DeviceType.ANDROID_TV.brandColor,
                                steps = listOf(
                                    "On your Android TV / Google TV, open Settings (Gear icon).",
                                    "Select Network & Internet ➔ Select your connected Wi-Fi.",
                                    "Scroll down to find the IP address (e.g. 192.168.1.75).",
                                    "OmniRemote auto-connects via Google Cast (8008), Google TV Remote (6466), Sony BRAVIA (80), Philips JointSPACE, or ADB (5555)."
                                )
                            )
                        }

                        item {
                            GuideCard(
                                title = "Android Quick Settings Tiles (Acceso Rápido)",
                                brandColor = MaterialTheme.colorScheme.primary,
                                steps = listOf(
                                    "Swipe down twice from the top of your Android screen to open Quick Settings.",
                                    "Tap the Edit button (pencil icon ✏️).",
                                    "Find 'Control TV' and 'Encender TV' in the list of available tiles.",
                                    "Drag them to your top active tiles.",
                                    "Now you can open the remote in 1 tap or turn on your TV via Wake-on-LAN straight from the notification shade!"
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TvDeviceCard(
    device: TvDeviceEntity,
    isActive: Boolean,
    onSelect: () -> Unit,
    onSetDefault: () -> Unit,
    onDelete: () -> Unit,
    onWakeOnLan: () -> Unit,
    onEditMac: (TvDeviceEntity) -> Unit = {},
    onPair: (() -> Unit)? = null
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Sharp)
            .border(
                width = if (isActive) 1.5.dp else 1.dp,
                color = if (isActive) Color.White else RemoteBorderStrong,
                shape = Sharp
            )
            .clickable { onSelect() }
            .testTag("device_card_${device.id}"),
        color = Ink
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                color = if (isActive) Color.White else Hairline,
                                shape = CircleShape
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = device.name,
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    )
                }

                if (isActive) {
                    Box(
                        modifier = Modifier
                            .background(Color.White, Sharp)
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "ACTIVE",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = Color.Black,
                                fontWeight = FontWeight.Black,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                fontSize = 9.sp,
                                letterSpacing = 0.5.sp
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${device.deviceType.shortName.uppercase()} // ${device.ipAddress}:${device.port}",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                )

                if (device.macAddress.isNotBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(Sharp)
                            .background(Ink)
                            .border(1.dp, RemoteBorderStrong, Sharp)
                            .clickable { onEditMac(device) }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Icon(Icons.Default.Bolt, contentDescription = null, tint = Color.White, modifier = Modifier.size(11.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = device.macAddress,
                            color = Color.White,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(Sharp)
                            .background(Ink)
                            .border(1.dp, RemoteBorderStrong, Sharp)
                            .clickable { onEditMac(device) }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "[NO MAC]",
                            color = Ash,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Actions row: Set Default, Wake On Lan, Pair, Delete
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!device.isDefault) {
                        Surface(
                            onClick = onSetDefault,
                            shape = Sharp,
                            color = Ink,
                            border = androidx.compose.foundation.BorderStroke(1.dp, RemoteBorderStrong),
                            modifier = Modifier.testTag("set_default_${device.id}")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Star, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    "DEFAULT",
                                    fontSize = 10.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Star, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                "PRIMARY TV",
                                color = Color.White,
                                fontSize = 10.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }

                    Surface(
                        onClick = onWakeOnLan,
                        shape = Sharp,
                        color = Ink,
                        border = androidx.compose.foundation.BorderStroke(1.dp, RemoteBorderStrong),
                        modifier = Modifier.testTag("wake_device_${device.id}")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = "Wake-on-LAN",
                                tint = Color.White,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                "WOL",
                                fontSize = 10.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }

                    if (device.deviceType == DeviceType.ANDROID_TV && onPair != null) {
                        Surface(
                            onClick = onPair,
                            shape = Sharp,
                            color = Color.White,
                            modifier = Modifier.testTag("pair_device_${device.id}")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Key, contentDescription = null, tint = Color.Black, modifier = Modifier.size(12.dp))
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    "PAIR",
                                    fontSize = 10.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    fontWeight = FontWeight.Black,
                                    color = Color.Black
                                )
                            }
                        }
                    }
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .size(30.dp)
                        .testTag("delete_device_${device.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = Ash,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun GuideCard(
    title: String,
    brandColor: Color,
    steps: List<String>
) {
    Surface(
        color = RemoteCardBg,
        shape = Sharp,
        border = androidx.compose.foundation.BorderStroke(1.dp, RemoteBorder)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(
                    color = brandColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            )
            Spacer(modifier = Modifier.height(10.dp))
            steps.forEachIndexed { index, step ->
                Row(modifier = Modifier.padding(vertical = 4.dp)) {
                    Text(
                        text = "${index + 1}. ",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = brandColor,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Text(
                        text = step,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = TextSecondary,
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                    )
                }
            }
        }
    }
}

@Composable
fun formTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = RemoteBorder,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    cursorColor = MaterialTheme.colorScheme.primary,
    focusedLabelColor = MaterialTheme.colorScheme.primary,
    unfocusedLabelColor = TextMuted
)
