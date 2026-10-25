package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.isOutOfBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.Ash
import com.example.ui.theme.Hairline
import com.example.ui.theme.Paper
import com.example.ui.theme.TextMuted
import com.example.ui.theme.Void

/** Gap between grid cells; drawn by the hairline showing through. */
val GridGap = 1.dp

/**
 * Turns a Row/Column of [GridKey]s into a ruled grid: the container is painted
 * hairline and its cells sit 1dp apart on black, so every line is shared.
 */
fun Modifier.hairlineGrid(): Modifier = this.background(Hairline).padding(GridGap)

/**
 * Hold-to-repeat timing, modelled on a physical remote: a tap sends one
 * command; holding past [HOLD_DELAY_MS] sends it again and keeps repeating,
 * a little faster the longer the key stays down.
 */
object KeyRepeat {
    const val HOLD_DELAY_MS = 400L
    const val INITIAL_INTERVAL_MS = 160L
    const val MIN_INTERVAL_MS = 70L
    private const val ACCELERATION_MS = 15L

    /** Delay before the next repeat, after [repeats] repeats have been sent. */
    fun intervalAfter(repeats: Int): Long =
        (INITIAL_INTERVAL_MS - (repeats - 1).coerceAtLeast(0) * ACCELERATION_MS)
            .coerceAtLeast(MIN_INTERVAL_MS)
}

/**
 * Click that repeats while held. A tap fires on release, like [clickable];
 * holding fires after [KeyRepeat.HOLD_DELAY_MS] and then at [KeyRepeat.intervalAfter],
 * without an extra command on release. Sliding off the key or a parent
 * gesture consuming the touch (D-pad swipe, scroll) cancels it.
 */
@Composable
private fun Modifier.repeatingClickable(
    interaction: MutableInteractionSource,
    onClick: () -> Unit
): Modifier {
    val currentOnClick by rememberUpdatedState(onClick)
    return this
        .semantics {
            role = Role.Button
            onClick { currentOnClick(); true }
        }
        .pointerInput(interaction) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val press = PressInteraction.Press(down.position)
                interaction.tryEmit(press)
                var now = down.uptimeMillis
                var nextRepeatAt = now + KeyRepeat.HOLD_DELAY_MS
                var repeats = 0
                var lifted = false
                while (true) {
                    val event = withTimeoutOrNull((nextRepeatAt - now).coerceAtLeast(0)) {
                        awaitPointerEvent()
                    }
                    if (event == null) {
                        now = nextRepeatAt
                        repeats++
                        currentOnClick()
                        nextRepeatAt = now + KeyRepeat.intervalAfter(repeats)
                        continue
                    }
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (change.isConsumed) break
                    now = change.uptimeMillis
                    if (!change.pressed) {
                        change.consume()
                        lifted = true
                        break
                    }
                    if (change.isOutOfBounds(size, extendedTouchPadding)) break
                }
                if (lifted) {
                    interaction.tryEmit(PressInteraction.Release(press))
                    if (repeats == 0) currentOnClick()
                } else {
                    interaction.tryEmit(PressInteraction.Cancel(press))
                }
            }
        }
}

/**
 * A square-edged key that inverts on press. Inverted keys are the primary
 * actions: white at rest, grey while held. [repeatable] keys repeat while held.
 */
@Composable
fun GridKey(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null,
    inverted: Boolean = false,
    repeatable: Boolean = false,
    content: @Composable (tint: Color) -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val background = when {
        inverted && pressed -> Ash
        inverted -> Paper
        pressed -> Paper
        else -> Void
    }
    val tint = if (inverted || pressed) Void else Paper

    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .background(background)
            .then(
                if (repeatable) Modifier.repeatingClickable(interaction, onClick)
                else Modifier.clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            )
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        content(tint)
    }
}

/** [GridKey] carrying a single line icon. */
@Composable
fun IconKey(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null,
    inverted: Boolean = false,
    iconSize: Dp = 22.dp,
    repeatable: Boolean = false
) {
    GridKey(onClick = onClick, modifier = modifier, testTag = testTag, inverted = inverted, repeatable = repeatable) { tint ->
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** [GridKey] carrying a monospace caption. */
@Composable
fun LabelKey(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null,
    inverted: Boolean = false
) {
    GridKey(onClick = onClick, modifier = modifier, testTag = testTag, inverted = inverted) { tint ->
        Text(label, style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 1)
    }
}

/** Small uppercase monospace caption used for every label in the app. */
@Composable
fun MonoLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = TextMuted
) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

data class NoticeAction(
    val label: String,
    val primary: Boolean = false,
    val onClick: () -> Unit
)

/**
 * A status line: a white rule, a caption, one sentence and up to three actions.
 * Replaces the bordered banners with a single typographic row.
 */
@Composable
fun Notice(
    label: String,
    message: String,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    actions: List<NoticeAction> = emptyList(),
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(Void)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(2.dp)
                .fillMaxHeight()
                .background(Paper)
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            leading?.invoke()
            Column(modifier = Modifier.weight(1f)) {
                MonoLabel(label, color = Ash)
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = Paper,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (actions.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(start = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(GridGap)
            ) {
                actions.forEach { action ->
                    GridKey(onClick = action.onClick, inverted = action.primary) { tint ->
                        Text(
                            text = action.label.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = tint,
                            modifier = Modifier.padding(horizontal = 12.dp),
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}
