package com.example.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ServerSocket

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidTvRegressionTest {
    @Test
    fun unreachableTvIsNeverReportedConnected() = runBlocking {
        var reportedConnected = false
        val client = AndroidTvClient(onConnectionChanged = { connected, _ -> reportedConnected = connected })
        // Loopback refuses all normal TV ports; the preferred port is also closed.
        val closedPort = ServerSocket(0).use { it.localPort }
        try {
            assertFalse(client.connect("127.0.0.1", closedPort))
            assertFalse(client.isConnected)
            assertFalse(reportedConnected)
            assertFalse(client.sendKey(com.example.data.model.RemoteKey.UP))
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun scannerUsesWifiLinkAddressEvenWithoutInternetValidation() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val shadow = org.robolectric.Shadows.shadowOf(cm)
        cm.allNetworks.filter { cm.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true }
            .forEach { shadow.removeNetwork(it) }
        val wifi = org.robolectric.shadows.ShadowNetwork.newInstance(42)
        shadow.addNetwork(wifi, org.robolectric.shadows.ShadowNetworkInfo.newInstance(
            android.net.NetworkInfo.DetailedState.CONNECTED, android.net.ConnectivityManager.TYPE_WIFI,
            0, true, true))
        shadow.setNetworkCapabilities(wifi, android.net.NetworkCapabilities().apply {
            org.robolectric.util.ReflectionHelpers.callInstanceMethod<android.net.NetworkCapabilities>(this,
                "addTransportType", org.robolectric.util.ReflectionHelpers.ClassParameter.from(
                    Int::class.javaPrimitiveType!!, android.net.NetworkCapabilities.TRANSPORT_WIFI))
        })
        shadow.setLinkProperties(wifi, android.net.LinkProperties().apply {
            val address = org.robolectric.util.ReflectionHelpers.callConstructor(android.net.LinkAddress::class.java,
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(String::class.java, "192.168.0.16/24"))
            org.robolectric.util.ReflectionHelpers.callInstanceMethod<Boolean>(this, "addLinkAddress",
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(android.net.LinkAddress::class.java, address))
        })
        assertEquals("192.168.0.16", TvScanner(context).getLocalIpAddress())
    }

    @Test
    fun configureUsesRemoteDeviceInfoFieldNumbers() {
        // Published RemoteDeviceInfo: unknown1=3, unknown2=4, package=5, version=6.
        val expected = byteArrayOf(0x0a, 0x1c, 0x08, 0xee.toByte(), 0x04, 0x12, 0x17,
            0x18, 0x01, 0x22, 0x01, 0x31, 0x2a, 0x09) +
            "atvremote".toByteArray() + byteArrayOf(0x32, 0x05) + "1.0.0".toByteArray()
        assertArrayEquals(expected, AndroidTvProtocol.buildRemoteConfigure())
    }

    @Test
    fun incorrectPinChecksumIsRejectedLocally() {
        val certificate = AndroidTvCertificateManager.getClientCertificate(null)
        val nonce = "1234"
        val digest = java.security.MessageDigest.getInstance("SHA-256").apply {
            val key = certificate.publicKey as java.security.interfaces.RSAPublicKey
            repeat(2) {
                update(AndroidTvProtocol.bigIntegerToHexBytes(key.modulus))
                update(AndroidTvProtocol.bigIntegerToHexBytes(key.publicExponent))
            }
            update(AndroidTvProtocol.hexToBytes(nonce))
        }.digest()
        val wrongPrefix = ((digest[0].toInt() and 255) xor 1).toString(16).padStart(2, '0')
        assertThrows(IllegalArgumentException::class.java) {
            AndroidTvProtocol.computePairingSecret(certificate, certificate, wrongPrefix + nonce)
        }
    }
}
