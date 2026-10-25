package com.example

import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.example.controller.UnifiedTvController
import com.example.data.model.ConnectionState
import com.example.data.model.DeviceType
import com.example.data.model.TvDeviceEntity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConnectionRetryTest {
    @Test fun firstConnectionFailureIsRetriedBeforeShowingAnError() {
        val controller = UnifiedTvController(context = ApplicationProvider.getApplicationContext())
        // Nothing listens on the loopback Remote Service ports, so every attempt fails.
        val tv = TvDeviceEntity(id = 7, name = "Living TV", deviceType = DeviceType.ANDROID_TV,
            ipAddress = "127.0.0.1", port = 6466)
        val looper = shadowOf(Looper.getMainLooper())
        val start = SystemClock.uptimeMillis()
        controller.setActiveDevice(tv)
        val deadline = System.currentTimeMillis() + 30_000
        var errorAt = -1L
        while (System.currentTimeMillis() < deadline) {
            looper.idle()
            val state = controller.connectionState.value
            if (state is ConnectionState.Error) { errorAt = SystemClock.uptimeMillis() - start; break }
            assertTrue("Unexpected state while retrying: $state", state is ConnectionState.Connecting)
            Thread.sleep(20)
            looper.idleFor(Duration.ofMillis(20))
        }
        assertTrue("The error must eventually be shown", errorAt >= 0)
        assertTrue("The error appeared before both retries ran ($errorAt ms)", errorAt >= 2800)
        controller.disconnectCurrent()
    }

    @Test fun returningToTheAppReconnectsOnlyAfterAnError() {
        val controller = UnifiedTvController(context = ApplicationProvider.getApplicationContext())
        controller.reconnectIfFailed()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(controller.connectionState.value is ConnectionState.Disconnected)
    }
}
