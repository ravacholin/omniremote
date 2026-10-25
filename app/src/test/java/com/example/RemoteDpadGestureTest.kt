package com.example

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
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
import com.example.ui.components.KeyRepeat
import com.example.ui.components.RemoteDpad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test fun swipeFromArrowNavigatesInsteadOfClickingArrow() {
        showDpad()
        compose.onNodeWithTag("key_dpad_up").performTouchInput {
            down(center)
            moveTo(center + Offset(100f, 0f), delayMillis = 100)
            up()
        }
        compose.runOnIdle { assertEquals(listOf(RemoteKey.RIGHT), keys) }
    }

    @Test fun swipeFromPadBackgroundNavigates() {
        showDpad()
        compose.onNodeWithTag("dpad_circle").performTouchInput {
            val start = Offset(width * 0.25f, height * 0.25f)
            down(start)
            moveTo(start + Offset(100f, 0f), delayMillis = 100)
            up()
        }
        compose.runOnIdle { assertEquals(listOf(RemoteKey.RIGHT), keys) }
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

    @Test fun touchTapsOnArrowsKeepTheirDirections() {
        showDpad()
        for ((tag, key) in listOf(
            "key_dpad_up" to RemoteKey.UP,
            "key_dpad_down" to RemoteKey.DOWN,
            "key_dpad_left" to RemoteKey.LEFT,
            "key_dpad_right" to RemoteKey.RIGHT
        )) {
            compose.onNodeWithTag(tag).performTouchInput { click() }
            compose.runOnIdle { assertEquals(listOf(key), keys.toList()); keys.clear() }
        }
    }

    @Test fun smallFingerMovementStillTapsOk() {
        showDpad()
        compose.onNodeWithTag("key_dpad_enter").performTouchInput {
            down(center)
            moveTo(center + Offset(1f, 1f), delayMillis = 100)
            up()
        }
        compose.runOnIdle { assertEquals(listOf(RemoteKey.ENTER), keys) }
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

    @Test fun swipeInsideScrollableMenuDoesNotScrollOrClick() {
        val scroll = ScrollState(0)
        compose.setContent {
            Box(Modifier.size(360.dp, 480.dp)) {
                Column(Modifier.verticalScroll(scroll)) {
                    RemoteDpad(
                        DeviceType.SAMSUNG_TIZEN,
                        onKeyPress = { keys.add(it) },
                        onBrightnessClick = {},
                        modifier = Modifier.height(650.dp)
                    )
                }
            }
        }
        compose.onNodeWithTag("key_dpad_enter").performTouchInput {
            down(center)
            moveTo(center + Offset(0f, -100f), delayMillis = 100)
            up()
        }
        compose.runOnIdle {
            assertEquals(listOf(RemoteKey.UP), keys)
            assertEquals(0, scroll.value)
        }
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

    private fun holdKey(tag: String, millis: Long) {
        compose.onNodeWithTag(tag).performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(millis)
        compose.onNodeWithTag(tag).performTouchInput { up() }
    }

    @Test fun holdingArrowRepeatsWithoutExtraOnRelease() {
        showDpad()
        holdKey("key_dpad_down", 1_000)
        compose.runOnIdle {
            assertTrue("expected several repeats, got $keys", keys.size >= 3)
            assertTrue(keys.all { it == RemoteKey.DOWN })
        }
    }

    @Test fun shortPressBelowHoldDelaySendsOnce() {
        showDpad()
        holdKey("key_dpad_up", KeyRepeat.HOLD_DELAY_MS / 2)
        compose.runOnIdle { assertEquals(listOf(RemoteKey.UP), keys) }
    }

    @Test fun holdingVolumeAndChannelRepeats() {
        showDpad()
        holdKey("vol_up", 1_000)
        compose.runOnIdle {
            assertTrue(keys.size >= 3)
            assertTrue(keys.all { it == RemoteKey.VOL_UP })
            keys.clear()
        }
        holdKey("ch_down", 1_000)
        compose.runOnIdle {
            assertTrue(keys.size >= 3)
            assertTrue(keys.all { it == RemoteKey.CH_DOWN })
        }
    }

    @Test fun holdingOkDoesNotRepeat() {
        showDpad()
        holdKey("key_dpad_enter", 1_000)
        compose.runOnIdle { assertEquals(listOf(RemoteKey.ENTER), keys) }
    }

    @Test fun swipeAndHoldRepeatsDirection() {
        showDpad()
        compose.onNodeWithTag("key_dpad_enter").performTouchInput {
            down(center)
            moveTo(center + Offset(100f, 0f), delayMillis = 50)
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("key_dpad_enter").performTouchInput { up() }
        compose.runOnIdle {
            assertTrue("expected several repeats, got $keys", keys.size >= 3)
            assertTrue(keys.all { it == RemoteKey.RIGHT })
        }
    }

    @Test fun repeatAccelerates() {
        assertEquals(KeyRepeat.INITIAL_INTERVAL_MS, KeyRepeat.intervalAfter(1))
        assertTrue(KeyRepeat.intervalAfter(4) < KeyRepeat.intervalAfter(1))
        assertEquals(KeyRepeat.MIN_INTERVAL_MS, KeyRepeat.intervalAfter(100))
    }
}
