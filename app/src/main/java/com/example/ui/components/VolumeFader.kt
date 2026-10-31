package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.RemoteKey
import com.example.ui.theme.Ash
import com.example.ui.theme.Graphite
import com.example.ui.theme.Hairline
import com.example.ui.theme.Ink
import com.example.ui.theme.Paper
import com.example.ui.theme.Void
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Vertical fader for an absolute 0–100 level: a white column rising from the
 * floor of a ruled shaft. Touching anywhere sets the level; dragging follows
 * the finger, with a tick of haptics every ten steps.
 */
@Composable
fun VolumeFader(
    level: Float?,
    muted: Boolean,
    onLevelChange: (Float) -> Unit,
    onLevelChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String = "volume_fader"
) {
    val context = LocalContext.current
    val currentOnChange by rememberUpdatedState(onLevelChange)
    val currentOnFinished by rememberUpdatedState(onLevelChangeFinished)
    val enabled = level != null
    val shown = (level ?: 0f).coerceIn(0f, 100f)
    val fill = if (muted || !enabled) Graphite else Paper

    Box(
        modifier = modifier
            .border(GridGap, Hairline)
            .background(Void)
            .semantics {
                contentDescription = "Volumen"
                progressBarRangeInfo = ProgressBarRangeInfo(shown, 0f..100f)
                if (enabled) setProgress { target ->
                    currentOnChange(target.coerceIn(0f, 100f))
                    currentOnFinished()
                    true
                }
            }
            .then(
                if (!enabled) Modifier else Modifier.pointerInput(Unit) {
                    fun levelAt(y: Float) = ((1f - y / size.height) * 100f).coerceIn(0f, 100f)
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        var last = levelAt(down.position.y)
                        var notch = (last / 10f).toInt()
                        HapticUtil.triggerKeyHaptic(context)
                        currentOnChange(last)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            val next = levelAt(change.position.y)
                            if (next.roundToInt() == last.roundToInt()) continue
                            last = next
                            val nextNotch = (next / 10f).toInt()
                            if (nextNotch != notch) {
                                notch = nextNotch
                                HapticUtil.triggerKeyHaptic(context)
                            }
                            currentOnChange(next)
                        }
                        currentOnFinished()
                    }
                }
            )
            .drawBehind {
                val top = size.height * (1f - shown / 100f)
                drawRect(fill, topLeft = Offset(0f, top), size = Size(size.width, size.height - top))
                // Cap: a heavy rule riding the top of the column.
                val cap = 4.dp.toPx()
                drawRect(fill, topLeft = Offset(0f, (top - cap).coerceAtLeast(0f)), size = Size(size.width, cap))
                // Scale every 5, longer every 25; inverted where the column covers it.
                val inset = 10.dp.toPx()
                for (step in 1 until 20) {
                    val y = size.height * (1f - step / 20f)
                    val long = step % 5 == 0
                    val length = if (long) 18.dp.toPx() else 8.dp.toPx()
                    val color = if (y >= top) Void else if (long) Ash else Graphite
                    val stroke = GridGap.toPx()
                    drawRect(color, Offset(inset, y), Size(length, stroke))
                    drawRect(color, Offset(size.width - inset - length, y), Size(length, stroke))
                }
            }
            .testTag(testTag),
        contentAlignment = Alignment.TopCenter
    ) {
        Text(
            text = if (enabled) shown.roundToInt().toString() else "—",
            style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Light),
            color = if (shown > 82f) Void else Paper,
            modifier = Modifier.padding(top = 16.dp).testTag("${testTag}_level")
        )
        MonoLabel(
            text = if (muted) "Silencio" else "Vol",
            color = if (shown > 58f) Void else Ash,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

/**
 * Vertical jog strip for a volume with no readable level (a TV): dragging up
 * or down scrolls the scale and sends one VOL step per notch travelled.
 * A tap on the upper or lower half sends a single step.
 */
@Composable
fun VolumeJogFader(
    onStep: (RemoteKey) -> Unit,
    modifier: Modifier = Modifier,
    testTag: String = "volume_jog"
) {
    val currentOnStep by rememberUpdatedState(onStep)
    var scroll by remember { mutableFloatStateOf(0f) }
    var active by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .border(GridGap, Hairline)
            .background(if (active) Ink else Void)
            .semantics {
                contentDescription = "Volumen"
                customActions = listOf(
                    CustomAccessibilityAction("Subir volumen") { currentOnStep(RemoteKey.VOL_UP); true },
                    CustomAccessibilityAction("Bajar volumen") { currentOnStep(RemoteKey.VOL_DOWN); true }
                )
            }
            .pointerInput(Unit) {
                val notch = JogNotch.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    active = true
                    var travelled = 0f
                    var pending = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            if (travelled < viewConfiguration.touchSlop) {
                                currentOnStep(if (down.position.y < size.height / 2) RemoteKey.VOL_UP else RemoteKey.VOL_DOWN)
                            }
                            break
                        }
                        change.consume()
                        val dy = change.position.y - change.previousPosition.y
                        travelled += abs(dy)
                        if (travelled < viewConfiguration.touchSlop) continue
                        scroll += dy
                        pending -= dy
                        while (pending >= notch) {
                            pending -= notch
                            currentOnStep(RemoteKey.VOL_UP)
                        }
                        while (pending <= -notch) {
                            pending += notch
                            currentOnStep(RemoteKey.VOL_DOWN)
                        }
                    }
                    active = false
                }
            }
            .drawBehind {
                val notch = JogNotch.toPx()
                val centre = size.height / 2
                val phase = ((scroll % notch) + notch) % notch
                val margin = 44.dp.toPx()
                val stroke = GridGap.toPx()
                var y = phase - notch
                while (y < size.height) {
                    if (y > margin && y < size.height - margin) {
                        // Notches shrink toward the ends, so the strip reads as a drum.
                        val nearness = 1f - abs(y - centre) / (centre - margin)
                        val width = size.width * (0.22f + 0.38f * nearness)
                        drawRect(
                            if (nearness > 0.6f) Ash else Graphite,
                            Offset((size.width - width) / 2, y),
                            Size(width, stroke)
                        )
                    }
                    y += notch
                }
                // Fixed index: the one heavy white line on the strip.
                val bar = 3.dp.toPx()
                drawRect(Paper, Offset(0f, centre - bar / 2), Size(size.width, bar))
            }
            .testTag(testTag)
    ) {
        Icon(
            RemoteIcons.VolumeUp,
            contentDescription = null,
            tint = Paper,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp).size(22.dp)
        )
        MonoLabel(
            "Vol",
            color = Ash,
            modifier = Modifier.align(Alignment.Center).padding(bottom = 22.dp)
        )
        Icon(
            RemoteIcons.VolumeDown,
            contentDescription = null,
            tint = Paper,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp).size(22.dp)
        )
    }
}

/** Finger travel per volume step on the jog strip. */
private val JogNotch = 14.dp
