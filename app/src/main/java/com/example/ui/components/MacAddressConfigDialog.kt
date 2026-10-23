package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.DeviceType
import com.example.data.model.TvDeviceEntity
import com.example.network.WakeOnLan
import com.example.ui.theme.PowerRed
import com.example.ui.theme.RemoteDarkBg
import com.example.ui.theme.RemoteBorder
import com.example.ui.theme.RemoteCardBg
import com.example.ui.theme.RemoteSurfaceVariant
import com.example.ui.theme.StatusGreen
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@Composable
fun MacAddressConfigDialog(
    device: TvDeviceEntity,
    onDismiss: () -> Unit,
    onSaveMac: (TvDeviceEntity, String, Boolean) -> Unit,
    onDetectMac: suspend (TvDeviceEntity) -> String?
) {
    var macInput by remember(device.macAddress) { mutableStateOf(device.macAddress) }
    var isDetecting by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(0.dp))
                .border(1.dp, RemoteBorder, RoundedCornerShape(0.dp)),
            color = RemoteDarkBg
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(0.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Configurar Wake-on-LAN",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                            )
                            Text(
                                text = "${device.name} • ${device.ipAddress}",
                                style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary, fontSize = 11.sp)
                            )
                        }
                    }

                    IconButton(onClick = onDismiss, modifier = Modifier.testTag("close_mac_dialog")) {
                        Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = TextMuted)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Explanatory Note
                Surface(
                    color = RemoteSurfaceVariant.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(0.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, RemoteBorder)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp).padding(top = 2.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Para encender la TV desde modo apagado/reposo, la red necesita su dirección física MAC (Wake-on-LAN). La compatibilidad con Wake-on-LAN depende de la TV y de su conexión de red.",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = TextPrimary,
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = if (device.deviceType == DeviceType.SAMSUNG_TIZEN) {
                                "📍 Dónde verla en tu Samsung:\nMenú > Ajustes > General > Red > Estado de red (o en los clientes de tu router Wi-Fi)."
                            } else {
                                "📍 Dónde verla en Google/Android TV:\nAjustes > Sistema > Información > Estado (o en los clientes de tu router Wi-Fi)."
                            },
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = TextMuted,
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // MAC Input Field
                Text(
                    text = "DIRECCIÓN MAC DE LA TV",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = TextMuted,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                )
                Spacer(modifier = Modifier.height(6.dp))

                OutlinedTextField(
                    value = macInput,
                    onValueChange = { input ->
                        macInput = input.uppercase()
                        statusMessage = null
                        isError = false
                    },
                    placeholder = { Text("Ej: E4:7D:BD:2A:1B:9C", color = TextMuted) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("mac_address_input"),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = RemoteBorder,
                        focusedContainerColor = RemoteCardBg,
                        unfocusedContainerColor = RemoteCardBg
                    ),
                    shape = RoundedCornerShape(0.dp)
                )

                if (statusMessage != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = statusMessage!!,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = if (isError) PowerRed else StatusGreen,
                            fontSize = 11.sp
                        )
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Auto-detect Button
                OutlinedButton(
                    onClick = {
                        isDetecting = true
                        statusMessage = "Consultando TV en ${device.ipAddress}..."
                        isError = false
                        scope.launch {
                            val resolved = onDetectMac(device)
                            isDetecting = false
                            if (!resolved.isNullOrBlank()) {
                                macInput = resolved
                                statusMessage = "¡MAC detectada exitosamente de la TV!"
                                isError = false
                            } else {
                                statusMessage = "No se pudo obtener la MAC por red. Esto no confirma que la TV esté apagada. Puedes ingresar la MAC manualmente."
                                isError = true
                            }
                        }
                    },
                    enabled = !isDetecting,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(0.dp)
                ) {
                    if (isDetecting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Detectando...")
                    } else {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Detectar MAC automáticamente", fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Primary Save & Power On
                Button(
                    onClick = {
                        val formatted = WakeOnLan.formatMacAddress(macInput)
                        if (formatted == null) {
                            statusMessage = "Formato de MAC inválido. Debe tener 12 caracteres hexadecimales (ej: E4:7D:BD:2A:1B:9C)"
                            isError = true
                        } else {
                            onSaveMac(device, formatted, true)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("save_and_power_on_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                    shape = RoundedCornerShape(0.dp)
                ) {
                    Icon(Icons.Default.Bolt, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Guardar y Encender TV", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }
    }
}
