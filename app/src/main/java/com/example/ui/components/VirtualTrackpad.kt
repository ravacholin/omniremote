package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.DeviceType
import com.example.data.model.RemoteKey
import com.example.ui.theme.*
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Standardized navigation commands translated from virtual trackpad gestures.
 */
enum class NavigationCommand(
    val label: String,
    val remoteKey: RemoteKey,
    val icon: ImageVector
) {
    UP("Up", RemoteKey.UP, Icons.Default.KeyboardArrowUp),
    DOWN("Down", RemoteKey.DOWN, Icons.Default.KeyboardArrowDown),
    LEFT("Left", RemoteKey.LEFT, Icons.Default.KeyboardArrowLeft),
    RIGHT("Right", RemoteKey.RIGHT, Icons.Default.KeyboardArrowRight),
    SELECT("Select", RemoteKey.ENTER, Icons.Default.Check),
    MENU("Menu", RemoteKey.MENU, Icons.Default.Menu),
    PLAY_PAUSE("Play/Pause", RemoteKey.PLAY_PAUSE, Icons.Default.PlayArrow),
    BACK("Back", RemoteKey.BACK, Icons.AutoMirrored.Filled.ArrowBack),
    HOME("Home", RemoteKey.HOME, Icons.Default.Home)
}

/**
 * Gesture sensitivity setting for swipe threshold.
 */
enum class TrackpadSensitivity(
    val displayName: String,
    val thresholdDp: Float
) {
    HIGH("High", 28f),
    STANDARD("Standard", 46f),
    PRECISE("Precision", 68f)
}

/**
 * A sleek, high-precision Virtual Trackpad composable that captures swipe, tap,
 * double tap, and long press gestures and translates them into navigation commands.
 *
 * @param deviceType Connected TV brand for accent color theming
 * @param onNavigationCommand Callback invoked with translated [NavigationCommand]
 * @param onKeyPress Optional direct key-press callback for legacy compatibility
 * @param modifier Custom modifier
 */
