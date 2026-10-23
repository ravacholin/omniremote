package com.example.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
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
    val accentColor = deviceType.brandColor
    val currentOnKeyPress by rememberUpdatedState(onKeyPress)

    fun handlePress(key: RemoteKey) {
        HapticUtil.triggerKeyHaptic(context)
        onKeyPress(key)
    }

    BoxWithConstraints(
        modifier = modifier.testTag("remote_dpad_container"),
        contentAlignment = Alignment.Center
    ) {
        val availableHeight = maxHeight

        val isShortScreen = availableHeight < 550.dp
        val isTallScreen = availableHeight > 580.dp

        // Dynamic, responsive sizing that scales relative to available screen dimensions
        val dpadDiameter = when {
            isShortScreen -> 215.dp
            isTallScreen -> (availableHeight * 0.40f).coerceIn(255.dp, 290.dp)
            else -> 245.dp
        }.coerceAtMost(maxWidth - 16.dp)
        val okDiameter = (dpadDiameter * 0.35f).coerceIn(74.dp, 96.dp)
        val arrowIconSize = if (dpadDiameter >= 260.dp) 38.dp else if (dpadDiameter >= 235.dp) 34.dp else 28.dp
        val arrowVerticalHeight = (dpadDiameter * 0.24f).coerceIn(48.dp, 64.dp)
        val arrowVerticalWidth = (dpadDiameter * 0.35f).coerceIn(72.dp, 94.dp)
        val arrowHorizontalWidth = (dpadDiameter * 0.24f).coerceIn(48.dp, 64.dp)
        val arrowHorizontalHeight = (dpadDiameter * 0.35f).coerceIn(72.dp, 94.dp)

        // Top Navigation buttons responsive dimensions
        val navBtnSize = when {
            isShortScreen -> 48.dp
            isTallScreen -> 54.dp
            else -> 48.dp
        }
        val navIconSize = if (navBtnSize >= 52.dp) 24.dp else if (navBtnSize >= 48.dp) 22.dp else 20.dp

        // Bottom Controls responsive dimensions
        val rockerWidth = 56.dp
        val rockerBtnSize = if (isTallScreen) 50.dp else 48.dp
        val rockerCenterHeight = 48.dp

        val heroPlaySize = when {
            isTallScreen -> 68.dp
            isShortScreen -> 54.dp
            else -> 62.dp
        }
        val heroPlayIconSize = if (heroPlaySize >= 66.dp) 36.dp else if (heroPlaySize >= 60.dp) 32.dp else 28.dp
        val mediaBtnSize = 48.dp
        val mediaIconSize = if (isTallScreen) 22.dp else 20.dp

        val scrollModifier = if (isShortScreen) {
            Modifier.verticalScroll(rememberScrollState())
        } else {
            Modifier.fillMaxSize()
        }

        Column(
            modifier = Modifier
                .then(scrollModifier)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = if (isShortScreen) Arrangement.spacedBy(10.dp) else Arrangement.SpaceBetween
        ) {
            // Fourth action: Android TV brightness or Samsung Bluetooth audio.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircleRemoteButton(
                    icon = RemoteIcons.Back,
                    label = "Back",
                    testTag = "key_back",
                    size = navBtnSize,
                    iconSize = navIconSize,
                    onClick = { handlePress(RemoteKey.BACK) }
                )
                CircleRemoteButton(
                    icon = RemoteIcons.Home,
                    label = "Home",
                    testTag = "key_home",
                    accent = accentColor,
                    size = navBtnSize,
                    iconSize = navIconSize,
                    onClick = { handlePress(RemoteKey.HOME) }
                )
                CircleRemoteButton(
                    icon = RemoteIcons.Menu,
                    label = "Menu",
                    testTag = "key_menu",
                    size = navBtnSize,
                    iconSize = navIconSize,
                    onClick = { handlePress(RemoteKey.MENU) }
                )
                CircleRemoteButton(
                    icon = if (deviceType == DeviceType.SAMSUNG_TIZEN) Icons.Default.BluetoothAudio else Icons.Default.Brightness6,
                    label = if (deviceType == DeviceType.SAMSUNG_TIZEN) "Bluetooth" else "Brightness",
                    testTag = "brightness_button",
                    size = navBtnSize,
                    iconSize = navIconSize,
                    onClick = {
                        HapticUtil.triggerKeyHaptic(context)
                        onBrightnessClick()
                    }
                )
            }

            if (!isShortScreen) {
                Spacer(modifier = Modifier.weight(1f, fill = false).heightIn(max = 28.dp))
            }

            // Flat directional pad sized for the available width.
            Box(
                modifier = Modifier
                    .size(dpadDiameter)
                    .clip(RoundedCornerShape(0.dp))
                    .background(RemoteSurface)
                    .border(1.dp, RemoteBorderStrong, RoundedCornerShape(0.dp))
                    .testTag("dpad_circle"),
                contentAlignment = Alignment.Center
            ) {
                // Up Button
                val upInteraction = remember { MutableInteractionSource() }
                val upPressed by upInteraction.collectIsPressedAsState()
                val upScale by animateFloatAsState(
                    targetValue = if (upPressed) 0.96f else 1.0f,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy),
                    label = "dpad_up_scale"
                )

                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                        .size(width = arrowVerticalWidth, height = arrowVerticalHeight)
                        .graphicsLayer {
                            scaleX = upScale
                            scaleY = upScale
                        }
                        .clip(RoundedCornerShape(0.dp))
                        .background(if (upPressed) Color.White.copy(alpha = 0.25f) else Color.Transparent)
                        .clickable(
                            interactionSource = upInteraction,
                            indication = null
                        ) { handlePress(RemoteKey.UP) }
                        .testTag("key_dpad_up"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = RemoteIcons.Up,
                        contentDescription = "Up",
                        tint = if (upPressed) Color.White else TextPrimary,
                        modifier = Modifier.size(arrowIconSize)
                    )
                }

                // Down Button
                val downInteraction = remember { MutableInteractionSource() }
                val downPressed by downInteraction.collectIsPressedAsState()
                val downScale by animateFloatAsState(
                    targetValue = if (downPressed) 0.96f else 1.0f,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy),
                    label = "dpad_down_scale"
                )

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp)
                        .size(width = arrowVerticalWidth, height = arrowVerticalHeight)
                        .graphicsLayer {
                            scaleX = downScale
                            scaleY = downScale
                        }
                        .clip(RoundedCornerShape(0.dp))
                        .background(if (downPressed) Color.White.copy(alpha = 0.25f) else Color.Transparent)
                        .clickable(
                            interactionSource = downInteraction,
                            indication = null
                        ) { handlePress(RemoteKey.DOWN) }
                        .testTag("key_dpad_down"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = RemoteIcons.Down,
                        contentDescription = "Down",
                        tint = if (downPressed) Color.White else TextPrimary,
                        modifier = Modifier.size(arrowIconSize)
                    )
                }

                // Left Button
                val leftInteraction = remember { MutableInteractionSource() }
                val leftPressed by leftInteraction.collectIsPressedAsState()
                val leftScale by animateFloatAsState(
                    targetValue = if (leftPressed) 0.96f else 1.0f,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy),
                    label = "dpad_left_scale"
                )

                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 8.dp)
                        .size(width = arrowHorizontalWidth, height = arrowHorizontalHeight)
                        .graphicsLayer {
                            scaleX = leftScale
                            scaleY = leftScale
                        }
                        .clip(RoundedCornerShape(0.dp))
                        .background(if (leftPressed) Color.White.copy(alpha = 0.25f) else Color.Transparent)
                        .clickable(
                            interactionSource = leftInteraction,
                            indication = null
                        ) { handlePress(RemoteKey.LEFT) }
                        .testTag("key_dpad_left"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = RemoteIcons.Left,
                        contentDescription = "Left",
                        tint = if (leftPressed) Color.White else TextPrimary,
                        modifier = Modifier.size(arrowIconSize)
                    )
                }

                // Right Button
                val rightInteraction = remember { MutableInteractionSource() }
                val rightPressed by rightInteraction.collectIsPressedAsState()
                val rightScale by animateFloatAsState(
                    targetValue = if (rightPressed) 0.96f else 1.0f,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy),
                    label = "dpad_right_scale"
                )

                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 8.dp)
                        .size(width = arrowHorizontalWidth, height = arrowHorizontalHeight)
                        .graphicsLayer {
                            scaleX = rightScale
                            scaleY = rightScale
                        }
                        .clip(RoundedCornerShape(0.dp))
                        .background(if (rightPressed) Color.White.copy(alpha = 0.25f) else Color.Transparent)
                        .clickable(
                            interactionSource = rightInteraction,
                            indication = null
                        ) { handlePress(RemoteKey.RIGHT) }
                        .testTag("key_dpad_right"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = RemoteIcons.Right,
                        contentDescription = "Right",
                        tint = if (rightPressed) Color.White else TextPrimary,
                        modifier = Modifier.size(arrowIconSize)
                    )
                }

                // Inverted primary action.
                val okInteraction = remember { MutableInteractionSource() }
                val okPressed by okInteraction.collectIsPressedAsState()
                val okScale by animateFloatAsState(
                    targetValue = if (okPressed) 0.96f else 1.0f,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy),
                    label = "ok_scale"
                )

                Box(
                    modifier = Modifier
                        .size(okDiameter)
                        .graphicsLayer {
                            scaleX = okScale
                            scaleY = okScale
                        }
                        .clip(RoundedCornerShape(0.dp))
                        .background(if (okPressed) BrutalistOffWhite else Color.White)
                        .border(
                            if (okPressed) 2.dp else 1.dp,
                            Color.White,
                            RoundedCornerShape(0.dp)
                        )
                        .pointerInput(Unit) {
                            var dragOffset = Offset.Zero
                            // The drag detector consumes movement after touch slop, cancelling
                            // OK's click and keeping the surrounding column from scrolling.
                            detectDragGestures(
                                onDragStart = { dragOffset = Offset.Zero },
                                onDragCancel = { dragOffset = Offset.Zero },
                                onDragEnd = {
                                    if (dragOffset != Offset.Zero) {
                                        val key = if (abs(dragOffset.x) > abs(dragOffset.y)) {
                                            if (dragOffset.x > 0) RemoteKey.RIGHT else RemoteKey.LEFT
                                        } else {
                                            if (dragOffset.y > 0) RemoteKey.DOWN else RemoteKey.UP
                                        }
                                        HapticUtil.triggerKeyHaptic(context)
                                        currentOnKeyPress(key)
                                    }
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragOffset += amount
                                }
                            )
                        }
                        .clickable(
                            interactionSource = okInteraction,
                            indication = null
                        ) { handlePress(RemoteKey.ENTER) }
                        .testTag("key_dpad_enter"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "OK",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = Color.Black,
                            fontWeight = FontWeight.Black,
                            fontSize = if (okDiameter >= 90.dp) 20.sp else if (okDiameter >= 80.dp) 18.sp else 15.sp,
                            letterSpacing = 1.sp
                        )
                    )
                }
            }

            if (!isShortScreen) {
                Spacer(modifier = Modifier.weight(1.2f, fill = false).heightIn(max = 32.dp))
            }

            // Volume, Playback Hero, and Channel Controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Volume Rocker
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
                    accentColor = accentColor,
                    width = rockerWidth,
                    btnSize = rockerBtnSize,
                    centerHeight = rockerCenterHeight
                )

                // Playback Control Pod with Prominent Hero Play/Pause Button
                Surface(
                    color = Color(0xFF0C0C0C),
                    shape = RoundedCornerShape(0.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, RemoteBorderStrong)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        HeroPlayPauseButton(
                            accentColor = Color.White,
                            isPlaying = isPlaying,
                            onClick = {
                                HapticUtil.triggerKeyHaptic(context)
                                onTogglePlayPause()
                            },
                            size = heroPlaySize,
                            iconSize = heroPlayIconSize
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TactileMediaButton(
                                icon = RemoteIcons.Rewind,
                                contentDescription = "Retroceder",
                                testTag = "key_rewind",
                                onClick = { handlePress(RemoteKey.REWIND) },
                                accentColor = Color.White,
                                size = mediaBtnSize,
                                iconSize = mediaIconSize
                            )
                            TactileMediaButton(
                                icon = RemoteIcons.Forward,
                                contentDescription = "Avanzar",
                                testTag = "key_fast_forward",
                                onClick = { handlePress(RemoteKey.FAST_FORWARD) },
                                accentColor = Color.White,
                                size = mediaBtnSize,
                                iconSize = mediaIconSize
                            )
                        }
                    }
                }

                // Channel Rocker
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
                    accentColor = Color.White,
                    width = rockerWidth,
                    btnSize = rockerBtnSize,
                    centerHeight = rockerCenterHeight
                )
            }

            // Direct playback controls stay available beneath the main control group.
            Row(
                modifier = Modifier.padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MediaQuickActionPill(
                    icon = RemoteIcons.Play,
                    label = "Play",
                    onClick = { handlePress(RemoteKey.PLAY) },
                    accentColor = Color.White,
                    testTag = "key_explicit_play"
                )
                MediaQuickActionPill(
                    icon = RemoteIcons.Pause,
                    label = "Pausa",
                    onClick = { handlePress(RemoteKey.PAUSE) },
                    accentColor = Color(0xFFD4D4D4),
                    testTag = "key_explicit_pause"
                )
                MediaQuickActionPill(
                    icon = RemoteIcons.Stop,
                    label = "Stop",
                    onClick = { handlePress(RemoteKey.STOP) },
                    accentColor = Color(0xFFA3A3A3),
                    testTag = "key_explicit_stop"
                )
            }
        }
    }
}

