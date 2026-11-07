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
import com.example.sleep.nextSleepOccurrence
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
import java.time.Instant
import java.util.TimeZone

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

    @Test fun `custom duration persists and resets the quick cycle even when it is thirty minutes`() {
        val manager = SleepTimerManager(context)
        val now = SystemClock.elapsedRealtime()
        val custom = manager.scheduleFor(tv, 30)
        assertEquals(now + 1_800_000L, custom.deadlineElapsed)
        assertEquals(custom, SleepTimerManager(context).timer.value)
        assertEquals(30, manager.advance(tv)!!.minutes)
        assertEquals(60, manager.advance(tv)!!.minutes)
    }

    @Test fun `invalid durations do not replace the existing alarm`() {
        val manager = SleepTimerManager(context)
        val original = manager.advance(tv)!!
        for (minutes in listOf(0, -1, 1441, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { manager.scheduleFor(tv, minutes) }
            assertEquals(original, manager.timer.value)
        }
        assertEquals(original.deadlineElapsed, shadowOf(alarms).scheduledAlarms.single().triggerAtTime)
        assertEquals(86_400L, manager.scheduleFor(tv, 1440).remainingSeconds())
    }

    @Test fun `legacy timers restore their preset and custom extensions keep the next quick tap at thirty`() {
        val manager = SleepTimerManager(context)
        val preset = manager.advance(tv)!!
        val prefs = context.getSharedPreferences("sleep_timer", Context.MODE_PRIVATE)
        val json = org.json.JSONObject(prefs.getString("timer", null)!!)
        json.remove("quickPresetMinutes")
        prefs.edit().putString("timer", json.toString()).commit()
        val restored = SleepTimerManager(context)
        assertEquals(preset, restored.timer.value)
        assertEquals(60, restored.advance(tv)!!.minutes)
        val custom = restored.scheduleFor(tv, 30)
        val firstExtension = restored.extend(custom.token)!!
        val secondExtension = restored.extend(firstExtension.token)!!
        assertEquals(custom.deadlineElapsed + 1_800_000L, secondExtension.deadlineElapsed)
        assertEquals(30, SleepTimerManager(context).advance(tv)!!.minutes)
    }

    @Test fun `extension adds to the saved deadline rather than starting a new duration`() {
        val manager = SleepTimerManager(context)
        val original = manager.advance(tv)!!
        ShadowSystemClock.advanceBy(Duration.ofMinutes(10))
        val extended = manager.extend(original.token)!!
        assertEquals(original.deadlineElapsed + 900_000L, extended.deadlineElapsed)
        assertEquals(2_100L, extended.remainingSeconds())
        assertEquals(tv.id, extended.deviceId)
        assertEquals(tv.name, extended.deviceName)
        assertNotEquals(original.token, extended.token)
        assertEquals(extended, SleepTimerManager(context).timer.value)
        assertEquals(extended.deadlineElapsed, shadowOf(alarms).scheduledAlarms.single().triggerAtTime)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(20))
        assertNull(manager.consumeDue(original.token))
        assertEquals(60, manager.advance(tv.copy(id = 8))!!.minutes)
    }

    @Test fun `stale expired and executing timers cannot be extended`() {
        val manager = SleepTimerManager(context)
        val old = manager.advance(tv)!!
        val current = manager.scheduleFor(tv.copy(id = 8), 1)
        assertNull(manager.extend(old.token))
        assertEquals(current, manager.timer.value)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(1))
        assertNull(manager.extend(current.token))
        assertEquals(current, manager.timer.value)
        assertNotNull(manager.consumeDue(current.token))
        assertNull(manager.extend(current.token))
        assertTrue(manager.isExecutionCurrent(current.token))
    }

    @Test fun `denied permission preserves a timer during custom schedule and extension`() {
        val manager = SleepTimerManager(context)
        val current = manager.advance(tv)!!
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertThrows(SecurityException::class.java) { manager.scheduleFor(tv, 15) }
        assertThrows(SecurityException::class.java) { manager.scheduleAt(tv, 0, 30) }
        assertThrows(SecurityException::class.java) { manager.extend(current.token) }
        assertEquals(current, manager.timer.value)
    }

    @Test fun `clock schedule uses the next local occurrence and the existing monotonic alarm`() {
        val originalZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val wallNow = System.currentTimeMillis()
            val target = java.util.Calendar.getInstance().apply {
                timeInMillis = wallNow
                add(java.util.Calendar.MINUTE, 10)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            val now = SystemClock.elapsedRealtime()
            val manager = SleepTimerManager(context)
            val timer = manager.scheduleAt(tv, target.get(java.util.Calendar.HOUR_OF_DAY), target.get(java.util.Calendar.MINUTE))
            // JVM wall time advances independently of Robolectric's monotonic clock.
            val expected = now + target.timeInMillis - wallNow
            assertTrue(timer.deadlineElapsed in (expected - 1_000)..(expected + 1_000))
            assertTrue(timer.remainingSeconds() in 540L..600L)
            assertEquals(timer.deadlineElapsed, shadowOf(alarms).scheduledAlarms.single().triggerAtTime)
            assertEquals(timer, SleepTimerManager(context).timer.value)
            assertEquals(30, manager.advance(tv)!!.minutes)
        } finally { TimeZone.setDefault(originalZone) }
    }

    @Test fun `next time handles today tomorrow equal minute and DST without assuming twenty four hours`() {
        val cases = listOf(
            Triple("UTC", "2026-11-07T20:00:00Z", "2026-11-08T00:30:00Z"),
            Triple("UTC", "2026-11-07T00:30:00Z", "2026-11-08T00:30:00Z"),
            Triple("America/Argentina/Buenos_Aires", "2026-11-07T20:00:00Z", "2026-11-08T03:30:00Z"),
            Triple("America/New_York", "2026-03-08T05:45:00Z", "2026-03-09T04:30:00Z")
        )
        for ((zone, now, expected) in cases) {
            assertEquals(zone, Instant.parse(expected).toEpochMilli(), nextSleepOccurrence(
                0, 30, Instant.parse(now).toEpochMilli(), TimeZone.getTimeZone(zone)))
        }
        assertThrows(IllegalArgumentException::class.java) { nextSleepOccurrence(24, 0) }
        assertThrows(IllegalArgumentException::class.java) { nextSleepOccurrence(0, 60) }
    }

    @Test fun `ambiguous clock time chooses the earliest future occurrence and nonexistent time skips the gap`() {
        val zone = TimeZone.getTimeZone("America/New_York")
        val cases = listOf(
            listOf("2026-11-01T04:00:00Z", "1", "30", "2026-11-01T05:30:00Z"),
            listOf("2026-11-01T05:45:00Z", "1", "30", "2026-11-01T06:30:00Z"),
            listOf("2026-03-08T05:00:00Z", "2", "30", "2026-03-09T06:30:00Z")
        )
        for ((now, hour, minute, expected) in cases) {
            assertEquals(now, Instant.parse(expected).toEpochMilli(), nextSleepOccurrence(
                hour.toInt(), minute.toInt(), Instant.parse(now).toEpochMilli(), zone))
        }
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
