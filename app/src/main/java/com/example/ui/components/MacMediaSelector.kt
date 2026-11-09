package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.controller.MacMediaState

@Composable
fun MacMediaSelector(
    state: MacMediaState,
    onSelect: (String?) -> Unit,
    onRefresh: () -> Unit,
    connected: Boolean = true,
    onReveal: () -> Unit = {}
) {
    var open by rememberSaveable { mutableStateOf(false) }
    // Opening and reconnecting while open refresh; there is no background polling.
    LaunchedEffect(open, connected) { if (open && connected) onRefresh() }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().testTag("mac_media_selector")) {
            Text("Controlar: ${state.label}")
        }
        state.selectedSource?.let { source ->
            if (source.detail.isNotBlank()) Text(source.detail, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            Text(source.observationLabel, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick = onReveal, enabled = connected && state.canReveal,
            modifier = Modifier.fillMaxWidth().testTag("mac_show_source")) {
            Text("Mostrame esto")
        }
        if (state.selectedId == null) Text("Elegí una fuente para mostrarla en la Mac.", style = MaterialTheme.typography.bodySmall)
        if (state.selectedId != null && state.selectedSource == null) Text("Fuente no disponible. Elegí otra o actualizá.", style = MaterialTheme.typography.bodySmall)
        state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (state.selectedSource?.service?.isWeb == true) Text("Compatibilidad web pendiente de validar en tu Mac.", style = MaterialTheme.typography.bodySmall)
    }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("Controlar") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onSelect(null); open = false }, modifier = Modifier.fillMaxWidth().testTag("mac_source_automatic")) { Text("Automático") }
                Text("Elegir una fuente no inicia ni pausa la reproducción.", style = MaterialTheme.typography.bodySmall)
                if (!connected) Text("Conectá la Mac para actualizar y controlar reproductores.")
                if (state.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                if (!state.loading && state.sources.isEmpty()) Text("No se encontraron reproductores compatibles abiertos.")
                state.sources.forEach { source ->
                    OutlinedButton(onClick = { onSelect(source.id); open = false }, modifier = Modifier.fillMaxWidth().testTag("mac_source_${source.id}")) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(source.label)
                            if (source.detail.isNotBlank()) Text(source.detail, style = MaterialTheme.typography.bodySmall)
                            Text(source.observationLabel, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                state.issues.forEach { Text(it.message, style = MaterialTheme.typography.bodySmall) }
                if (state.sources.any { it.issue?.code == "CHROME_JAVASCRIPT" } || state.issues.any { it.code == "CHROME_JAVASCRIPT" }) {
                    Text("Chrome > View > Developer > Allow JavaScript from Apple Events.", style = MaterialTheme.typography.bodySmall)
                }
                Text("Apps: Spotify y Música. Chrome: YouTube, YouTube Music y Spotify web. Las pestañas de incógnito se excluyen.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onRefresh, enabled = connected && !state.loading && !state.busy) { Text("Actualizar") } },
        dismissButton = { TextButton(onClick = { open = false }) { Text("Cerrar") } }
    )
}
