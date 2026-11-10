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

    @Test fun `brightness and app grid do not require ADB but explicit setup remains available`() {
        val controller = UnifiedTvController(context)
        com.example.data.local.AdbSettingsStore(context).clear(tv.id)
        UnifiedTvController::class.java.getDeclaredField("activeDevice").apply { isAccessible = true }.set(controller, tv)
        controller.openBrightness(app)
        assertNull(controller.adbSetup.value)
        controller.launchTvApp(app)
        assertNull(controller.adbSetup.value)
        controller.openBrightness(app, configureAdb = true)
        assertEquals(app.appIdentifier, controller.adbSetup.value?.packageName)
        controller.disconnectCurrent()
        assertNull(controller.adbSetup.value)
    }

    @Test fun `navigation while the dimmer lookup is pending invalidates its result`() {
        val controller = UnifiedTvController(context)
        UnifiedTvController::class.java.getDeclaredField("activeDevice").apply { isAccessible = true }.set(controller, tv)
        val beforeLookup = controller.automaticNavigationGeneration
        // No dimmer job exists yet, as when Room is still resolving the Apps entry.
        controller.sendKey(com.example.data.model.RemoteKey.BACK)
        controller.openBrightness(app, configureAdb = true, expectedNavigationGeneration = beforeLookup)
        assertNull(controller.adbSetup.value)
        controller.openBrightness(app, configureAdb = true)
        assertNotNull(controller.adbSetup.value)
        controller.disconnectCurrent()
    }

    @Test fun `initial connection failure does not cancel explicit ADB setup during app lookup`() {
        val controller = UnifiedTvController(context)
        try {
            controller.javaClass.getDeclaredField("activeDevice").apply { isAccessible = true; set(controller, tv) }
            @Suppress("UNCHECKED_CAST")
            val state = controller.javaClass.getDeclaredField("_connectionState").let {
                it.isAccessible = true; it.get(controller) as kotlinx.coroutines.flow.MutableStateFlow<com.example.data.model.ConnectionState>
            }
            state.value = com.example.data.model.ConnectionState.Connecting(tv.name, tv.ipAddress)
            val beforeLookup = controller.automaticNavigationGeneration
            val client = controller.javaClass.getDeclaredField("androidClient").let { it.isAccessible = true; it.get(controller) }
            @Suppress("UNCHECKED_CAST")
            val connectionChanged = client.javaClass.getDeclaredField("onConnectionChanged").let {
                it.isAccessible = true; it.get(client) as (Boolean, String?) -> Unit
            }
            connectionChanged(false, "Remote service unavailable")
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            controller.openBrightness(app, configureAdb = true, expectedNavigationGeneration = beforeLookup)
            assertEquals("Offline setup must remain available", app.appIdentifier, controller.adbSetup.value?.packageName)
        } finally {
            controller.disconnectCurrent()
            (controller.javaClass.getDeclaredField("scope").let { it.isAccessible = true; it.get(controller) } as kotlinx.coroutines.CoroutineScope)
                .coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test fun `an app saved for a different TV cannot start ADB on current TV`() {
        val controller = UnifiedTvController(context)
        UnifiedTvController::class.java.getDeclaredField("activeDevice").apply { isAccessible = true }.set(controller, tv)
        controller.launchTvApp(app.copy(deviceId = 902))
        assertNull(controller.adbSetup.value)
        controller.disconnectCurrent()
    }
}
