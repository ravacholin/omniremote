package com.example

import android.os.SystemClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.sleep.SleepTimerState
import com.example.ui.components.SleepTimerNotice
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SleepTimerNoticeTest {
    @get:Rule val compose = createComposeRule()

    private fun timer(remainingMillis: Long = 30 * 60_000L) = SleepTimerState(
        token = "timer", deviceId = 7, deviceName = "FlowBox-Z3", minutes = 30,
        deadlineElapsed = SystemClock.elapsedRealtime() + remainingMillis, bootCount = 0
    )

    @Test fun `notice opened partway through a timer shows remaining whole minutes`() {
        val timer = timer(12 * 60_000L + 30_000L)
        var cancellations = 0
        compose.setContent { SleepTimerNotice(timer, onCancel = { cancellations++ }) }

        compose.onNodeWithText("SLEEP · 13 MIN").assertExists()
        compose.onNodeWithText("FlowBox-Z3").assertExists()
        compose.onNodeWithText("CANCELAR").performClick()
        assertEquals(1, cancellations)
    }

    @Test fun `notice updates at the next minute boundary and never shows negative time`() {
        compose.mainClock.autoAdvance = false
        val timer = timer(60_500L)
        compose.setContent { SleepTimerNotice(timer, onCancel = {}) }
        compose.onNodeWithText("SLEEP · 2 MIN").assertExists()

        advanceBy(600)
        compose.onNodeWithText("SLEEP · 1 MIN").assertExists()
        advanceBy(61_000)
        compose.onNodeWithText("SLEEP · 0 MIN").assertExists()
    }

    @Test fun `reprogramming resets the displayed countdown`() {
        var timer by mutableStateOf(timer(5 * 60_000L))
        compose.setContent { SleepTimerNotice(timer, onCancel = {}) }
        compose.onNodeWithText("SLEEP · 5 MIN").assertExists()

        compose.runOnIdle { timer = timer().copy(token = "replacement", minutes = 60,
            deadlineElapsed = SystemClock.elapsedRealtime() + 60 * 60_000L) }
        compose.onNodeWithText("SLEEP · 60 MIN").assertExists()
    }

    @Test fun `returning to the app immediately refreshes time spent in background`() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        val timer = timer()
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                SleepTimerNotice(timer, onCancel = {})
            }
        }
        compose.onNodeWithText("SLEEP · 30 MIN").assertExists()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        ShadowSystemClock.advanceBy(Duration.ofMinutes(7))
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.onNodeWithText("SLEEP · 23 MIN").assertExists()
    }

    private fun advanceBy(millis: Long) {
        ShadowSystemClock.advanceBy(Duration.ofMillis(millis))
        compose.mainClock.advanceTimeBy(millis)
        compose.waitForIdle()
    }
}
