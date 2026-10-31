package com.example

import android.app.AlarmManager
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.DeviceType
import com.example.data.model.RemoteKey
import com.example.data.model.TvDeviceEntity
import com.example.sleep.SleepTimerManager
import com.example.sleep.SleepTimerState
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SleepTimerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val tv = TvDeviceEntity(id = 7, name = "Living TV", deviceType = DeviceType.ANDROID_TV,
        ipAddress = "192.168.1.7", port = 6466)

    @Before fun setup() {
        context.getSharedPreferences("sleep_timer", Context.MODE_PRIVATE).edit().clear().commit()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @Test fun `each click advances and starts the full duration from that click`() {
        val manager = SleepTimerManager(context)
        for (minutes in listOf(30, 60, 120)) {
            val now = SystemClock.elapsedRealtime()
            val timer = manager.advance(tv)!!
            assertEquals(minutes, timer.minutes)
            assertEquals(now + minutes * 60_000L, timer.deadlineElapsed)
            val alarm = shadowOf(alarms).scheduledAlarms.single()
            assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, alarm.type)
            assertEquals(timer.deadlineElapsed, alarm.triggerAtTime)
            assertEquals("com.example.service.SleepTimerService", shadowOf(alarm.operation).savedIntent.component!!.className)
            ShadowSystemClock.advanceBy(Duration.ofMinutes(5))
        }
        assertNull(manager.advance(tv))
        assertNull(manager.timer.value)
        assertTrue(shadowOf(alarms).scheduledAlarms.isEmpty())
        assertEquals(30, manager.advance(tv)!!.minutes)
    }

    @Test fun `remaining seconds round up until the exact deadline and never become negative`() {
        val timer = SleepTimerState("countdown", 7, "Living", 30, 1_800_000L, 0)
        for ((elapsed, seconds) in listOf(
            0L to 1_800L, 1L to 1_800L, 999L to 1_800L, 1_000L to 1_799L,
            65_000L to 1_735L, 1_799_000L to 1L, 1_799_999L to 1L,
            1_800_000L to 0L, 2_000_000L to 0L
        )) {
            assertEquals("At elapsed $elapsed", seconds, timer.remainingSeconds(elapsed))
        }
    }

    @Test fun `restored timer retains its TV regardless of a different selection`() {
        val timer = SleepTimerManager(context).advance(tv)!!
        val restored = SleepTimerManager(context)
        assertEquals(timer, restored.timer.value)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(30))
        val due = restored.consumeDue(timer.token)!!
        assertEquals(7L, due.deviceId)
        assertEquals("Living TV", due.deviceName)
        assertNull(restored.consumeDue(timer.token))
    }

    @Test fun `rescheduling invalidates old delivery and uses the newly selected TV`() {
        val manager = SleepTimerManager(context)
        val old = manager.advance(tv)!!
        val next = manager.advance(tv.copy(id = 8, name = "Bedroom"))!!
        assertNotEquals(old.token, next.token)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(60))
        assertNull(manager.consumeDue(old.token))
        assertEquals(8L, manager.consumeDue(next.token)!!.deviceId)
    }

    @Test fun `early duplicate and cancelled deliveries cannot trigger power off`() {
        val manager = SleepTimerManager(context)
        val timer = manager.advance(tv)!!
        assertNull(manager.consumeDue(timer.token))
        manager.cancel()
        ShadowSystemClock.advanceBy(Duration.ofMinutes(30))
        assertNull(manager.consumeDue(timer.token))
        assertTrue(shadowOf(alarms).scheduledAlarms.isEmpty())
    }

    @Test fun `missing exact alarm permission does not replace an existing timer`() {
        val manager = SleepTimerManager(context)
        val timer = manager.advance(tv)!!
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertThrows(SecurityException::class.java) { manager.advance(tv) }
        assertEquals(timer, manager.timer.value)
    }

    @Test fun `off remains available if exact alarm permission was revoked`() {
        val manager = SleepTimerManager(context)
        repeat(3) { manager.advance(tv) }
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertNull(manager.advance(tv))
        assertTrue(shadowOf(alarms).scheduledAlarms.isEmpty())
    }

    @Test fun `deleting another TV leaves timer intact but deleting its target cancels it`() {
        val manager = SleepTimerManager(context)
        val timer = manager.advance(tv)!!
        manager.cancelForDevice(8)
        assertEquals(timer, manager.timer.value)
        manager.cancelForDevice(7)
        assertNull(manager.timer.value)
        assertTrue(shadowOf(alarms).scheduledAlarms.isEmpty())
    }

    @Test fun `a reboot invalidates persisted elapsed realtime deadlines`() {
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 1)
        val old = SleepTimerManager(context).advance(tv)!!
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 2)
        val restored = SleepTimerManager(context)
        assertNull(restored.timer.value)
        assertNull(restored.consumeDue(old.token))
    }

    @Test fun `Mac cannot be scheduled as a TV`() {
        val manager = SleepTimerManager(context)
        assertThrows(IllegalArgumentException::class.java) { manager.advance(tv.copy(deviceType = DeviceType.MACOS)) }
        assertNull(manager.timer.value)
    }

    @Test fun `a claimed expiry can still be invalidated before its command is sent`() {
        for (action in listOf("cancel", "reprogram", "delete")) {
            val manager = SleepTimerManager(context)
            manager.cancel()
            val timer = manager.advance(tv)!!
            ShadowSystemClock.advanceBy(Duration.ofMinutes(30))
            assertNotNull(manager.consumeDue(timer.token))
            assertTrue(manager.isExecutionCurrent(timer.token))
            when (action) {
                "cancel" -> manager.cancel()
                "reprogram" -> manager.advance(tv.copy(id = 8))
                "delete" -> manager.cancelForDevice(tv.id)
            }
            assertFalse(action, manager.isExecutionCurrent(timer.token))
        }
    }

    @Test fun `process loss during power off is reported without repeating the power command`() {
        val manager = SleepTimerManager(context)
        val timer = manager.advance(tv)!!
        ShadowSystemClock.advanceBy(Duration.ofMinutes(30))
        assertNotNull(manager.consumeDue(timer.token))

        val restarted = SleepTimerManager(context)
        assertNull(restarted.timer.value)
        assertNull(restarted.consumeDue(timer.token))
        assertFalse(restarted.isExecutionCurrent(timer.token))
        assertTrue("An interrupted Sleep must not silently disappear",
            restarted.result.value?.contains("interrump", ignoreCase = true) == true)
    }

    @Test fun `completed cancelled and replaced executions are not reported as interrupted`() {
        for (action in listOf("finish", "cancel", "replace")) {
            val manager = SleepTimerManager(context)
            manager.cancel()
            val timer = manager.advance(tv)!!
            ShadowSystemClock.advanceBy(Duration.ofMinutes(30))
            assertNotNull(manager.consumeDue(timer.token))
            when (action) {
                "finish" -> manager.finishExecution(timer.token, "Confirmed standby")
                "cancel" -> manager.cancel()
                "replace" -> manager.advance(tv)
            }
            val restarted = SleepTimerManager(context)
            assertFalse(action, restarted.result.value?.contains("interrump", ignoreCase = true) == true)
            if (action == "finish") assertEquals("Confirmed standby", restarted.result.value)
            if (action == "replace") assertNotNull(restarted.timer.value)
        }
    }

    @Test fun `power off uses explicit sleep rather than Android power toggle`() {
        assertEquals(223, RemoteKey.POWER_OFF.androidKeyCode)
        assertEquals("KEY_POWEROFF", RemoteKey.POWER_OFF.samsungKey)
        assertEquals(26, RemoteKey.POWER.androidKeyCode)
    }
}
