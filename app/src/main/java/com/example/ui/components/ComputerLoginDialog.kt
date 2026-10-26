package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.data.model.DeviceType
import com.example.ui.theme.Ash
import com.example.ui.theme.Ink
import com.example.ui.theme.Paper
import com.example.ui.theme.RemoteBorderStrong
import com.example.ui.theme.Sharp
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSecondary

/**
 * One-time Mac or PC login. The password authorizes a key generated on the phone and is then
 * forgotten; the computer only needs its built-in SSH server turned on.
 */
@Composable
fun ComputerLoginDialog(
    isOpen: Boolean,
    deviceType: DeviceType,
    deviceName: String,
    deviceIp: String,
    isLoading: Boolean,
    errorMessage: String?,
    onSubmit: (username: String, password: String) -> Unit,
    onDismiss: () -> Unit
) {
    if (!isOpen) return
    val isWindows = deviceType == DeviceType.WINDOWS

    var username by rememberSaveable(deviceIp) { mutableStateOf("") }
    var password by remember(deviceIp) { mutableStateOf("") }
    val canSubmit = !isLoading && username.isNotBlank() && password.isNotEmpty()
    val submit = { if (canSubmit) onSubmit(username, password) }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Paper,
        unfocusedBorderColor = RemoteBorderStrong,
        focusedContainerColor = Ink,
        unfocusedContainerColor = Ink,
        focusedLabelColor = Paper,
        unfocusedLabelColor = TextMuted,
        cursorColor = Paper
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("mac_login_dialog"),
        containerColor = Ink,
        shape = Sharp,
        title = {
            Column {
                Text(if (isWindows) "Conectar la PC" else "Conectar la Mac", style = MaterialTheme.typography.titleMedium, color = Paper)
                MonoLabel("$deviceName // $deviceIp")
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = if (isWindows) {
                        "En la PC activá Configuración > Sistema > Características opcionales > Servidor OpenSSH. " +
                            "No hace falta instalar nada más. La contraseña se usa una sola vez para autorizar este teléfono y no se guarda."
                    } else {
                        "En la Mac activá Ajustes del Sistema > General > Compartir > Sesión remota. " +
                            "No hace falta instalar nada. La contraseña se usa una sola vez para autorizar este teléfono y no se guarda."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it.trim() },
                    enabled = !isLoading,
                    label = { Text(if (isWindows) "Usuario de Windows" else "Usuario de la Mac") },
                    placeholder = { Text(if (isWindows) "carpeta en C:\\Users, p. ej. pablo" else "nombre corto, p. ej. pablo") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Next
                    ),
                    colors = fieldColors,
                    shape = Sharp,
                    modifier = Modifier.fillMaxWidth().testTag("mac_login_user")
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    enabled = !isLoading,
                    label = { Text(if (isWindows) "Contraseña (no el PIN)" else "Contraseña") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    colors = fieldColors,
                    shape = Sharp,
                    modifier = Modifier.fillMaxWidth().testTag("mac_login_password")
                )
                if (!errorMessage.isNullOrBlank()) {
                    Notice(label = deviceType.shortName, message = errorMessage)
                }
                Text(
                    text = if (isWindows) {
                        "En la PC, OmniRemote controla el volumen y el silencio."
                    } else {
                        "Para controlar el reproductor, la Mac puede pedir permiso la primera vez: aceptalo en la pantalla de la Mac. " +
                            "Para pausar YouTube o cualquier app, agregá sshd-keygen-wrapper en Privacidad y seguridad > Accesibilidad."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
            }
        },
        confirmButton = {
            GridKey(onClick = submit, inverted = canSubmit, testTag = "mac_login_submit") { tint ->
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Paper, strokeWidth = 2.dp)
                } else {
                    Text(
                        "CONECTAR",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (canSubmit) tint else Ash,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
        },
        dismissButton = {
            GridKey(onClick = onDismiss) { tint ->
                Text("CANCELAR", style = MaterialTheme.typography.labelSmall, color = tint, modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    )
}
