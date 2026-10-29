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
fun SleepTimerButton(minutes: Int?, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val active = minutes != null
    val tint = when { !enabled -> TextMuted; active -> Void; else -> Paper }
    Column(
        modifier = modifier
            .size(56.dp)
            .background(if (active) Paper else Void)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = "Sleep: ${minutes?.let { "$it minutos" } ?: "OFF"}"
            }
            .testTag("sleep_button"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)
    ) {
        Icon(Icons.Outlined.Bedtime, contentDescription = null, tint = tint, modifier = Modifier.size(19.dp))
        MonoLabel(minutes?.let { "${it}m" } ?: "Sleep", color = tint)
    }
}
