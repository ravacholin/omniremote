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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Tv
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

    val statusLabel = when (connectionState) {
        is ConnectionState.Connected -> "Conectado"
        is ConnectionState.Connecting -> "Conectando…"
        is ConnectionState.WaitingAuth -> "Autorización pendiente"
        is ConnectionState.Error -> "Error de conexión"
        is ConnectionState.Disconnected -> "Sin conexión"
    }

    Column(
        modifier = modifier.fillMaxWidth().testTag("device_selector_bar"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .clickable { expanded = true }
                        .testTag("device_dropdown_trigger")
                        .padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = activeDevice?.name ?: "Añadir TV",
                            modifier = Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.headlineMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Icon(RemoteIcons.Down, "Cambiar TV", tint = TextSecondary)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        StatusIndicatorDot(connectionState)
                        Text(
                            text = statusLabel + (activeDevice?.room?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Dropdown Menu for seamless 1-tap switching
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier
                        .background(RemoteCardBg)
                        .border(1.dp, RemoteBorder, RoundedCornerShape(4.dp))
                ) {
                    Text(
                        text = "TUS TELEVISORES",
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
                                    imageVector = RemoteIcons.Search,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Buscar televisores", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
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
                                Text("Añadir TV por dirección IP", color = TextSecondary, fontSize = 13.sp)
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

            IconButton(
                onClick = onPowerClick,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White)
                    .testTag("power_button")
            ) {
                Icon(RemoteIcons.Power, "Encender o apagar TV", tint = Color.Black, modifier = Modifier.size(26.dp))
            }
        }
        HorizontalDivider(color = RemoteBorderStrong)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HeaderAction(RemoteIcons.Search, "Buscar TV", "open_scan_radar_button", onOpenScan, Modifier.weight(1f))
            HeaderAction(
                RemoteIcons.Vibration,
                if (isHapticEnabled) "Vibración sí" else "Vibración no",
                "toggle_haptic_button",
                onToggleHaptic,
                Modifier.weight(1f),
                isHapticEnabled
            )
            HeaderAction(RemoteIcons.Settings, "Ajustes", "open_manager_button", onOpenManager, Modifier.weight(1f))
        }
    }
}

@Composable
private fun HeaderAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false
) {
    Column(
        modifier = modifier
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(if (active) RemoteBtnActive else RemoteSurface)
            .clickable(onClick = onClick)
            .testTag(tag)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)
    ) {
        Icon(icon, contentDescription = label, tint = TextPrimary, modifier = Modifier.size(24.dp))
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
            .size(6.dp)
            .alpha(if (isPulsing) alphaAnim else 1f)
            .background(color, CircleShape)
            .border(1.5.dp, color.copy(alpha = 0.35f), CircleShape)
    )
}
