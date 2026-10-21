package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*

@Composable
fun AndroidTvPairingDialog(
    isOpen: Boolean,
    deviceName: String,
    deviceIp: String,
    isLoading: Boolean,
    errorMessage: String?,
    successMessage: String?,
    onSubmitPin: (String) -> Unit,
    onRetry: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    if (!isOpen) return

    var pinText by remember(deviceIp) { mutableStateOf("") }
    val canEnterPin = !isLoading && errorMessage.isNullOrBlank() && successMessage.isNullOrBlank()
    val canSubmit = canEnterPin && pinText.matches(Regex("[0-9A-F]{6}"))
    val focusManager = LocalFocusManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("android_tv_pairing_dialog"),
        containerColor = Color(0xFF0C0C0C),
        shape = RoundedCornerShape(12.dp),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .background(Color(0xFF181818), RoundedCornerShape(6.dp))
                        .border(1.dp, RemoteBorderStrong, RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Key,
                        contentDescription = "Pairing Key",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column {
                    Text(
                        text = "PAIR ANDROID / GOOGLE TV",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            color = Color.White,
                            letterSpacing = 0.5.sp
                        )
                    )
                    Text(
                        text = "$deviceName // $deviceIp",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            color = TextMuted,
                            fontSize = 10.sp
                        )
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (isLoading && errorMessage.isNullOrBlank()) {
                    Surface(
                        color = Color(0xFF121212),
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, RemoteBorderStrong)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Column {
                                Text(
                                    text = "Connecting to TV...",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                )
                                Text(
                                    text = "Requesting pairing code from your TV screen...",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = TextSecondary,
                                        fontSize = 11.sp
                                    )
                                )
                            }
                        }
                    }
                } else if (canEnterPin) {
                    Surface(
                        color = Color(0xFF121212),
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, RemoteBorderStrong)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tv,
                                contentDescription = "TV Screen",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "La TV aceptó la solicitud de vinculación. Introduce el código de 6 caracteres (0–9, A–F) que aparezca en su pantalla.",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = TextSecondary,
                                    fontSize = 11.sp,
                                    lineHeight = 16.sp
                                )
                            )
                        }
                    }
                }

                // PIN Input Field
                OutlinedTextField(
                    value = pinText,
                    enabled = canEnterPin,
                    onValueChange = { input ->
                        pinText = input.uppercase().filter { it in "0123456789ABCDEF" }.take(6)
                    },
                    label = { Text("6-CHARACTER CODE", fontFamily = FontFamily.Monospace, fontSize = 10.sp) },
                    placeholder = { Text("e.g. 4A8B2C", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("pairing_pin_input"),
                    textStyle = MaterialTheme.typography.titleLarge.copy(
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 6.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color.White
                    ),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            focusManager.clearFocus()
                            if (canSubmit) onSubmitPin(pinText)
                        }
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.White,
                        unfocusedBorderColor = RemoteBorderStrong,
                        focusedContainerColor = Color(0xFF141414),
                        unfocusedContainerColor = Color(0xFF141414),
                        focusedLabelColor = Color.White,
                        unfocusedLabelColor = TextMuted,
                        cursorColor = Color.White
                    )
                )

                // Error State with Retry Button
                AnimatedVisibility(visible = !errorMessage.isNullOrBlank()) {
                    Surface(
                        color = Color(0xFF161616),
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF555555))
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.Warning, contentDescription = "Error", tint = Color.White, modifier = Modifier.size(16.dp))
                                Text(
                                    text = errorMessage ?: "",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (onRetry != null) {
                                Surface(
                                    onClick = { pinText = ""; onRetry() },
                                    color = Color.White,
                                    shape = RoundedCornerShape(4.dp),
                                    modifier = Modifier.align(Alignment.End).testTag("pairing_retry_button")
                                ) {
                                    Text(
                                        "RETRY",
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = Color.Black,
                                        fontWeight = FontWeight.Black
                                    )
                                }
                            }
                        }
                    }
                }

                // Success State
                AnimatedVisibility(visible = !successMessage.isNullOrBlank()) {
                    Surface(
                        color = Color(0xFF141414),
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White)
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = "Success", tint = Color.White, modifier = Modifier.size(16.dp))
                            Text(
                                text = successMessage ?: "",
                                color = Color.White,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                // Tip
                Text(
                    text = "Si falla la conexión, comprueba la IP actual de la TV y que la red permita la comunicación entre dispositivos. La MAC solo se utiliza para Wake-on-LAN.",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = TextMuted,
                        fontSize = 10.sp
                    )
                )
            }
        },
        confirmButton = {
            Surface(
                onClick = {
                    focusManager.clearFocus()
                    onSubmitPin(pinText)
                },
                enabled = canSubmit,
                color = if (canSubmit) Color.White else Color(0xFF222222),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.testTag("pairing_submit_button")
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Color.Black,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(
                            "SUBMIT CODE",
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = if (canSubmit) Color.Black else TextMuted
                        )
                    }
                }
            }
        },
        dismissButton = {
            Surface(
                onClick = onDismiss,
                shape = RoundedCornerShape(6.dp),
                color = Color.Transparent,
                border = androidx.compose.foundation.BorderStroke(1.dp, RemoteBorderStrong)
            ) {
                Text(
                    "CANCEL",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    color = TextSecondary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    )
}
