package com.example

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
import com.example.data.model.ConnectionState
import com.example.data.model.DeviceType
import com.example.data.model.TvDeviceEntity
import com.example.sleep.SleepTimerManager
import com.example.ui.components.DeviceSelectorBar
import com.example.ui.components.SleepTimerButton
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import androidx.test.core.app.ApplicationProvider
import android.content.Context

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SleepTimerButtonTest {
    @get:Rule val compose = createComposeRule()
    private val tv = TvDeviceEntity(id = 7, name = "Living", deviceType = DeviceType.SAMSUNG_TIZEN,
        ipAddress = "192.168.1.7")

    @Test fun `Sleep button clicks program the actual cycle and remain enabled for OFF`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val manager = SleepTimerManager(context)
        manager.cancel()
        compose.setContent {
            var minutes by remember { mutableStateOf<Int?>(null) }
            SleepTimerButton(minutes = minutes, enabled = true, onClick = {
                minutes = manager.advance(tv)?.minutes
            })
        }
        for (value in listOf(30, 60, 120)) {
            compose.onNodeWithTag("sleep_button").assertIsEnabled().performClick()
            compose.onNodeWithContentDescription("Sleep: $value minutos").assertExists()
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
                sleepMinutes = 60, onSleepClick = { clicks++ })
        }
        compose.onNodeWithContentDescription("Sleep: 60 minutos").performClick()
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
