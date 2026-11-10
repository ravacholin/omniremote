package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.controller.AdbSetupState

@Composable
fun AndroidAdbSetupDialog(
    state: AdbSetupState,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
    onForget: () -> Unit
) {
    var port by remember(state.device.id, state.device.ipAddress, state.port) { mutableStateOf(state.port.toString()) }
    val validPort = port.toIntOrNull()?.let { it in 1..65535 } == true
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Abrir TVDimmer directamente") },
        text = {
            ForwardRemoteVolumeKeys()
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${state.device.name} · ${state.device.ipAddress}")
                Text("1. En el FlowBox, activa Opciones de desarrollador y Depuración de red (ADB por TCP).\n" +
                    "2. Pulsa Conectar y abrir.\n" +
                    "3. En la TV, acepta la autorización de OmniRemote y marca Permitir siempre.")
                OutlinedTextField(
                    value = port, onValueChange = { port = it },
                    label = { Text("Puerto ADB") },
                    supportingText = { Text("Normalmente 5555. No es el puerto del mando.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true, enabled = !state.busy,
                    isError = !validPort,
                    modifier = Modifier.fillMaxWidth().testTag("adb_port")
                )
                Text("Si solo aparece Depuración inalámbrica con un código, ese puerto no sirve aquí. " +
                    "Puedes habilitar TCP desde una computadora por USB con adb tcpip 5555. " +
                    "Al reiniciar el FlowBox puede ser necesario habilitarlo otra vez.", style = MaterialTheme.typography.bodySmall)
                if (state.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Conectando… Mira la TV y acepta la autorización si aparece.")
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onForget, enabled = !state.busy) { Text("Olvidar configuración ADB") }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(port) }, enabled = validPort && !state.busy,
                modifier = Modifier.testTag("adb_connect")) { Text("Conectar y abrir") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        modifier = Modifier.testTag("adb_setup_dialog")
    )
}
