package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
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
import com.example.data.model.RemoteKey
import com.example.network.MacVolume
import kotlin.math.roundToInt

/** Volume and media for a Mac: the only controls macOS exposes without installing an app. */
@Composable
fun MacRemotePanel(
    volume: MacVolume?,
    onKeyPress: (RemoteKey) -> Unit,
    onVolumeChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    fillHeight: Boolean = true
) {
    val context = LocalContext.current
    fun press(key: RemoteKey) {
        HapticUtil.triggerKeyHaptic(context)
        onKeyPress(key)
    }
    // Follows the Mac, except while the finger is on the fader.
    var dragLevel by remember { mutableStateOf<Float?>(null) }
    val shownLevel = dragLevel ?: volume?.level?.toFloat() ?: 0f

    Column(
        modifier = modifier.testTag("mac_remote_panel"),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        MonoLabel(if (volume?.muted == true) "Volumen // silencio" else "Volumen")

        // A tall fader with the step keys stacked beside it: one block, one thumb.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (fillHeight) Modifier.weight(1f) else Modifier.height(320.dp)),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            VolumeFader(
                level = volume?.let { shownLevel },
                muted = volume?.muted == true,
                onLevelChange = { level ->
                    dragLevel = level
                    onVolumeChange(level.roundToInt())
                },
                onLevelChangeFinished = { dragLevel = null },
                modifier = Modifier.weight(1f).fillMaxHeight(),
                testTag = "mac_volume_slider"
            )
            Column(
                modifier = Modifier.width(88.dp).fillMaxHeight().hairlineGrid(),
                verticalArrangement = Arrangement.spacedBy(GridGap)
            ) {
                val cell = Modifier.weight(1f).fillMaxWidth()
                IconKey(RemoteIcons.VolumeUp, "Vol +", { press(RemoteKey.VOL_UP) }, cell, "mac_vol_up", repeatable = true)
                IconKey(RemoteIcons.Mute, "Mute", { press(RemoteKey.MUTE) }, cell, "mac_mute")
                IconKey(RemoteIcons.VolumeDown, "Vol -", { press(RemoteKey.VOL_DOWN) }, cell, "mac_vol_down", repeatable = true)
            }
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

        MonoLabel("Música, Spotify o la app que esté sonando")
    }
}
