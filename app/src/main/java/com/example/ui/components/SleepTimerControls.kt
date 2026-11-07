package com.example.ui.components

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.example.data.model.DeviceType
import com.example.data.model.TvDeviceEntity
import com.example.sleep.SleepTimerState

private enum class SleepRequestKind { QUICK, DURATION, TIME, EXTEND }
private data class SleepRequest(val kind: SleepRequestKind, val deviceId: Long,
    val first: Int = 0, val second: Int = 0, val token: String = "")

private val SleepRequestSaver = Saver<SleepRequest?, Any>(
    save = { request -> request?.let { arrayListOf(it.kind.name, it.deviceId.toString(),
        it.first.toString(), it.second.toString(), it.token) } },
    restore = {
        val values = it as List<*>
        SleepRequest(SleepRequestKind.valueOf(values[0] as String), (values[1] as String).toLong(),
            (values[2] as String).toInt(), (values[3] as String).toInt(), values[4] as String)
    }
)

/** Owns options and permission requests, retaining their exact destination through Settings. */
@Composable
fun SleepTimerControls(
    activeDevice: TvDeviceEntity?,
    timer: SleepTimerState?,
    remainingSeconds: Long?,
    onQuick: (Long) -> Boolean,
    onDuration: (Long, Int) -> Boolean,
    onTime: (Long, Int, Int) -> Boolean,
    onExtend: (String) -> Boolean,
    onCancel: () -> Unit,
    header: @Composable (onClick: () -> Unit, onLongClick: () -> Unit) -> Unit
) {
    val context = LocalContext.current
    var optionsDeviceId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pending by rememberSaveable(stateSaver = SleepRequestSaver) { mutableStateOf<SleepRequest?>(null) }
    var showPermission by rememberSaveable { mutableStateOf(false) }

    fun isCurrent(request: SleepRequest): Boolean = when (request.kind) {
        SleepRequestKind.EXTEND -> timer?.token == request.token && timer.remainingSeconds() > 0
        else -> activeDevice?.id == request.deviceId && activeDevice.deviceType != DeviceType.MACOS
    }
    fun perform(request: SleepRequest): Boolean = when (request.kind) {
        SleepRequestKind.QUICK -> onQuick(request.deviceId)
        SleepRequestKind.DURATION -> onDuration(request.deviceId, request.first)
        SleepRequestKind.TIME -> onTime(request.deviceId, request.first, request.second)
        SleepRequestKind.EXTEND -> onExtend(request.token)
    }
    fun submit(request: SleepRequest) {
        if (!isCurrent(request)) return
        // A new explicit action supersedes any request still waiting for Settings.
        pending = null
        showPermission = false
        if (perform(request)) optionsDeviceId = null
        else { pending = request; showPermission = true }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val request = pending
        pending = null
        if (request != null && isCurrent(request)) {
            if (perform(request)) optionsDeviceId = null
            else Toast.makeText(context, "Sleep no se activó: falta permitir Alarmas y recordatorios", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(activeDevice?.id, timer?.token) {
        if (optionsDeviceId != activeDevice?.id) optionsDeviceId = null
        if (pending?.let { !isCurrent(it) } == true) {
            pending = null
            showPermission = false
        }
    }

    header(
        { activeDevice?.let { submit(SleepRequest(SleepRequestKind.QUICK, it.id)) } },
        { activeDevice?.takeIf { it.id > 0 && it.deviceType != DeviceType.MACOS }?.let { optionsDeviceId = it.id } }
    )
    timer?.let { scheduled ->
        SleepTimerNotice(scheduled, remainingSeconds ?: 0L, onCancel,
            onExtend = { submit(SleepRequest(SleepRequestKind.EXTEND, scheduled.deviceId, token = scheduled.token)) })
    }
    if (optionsDeviceId != null && activeDevice != null && activeDevice.id == optionsDeviceId) {
        SleepTimerOptionsDialog(
            deviceName = activeDevice.name,
            onDismiss = { optionsDeviceId = null; pending = null },
            onDuration = { submit(SleepRequest(SleepRequestKind.DURATION, activeDevice.id, first = it)) },
            onTime = { hour, minute -> submit(SleepRequest(SleepRequestKind.TIME, activeDevice.id, hour, minute)) }
        )
    }
    if (showPermission && pending?.let(::isCurrent) == true) {
        AlertDialog(
            onDismissRequest = { showPermission = false; pending = null },
            title = { ForwardRemoteVolumeKeys(); Text("Activar Sleep") },
            text = { Text("Permití Alarmas y recordatorios para apagar la TV a tiempo, incluso con el teléfono bloqueado.") },
            confirmButton = {
                TextButton(onClick = {
                    showPermission = false
                    try {
                        permissionLauncher.launch(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:${context.packageName}")))
                    } catch (_: ActivityNotFoundException) {
                        pending = null
                        Toast.makeText(context, "Habilitá Alarmas y recordatorios para OmniRemote en Ajustes", Toast.LENGTH_LONG).show()
                    }
                }) { Text("Permitir") }
            },
            dismissButton = { TextButton(onClick = { showPermission = false; pending = null }) { Text("Cancelar") } }
        )
    }
}
