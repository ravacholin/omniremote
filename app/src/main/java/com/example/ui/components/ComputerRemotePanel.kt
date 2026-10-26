package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.model.DeviceType
import com.example.data.model.RemoteKey
import com.example.network.ComputerVolume
import com.example.ui.theme.Ash
import com.example.ui.theme.Hairline
import com.example.ui.theme.Paper
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import kotlin.math.roundToInt

/** Volume and media for a Mac or PC: the controls its SSH server can reach. */
@Composable
fun ComputerRemotePanel(
    deviceType: DeviceType,
    volume: ComputerVolume?,
    onKeyPress: (RemoteKey) -> Unit,
    onVolumeChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    fun press(key: RemoteKey) {
        HapticUtil.triggerKeyHaptic(context)
        onKeyPress(key)
    }
    // Follows the computer, except while the finger is on the slider.
    var dragLevel by remember { mutableStateOf<Float?>(null) }
    val shownLevel = dragLevel ?: volume?.level?.toFloat() ?: 0f

    Column(
        modifier = modifier.testTag("mac_remote_panel"),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MonoLabel(if (volume?.muted == true) "Volumen // silencio" else "Volumen")
            Text(
                text = if (volume == null) "—" else shownLevel.roundToInt().toString(),
                style = MaterialTheme.typography.displayMedium,
                color = if (volume?.muted == true) TextMuted else TextPrimary,
                modifier = Modifier.testTag("mac_volume_level")
            )
            Slider(
                value = shownLevel,
                onValueChange = { level ->
                    dragLevel = level
                    onVolumeChange(level.roundToInt())
                },
                onValueChangeFinished = { dragLevel = null },
                valueRange = 0f..100f,
                enabled = volume != null,
                colors = SliderDefaults.colors(
                    thumbColor = Paper,
                    activeTrackColor = Paper,
                    inactiveTrackColor = Hairline,
                    disabledThumbColor = Ash,
                    disabledActiveTrackColor = Hairline,
                    disabledInactiveTrackColor = Hairline
                ),
                modifier = Modifier.fillMaxWidth().testTag("mac_volume_slider")
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().height(64.dp).hairlineGrid(),
            horizontalArrangement = Arrangement.spacedBy(GridGap)
        ) {
            val cell = Modifier.weight(1f).fillMaxHeight()
            IconKey(RemoteIcons.VolumeDown, "Vol -", { press(RemoteKey.VOL_DOWN) }, cell, "mac_vol_down", repeatable = true)
            IconKey(RemoteIcons.Mute, "Mute", { press(RemoteKey.MUTE) }, cell, "mac_mute")
            IconKey(RemoteIcons.VolumeUp, "Vol +", { press(RemoteKey.VOL_UP) }, cell, "mac_vol_up", repeatable = true)
        }

        Row(
            modifier = Modifier.fillMaxWidth().height(88.dp).hairlineGrid(),
            horizontalArrangement = Arrangement.spacedBy(GridGap)
        ) {
            val cell = Modifier.weight(1f).fillMaxHeight()
            IconKey(RemoteIcons.Rewind, "Anterior", { press(RemoteKey.REWIND) }, cell, "mac_previous")
            GridKey(onClick = { press(RemoteKey.PLAY_PAUSE) }, modifier = Modifier.weight(1.4f).fillMaxHeight(), testTag = "mac_play_pause", inverted = true) { tint ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(RemoteIcons.Play, contentDescription = "Play/Pause", tint = tint, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.size(4.dp))
                    Icon(RemoteIcons.Pause, contentDescription = null, tint = tint, modifier = Modifier.size(26.dp))
                }
            }
            IconKey(RemoteIcons.Forward, "Siguiente", { press(RemoteKey.FAST_FORWARD) }, cell, "mac_next")
        }

        MonoLabel(
            if (deviceType == DeviceType.WINDOWS) "Spotify, el navegador o la app que esté sonando"
            else "Música, Spotify o la app que esté sonando"
        )
    }
}
