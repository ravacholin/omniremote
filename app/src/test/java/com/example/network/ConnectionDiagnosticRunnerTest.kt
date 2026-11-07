package com.example.network

import com.example.data.model.ConnectionState
import com.example.data.model.DeviceType
import com.example.data.model.TvDeviceEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ConnectionDiagnosticRunnerTest {
    private val tv = TvDeviceEntity(id = 7, name = "Sala", deviceType = DeviceType.SAMSUNG_TIZEN,
        ipAddress = "192.168.1.10", macAddress = "AA:BB:CC:DD:EE:10", authToken = "secret")
    private class Network : DiagnosticEnvironment {
        var ip: String? = "192.168.1.2"
        var reachable = false
        var hardware: String? = "AA:BB:CC:DD:EE:10"
        var devices = emptyList<DiscoveredTv>()
        var probes = 0
        var scans = 0
        var cancel = false
        var evidence = AuthorizationEvidence(ConnectionState.Error("technical secret"), true)
        override fun localIp() = ip
        override suspend fun responds(device: TvDeviceEntity): Boolean { probes++; return reachable }
        override suspend fun hardwareAddress(device: TvDeviceEntity) = hardware
        override suspend fun discover(): List<DiscoveredTv> {
            scans++
            if (cancel) throw CancellationException("closed")
            return devices
        }
        override fun authorization(device: TvDeviceEntity) = evidence
    }
    private fun found(ip: String = "192.168.1.99", mac: String = "aa-bb-cc-dd-ee-10") =
        DiscoveredTv(tv.name, ip, tv.deviceType, 8002, macAddress = mac)

    @Test fun `no local network skips probes and does not blame authorization`() = runTest {
        val network = Network().apply { ip = null }
        val report = ConnectionDiagnosticRunner(network).run(tv)
        assertEquals(0, network.probes)
        assertEquals(0, network.scans)
        assertEquals(DiagnosticStatus.UNKNOWN, report.checks.last().status)
        assertEquals(listOf(DiagnosticAction.NETWORK_SETTINGS), report.actions)
    }

    @Test fun `unique hardware match proposes new IP without exposing credentials`() = runTest {
        val network = Network().apply { devices = listOf(found()) }
        val report = ConnectionDiagnosticRunner(network).run(tv)
        assertEquals("192.168.1.99", report.newAddress)
        assertTrue(DiagnosticAction.USE_NEW_ADDRESS in report.actions)
        assertFalse(report.toString().contains("secret"))
        assertEquals(DiagnosticStatus.UNKNOWN, report.checks.last().status)
    }

    @Test fun `same name or ambiguous hardware never proposes replacing address`() = runTest {
        for (devices in listOf(listOf(found(mac = "")), listOf(found(), found("192.168.1.98")),
            listOf(found().copy(deviceType = DeviceType.ANDROID_TV)))) {
            val report = ConnectionDiagnosticRunner(Network().apply { this.devices = devices }).run(tv)
            assertNull(report.newAddress)
            assertFalse(DiagnosticAction.USE_NEW_ADDRESS in report.actions)
        }
    }

    @Test fun `a different device at the saved IP triggers discovery`() = runTest {
        val network = Network().apply {
            reachable = true; hardware = "AA:BB:CC:DD:EE:20"; devices = listOf(found())
        }
        val report = ConnectionDiagnosticRunner(network).run(tv)
        assertEquals(1, network.scans)
        assertEquals("192.168.1.99", report.newAddress)
        assertFalse(DiagnosticAction.RECONNECT in report.actions)
    }

    @Test fun `a responding old IP without hardware confirmation still searches for the saved device`() = runTest {
        val network = Network().apply { reachable = true; hardware = null; devices = listOf(found()) }
        val report = ConnectionDiagnosticRunner(network).run(tv)
        assertEquals("192.168.1.99", report.newAddress)
        assertFalse(DiagnosticAction.RECONNECT in report.actions)
    }

    @Test fun `open service and stored credential do not confirm authorization`() = runTest {
        val report = ConnectionDiagnosticRunner(Network().apply { reachable = true }).run(tv)
        assertEquals(DiagnosticStatus.UNKNOWN, report.checks.last().status)
        assertTrue(DiagnosticAction.RECONNECT in report.actions)
        assertFalse(DiagnosticAction.AUTHORIZE in report.actions)
    }

    @Test fun `live session confirms authorization and does not probe or scan`() = runTest {
        val network = Network().apply { evidence = AuthorizationEvidence(ConnectionState.Connected(tv.name, tv.ipAddress), true) }
        val report = ConnectionDiagnosticRunner(network).run(tv)
        assertEquals(DiagnosticStatus.PASSED, report.checks.last().status)
        assertEquals(0, network.probes)
        assertEquals(0, network.scans)
        assertTrue(report.actions.isEmpty())
    }

    @Test fun `waiting permission offers authorization without restarting session`() = runTest {
        val network = Network().apply {
            reachable = true; evidence = AuthorizationEvidence(ConnectionState.WaitingAuth(tv.name, "prompt"), true)
        }
        val report = ConnectionDiagnosticRunner(network).run(tv)
        assertEquals(DiagnosticStatus.ATTENTION, report.checks.last().status)
        assertTrue(DiagnosticAction.AUTHORIZE in report.actions)
        assertFalse(DiagnosticAction.RECONNECT in report.actions)
    }

    @Test fun `historical rejection is explained as a previous attempt`() = runTest {
        val network = Network().apply { reachable = true; evidence = evidence.copy(samsungEvent = "authorization_rejected") }
        val report = ConnectionDiagnosticRunner(network).run(tv)
        assertTrue(report.checks.last().message.contains("último intento"))
        assertEquals(DiagnosticStatus.UNKNOWN, report.checks.last().status)
    }

    @Test(expected = CancellationException::class) fun `closing diagnosis cancels discovery`() = runTest {
        ConnectionDiagnosticRunner(Network().apply { cancel = true }).run(tv)
        Unit
    }
}