@Composable
fun VirtualTrackpad(
    deviceType: DeviceType,
    onNavigationCommand: (NavigationCommand) -> Unit,
    modifier: Modifier = Modifier,
    onKeyPress: ((RemoteKey) -> Unit)? = null,
    isPlaying: Boolean = true,
    onTogglePlayPause: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val accentColor = deviceType.brandColor

    var sensitivity by remember { mutableStateOf(TrackpadSensitivity.STANDARD) }
    val thresholdPx = with(density) { sensitivity.thresholdDp.dp.toPx() }

    // Pointer state
    var touchPosition by remember { mutableStateOf(Offset.Zero) }
    var isTouching by remember { mutableStateOf(false) }
    var activeDirection by remember { mutableStateOf<NavigationCommand?>(null) }
    var lastCommand by remember { mutableStateOf<NavigationCommand?>(null) }
    var lastCommandLabel by remember { mutableStateOf("Swipe to navigate • Tap to select") }

    // Double-tap timing tracker
    var lastTapTime by remember { mutableLongStateOf(0L) }

    // Clear active direction highlight after swipe
    LaunchedEffect(activeDirection) {
        if (activeDirection != null) {
            delay(280)
            activeDirection = null
        }
    }

    // Keep current callbacks updated for pointer input
    val currentOnCommand by rememberUpdatedState(onNavigationCommand)
    val currentOnKeyPress by rememberUpdatedState(onKeyPress)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .testTag("virtual_trackpad"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Top status badge & Sensitivity selector
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Live Gesture HUD
            Surface(
                color = Color(0xFF101010),
                shape = RoundedCornerShape(6.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, RemoteBorderStrong),
                modifier = Modifier.testTag("trackpad_hud")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = lastCommand?.icon ?: Icons.Default.TouchApp,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = lastCommandLabel.uppercase(),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            letterSpacing = 0.5.sp
                        )
                    )
                }
            }

            // Sensitivity Switcher Pill
            Surface(
                color = Color(0xFF0C0C0C),
                shape = RoundedCornerShape(6.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, RemoteBorderStrong)
            ) {
                Row(
                    modifier = Modifier.padding(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TrackpadSensitivity.values().forEach { sens ->
                        val isSelected = sens == sensitivity
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (isSelected) Color.White else Color.Transparent)
                                .clickable { sensitivity = sens }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                .testTag("sensitivity_${sens.name.lowercase()}"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = sens.displayName.uppercase(),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    color = if (isSelected) Color.Black else TextSecondary,
                                    fontWeight = if (isSelected) FontWeight.Black else FontWeight.Bold,
                                    fontSize = 9.sp,
                                    letterSpacing = 0.5.sp
                                )
                            )
                        }
                    }
                }
            }
        }

        // Virtual Trackpad Interactive Surface: dynamically fills full available height
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .heightIn(min = 220.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(1.5.dp, RemoteBorderStrong, RoundedCornerShape(12.dp))
                .pointerInput(sensitivity) {
                    awaitEachGesture {
                        val down = awaitFirstDown(pass = PointerEventPass.Main, requireUnconsumed = false)
                        val startPos = down.position
                        val startTime = System.currentTimeMillis()
                        touchPosition = startPos
                        isTouching = true

                        var accumulatedX = 0f
                        var accumulatedY = 0f
                        var totalMovement = 0f
                        var didSwipe = false

                        while (true) {
                            val event = awaitPointerEvent(pass = PointerEventPass.Main)
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null || !change.pressed) {
                                break
                            }

                            val positionDelta = change.positionChange()
                            change.consume()

                            touchPosition = change.position
                            accumulatedX += positionDelta.x
                            accumulatedY += positionDelta.y
                            totalMovement += sqrt(positionDelta.x * positionDelta.x + positionDelta.y * positionDelta.y)

                            // Swipe translation threshold
                            if (abs(accumulatedX) >= thresholdPx || abs(accumulatedY) >= thresholdPx) {
                                val command = if (abs(accumulatedX) > abs(accumulatedY)) {
                                    if (accumulatedX > 0) NavigationCommand.RIGHT else NavigationCommand.LEFT
                                } else {
                                    if (accumulatedY > 0) NavigationCommand.DOWN else NavigationCommand.UP
                                }

                                HapticUtil.triggerKeyHaptic(context)
                                activeDirection = command
                                lastCommand = command
                                lastCommandLabel = when (command) {
                                    NavigationCommand.UP -> "▲ Swipe Up"
                                    NavigationCommand.DOWN -> "▼ Swipe Down"
                                    NavigationCommand.LEFT -> "◀ Swipe Left"
                                    NavigationCommand.RIGHT -> "▶ Swipe Right"
                                    else -> command.label
                                }

                                currentOnCommand(command)
                                currentOnKeyPress?.invoke(command.remoteKey)

                                accumulatedX = 0f
                                accumulatedY = 0f
                                didSwipe = true
                            }
                        }

                        isTouching = false
                        val gestureDuration = System.currentTimeMillis() - startTime

                        // Tap translation if no significant dragging occurred
                        if (!didSwipe && totalMovement < 24f) {
                            HapticUtil.triggerKeyHaptic(context)
                            if (gestureDuration >= 450L) {
                                // Long press -> MENU
                                val command = NavigationCommand.MENU
                                activeDirection = command
                                lastCommand = command
                                lastCommandLabel = "☰ Long Press: Menu"
                                currentOnCommand(command)
                                currentOnKeyPress?.invoke(command.remoteKey)
                            } else {
                                val now = System.currentTimeMillis()
                                if (now - lastTapTime < 320L) {
                                    // Double Tap -> PLAY / PAUSE
                                    val command = NavigationCommand.PLAY_PAUSE
                                    activeDirection = command
                                    lastCommand = command
                                    lastCommandLabel = "⏯ Double Tap: Play/Pause"
                                    currentOnCommand(command)
                                    currentOnKeyPress?.invoke(command.remoteKey)
                                    lastTapTime = 0L
                                } else {
                                    // Single Tap -> SELECT / OK
                                    val command = NavigationCommand.SELECT
                                    activeDirection = command
                                    lastCommand = command
                                    lastCommandLabel = "✓ Tap: Select (OK)"
                                    currentOnCommand(command)
                                    currentOnKeyPress?.invoke(command.remoteKey)
                                    lastTapTime = now
                                }
                            }
                        }
                    }
                }
                .testTag("trackpad_surface"),
            color = Color(0xFF090909),
            shape = RoundedCornerShape(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
            ) {
                // Directional visual feedback indicators on borders
                // UP Arrow Indicator
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 10.dp)
                ) {
                    DirectionIndicator(
                        icon = Icons.Default.KeyboardArrowUp,
                        isActive = activeDirection == NavigationCommand.UP,
                        accentColor = accentColor,
                        contentDescription = "Swipe Up"
                    )
                }

                // DOWN Arrow Indicator
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 10.dp)
                ) {
                    DirectionIndicator(
                        icon = Icons.Default.KeyboardArrowDown,
                        isActive = activeDirection == NavigationCommand.DOWN,
                        accentColor = accentColor,
                        contentDescription = "Swipe Down"
                    )
                }

                // LEFT Arrow Indicator
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 10.dp)
                ) {
                    DirectionIndicator(
                        icon = Icons.Default.KeyboardArrowLeft,
                        isActive = activeDirection == NavigationCommand.LEFT,
                        accentColor = accentColor,
                        contentDescription = "Swipe Left"
                    )
                }

                // RIGHT Arrow Indicator
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 10.dp)
                ) {
                    DirectionIndicator(
                        icon = Icons.Default.KeyboardArrowRight,
                        isActive = activeDirection == NavigationCommand.RIGHT,
                        accentColor = accentColor,
                        contentDescription = "Swipe Right"
                    )
                }

                // Center Tactile Target Ring
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(96.dp)
                        .border(
                            width = 1.dp,
                            color = if (activeDirection == NavigationCommand.SELECT) Color.White else Color(0xFF222222),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.TouchApp,
                        contentDescription = "Virtual Trackpad Target",
                        tint = if (activeDirection == NavigationCommand.SELECT) Color.White else Color(0xFF444444),
                        modifier = Modifier.size(32.dp)
                    )
                }

                // Interactive touch coordinate follower with sharp brutalist crosshair/ring
                if (isTouching) {
                    Box(
                        modifier = Modifier
                            .offset {
                                IntOffset(
                                    touchPosition.x.roundToInt() - 24,
                                    touchPosition.y.roundToInt() - 24
                                )
                            }
                            .size(48.dp)
                            .background(Color.White.copy(alpha = 0.12f), CircleShape)
                            .border(1.5.dp, Color.White, CircleShape)
                            .testTag("trackpad_touch_cursor")
                    )
                }
            }
        }

        // Quick bottom shortcuts (Back, Home, Volume)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircleRemoteButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                label = "Back",
                testTag = "trackpad_btn_back",
                onClick = {
                    HapticUtil.triggerKeyHaptic(context)
                    val cmd = NavigationCommand.BACK
                    lastCommand = cmd
                    lastCommandLabel = "Back"
                    currentOnCommand(cmd)
                    currentOnKeyPress?.invoke(cmd.remoteKey)
                }
            )

            CircleRemoteButton(
                icon = Icons.AutoMirrored.Filled.VolumeDown,
                label = "Vol -",
                testTag = "trackpad_btn_vol_down",
                onClick = {
                    HapticUtil.triggerKeyHaptic(context)
                    lastCommandLabel = "Volume Down"
                    currentOnKeyPress?.invoke(RemoteKey.VOL_DOWN)
                }
            )

            CircleRemoteButton(
                icon = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                label = if (isPlaying) "Pause" else "Play",
                accent = accentColor,
                testTag = "trackpad_btn_play_pause",
                onClick = {
                    HapticUtil.triggerKeyHaptic(context)
                    if (onTogglePlayPause != null) {
                        onTogglePlayPause()
                    } else {
                        val cmd = NavigationCommand.PLAY_PAUSE
                        lastCommand = cmd
                        lastCommandLabel = if (isPlaying) "Pause" else "Play"
                        currentOnCommand(cmd)
                        currentOnKeyPress?.invoke(cmd.remoteKey)
                    }
                }
            )

            CircleRemoteButton(
                icon = Icons.AutoMirrored.Filled.VolumeMute,
                label = "Mute",
                testTag = "trackpad_btn_mute",
                onClick = {
                    HapticUtil.triggerKeyHaptic(context)
                    lastCommandLabel = "Mute"
                    currentOnKeyPress?.invoke(RemoteKey.MUTE)
                }
            )

            CircleRemoteButton(
                icon = Icons.AutoMirrored.Filled.VolumeUp,
                label = "Vol +",
                testTag = "trackpad_btn_vol_up",
                onClick = {
                    HapticUtil.triggerKeyHaptic(context)
                    lastCommandLabel = "Volume Up"
                    currentOnKeyPress?.invoke(RemoteKey.VOL_UP)
                }
            )

            CircleRemoteButton(
                icon = Icons.Default.Home,
                label = "Home",
                accent = accentColor,
                testTag = "trackpad_btn_home",
                onClick = {
                    HapticUtil.triggerKeyHaptic(context)
                    val cmd = NavigationCommand.HOME
                    lastCommand = cmd
                    lastCommandLabel = "Home"
                    currentOnCommand(cmd)
                    currentOnKeyPress?.invoke(cmd.remoteKey)
                }
            )
        }
    }
}

/**
 * Animated edge directional indicator icon for virtual trackpad swipes.
 */
@Composable
private fun DirectionIndicator(
    icon: ImageVector,
    isActive: Boolean,
    accentColor: Color,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    val scale by animateFloatAsState(
        targetValue = if (isActive) 1.35f else 1.0f,
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "dir_scale"
    )
    val color by animateColorAsState(
        targetValue = if (isActive) Color.White else TextMuted.copy(alpha = 0.4f),
        animationSpec = tween(durationMillis = 200),
        label = "dir_color"
    )

    Surface(
        color = if (isActive) Color.White.copy(alpha = 0.25f) else Color.Transparent,
        shape = CircleShape,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isActive) Color.White else Color.Transparent
        ),
        modifier = modifier.scale(scale)
    ) {
        Box(
            modifier = Modifier.padding(6.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = color,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}
