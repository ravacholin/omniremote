package com.example

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.example.data.model.ConnectionState
import com.example.data.model.DeviceType
import com.example.data.model.TvDeviceEntity
import com.example.sleep.SleepTimerManager
import com.example.ui.components.DeviceSelectorBar
import com.example.ui.components.SleepTimerButton
import com.example.ui.components.SleepTimerNotice
import com.example.ui.components.rememberSleepTimerRemainingSeconds
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import android.app.AlarmManager
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SleepTimerButtonTest {
    @get:Rule val compose = createComposeRule()
    private val tv = TvDeviceEntity(id = 7, name = "Living", deviceType = DeviceType.ANDROID_TV,
        ipAddress = "192.168.1.7")

    @Test fun `Sleep shows elapsed time without changing the scheduled alarm`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val manager = SleepTimerManager(context)
        manager.cancel()
        val scheduled = manager.advance(tv)!!
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val timer by manager.timer.collectAsStateWithLifecycle()
            DeviceSelectorBar(activeDevice = tv, allDevices = listOf(tv),
                connectionState = ConnectionState.Connected(tv.name, tv.ipAddress),
                onSelectDevice = {}, onOpenManager = {}, onOpenScan = {}, onPowerClick = {},
                sleepRemainingSeconds = rememberSleepTimerRemainingSeconds(timer),
                onSleepClick = { manager.advance(tv) })
        }
        ShadowSystemClock.advanceBy(Duration.ofSeconds(65))
        compose.mainClock.advanceTimeBy(1_100)
        compose.onNodeWithText("28:55").assertExists()
        assertEquals(scheduled, manager.timer.value)
        assertEquals(scheduled.deadlineElapsed,
            shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.single().triggerAtTime)
        compose.onNodeWithTag("sleep_button").performClick()
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithText("60:00").assertExists()
        assertEquals(60, manager.timer.value!!.minutes)
    }

    @Test fun `returning from the background immediately catches up both countdown displays`() {
        val manager = newTimerManager()
        manager.advance(tv)
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply {
                currentState = Lifecycle.State.RESUMED
            }
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) { TimerControls(manager) }
        }
        compose.onNodeWithText("30:00").assertExists()
        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.CREATED }
        ShadowSystemClock.advanceBy(Duration.ofMinutes(10))
        compose.mainClock.advanceTimeBy(1_100)
        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithText("20:00").assertExists()
        compose.onNodeWithText("SLEEP · 20:00 RESTANTES").assertExists()
    }

    @Test fun `reopening the UI restores the remaining time and target and allows cancellation`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        newTimerManager().advance(tv)
        var visible by mutableStateOf(true)
        compose.setContent {
            if (visible) {
                val restored = remember { SleepTimerManager(context) }
                TimerControls(restored)
            }
        }
        compose.onNodeWithText("30:00").assertExists()
        compose.runOnIdle { visible = false }
        compose.onNodeWithTag("sleep_button").assertDoesNotExist()
        ShadowSystemClock.advanceBy(Duration.ofMinutes(12))
        compose.runOnIdle { visible = true }
        compose.onNodeWithText("18:00").assertExists()
        compose.onNodeWithText("Living").assertExists()
        compose.onNodeWithText("CANCELAR").performClick()
        compose.onNodeWithContentDescription("Sleep: OFF").assertExists()
        compose.onNodeWithTag("sleep_timer_notice").assertDoesNotExist()
        assertNull(SleepTimerManager(context).timer.value)
    }

    @Test fun `an expired timer shows pending power off instead of negative time or OFF`() {
        val manager = newTimerManager()
        val timer = manager.advance(tv)!!
        compose.mainClock.autoAdvance = false
        compose.setContent { TimerControls(manager) }
        ShadowSystemClock.advanceBy(Duration.ofMinutes(31))
        compose.mainClock.advanceTimeBy(1_100)
        compose.onNodeWithText("0:00").assertExists()
        compose.onNodeWithText("SLEEP · VENCIDO").assertExists()
        compose.onNodeWithText("Living · Esperando apagado").assertExists()
        compose.onNodeWithContentDescription("Sleep: vencido, esperando apagado").assertExists()
        // Rendering expiry does not consume the alarm or send any TV command.
        assertEquals(timer, manager.timer.value)
        compose.runOnIdle { assertEquals(timer, manager.consumeDue(timer.token)) }
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithTag("sleep_timer_notice").assertDoesNotExist()
        compose.onNodeWithContentDescription("Sleep: OFF").assertExists()
    }

    private fun newTimerManager(): SleepTimerManager {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        return SleepTimerManager(ApplicationProvider.getApplicationContext()).also { it.cancel() }
    }

    @Composable
    private fun TimerControls(manager: SleepTimerManager) {
        val timer by manager.timer.collectAsStateWithLifecycle()
        val remaining = rememberSleepTimerRemainingSeconds(timer)
        Column {
            SleepTimerButton(remainingSeconds = remaining, enabled = true,
                onClick = { manager.advance(tv) })
            timer?.let { SleepTimerNotice(it, remaining ?: 0L, onCancel = { manager.cancel() }) }
        }
    }

    @Test fun `Sleep button clicks program the actual cycle and remain enabled for OFF`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val manager = SleepTimerManager(context)
        manager.cancel()
        compose.setContent {
            val timer by manager.timer.collectAsStateWithLifecycle()
            SleepTimerButton(remainingSeconds = rememberSleepTimerRemainingSeconds(timer),
                enabled = true, onClick = { manager.advance(tv) })
        }
        for (value in listOf(30, 60, 120)) {
            compose.onNodeWithTag("sleep_button").assertIsEnabled().performClick()
            compose.onNodeWithContentDescription("Sleep: quedan $value:00").assertExists()
            assertEquals(value, manager.timer.value!!.minutes)
        }
        compose.onNodeWithTag("sleep_button").performClick()
        compose.onNodeWithContentDescription("Sleep: OFF").assertExists()
        assertNull(manager.timer.value)
    }

    @Test fun `TV header exposes Sleep beside the power button`() {
        var clicks = 0
        compose.setContent {
            DeviceSelectorBar(activeDevice = tv, allDevices = listOf(tv),
                connectionState = ConnectionState.Connected(tv.name, tv.ipAddress),
                onSelectDevice = {}, onOpenManager = {}, onOpenScan = {}, onPowerClick = {},
                sleepRemainingSeconds = 3_600L, onSleepClick = { clicks++ })
        }
        compose.onNodeWithContentDescription("Sleep: quedan 60:00").performClick()
        compose.onNodeWithTag("power_button").assertExists()
        assertEquals(1, clicks)
    }

    @Test fun `Mac header has no TV sleep button`() {
        compose.setContent {
            DeviceSelectorBar(activeDevice = tv.copy(deviceType = DeviceType.MACOS), allDevices = emptyList(),
                connectionState = ConnectionState.Disconnected,
                onSelectDevice = {}, onOpenManager = {}, onOpenScan = {}, onPowerClick = {},
                onSleepClick = {})
        }
        compose.onNodeWithTag("sleep_button").assertDoesNotExist()
    }

    @Test fun `a missing selected TV disables Sleep`() {
        compose.setContent {
            DeviceSelectorBar(activeDevice = null, allDevices = emptyList(),
                connectionState = ConnectionState.Disconnected,
                onSelectDevice = {}, onOpenManager = {}, onOpenScan = {}, onPowerClick = {},
                onSleepClick = {})
        }
        compose.onNodeWithTag("sleep_button").assertIsNotEnabled()
    }
}
