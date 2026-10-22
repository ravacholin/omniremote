package com.example

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.example.data.model.DeviceType
import com.example.data.model.RemoteKey
import com.example.ui.components.RemoteDpad
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RemoteDpadGestureTest {
    @get:Rule val compose = createComposeRule()
    private val keys = mutableListOf<RemoteKey>()

    private fun showDpad(height: Int = 650) {
        compose.setContent {
            Box(Modifier.size(360.dp, height.dp)) {
                RemoteDpad(
                    DeviceType.SAMSUNG_TIZEN,
                    onKeyPress = { keys.add(it) },
                    onBrightnessClick = {}
                )
            }
        }
    }

    @Test fun swipeFromOkNavigatesWithoutSelecting() {
        showDpad()
        val directions = listOf(
            Offset(0f, -100f) to RemoteKey.UP,
            Offset(0f, 100f) to RemoteKey.DOWN,
            Offset(-100f, 0f) to RemoteKey.LEFT,
            Offset(100f, 0f) to RemoteKey.RIGHT
        )
        for ((delta, key) in directions) {
            compose.onNodeWithTag("key_dpad_enter").performTouchInput {
                down(center)
                moveTo(center + delta, delayMillis = 100)
                up()
            }
            compose.runOnIdle { assertEquals(listOf(key), keys.toList()); keys.clear() }
        }
    }

    @Test fun tappingOkAndArrowsKeepsDpadActions() {
        showDpad()
        compose.onNodeWithTag("key_dpad_enter").performTouchInput { click() }
        compose.onNodeWithTag("key_dpad_up").performClick()
        compose.onNodeWithTag("key_dpad_down").performClick()
        compose.onNodeWithTag("key_dpad_left").performClick()
        compose.onNodeWithTag("key_dpad_right").performClick()
        compose.runOnIdle {
            assertEquals(listOf(RemoteKey.ENTER, RemoteKey.UP, RemoteKey.DOWN, RemoteKey.LEFT, RemoteKey.RIGHT), keys)
        }
    }

    @Test fun cancelledSwipeSendsNoCommand() {
        showDpad()
        compose.onNodeWithTag("key_dpad_enter").performTouchInput {
            down(center)
            moveTo(center + Offset(0f, -100f), delayMillis = 100)
            cancel()
        }
        compose.runOnIdle { assertEquals(emptyList<RemoteKey>(), keys) }
    }

    @Test fun swipeOnShortScreenDoesNotScrollOrSelect() {
        showDpad(height = 480)
        compose.onNodeWithTag("key_dpad_enter").performTouchInput {
            down(center)
            moveTo(center + Offset(0f, -100f), delayMillis = 100)
            up()
        }
        compose.runOnIdle { assertEquals(listOf(RemoteKey.UP), keys) }
    }
}