/**
 * Prominent, hero-sized Play/Pause toggle button with dynamic Play/Pause icon,
 * responsive spring physics, tactile haptic pulse, and radiant glow reaction on touch.
 */
@Composable
fun HeroPlayPauseButton(
    accentColor: Color,
    isPlaying: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 62.dp,
    iconSize: Dp = 32.dp
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "hero_play_scale"
    )

    Box(
        modifier = modifier
            .size(size.coerceAtLeast(48.dp))
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(0.dp))
            .background(if (isPressed) Color(0xFFE5E5E5) else Color.White)
            .border(
                width = if (isPressed) 2.dp else 1.5.dp,
                color = if (isPressed) Color(0xFF888888) else Color.White,
                shape = RoundedCornerShape(0.dp)
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) {
                onClick()
            }
            .testTag("key_play_pause"),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isPlaying) RemoteIcons.Pause else RemoteIcons.Play,
            contentDescription = if (isPlaying) "Pause" else "Play",
            tint = Color.Black,
            modifier = Modifier.size(iconSize)
        )
    }
}

/**
 * Dedicated tactile quick-action pill for direct PLAY, PAUSE, and STOP commands.
 */
@Composable
fun MediaQuickActionPill(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    accentColor: Color,
    testTag: String,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    Surface(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(0.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) { onClick() }
            .testTag(testTag),
        color = if (isPressed) Color.White else Color(0xFF101010),
        shape = RoundedCornerShape(0.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isPressed) Color.White else RemoteBorderStrong
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp).heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isPressed) Color.Black else TextPrimary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = if (isPressed) Color.Black else TextSecondary)
        }
    }
}

