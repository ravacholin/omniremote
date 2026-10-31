package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.ui.theme.Paper
import com.example.ui.theme.TextMuted
import com.example.ui.theme.Void

@Composable
fun SleepTimerButton(remainingSeconds: Long?, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val active = remainingSeconds != null
    val tint = when { !enabled -> TextMuted; active -> Void; else -> Paper }
    Column(
        modifier = modifier
            .size(56.dp)
            .background(if (active) Paper else Void)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = when {
                    remainingSeconds == null -> "Sleep: OFF"
                    remainingSeconds > 0 -> "Sleep: quedan ${formatSleepCountdown(remainingSeconds)}"
                    else -> "Sleep: vencido, esperando apagado"
                }
            }
            .testTag("sleep_button"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)
    ) {
        Icon(Icons.Outlined.Bedtime, contentDescription = null, tint = tint, modifier = Modifier.size(19.dp))
        MonoLabel(remainingSeconds?.let { formatSleepCountdown(it) } ?: "Sleep", color = tint)
    }
}
