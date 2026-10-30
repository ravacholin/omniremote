package com.example.ui.components

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.sleep.SleepTimerState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow

@Composable
fun SleepTimerNotice(timer: SleepTimerState, onCancel: () -> Unit) {
    key(timer.token, timer.deadlineElapsed) {
        val now by remember(timer.deadlineElapsed) {
            flow {
                while (true) {
                    val elapsed = SystemClock.elapsedRealtime()
                    emit(elapsed)
                    val remaining = timer.deadlineElapsed - elapsed
                    if (remaining <= 0) break
                    // Update at each remaining-minute boundary, even when opened mid-timer.
                    delay((remaining - 1) % 60_000L + 1)
                }
            }
        }.collectAsStateWithLifecycle(initialValue = SystemClock.elapsedRealtime())
        val remainingMillis = (timer.deadlineElapsed - now).coerceAtLeast(0)
        // Round up so the final minute stays visible until the timer actually expires.
        val remainingMinutes = (remainingMillis + 59_999L) / 60_000L

        Notice(
            label = "Sleep · $remainingMinutes min",
            message = timer.deviceName,
            modifier = Modifier.testTag("sleep_timer_notice"),
            actions = listOf(NoticeAction("Cancelar", onClick = onCancel))
        )
    }
}
