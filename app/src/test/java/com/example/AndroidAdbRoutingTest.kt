package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.controller.UnifiedTvController
import com.example.data.model.DeviceType
import com.example.data.model.TvAppEntity
import com.example.data.model.TvDeviceEntity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AndroidAdbRoutingTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val tv = TvDeviceEntity(id = 901, name = "FlowBox", deviceType = DeviceType.ANDROID_TV, ipAddress = "192.168.1.50")
    private val app = TvAppEntity(deviceId = 901, appName = "TvDimmer - NightCompanion", appIdentifier = "com.tribalapps.tvdimmer")

    @Test fun `brightness and app grid request ADB setup instead of opening store`() {
        val controller = UnifiedTvController(context)
        com.example.data.local.AdbSettingsStore(context).clear(tv.id)
        UnifiedTvController::class.java.getDeclaredField("activeDevice").apply { isAccessible = true }.set(controller, tv)
        controller.openBrightness(app)
        assertEquals(tv, controller.adbSetup.value?.device)
        controller.dismissAdbSetup()
        controller.launchTvApp(app)
        assertEquals(app.appIdentifier, controller.adbSetup.value?.packageName)
        controller.disconnectCurrent()
        assertNull(controller.adbSetup.value)
    }

    @Test fun `an app saved for a different TV cannot start ADB on current TV`() {
        val controller = UnifiedTvController(context)
        UnifiedTvController::class.java.getDeclaredField("activeDevice").apply { isAccessible = true }.set(controller, tv)
        controller.launchTvApp(app.copy(deviceId = 902))
        assertNull(controller.adbSetup.value)
        controller.disconnectCurrent()
    }
}
