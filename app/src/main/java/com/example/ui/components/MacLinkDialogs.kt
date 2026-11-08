package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.MacLinkEntity
import com.example.data.model.TvDeviceEntity
import com.example.share.SharedMacLink

@Composable
fun MacShareDialog(
    requestId: String,
    link: SharedMacLink?,
    macs: List<TvDeviceEntity>,
    activeMacId: Long?,
    connected: Boolean,
    busy: Boolean,
    error: String?,
    onSave: (Long) -> Unit,
    onOpen: (Long) -> Unit,
    onConnect: (Long) -> Unit,
    onSetup: () -> Unit,
    onDismiss: () -> Unit
) {
    var chosenId by rememberSaveable(requestId) { mutableStateOf(activeMacId ?: macs.firstOrNull()?.id) }
    // Devices may finish loading after the share Activity is created.
    val selected = macs.firstOrNull { it.id == chosenId } ?: macs.firstOrNull()
    val ready = selected != null && activeMacId == selected.id && connected
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        modifier = Modifier.testTag("mac_share_dialog"),
        title = { ForwardRemoteVolumeKeys(); Text("Pon esto en el Mac") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (link == null) {
                    Text("Compartí un enlace web, de vídeo o de mapas. No se admiten archivos adjuntos.",
                        Modifier.testTag("mac_share_invalid"))
                } else {
                    Text(link.title, style = MaterialTheme.typography.titleMedium)
                    Text(link.url, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    if (macs.isEmpty()) {
                        Text("Agregá tu Mac para enviarle enlaces.")
                        TextButton(onClick = onSetup, enabled = !busy) { Text("Agregar Mac") }
                    } else {
                        Text("Mac de destino")
                        macs.forEach { mac ->
                            FilterChip(selected = mac.id == selected?.id, enabled = !busy,
                                onClick = { chosenId = mac.id }, label = { Text(mac.name) })
                        }
                        if (!ready) {
                            Text("Podés guardar sin conexión. Para abrir, conectá este Mac.")
                            TextButton(enabled = !busy, onClick = { selected?.id?.let(onConnect) }) { Text("Conectar") }
                        }
                    }
                }
                if (busy) Text("Procesando…")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Column {
                TextButton(enabled = link != null && selected != null && !busy,
                    onClick = { selected?.id?.let(onSave) }) { Text("Guardar para después") }
                TextButton(enabled = link != null && ready && !busy,
                    onClick = { selected?.id?.let(onOpen) }) { Text("Abrir") }
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
fun MacQueueDialog(
    deviceName: String,
    links: List<MacLinkEntity>,
    connected: Boolean,
    busy: Boolean,
    error: String?,
    onNext: () -> Unit,
    onRemove: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        modifier = Modifier.testTag("mac_queue_dialog"),
        onDismissRequest = onDismiss,
        title = { ForwardRemoteVolumeKeys(); Text("Enlaces · $deviceName") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (links.isEmpty()) Text("No hay enlaces pendientes. Desde Compartir, elegí OmniRemote y Guardar para después.")
                else {
                    Text("Se abren en orden. Al terminar, tocá Abrir siguiente.")
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                        itemsIndexed(links, key = { _, link -> link.id }) { index, link ->
                            Row(Modifier.fillMaxWidth()) {
                                Column(Modifier.weight(1f)) {
                                    Text("${index + 1}. ${link.title}", maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(link.url, style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                IconButton(enabled = !busy, onClick = { onRemove(link.id) }) {
                                    Icon(Icons.Outlined.Delete, contentDescription = "Eliminar ${link.title}")
                                }
                            }
                        }
                    }
                    if (!connected) Text("Conectá este Mac para abrir el siguiente enlace.")
                }
                if (busy) Text("Abriendo en el Mac…")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(enabled = links.isNotEmpty() && connected && !busy, onClick = onNext) { Text("Abrir siguiente") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } }
    )
}
