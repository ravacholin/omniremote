package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.DeviceType
import com.example.network.DiscoveredTv
import com.example.network.NsdTvDiscoveryService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DiscoveryMetadataTest {
    @Test fun `generic NSD update retains verified MAC model name and remote port`() {
        val verified = DiscoveredTv("Living Room", "192.168.1.8", DeviceType.ANDROID_TV, 6466,
            "FlowBox Z3", "AA:BB:CC:DD:EE:FF")
        val generic = DiscoveredTv("Google TV", verified.ipAddress, DeviceType.ANDROID_TV, 8008,
            "Discovered via NsdManager (_googlecast._tcp)")
        for (updates in listOf(listOf(verified, generic), listOf(generic, verified))) {
            val merged = merge(updates)
            assertEquals("AA:BB:CC:DD:EE:FF", merged.macAddress)
            assertEquals("FlowBox Z3", merged.modelInfo)
            assertEquals("Living Room", merged.name)
            assertEquals(6466, merged.port)
        }
    }

    @Test fun `Mac SSH port is retained when its AirPlay announcement arrives later`() {
        val ssh = DiscoveredTv("Office Mac", "192.168.1.9", DeviceType.MACOS, 2222,
            "Discovered via NsdManager (_ssh._tcp)", "AA:BB:CC:DD:EE:FF")
        val airplay = DiscoveredTv("Office Mac", ssh.ipAddress, DeviceType.MACOS, 22,
            "Discovered via NsdManager (_airplay._tcp)")
        for (updates in listOf(listOf(ssh, airplay), listOf(airplay, ssh))) {
            val merged = merge(updates)
            assertEquals(DeviceType.MACOS, merged.deviceType)
            assertEquals(2222, merged.port)
            assertEquals("AA:BB:CC:DD:EE:FF", merged.macAddress)
        }
    }

    private fun merge(updates: List<DiscoveredTv>): DiscoveredTv {
        val service = NsdTvDiscoveryService(ApplicationProvider.getApplicationContext<Context>())
        try {
            val add = service.javaClass.getDeclaredMethod("addOrUpdateDiscoveredTv", DiscoveredTv::class.java).apply { isAccessible = true }
            updates.forEach { add.invoke(service, it) }
            return service.discoveredTvs.value.single()
        } finally {
            service.stopDiscovery()
            (service.javaClass.getDeclaredField("scope").let { it.isAccessible = true; it.get(service) } as CoroutineScope).cancel()
        }
    }
}
