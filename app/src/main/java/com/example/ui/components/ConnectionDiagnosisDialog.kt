package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.network.ConnectionDiagnosticReport
import com.example.network.DiagnosticAction
import com.example.network.DiagnosticStatus

data class ConnectionDiagnosisUiState(
    val deviceName: String,
    val isRunning: Boolean = true,
    val stage: String = "Comprobando la conexión…",
    val report: ConnectionDiagnosticReport? = null,
    val message: String? = null
)

@Composable
fun ConnectionDiagnosisDialog(
    state: ConnectionDiagnosisUiState,
    onDismiss: () -> Unit,
    onRecheck: () -> Unit,
    onAction: (DiagnosticAction) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            ForwardRemoteVolumeKeys()
            Text("Diagnóstico de conexión")
        },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(state.deviceName, style = MaterialTheme.typography.titleMedium)
                if (state.isRunning) {
                    CircularProgressIndicator()
                    Text(state.stage)
                } else {
                    state.report?.checks?.forEach { check ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(check.title, style = MaterialTheme.typography.titleSmall)
                            Text(check.status.label, style = MaterialTheme.typography.labelMedium,
                                color = if (check.status == DiagnosticStatus.ATTENTION) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary)
                            Text(check.message)
                        }
                        HorizontalDivider()
                    }
                    state.message?.let { Text(it) }
                    state.report?.actions?.forEach { action ->
                        OutlinedButton(onClick = { onAction(action) }, modifier = Modifier.fillMaxWidth()) {
                            Text(action.label, modifier = Modifier.padding(vertical = 2.dp))
                        }
                    }
                    Text("La vinculación guardada se conserva. No se borran credenciales al comprobar.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
        dismissButton = {
            if (!state.isRunning) TextButton(onClick = onRecheck) { Text("Volver a comprobar") }
        }
    )
}
