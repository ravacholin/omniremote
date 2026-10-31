package com.example.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.sleep.SleepTimerState
import kotlinx.coroutines.delay

/** UI refresh only. AlarmManager owns expiry even when this composition is gone. */
@Composable
fun rememberSleepTimerRemainingSeconds(timer: SleepTimerState?): Long? {
    if (timer == null) return null
    return key(timer) {
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val remaining by produceState(initialValue = timer.remainingSeconds(), lifecycle) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                do {
                    value = timer.remainingSeconds()
                    if (value == 0L) break
                    delay(1_000)
                } while (true)
            }
        }
        remaining
    }
}

fun formatSleepCountdown(remainingSeconds: Long): String {
    val seconds = remainingSeconds.coerceAtLeast(0L)
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

@Composable
fun SleepTimerNotice(timer: SleepTimerState, remainingSeconds: Long, onCancel: () -> Unit) {
    Notice(
        label = if (remainingSeconds > 0) "Sleep · ${formatSleepCountdown(remainingSeconds)} restantes"
            else "Sleep · Vencido",
        message = if (remainingSeconds > 0) timer.deviceName else "${timer.deviceName} · Esperando apagado",
        modifier = Modifier.testTag("sleep_timer_notice"),
        actions = listOf(NoticeAction("Cancelar", onClick = onCancel))
    )
}
