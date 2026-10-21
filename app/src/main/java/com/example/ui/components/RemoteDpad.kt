package com.example.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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

@Composable
fun RemoteDpad(
    deviceType: DeviceType,
    onKeyPress: (RemoteKey) -> Unit,
    isPlaying: Boolean = true,
    onTogglePlayPause: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val accentColor = deviceType.brandColor

    fun handlePress(key: RemoteKey) {
        HapticUtil.triggerKeyHaptic(context)
        onKeyPress(key)
    }

    BoxWithConstraints(
        modifier = modifier.testTag("remote_dpad_container"),
        contentAlignment = Alignment.Center
    ) {
        val availableHeight = maxHeight
        val availableWidth = maxWidth

        val isShortScreen = availableHeight < 490.dp
        val isTallScreen = availableHeight > 580.dp

        // Dynamic, responsive sizing that scales relative to available screen dimensions
        val dpadDiameter = when {
            isShortScreen -> 215.dp
            isTallScreen -> (availableHeight * 0.40f).coerceIn(255.dp, 290.dp)
            else -> 245.dp
        }
        val okDiameter = (dpadDiameter * 0.35f).coerceIn(74.dp, 96.dp)
        val arrowIconSize = if (dpadDiameter >= 260.dp) 38.dp else if (dpadDiameter >= 235.dp) 34.dp else 28.dp
        val arrowVerticalHeight = (dpadDiameter * 0.24f).coerceIn(48.dp, 64.dp)
        val arrowVerticalWidth = (dpadDiameter * 0.35f).coerceIn(72.dp, 94.dp)
        val arrowHorizontalWidth = (dpadDiameter * 0.24f).coerceIn(48.dp, 64.dp)
        val arrowHorizontalHeight = (dpadDiameter * 0.35f).coerceIn(72.dp, 94.dp)

        // Top Navigation buttons responsive dimensions
        val navBtnSize = when {
            isShortScreen -> 44.dp
            isTallScreen -> 54.dp
            else -> 48.dp
        }
        val navIconSize = if (navBtnSize >= 52.dp) 24.dp else if (navBtnSize >= 48.dp) 22.dp else 20.dp

        // Bottom Controls responsive dimensions
        val rockerWidth = if (isTallScreen) 56.dp else if (isShortScreen) 48.dp else 52.dp
        val rockerBtnSize = if (isTallScreen) 50.dp else if (isShortScreen) 42.dp else 46.dp
        val rockerCenterHeight = if (isTallScreen) 34.dp else 30.dp

        val heroPlaySize = when {
            isTallScreen -> 68.dp
            isShortScreen -> 54.dp
            else -> 62.dp
        }
        val heroPlayIconSize = if (heroPlaySize >= 66.dp) 36.dp else if (heroPlaySize >= 60.dp) 32.dp else 28.dp
        val mediaBtnSize = if (isTallScreen) 46.dp else 42.dp
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
            // Top Navigation Helpers: Back, Home, Menu, Source
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircleRemoteButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    label = "Back",
                    testTag = "key_back",
                    size = navBtnSize,
                    iconSize = navIconSize,
                    onClick = { handlePress(RemoteKey.BACK) }
                )
                CircleRemoteButton(
                    icon = Icons.Default.Home,
                    label = "Home",
                    testTag = "key_home",
                    accent = accentColor,
                    size = navBtnSize,
                    iconSize = navIconSize,
                    onClick = { handlePress(RemoteKey.HOME) }
                )
                CircleRemoteButton(
                    icon = Icons.Default.Menu,
                    label = "Menu",
                    testTag = "key_menu",
                    size = navBtnSize,
                    iconSize = navIconSize,
                    onClick = { handlePress(RemoteKey.MENU) }
                )
                CircleRemoteButton(
                    icon = Icons.Default.Settings,
                    label = "Source",
                    testTag = "key_source",
                    size = navBtnSize,
                    iconSize = navIconSize,
                    onClick = { handlePress(RemoteKey.SOURCE) }
                )
            }

            if (!isShortScreen) {
                Spacer(modifier = Modifier.weight(1f, fill = false).heightIn(max = 28.dp))
            }

            // Circular D-Pad Controller with dynamic diameter
            Box(
                modifier = Modifier
                    .size(dpadDiameter)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color(0xFF161616),
                                Color(0xFF0C0C0C),
                                Color(0xFF050505)
                            )
                        )
                    )
                    .border(1.5.dp, RemoteBorderStrong, CircleShape)
                    .testTag("dpad_circle"),
                contentAlignment = Alignment.Center
            ) {
                // Up Button
                val upInteraction = remember { MutableInteractionSource() }
                val upPressed by upInteraction.collectIsPressedAsState()
                val upScale by animateFloatAsState(
                    targetValue = if (upPressed) 0.90f else 1.0f,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
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
                        .clip(RoundedCornerShape(topStart = 40.dp, topEnd = 40.dp))
                        .background(if (upPressed) Color.White.copy(alpha = 0.25f) else Color.Transparent)
                        .clickable(
                            interactionSource = upInteraction,
                            indication = null
                        ) { handlePress(RemoteKey.UP) }
                        .testTag("key_dpad_up"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowUp,
                        contentDescription = "Up",
                        tint = if (upPressed) Color.White else TextPrimary,
                        modifier = Modifier.size(arrowIconSize)
                    )
                }

                // Down Button
                val downInteraction = remember { MutableInteractionSource() }
                val downPressed by downInteraction.collectIsPressedAsState()
                val downScale by animateFloatAsState(
                    targetValue = if (downPressed) 0.90f else 1.0f,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
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
                        .clip(RoundedCornerShape(bottomStart = 40.dp, bottomEnd = 40.dp))
                        .background(if (downPressed) Color.White.copy(alpha = 0.25f) else Color.Transparent)
                        .clickable(
                            interactionSource = downInteraction,
                            indication = null
                        ) { handlePress(RemoteKey.DOWN) }
                        .testTag("key_dpad_down"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Down",
                        tint = if (downPressed) Color.White else TextPrimary,
                        modifier = Modifier.size(arrowIconSize)
                    )
                }

                // Left Button
                val leftInteraction = remember { MutableInteractionSource() }
                val leftPressed by leftInteraction.collectIsPressedAsState()
                val leftScale by animateFloatAsState(
                    targetValue = if (leftPressed) 0.90f else 1.0f,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
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
                        .clip(RoundedCornerShape(topStart = 40.dp, bottomStart = 40.dp))
                        .background(if (leftPressed) Color.White.copy(alpha = 0.25f) else Color.Transparent)
                        .clickable(
                            interactionSource = leftInteraction,
                            indication = null
                        ) { handlePress(RemoteKey.LEFT) }
                        .testTag("key_dpad_left"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowLeft,
                        contentDescription = "Left",
                        tint = if (leftPressed) Color.White else TextPrimary,
                        modifier = Modifier.size(arrowIconSize)
                    )
                }

                // Right Button
                val rightInteraction = remember { MutableInteractionSource() }
                val rightPressed by rightInteraction.collectIsPressedAsState()
                val rightScale by animateFloatAsState(
                    targetValue = if (rightPressed) 0.90f else 1.0f,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
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
                        .clip(RoundedCornerShape(topEnd = 40.dp, bottomEnd = 40.dp))
                        .background(if (rightPressed) Color.White.copy(alpha = 0.25f) else Color.Transparent)
                        .clickable(
                            interactionSource = rightInteraction,
                            indication = null
                        ) { handlePress(RemoteKey.RIGHT) }
                        .testTag("key_dpad_right"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowRight,
                        contentDescription = "Right",
                        tint = if (rightPressed) Color.White else TextPrimary,
                        modifier = Modifier.size(arrowIconSize)
                    )
                }

                // Center OK / SELECT Button (Brutalist Inverted Core)
                val okInteraction = remember { MutableInteractionSource() }
                val okPressed by okInteraction.collectIsPressedAsState()
                val okScale by animateFloatAsState(
                    targetValue = if (okPressed) 0.88f else 1.0f,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                    label = "ok_scale"
                )

                Box(
                    modifier = Modifier
                        .size(okDiameter)
                        .graphicsLayer {
                            scaleX = okScale
                            scaleY = okScale
                        }
                        .clip(CircleShape)
                        .background(if (okPressed) Color.White else Color(0xFF181818))
                        .border(
                            if (okPressed) 2.dp else 1.dp,
                            if (okPressed) Color.White else Color(0xFF444444),
                            CircleShape
                        )
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
                            color = if (okPressed) Color.Black else Color.White,
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
                    topIcon = Icons.AutoMirrored.Filled.VolumeUp,
                    topLabel = "VOL +",
                    onTopClick = { handlePress(RemoteKey.VOL_UP) },
                    centerIcon = Icons.AutoMirrored.Filled.VolumeMute,
                    centerLabel = "Mute",
                    onCenterClick = { handlePress(RemoteKey.MUTE) },
                    bottomIcon = Icons.AutoMirrored.Filled.VolumeDown,
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
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, RemoteBorderStrong)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Rewind
                        TactileMediaButton(
                            icon = Icons.Default.FastRewind,
                            contentDescription = "Rewind",
                            testTag = "key_rewind",
                            onClick = { handlePress(RemoteKey.REWIND) },
                            accentColor = Color.White,
                            size = mediaBtnSize,
                            iconSize = mediaIconSize
                        )

                        // Hero Play/Pause Button (Iconic Brutalist High-Contrast Button)
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

                        // Fast Forward
                        TactileMediaButton(
                            icon = Icons.Default.FastForward,
                            contentDescription = "Fast Forward",
                            testTag = "key_fast_forward",
                            onClick = { handlePress(RemoteKey.FAST_FORWARD) },
                            accentColor = Color.White,
                            size = mediaBtnSize,
                            iconSize = mediaIconSize
                        )
                    }
                }

                // Channel Rocker
                RockerColumn(
                    topIcon = Icons.Default.KeyboardArrowUp,
                    topLabel = "CH +",
                    onTopClick = { handlePress(RemoteKey.CH_UP) },
                    centerIcon = null,
                    centerLabel = "CH",
                    onCenterClick = { handlePress(RemoteKey.NUM_PRE_CH) },
                    bottomIcon = Icons.Default.KeyboardArrowDown,
                    bottomLabel = "CH -",
                    onBottomClick = { handlePress(RemoteKey.CH_DOWN) },
                    testTagPrefix = "ch",
                    accentColor = Color.White,
                    width = rockerWidth,
                    btnSize = rockerBtnSize,
                    centerHeight = rockerCenterHeight
                )
            }

            // Quick Access Explicit Media Controls: PLAY, PAUSE, STOP
            // Brutalist monochromatic tactile pills
            Row(
                modifier = Modifier.padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MediaQuickActionPill(
                    icon = Icons.Default.PlayArrow,
                    label = "PLAY",
                    onClick = { handlePress(RemoteKey.PLAY) },
                    accentColor = Color.White,
                    testTag = "key_explicit_play"
                )
                MediaQuickActionPill(
                    icon = Icons.Default.Pause,
                    label = "PAUSE",
                    onClick = { handlePress(RemoteKey.PAUSE) },
                    accentColor = Color(0xFFD4D4D8),
                    testTag = "key_explicit_pause"
                )
                MediaQuickActionPill(
                    icon = Icons.Default.Stop,
                    label = "STOP",
                    onClick = { handlePress(RemoteKey.STOP) },
                    accentColor = Color(0xFFA1A1AA),
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
        targetValue = if (isPressed) 0.88f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "hero_play_scale"
    )

    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(if (isPressed) Color(0xFFE4E4E7) else Color.White)
            .border(
                width = if (isPressed) 2.dp else 1.5.dp,
                color = if (isPressed) Color(0xFF888888) else Color.White,
                shape = CircleShape
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
            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
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
            .clip(RoundedCornerShape(6.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) { onClick() }
            .testTag(testTag),
        color = if (isPressed) Color.White else Color(0xFF101010),
        shape = RoundedCornerShape(6.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isPressed) Color.White else RemoteBorderStrong
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isPressed) Color.Black else TextPrimary,
                modifier = Modifier.size(13.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    color = if (isPressed) Color.Black else TextSecondary,
                    letterSpacing = 0.5.sp
                )
            )
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
    size: Dp = 44.dp,
    iconSize: Dp = 22.dp
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.88f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "media_btn_scale"
    )

    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(if (isPressed) Color.White else Color(0xFF121212))
            .border(
                1.dp,
                if (isPressed) Color.White else RemoteBorderStrong,
                CircleShape
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
        targetValue = if (isPressed) 0.88f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
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
                .size(size)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(CircleShape)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null
                ) { onClick() }
                .testTag(testTag),
            color = if (isPressed) Color.White else RemoteBtnDefault,
            shape = CircleShape,
            border = androidx.compose.foundation.BorderStroke(
                if (isPressed) 1.5.dp else 1.dp,
                if (isPressed) Color.White else RemoteBorderStrong
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
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontSize = if (size >= 52.dp) 10.sp else 9.sp,
                color = if (isPressed) Color.White else TextSecondary,
                fontWeight = if (isPressed) FontWeight.Bold else FontWeight.Medium,
                letterSpacing = 0.5.sp
            )
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
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, RemoteBorderStrong)
    ) {
        Column(
            modifier = Modifier
                .width(width)
                .padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val topInteraction = remember { MutableInteractionSource() }
            val topPressed by topInteraction.collectIsPressedAsState()
            val topScale by animateFloatAsState(
                targetValue = if (topPressed) 0.88f else 1.0f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                label = "rocker_top_scale"
            )

            Box(
                modifier = Modifier
                    .size(btnSize)
                    .graphicsLayer {
                        scaleX = topScale
                        scaleY = topScale
                    }
                    .clip(CircleShape)
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
                targetValue = if (centerPressed) 0.90f else 1.0f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                label = "rocker_center_scale"
            )

            Box(
                modifier = Modifier
                    .size(width = width - 8.dp, height = centerHeight)
                    .graphicsLayer {
                        scaleX = centerScale
                        scaleY = centerScale
                    }
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (centerPressed) Color.White else Color(0xFF181818))
                    .border(1.dp, if (centerPressed) Color.White else RemoteBorderStrong, RoundedCornerShape(6.dp))
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
                targetValue = if (bottomPressed) 0.88f else 1.0f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                label = "rocker_bottom_scale"
            )

            Box(
                modifier = Modifier
                    .size(btnSize)
                    .graphicsLayer {
                        scaleX = bottomScale
                        scaleY = bottomScale
                    }
                    .clip(CircleShape)
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
        targetValue = if (isPressed) 0.88f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "mini_scale"
    )

    Box(
        modifier = Modifier
            .size(48.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(if (isPressed) Color.White else Color(0xFF141414))
            .border(1.dp, if (isPressed) Color.White else RemoteBorderStrong, CircleShape)
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
