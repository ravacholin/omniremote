package com.example.external

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.controller.AndroidAdbBrightness
import com.example.controller.RemoteSession
import com.example.data.model.DeviceType
import com.example.data.model.TvAppEntity
import com.example.data.model.TvDeviceEntity
import com.example.sleep.SleepTimerManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RemoteTargetsTest {
    @Test fun savedIdsAreScopedAndSchedulingAnotherTvPreservesSelection() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val session = RemoteSession.getInstance(context)
        val selected = saveTv(session, "Living", "192.0.2.1")
        val other = saveTv(session, "Dormitorio", "192.0.2.2")
        session.selectedDevice.value = selected
        val dimmer = session.apps.saveApp(TvAppEntity(deviceId = other.id, appName = "TVDimmer",
            appIdentifier = AndroidAdbBrightness.DIMMER_PACKAGE, category = "Tools", colorHex = 0L,
            deepLinkUrl = "https://example.org/private"))
        val targets = RemoteTargets(context, session)
        val page = checkNotNull(targets.listDevices(0).data).getJSONArray("devices")
        assertEquals(selected.id, page.getJSONObject(0).getLong("deviceId"))
        assertEquals(other.id, page.getJSONObject(1).getLong("deviceId"))
        assertFalse(page.toString().contains("192.0.2."))
        assertFalse(page.toString().contains("internal-token"))

        val apps = checkNotNull(targets.listApps(other.id, 0).data).getJSONArray("apps")
        assertEquals(dimmer, apps.getJSONObject(0).getLong("appId"))
        assertFalse(apps.toString().contains("private"))
        assertTrue(checkNotNull(targets.getDevice(other.id).data).getJSONArray("commands").toString()
            .contains("\"OPEN_BRIGHTNESS\""))
        assertFalse(checkNotNull(targets.getDevice(selected.id).data).getJSONArray("commands").toString()
            .contains("\"OPEN_BRIGHTNESS\""))

        val foreignApp = RemoteRequest.parse(1, selected.id, "OPEN_APP", """{"appId":$dimmer}""")
        assertEquals("UNKNOWN_APP", targets.dispatch(foreignApp) { true }.code)
        val schedule = RemoteRequest.parse(1, other.id, "SLEEP_FOR", """{"minutes":5}""")
        assertEquals("VERIFIED", targets.dispatch(schedule) { true }.status)

        val timers = SleepTimerManager.getInstance(context)
        assertEquals(other.id, timers.timer.value?.deviceId)
        assertEquals("TIMER_OTHER_DEVICE", targets.dispatch(schedule.copy(deviceId = selected.id)) { true }.code)
        val cancel = RemoteRequest(selected.id, Command.SLEEP_CANCEL, emptyList())
        assertEquals("VERIFIED", targets.dispatch(cancel) { true }.status)
        assertEquals(other.id, timers.timer.value?.deviceId)
        assertEquals(selected, session.selectedDevice.value)
        assertEquals(selected.id, session.repository.getDefaultDevice()?.id)
    }

    private suspend fun saveTv(session: RemoteSession, room: String, ip: String): TvDeviceEntity {
        val device = TvDeviceEntity(name = "FlowBox", room = room, deviceType = DeviceType.ANDROID_TV,
            ipAddress = ip, authToken = "internal-token")
        return checkNotNull(session.repository.getDeviceById(session.repository.saveDevice(device)))
    }
}
