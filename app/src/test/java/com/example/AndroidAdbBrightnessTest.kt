package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.controller.AndroidAdbBrightness
import com.example.data.local.AdbSettingsStore
import com.example.data.model.DeviceType
import com.example.data.model.TvDeviceEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AndroidAdbBrightnessTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val settings = AdbSettingsStore(context)
    private val tv = TvDeviceEntity(id = 42, name = "FlowBox", deviceType = DeviceType.ANDROID_TV, ipAddress = "192.168.1.50")
    private val pkg = "com.tribalapps.tvdimmer"

    @Test fun `first tap asks for setup and successful submit persists port for that TV`() = runTest {
        settings.clear(tv.id)
        val calls = mutableListOf<Triple<String,Int,String>>()
        val flow = AndroidAdbBrightness(this, settings, { host, port, app -> calls += Triple(host,port,app) }) { _,_,_ -> }
        flow.open(tv, pkg)
        assertEquals(tv, flow.setup.value?.device)
        assertTrue(calls.isEmpty())
        flow.submit("5656"); runCurrent()
        assertNull(flow.setup.value)
        assertEquals(listOf(Triple("192.168.1.50", 5656, pkg)), calls)
        assertEquals(5656, AdbSettingsStore(context).port(tv.id))
        assertNull(settings.port(43))
        flow.open(tv.copy(ipAddress = "192.168.1.51"), pkg); runCurrent()
        assertEquals(Triple("192.168.1.51", 5656, pkg), calls.last())
        assertNull(flow.setup.value)
    }

    @Test fun `authorization failure shows setup guidance without persisting or reporting success`() = runTest {
        settings.clear(tv.id)
        val results = mutableListOf<Boolean>()
        val flow = AndroidAdbBrightness(this, settings, { _,_,_ -> error("Autoriza OmniRemote en la TV") }) { _,_,ok -> results += ok }
        flow.open(tv, pkg); flow.submit("5555"); runCurrent()
        assertFalse(flow.setup.value!!.busy)
        assertTrue(flow.setup.value!!.error!!.contains("Autoriza"))
        assertNull(settings.port(tv.id))
        assertEquals(listOf(false), results)
    }

    @Test fun `invalid port cannot trigger a connection`() = runTest {
        settings.clear(tv.id)
        val flow = AndroidAdbBrightness(this, settings, { _,_,_ -> fail("Must validate before connecting") }) { _,_,_ -> }
        flow.open(tv, pkg)
        for (port in listOf("", "0", "65536", "-1", "5555;reboot")) {
            flow.submit(port); runCurrent()
            assertNotNull(flow.setup.value?.error)
            assertFalse(flow.setup.value!!.busy)
        }
    }

    @Test fun `duplicate taps cannot launch twice and cancelling prevents late results and writes`() = runTest {
        settings.clear(tv.id)
        var connections = 0
        val release = CompletableDeferred<Unit>()
        val results = mutableListOf<Boolean>()
        val flow = AndroidAdbBrightness(this, settings, { _,_,_ ->
            connections++
            withContext(NonCancellable) { release.await() }
        }) { _,_,ok -> results += ok }
        flow.open(tv, pkg); flow.submit("5555"); runCurrent()
        flow.open(tv, pkg); flow.submit("5555"); runCurrent()
        assertEquals(1, connections)
        flow.cancel()
        release.complete(Unit); advanceUntilIdle()
        assertNull(flow.setup.value)
        assertNull(settings.port(tv.id))
        assertTrue(results.isEmpty())
    }

    @Test fun `forgetting settings cancels pending work without affecting another TV`() = runTest {
        settings.save(tv.id, 5555); settings.save(43, 5556)
        val flow = AndroidAdbBrightness(this, settings, { _,_,_ -> awaitCancellation() }) { _,_,_ -> }
        flow.open(tv, pkg); runCurrent()
        flow.forget(tv.id)
        advanceUntilIdle()
        assertNull(settings.port(tv.id))
        assertEquals(5556, settings.port(43))
        assertNull(flow.setup.value)
    }

    @Test fun `non Android targets cannot be configured or launched through ADB`() = runTest {
        val flow = AndroidAdbBrightness(this, settings, { _,_,_ -> fail("Wrong platform") }) { _,_,_ -> }
        flow.open(tv.copy(deviceType = DeviceType.SAMSUNG_TIZEN), pkg)
        flow.configure(tv.copy(deviceType = DeviceType.MACOS), pkg)
        runCurrent()
        assertNull(flow.setup.value)
    }
}
