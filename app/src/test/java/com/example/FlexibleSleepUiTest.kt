package com.example

import android.content.Context
import android.content.Intent
import android.app.Activity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.ConnectionState
import com.example.data.model.DeviceType
import com.example.data.model.TvDeviceEntity
import com.example.sleep.SleepTimerManager
import com.example.ui.components.DeviceSelectorBar
import com.example.ui.components.SleepTimerControls
import com.example.ui.components.rememberSleepTimerRemainingSeconds
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FlexibleSleepUiTest {
    @get:Rule val compose = createComposeRule()
    private val tv = TvDeviceEntity(id = 7, name = "Living", deviceType = DeviceType.ANDROID_TV,
        ipAddress = "192.168.1.7")
    private lateinit var manager: SleepTimerManager
    private var selected by mutableStateOf<TvDeviceEntity?>(tv)
    private val settings = SettingsResultRegistry()

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        manager = SleepTimerManager(context).also { it.cancel() }
    }

    private fun showControls() {
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides settings) {
            val timer by manager.timer.collectAsStateWithLifecycle()
            SleepTimerControls(
                activeDevice = selected, timer = timer,
                remainingSeconds = rememberSleepTimerRemainingSeconds(timer),
                onQuick = { id -> attempt { selected?.takeIf { it.id == id }?.let(manager::advance) } },
                onDuration = { id, minutes -> attempt { selected?.takeIf { it.id == id }?.let { manager.scheduleFor(it, minutes) } } },
                onTime = { id, hour, minute -> attempt { selected?.takeIf { it.id == id }?.let { manager.scheduleAt(it, hour, minute) } } },
                onExtend = { token -> attempt { manager.extend(token) } },
                onCancel = manager::cancel
            ) { click, longClick ->
                DeviceSelectorBar(activeDevice = selected, allDevices = listOfNotNull(selected),
                    connectionState = ConnectionState.Disconnected,
                    onSelectDevice = {}, onOpenManager = {}, onOpenScan = {}, onPowerClick = {},
                    sleepRemainingSeconds = rememberSleepTimerRemainingSeconds(timer),
                    onSleepClick = click, onSleepLongClick = longClick)
            }
            }
        }
    }

    private fun attempt(action: () -> Unit): Boolean = try { action(); true } catch (_: SecurityException) { false }
    private fun openOptions() = compose.onNodeWithTag("sleep_button").performTouchInput { longClick() }

    @Test fun `long press opens options without advancing or cancelling the active preset`() {
        val timer = manager.advance(tv)!!
        showControls()
        openOptions()
        compose.onNodeWithTag("sleep_options_dialog").assertExists()
        assertEquals(timer, manager.timer.value)
        compose.onNodeWithText("Cerrar").performClick()
        assertEquals(timer, manager.timer.value)
        compose.onNodeWithTag("sleep_button").performClick()
        assertEquals(60, manager.timer.value!!.minutes)
    }

    @Test fun `custom duration validates input before programming and keeps the quick cycle`() {
        showControls()
        openOptions()
        compose.onNodeWithTag("sleep_duration_input").performTextReplacement("0")
        compose.onNodeWithText("Programar").assertIsNotEnabled()
        compose.onNodeWithTag("sleep_duration_input").performTextReplacement("1441")
        compose.onNodeWithText("Programar").assertIsNotEnabled()
        assertNull(manager.timer.value)
        compose.onNodeWithTag("sleep_duration_input").performTextReplacement("45")
        compose.onNodeWithText("Programar").performClick()
        compose.onNodeWithTag("sleep_options_dialog").assertDoesNotExist()
        assertEquals(2_700L, manager.timer.value!!.remainingSeconds())
        compose.onNodeWithTag("sleep_button").performClick()
        assertEquals(30, manager.timer.value!!.minutes)
    }

    @Test fun `clock mode programs a future time and rejects invalid clock fields`() {
        showControls()
        openOptions()
        compose.onNodeWithText("Hora").performClick()
        compose.onNodeWithTag("sleep_hour_input").performTextReplacement("24")
        compose.onNodeWithText("Programar").assertIsNotEnabled()
        compose.onNodeWithTag("sleep_hour_input").performTextReplacement("00")
        compose.onNodeWithTag("sleep_minute_input").performTextReplacement("30")
        compose.onNodeWithTag("sleep_time_preview").assertExists()
        compose.onNodeWithText("Programar").performClick()
        assertNotNull(manager.timer.value)
        assertTrue(manager.timer.value!!.remainingSeconds() > 0)
    }

    @Test fun `plus fifteen extends the original TV after selecting another device`() {
        val timer = manager.advance(tv)!!
        ShadowSystemClock.advanceBy(Duration.ofMinutes(10))
        showControls()
        compose.runOnIdle { selected = tv.copy(id = 8, name = "Bedroom") }
        compose.onNodeWithText("+15 MIN").performClick()
        assertEquals(tv.id, manager.timer.value!!.deviceId)
        assertEquals(timer.deadlineElapsed + 900_000L, manager.timer.value!!.deadlineElapsed)
        compose.onNodeWithText("35:00").assertExists()
    }

    @Test fun `changing the selected TV dismisses custom options without scheduling`() {
        showControls()
        openOptions()
        compose.runOnIdle { selected = tv.copy(id = 8) }
        compose.onNodeWithTag("sleep_options_dialog").assertDoesNotExist()
        assertNull(manager.timer.value)
    }

    @Test fun `denied permission keeps custom input and the existing timer`() {
        val timer = manager.advance(tv)!!
        showControls()
        openOptions()
        compose.onNodeWithTag("sleep_duration_input").performTextReplacement("47")
        compose.runOnIdle { ShadowAlarmManager.setCanScheduleExactAlarms(false) }
        compose.onNodeWithText("Programar").performClick()
        compose.onNodeWithText("Activar Sleep").assertExists()
        compose.onNodeWithText("Cancelar").performClick()
        compose.onNodeWithTag("sleep_duration_input").assertTextContains("47")
        assertEquals(timer, manager.timer.value)
        compose.runOnIdle { ShadowAlarmManager.setCanScheduleExactAlarms(true) }
        compose.onNodeWithText("Programar").performClick()
        assertEquals(2_820L, manager.timer.value!!.remainingSeconds())
    }

    @Test fun `expired timer has cancellation but no extension action`() {
        manager.advance(tv)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(31))
        showControls()
        compose.onNodeWithText("+15 MIN").assertDoesNotExist()
        compose.onNodeWithText("CANCELAR").performClick()
        assertNull(manager.timer.value)
    }

    @Test fun `returning from Settings retries the custom request rather than the quick preset`() {
        showControls()
        openOptions()
        compose.onNodeWithTag("sleep_duration_input").performTextReplacement("47")
        compose.runOnIdle { ShadowAlarmManager.setCanScheduleExactAlarms(false) }
        compose.onNodeWithText("Programar").performClick()
        compose.onNodeWithText("Permitir").performClick()
        compose.runOnIdle {
            ShadowAlarmManager.setCanScheduleExactAlarms(true)
            settings.finish()
        }
        assertEquals(2_820L, manager.timer.value!!.remainingSeconds())
        compose.onNodeWithTag("sleep_options_dialog").assertDoesNotExist()
    }

    @Test fun `permission result after changing TVs never schedules the new selection`() {
        showControls()
        openOptions()
        compose.runOnIdle { ShadowAlarmManager.setCanScheduleExactAlarms(false) }
        compose.onNodeWithText("Programar").performClick()
        compose.onNodeWithText("Permitir").performClick()
        compose.runOnIdle { selected = tv.copy(id = 8) }
        compose.onNodeWithTag("sleep_options_dialog").assertDoesNotExist()
        compose.runOnIdle {
            ShadowAlarmManager.setCanScheduleExactAlarms(true)
            settings.finish()
        }
        assertNull(manager.timer.value)
    }

    @Test fun `permission result for an old extension cannot modify a newer timer`() {
        manager.advance(tv)
        showControls()
        compose.runOnIdle { ShadowAlarmManager.setCanScheduleExactAlarms(false) }
        compose.onNodeWithText("+15 MIN").performClick()
        compose.onNodeWithText("Permitir").performClick()
        compose.runOnIdle {
            ShadowAlarmManager.setCanScheduleExactAlarms(true)
            manager.scheduleFor(tv, 9)
        }
        val replacement = manager.timer.value
        compose.runOnIdle { settings.finish() }
        assertEquals(replacement, manager.timer.value)
    }

    @Test fun `a new manual action supersedes a pending request from Settings`() {
        showControls()
        openOptions()
        compose.onNodeWithTag("sleep_duration_input").performTextReplacement("47")
        compose.runOnIdle { ShadowAlarmManager.setCanScheduleExactAlarms(false) }
        compose.onNodeWithText("Programar").performClick()
        compose.onNodeWithText("Permitir").performClick()
        // The user returns to the app independently, then chooses a quick preset.
        compose.runOnIdle { ShadowAlarmManager.setCanScheduleExactAlarms(true) }
        compose.onNodeWithText("Cerrar").performClick()
        compose.onNodeWithTag("sleep_button").performClick()
        val newer = manager.timer.value
        compose.runOnIdle { settings.finish() }
        assertEquals(newer, manager.timer.value)
    }

    private class SettingsResultRegistry : ActivityResultRegistry(), ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry get() = this
        private var requestCode: Int? = null
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>,
            input: I, options: ActivityOptionsCompat?) { this.requestCode = requestCode }
        fun finish() { dispatchResult(requireNotNull(requestCode), Activity.RESULT_OK, null as Intent?) }
    }
}
