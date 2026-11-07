package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.sleep.nextSleepOccurrence
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun SleepTimerOptionsDialog(
    deviceName: String,
    onDismiss: () -> Unit,
    onDuration: (Int) -> Unit,
    onTime: (Int, Int) -> Unit
) {
    var clockMode by rememberSaveable { mutableStateOf(false) }
    var duration by rememberSaveable { mutableStateOf("30") }
    var hour by rememberSaveable { mutableStateOf(Calendar.getInstance().get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0')) }
    var minute by rememberSaveable { mutableStateOf(Calendar.getInstance().get(Calendar.MINUTE).toString().padStart(2, '0')) }
    val minutes = duration.toIntOrNull()?.takeIf { it in 1..1440 }
    val h = hour.toIntOrNull()?.takeIf { it in 0..23 }
    val m = minute.toIntOrNull()?.takeIf { it in 0..59 }
    val valid = if (clockMode) h != null && m != null else minutes != null

    AlertDialog(
        modifier = Modifier.testTag("sleep_options_dialog"),
        onDismissRequest = onDismiss,
        title = {
            ForwardRemoteVolumeKeys()
            Text("Personalizar Sleep")
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(deviceName)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilterChip(selected = !clockMode, onClick = { clockMode = false }, label = { Text("Duración") })
                    FilterChip(selected = clockMode, onClick = { clockMode = true }, label = { Text("Hora") })
                }
                if (!clockMode) {
                    OutlinedTextField(
                        value = duration, onValueChange = { duration = it },
                        label = { Text("Minutos") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = minutes == null,
                        supportingText = { Text("Entre 1 y 1440 minutos") },
                        modifier = Modifier.fillMaxWidth().testTag("sleep_duration_input")
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(value = hour, onValueChange = { hour = it }, label = { Text("Hora (0–23)") },
                            singleLine = true, isError = h == null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f).widthIn(min = 80.dp).testTag("sleep_hour_input"))
                        OutlinedTextField(value = minute, onValueChange = { minute = it }, label = { Text("Minutos") },
                            singleLine = true, isError = m == null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f).widthIn(min = 80.dp).testTag("sleep_minute_input"))
                    }
                    if (h != null && m != null) {
                        Text(sleepTimeDescription(nextSleepOccurrence(h, m)), Modifier.testTag("sleep_time_preview"))
                    }
                    Text("Hora local del teléfono · una sola vez")
                }
                Text("Toque rápido: 30 → 60 → 120 → OFF")
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                if (clockMode && h != null && m != null) onTime(h, m)
                else if (!clockMode && minutes != null) onDuration(minutes)
            }) { Text("Programar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } }
    )
}

internal fun sleepTimeDescription(targetMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
    val now = Calendar.getInstance().apply { timeInMillis = nowMillis }
    val target = Calendar.getInstance().apply { timeInMillis = targetMillis }
    val today = now.get(Calendar.ERA) == target.get(Calendar.ERA) &&
        now.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
    return "${if (today) "Hoy" else "Mañana"} · ${SimpleDateFormat("d MMM · HH:mm", Locale.forLanguageTag("es")).format(Date(targetMillis))}"
}
