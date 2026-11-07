package com.example.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.TvTokenStore
import com.example.data.model.ConnectionState
import com.example.data.model.DeviceType
import com.example.data.model.TvDeviceEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AndroidDiagnosticEnvironmentTest {
    @Test fun `a TV exposing only pairing is reachable but authorization stays unconfirmed`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scanner = TvScanner(context, portProbe = { _, port, _ -> port == 6467 })
        val environment = AndroidDiagnosticEnvironment(context, scanner, TvTokenStore(context)) { ConnectionState.Disconnected }
        val tv = TvDeviceEntity(id = 1, name = "Google TV", deviceType = DeviceType.ANDROID_TV, ipAddress = "192.168.1.80")
        assertTrue(environment.responds(tv))
        assertFalse(environment.authorization(tv).state is ConnectionState.Connected)
    }
}
