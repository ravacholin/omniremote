package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.DeviceType
import com.example.data.model.RemoteKey
import com.example.ui.theme.*
import kotlin.math.abs

@Composable
fun RemoteDpad(
    deviceType: DeviceType,
    onKeyPress: (RemoteKey) -> Unit,
    onBrightnessClick: () -> Unit,
    isPlaying: Boolean = true,
    onTogglePlayPause: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val currentOnKeyPress by rememberUpdatedState(onKeyPress)

    fun handlePress(key: RemoteKey) {
        HapticUtil.triggerKeyHaptic(context)
        onKeyPress(key)
    }

    BoxWithConstraints(
        modifier = modifier.testTag("remote_dpad_container"),
        contentAlignment = Alignment.TopCenter
    ) {
        val isShortScreen = maxHeight < 550.dp
        val cell = if (isShortScreen) 48.dp else 54.dp
        val navHeight = cell + GridGap * 2
        val controlsHeight = cell * 4 + GridGap * 5
        val gap = if (isShortScreen) 12.dp else 20.dp
        val faderWidth = if (isShortScreen) 64.dp else 76.dp
        val faderGap = 12.dp

        // The pad takes whatever height the rows leave, but never more than the width beside the fader.
        val padSize = if (isShortScreen) {
            216.dp
        } else {
            (maxHeight - navHeight - controlsHeight - gap * 2).coerceIn(192.dp, 320.dp)
        }.coerceAtMost(maxWidth - faderWidth - faderGap)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (isShortScreen) Modifier.verticalScroll(rememberScrollState()) else Modifier.fillMaxSize()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = if (isShortScreen) Arrangement.spacedBy(gap) else Arrangement.SpaceBetween
        ) {
            // Navigation strip. The fourth key is brightness on Android TV, Bluetooth audio on Samsung.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(navHeight)
                    .hairlineGrid(),
                horizontalArrangement = Arrangement.spacedBy(GridGap)
            ) {
                NavKey(RemoteIcons.Back, "Back", "key_back") { handlePress(RemoteKey.BACK) }
                NavKey(RemoteIcons.Home, "Home", "key_home") { handlePress(RemoteKey.HOME) }
                NavKey(RemoteIcons.Menu, "Menu", "key_menu") { handlePress(RemoteKey.MENU) }
                val isSamsung = deviceType == DeviceType.SAMSUNG_TIZEN
                NavKey(
                    icon = if (isSamsung) Icons.Default.BluetoothAudio else Icons.Default.Brightness6,
                    label = if (isSamsung) "Bluetooth" else "Brightness",
                    testTag = "brightness_button"
                ) {
                    HapticUtil.triggerKeyHaptic(context)
                    onBrightnessClick()
                }
            }

            // Volume jog strip beside the pad, within reach of the same thumb.
            Row(
                modifier = Modifier.fillMaxWidth().height(padSize),
                horizontalArrangement = Arrangement.spacedBy(faderGap, Alignment.CenterHorizontally)
            ) {
                VolumeJogFader(
                    onStep = { key -> handlePress(key) },
                    modifier = Modifier.width(faderWidth).fillMaxHeight()
                )
                // Directional pad: a ruled 3×3 field with an inverted OK at its centre.
                Column(
                    modifier = Modifier
                        .size(padSize)
                        .hairlineGrid()
                        .testTag("dpad_circle")
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(
                                    requireUnconsumed = false,
                                    pass = PointerEventPass.Initial
                                )
                                var swiping = false
                                var displacement = Offset.Zero
                                var now = down.uptimeMillis
                                var nextRepeatAt = Long.MAX_VALUE
                                var repeats = 0
                                fun sendSwipe() {
                                    HapticUtil.triggerKeyHaptic(context)
                                    currentOnKeyPress(swipeDirection(displacement))
                                }
                                // Observe before child clicks and surrounding scroll containers.
                                // Leave taps alone; once dragging, cancel the child click.
                                // Holding at the end of a swipe repeats it, steered by the finger.
                                while (true) {
                                    val event = if (swiping) {
                                        withTimeoutOrNull((nextRepeatAt - now).coerceAtLeast(0)) {
                                            awaitPointerEvent(PointerEventPass.Initial)
                                        }
                                    } else {
                                        awaitPointerEvent(PointerEventPass.Initial)
                                    }
                                    if (event == null) {
                                        now = nextRepeatAt
                                        repeats++
                                        sendSwipe()
                                        nextRepeatAt = now + KeyRepeat.intervalAfter(repeats)
                                        continue
                                    }
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                        ?: break
                                    if (change.isConsumed) break
                                    now = change.uptimeMillis
                                    displacement = change.position - down.position
                                    if (!swiping && displacement.getDistance() > viewConfiguration.touchSlop) {
                                        swiping = true
                                        nextRepeatAt = now + KeyRepeat.HOLD_DELAY_MS
                                    }
                                    if (swiping) change.consume()
                                    if (!change.pressed) {
                                        if (swiping && repeats == 0 && displacement != Offset.Zero) {
                                            sendSwipe()
                                        }
                                        break
                                    }
                                }
                            }
                        },
                    verticalArrangement = Arrangement.spacedBy(GridGap)
                ) {
                    val arrowSize = if (padSize >= 260.dp) 30.dp else 26.dp
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(GridGap)) {
                        PadCorner()
                        IconKey(RemoteIcons.Up, "Up", { handlePress(RemoteKey.UP) }, Modifier.weight(1f).fillMaxHeight(), "key_dpad_up", iconSize = arrowSize, repeatable = true)
                        PadCorner()
                    }
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(GridGap)) {
                        IconKey(RemoteIcons.Left, "Left", { handlePress(RemoteKey.LEFT) }, Modifier.weight(1f).fillMaxHeight(), "key_dpad_left", iconSize = arrowSize, repeatable = true)
                        GridKey(
                            onClick = { handlePress(RemoteKey.ENTER) },
                            inverted = true,
                            testTag = "key_dpad_enter",
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        ) { tint ->
                            Text(
                                text = "OK",
                                style = MaterialTheme.typography.headlineSmall.copy(
                                    fontWeight = FontWeight.Light,
                                    letterSpacing = 1.sp
                                ),
                                color = tint
                            )
                        }
                        IconKey(RemoteIcons.Right, "Right", { handlePress(RemoteKey.RIGHT) }, Modifier.weight(1f).fillMaxHeight(), "key_dpad_right", iconSize = arrowSize, repeatable = true)
                    }
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(GridGap)) {
                        PadCorner()
                        IconKey(RemoteIcons.Down, "Down", { handlePress(RemoteKey.DOWN) }, Modifier.weight(1f).fillMaxHeight(), "key_dpad_down", iconSize = arrowSize, repeatable = true)
                        PadCorner()
                    }
                }
            }

            // Volume · transport · channel, then explicit play/pause/stop, as one ruled block.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(controlsHeight)
                    .hairlineGrid(),
                verticalArrangement = Arrangement.spacedBy(GridGap)
            ) {
                Row(Modifier.weight(3f), horizontalArrangement = Arrangement.spacedBy(GridGap)) {
                    RockerColumn(
                        topIcon = RemoteIcons.VolumeUp,
                        topLabel = "VOL +",
                        onTopClick = { handlePress(RemoteKey.VOL_UP) },
                        centerIcon = RemoteIcons.Mute,
                        centerLabel = "Mute",
                        onCenterClick = { handlePress(RemoteKey.MUTE) },
                        bottomIcon = RemoteIcons.VolumeDown,
                        bottomLabel = "VOL -",
                        onBottomClick = { handlePress(RemoteKey.VOL_DOWN) },
                        testTagPrefix = "vol",
                        modifier = Modifier.weight(1f)
                    )
                    Column(
                        modifier = Modifier.weight(1.4f),
                        verticalArrangement = Arrangement.spacedBy(GridGap)
                    ) {
                        HeroPlayPauseButton(
                            accentColor = Paper,
                            isPlaying = isPlaying,
                            onClick = {
                                HapticUtil.triggerKeyHaptic(context)
                                onTogglePlayPause()
                            },
                            modifier = Modifier
                                .weight(2f)
                                .fillMaxWidth()
                        )
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(GridGap)) {
                            TactileMediaButton(
                                icon = RemoteIcons.Rewind,
                                contentDescription = "Retroceder",
                                testTag = "key_rewind",
                                onClick = { handlePress(RemoteKey.REWIND) },
                                accentColor = Paper,
                                modifier = Modifier.weight(1f).fillMaxHeight()
                            )
                            TactileMediaButton(
                                icon = RemoteIcons.Forward,
                                contentDescription = "Avanzar",
                                testTag = "key_fast_forward",
                                onClick = { handlePress(RemoteKey.FAST_FORWARD) },
                                accentColor = Paper,
                                modifier = Modifier.weight(1f).fillMaxHeight()
                            )
                        }
                    }
                    RockerColumn(
                        topIcon = RemoteIcons.Up,
                        topLabel = "CH +",
                        onTopClick = { handlePress(RemoteKey.CH_UP) },
                        centerIcon = null,
                        centerLabel = "CH",
                        onCenterClick = { handlePress(RemoteKey.NUM_PRE_CH) },
                        bottomIcon = RemoteIcons.Down,
                        bottomLabel = "CH -",
                        onBottomClick = { handlePress(RemoteKey.CH_DOWN) },
                        testTagPrefix = "ch",
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(GridGap)) {
                    MediaQuickActionPill(RemoteIcons.Play, "PLAY", { handlePress(RemoteKey.PLAY) }, Paper, "key_explicit_play", Modifier.weight(1f).fillMaxHeight())
                    MediaQuickActionPill(RemoteIcons.Pause, "PAUSE", { handlePress(RemoteKey.PAUSE) }, Paper, "key_explicit_pause", Modifier.weight(1f).fillMaxHeight())
                    MediaQuickActionPill(RemoteIcons.Stop, "STOP", { handlePress(RemoteKey.STOP) }, Paper, "key_explicit_stop", Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

/** Dominant axis of a D-pad swipe. */
private fun swipeDirection(displacement: Offset): RemoteKey =
    if (abs(displacement.x) > abs(displacement.y)) {
        if (displacement.x > 0) RemoteKey.RIGHT else RemoteKey.LEFT
    } else {
        if (displacement.y > 0) RemoteKey.DOWN else RemoteKey.UP
    }

@Composable
private fun RowScope.NavKey(icon: ImageVector, label: String, testTag: String, onClick: () -> Unit) {
    IconKey(icon, label, onClick, Modifier.weight(1f).fillMaxHeight(), testTag)
}

/** Empty corner of the pad, marked with a small registration cross. */
@Composable
private fun RowScope.PadCorner() {
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .background(Void),
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.size(width = 7.dp, height = 1.dp).background(Graphite))
        Box(Modifier.size(width = 1.dp, height = 7.dp).background(Graphite))
    }
}

/** Primary transport key: the only large white surface below the pad. */
@Composable
fun HeroPlayPauseButton(
    accentColor: Color,
    isPlaying: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 62.dp,
    iconSize: Dp = 30.dp
) {
    IconKey(
        icon = if (isPlaying) RemoteIcons.Pause else RemoteIcons.Play,
        contentDescription = if (isPlaying) "Pause" else "Play",
        onClick = onClick,
        modifier = modifier,
        testTag = "key_play_pause",
        inverted = true,
        iconSize = iconSize
    )
}

/** Explicit PLAY, PAUSE and STOP commands. */
@Composable
fun MediaQuickActionPill(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    accentColor: Color,
    testTag: String,
    modifier: Modifier = Modifier
) {
    GridKey(onClick = onClick, modifier = modifier, testTag = testTag) { tint ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(14.dp))
            Spacer(Modifier.size(8.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
        }
    }
}

/** Secondary transport key (rewind, fast-forward); repeats while held. */
@Composable
fun TactileMediaButton(
    icon: ImageVector,
    contentDescription: String,
    testTag: String,
    onClick: () -> Unit,
    accentColor: Color,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    iconSize: Dp = 20.dp
) {
    IconKey(icon, contentDescription, onClick, modifier, testTag, iconSize = iconSize, repeatable = true)
}

/** Free-standing square key outlined by a hairline. */
@Composable
fun CircleRemoteButton(
    icon: ImageVector,
    label: String,
    testTag: String,
    onClick: () -> Unit,
    accent: Color? = null,
    modifier: Modifier = Modifier,
    size: Dp = 50.dp,
    iconSize: Dp = 22.dp
) {
    IconKey(
        icon = icon,
        contentDescription = label,
        onClick = onClick,
        modifier = modifier
            .size(size.coerceAtLeast(48.dp))
            .border(GridGap, Hairline, Sharp),
        testTag = testTag,
        iconSize = iconSize
    )
}

/** Up / centre / down column used for volume and channel; up and down repeat while held. */
@Composable
fun RockerColumn(
    topIcon: ImageVector,
    topLabel: String,
    onTopClick: () -> Unit,
    centerIcon: ImageVector?,
    centerLabel: String,
    onCenterClick: () -> Unit,
    bottomIcon: ImageVector,
    bottomLabel: String,
    onBottomClick: () -> Unit,
    testTagPrefix: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(GridGap)) {
        val cellModifier = Modifier.weight(1f).fillMaxWidth()
        IconKey(topIcon, topLabel, onTopClick, cellModifier, "${testTagPrefix}_up", repeatable = true)
        GridKey(onClick = onCenterClick, modifier = cellModifier, testTag = "${testTagPrefix}_center") { tint ->
            if (centerIcon != null) {
                Icon(centerIcon, contentDescription = centerLabel, tint = if (tint == Paper) Ash else tint, modifier = Modifier.size(18.dp))
            } else {
                Text(centerLabel, style = MaterialTheme.typography.labelMedium, color = if (tint == Paper) Ash else tint)
            }
        }
        IconKey(bottomIcon, bottomLabel, onBottomClick, cellModifier, "${testTagPrefix}_down", repeatable = true)
    }
}