/**
 * Secondary media button with spring reaction and haptic response.
 */
@Composable
fun TactileMediaButton(
    icon: ImageVector,
    contentDescription: String,
    testTag: String,
    onClick: () -> Unit,
    accentColor: Color,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    iconSize: Dp = 22.dp
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy),
        label = "media_btn_scale"
    )

    Box(
        modifier = modifier
            .size(size.coerceAtLeast(48.dp))
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(0.dp))
            .background(if (isPressed) Color.White else Color(0xFF121212))
            .border(
                1.dp,
                if (isPressed) Color.White else RemoteBorderStrong,
                RoundedCornerShape(0.dp)
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) { onClick() }
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (isPressed) Color.Black else TextPrimary,
            modifier = Modifier.size(iconSize)
        )
    }
}

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
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "circle_btn_scale"
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Surface(
            modifier = Modifier
                .size(size.coerceAtLeast(48.dp))
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(RoundedCornerShape(0.dp))
                .clickable(
                    interactionSource = interactionSource,
                    indication = null
                ) { onClick() }
                .testTag(testTag),
            color = if (isPressed) Color.White else RemoteBtnDefault,
            shape = RoundedCornerShape(0.dp),
            border = androidx.compose.foundation.BorderStroke(
                if (isPressed) 1.5.dp else 1.dp,
                if (isPressed) Color.White else RemoteBorder
            )
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = if (isPressed) Color.Black else TextPrimary,
                    modifier = Modifier.size(iconSize)
                )
            }
        }
        Text(
            text = when (label) {
                "Back" -> "Atrás"
                "Home" -> "Inicio"
                "Menu" -> "Menú"
                "Brightness" -> "Brillo"
                else -> label
            },
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, letterSpacing = 0.sp),
            color = TextSecondary
        )
    }
}

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
    accentColor: Color = Color.White,
    width: Dp = 54.dp,
    btnSize: Dp = 48.dp,
    centerHeight: Dp = 32.dp
) {
    Surface(
        color = Color(0xFF0C0C0C),
        shape = RoundedCornerShape(0.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, RemoteBorderStrong)
    ) {
        Column(
            modifier = Modifier
                .width(width)
                .padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (testTagPrefix == "vol") "VOL" else "CANAL",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = TextMuted,
                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
            )
            val topInteraction = remember { MutableInteractionSource() }
            val topPressed by topInteraction.collectIsPressedAsState()
            val topScale by animateFloatAsState(
                targetValue = if (topPressed) 0.96f else 1.0f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy),
                label = "rocker_top_scale"
            )

            Box(
                modifier = Modifier
                    .size(btnSize.coerceAtLeast(48.dp))
                    .graphicsLayer {
                        scaleX = topScale
                        scaleY = topScale
                    }
                    .clip(RoundedCornerShape(0.dp))
                    .background(if (topPressed) Color.White else Color.Transparent)
                    .clickable(
                        interactionSource = topInteraction,
                        indication = null
                    ) { onTopClick() }
                    .testTag("${testTagPrefix}_up"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = topIcon,
                    contentDescription = topLabel,
                    tint = if (topPressed) Color.Black else Color.White,
                    modifier = Modifier.size(if (btnSize >= 50.dp) 24.dp else 22.dp)
                )
            }

            val centerInteraction = remember { MutableInteractionSource() }
            val centerPressed by centerInteraction.collectIsPressedAsState()
            val centerScale by animateFloatAsState(
                targetValue = if (centerPressed) 0.96f else 1.0f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy),
                label = "rocker_center_scale"
            )

            Box(
                modifier = Modifier
                    .size(width = (width - 8.dp).coerceAtLeast(48.dp), height = centerHeight.coerceAtLeast(48.dp))
                    .graphicsLayer {
                        scaleX = centerScale
                        scaleY = centerScale
                    }
                    .clip(RoundedCornerShape(0.dp))
                    .background(if (centerPressed) Color.White else Color(0xFF181818))
                    .border(1.dp, if (centerPressed) Color.White else RemoteBorderStrong, RoundedCornerShape(0.dp))
                    .clickable(
                        interactionSource = centerInteraction,
                        indication = null
                    ) { onCenterClick() }
                    .testTag("${testTagPrefix}_center"),
                contentAlignment = Alignment.Center
            ) {
                if (centerIcon != null) {
                    Icon(
                        imageVector = centerIcon,
                        contentDescription = centerLabel,
                        tint = if (centerPressed) Color.Black else TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                } else {
                    Text(
                        text = centerLabel,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = if (centerPressed) Color.Black else TextSecondary,
                            fontWeight = FontWeight.Bold,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    )
                }
            }

            val bottomInteraction = remember { MutableInteractionSource() }
            val bottomPressed by bottomInteraction.collectIsPressedAsState()
            val bottomScale by animateFloatAsState(
                targetValue = if (bottomPressed) 0.96f else 1.0f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy),
                label = "rocker_bottom_scale"
            )

            Box(
                modifier = Modifier
                    .size(btnSize.coerceAtLeast(48.dp))
                    .graphicsLayer {
                        scaleX = bottomScale
                        scaleY = bottomScale
                    }
                    .clip(RoundedCornerShape(0.dp))
                    .background(if (bottomPressed) Color.White else Color.Transparent)
                    .clickable(
                        interactionSource = bottomInteraction,
                        indication = null
                    ) { onBottomClick() }
                    .testTag("${testTagPrefix}_down"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = bottomIcon,
                    contentDescription = bottomLabel,
                    tint = if (bottomPressed) Color.Black else Color.White,
                    modifier = Modifier.size(if (btnSize >= 50.dp) 24.dp else 22.dp)
                )
            }
        }
    }
}

@Composable
fun IconButtonMini(
    icon: ImageVector,
    contentDescription: String,
    testTag: String,
    onClick: () -> Unit,
    accent: Color? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy),
        label = "mini_scale"
    )

    Box(
        modifier = Modifier
            .size(48.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(0.dp))
            .background(if (isPressed) Color.White else Color(0xFF141414))
            .border(1.dp, if (isPressed) Color.White else RemoteBorderStrong, RoundedCornerShape(0.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) { onClick() }
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (isPressed) Color.Black else Color.White,
            modifier = Modifier.size(20.dp)
        )
    }
}
