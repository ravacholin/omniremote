package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.ConnectionState
import com.example.data.model.DeviceType
import com.example.data.model.RemoteKey
import com.example.data.model.TvDeviceEntity
import com.example.ui.theme.*

@Composable
fun DeviceSelectorBar(
    activeDevice: TvDeviceEntity?,
    allDevices: List<TvDeviceEntity>,
    connectionState: ConnectionState,
    isHapticEnabled: Boolean = true,
    onToggleHaptic: () -> Unit = {},
    onSelectDevice: (TvDeviceEntity) -> Unit,
    onOpenManager: () -> Unit,
    onOpenScan: () -> Unit,
    onPowerClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var expanded by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("device_selector_bar"),
        color = RemoteCardBg,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, RemoteBorderStrong)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left: Device pill & switcher dropdown
            Box {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { expanded = true }
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                        .testTag("device_dropdown_trigger"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Pulsing status dot
                    StatusIndicatorDot(connectionState)

                    Spacer(modifier = Modifier.width(10.dp))

                    Column(modifier = Modifier.widthIn(max = 210.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = activeDevice?.name ?: "NO TV CONNECTED",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Black,
                                    fontSize = 14.sp,
                                    letterSpacing = 0.5.sp,
                                    color = TextPrimary
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "Switch TV",
                                tint = TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            activeDevice?.let { dev ->
                                // Brand Pill with monospaced brutalist tag
                                Box(
                                    modifier = Modifier
                                        .background(
                                            color = Color(0xFF181818),
                                            shape = RoundedCornerShape(3.dp)
                                        )
                                        .border(
                                            width = 1.dp,
                                            color = Color(0xFF383838),
                                            shape = RoundedCornerShape(3.dp)
                                        )
                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                ) {
                                    Text(
                                        text = dev.deviceType.brandTag,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = TextPrimary,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 9.sp
                                        )
                                    )
                                }

                                Text(
                                    text = "• ${dev.room.uppercase()}",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = TextMuted,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                )
                            }
                        }
                    }
                }

                // Dropdown Menu for seamless 1-tap switching
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier
                        .background(RemoteCardBg)
                        .border(1.dp, RemoteBorder, RoundedCornerShape(12.dp))
                ) {
                    Text(
                        text = "SWITCH SMART TV",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = TextMuted,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    )

                    allDevices.forEach { device ->
                        val isCurrent = device.id == activeDevice?.id
                        DropdownMenuItem(
                            text = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text(
                                            text = device.name,
                                            color = if (isCurrent) device.deviceType.brandColor else TextPrimary,
                                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                            fontSize = 14.sp
                                        )
                                        Text(
                                            text = "${device.deviceType.shortName} • ${device.ipAddress}",
                                            color = TextMuted,
                                            fontSize = 11.sp
                                        )
                                    }
                                    if (isCurrent) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Active",
                                            tint = device.deviceType.brandColor,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            },
                            onClick = {
                                onSelectDevice(device)
                                expanded = false
                            },
                            modifier = Modifier.testTag("switch_device_${device.id}")
                        )
                    }

                    HorizontalDivider(color = RemoteBorder, modifier = Modifier.padding(vertical = 4.dp))

                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Radar,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Scan Network (NsdManager)...", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                        },
                        onClick = {
                            expanded = false
                            onOpenScan()
                        },
                        modifier = Modifier.testTag("scan_network_menu_item")
                    )

                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = null,
                                    tint = TextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Add TV by IP / Setup...", color = TextSecondary, fontSize = 13.sp)
                            }
                        },
                        onClick = {
                            expanded = false
                            onOpenManager()
                        },
                        modifier = Modifier.testTag("add_tv_menu_item")
                    )

                    HorizontalDivider(color = RemoteBorder, modifier = Modifier.padding(vertical = 4.dp))

                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Tv,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Añadir a Ajustes Rápidos...", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                        },
                        onClick = {
                            expanded = false
                            com.example.util.QuickSettingsHelper.requestAddRemoteTile(context) { msg ->
                                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                            }
                        },
                        modifier = Modifier.testTag("add_qs_tile_menu_item")
                    )
                }
            }

            // Right: Radar Scan button, Haptic toggle, Manage Devices button & Power button
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Quick Scan Radar Button
                IconButton(
                    onClick = onOpenScan,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF141414))
                        .border(1.dp, RemoteBorderStrong, RoundedCornerShape(8.dp))
                        .testTag("open_scan_radar_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Radar,
                        contentDescription = "Scan Network for TVs",
                        tint = TextPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Quick Haptic Feedback Toggle
                IconButton(
                    onClick = onToggleHaptic,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isHapticEnabled) Color(0xFF262626) else Color(0xFF101010))
                        .border(
                            1.dp,
                            if (isHapticEnabled) Color(0xFF555555) else RemoteBorder,
                            RoundedCornerShape(8.dp)
                        )
                        .testTag("toggle_haptic_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Vibration,
                        contentDescription = if (isHapticEnabled) "Haptic Feedback: ON" else "Haptic Feedback: OFF",
                        tint = if (isHapticEnabled) TextPrimary else TextMuted,
                        modifier = Modifier.size(17.dp)
                    )
                }

                IconButton(
                    onClick = onOpenManager,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF141414))
                        .border(1.dp, RemoteBorderStrong, RoundedCornerShape(8.dp))
                        .testTag("open_manager_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Device Manager",
                        tint = TextSecondary,
                        modifier = Modifier.size(17.dp)
                    )
                }

                // Brutalist Minimalist High-Contrast Power button
                IconButton(
                    onClick = onPowerClick,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFFFFFFF))
                        .border(1.dp, Color(0xFFFFFFFF), RoundedCornerShape(8.dp))
                        .testTag("power_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = "Power",
                        tint = Color(0xFF000000),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun StatusIndicatorDot(state: ConnectionState) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alphaAnim by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    val (color, isPulsing) = when (state) {
        is ConnectionState.Connected -> StatusGreen to false
        is ConnectionState.WaitingAuth -> StatusAmber to true
        is ConnectionState.Connecting -> StatusAmber to true
        is ConnectionState.Disconnected -> TextMuted to false
        is ConnectionState.Error -> PowerRed to false
    }

    Box(
        modifier = Modifier
            .size(10.dp)
            .alpha(if (isPulsing) alphaAnim else 1f)
            .background(color, CircleShape)
            .border(1.5.dp, color.copy(alpha = 0.35f), CircleShape)
    )
}
