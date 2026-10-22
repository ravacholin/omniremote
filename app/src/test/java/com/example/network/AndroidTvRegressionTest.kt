package com.example.network

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AndroidTvRegressionTest {
    // Decode independently of the production parser, following remotemessage.proto.
    private fun fields(bytes: ByteArray): Map<Int, Any> {
        val input = ByteArrayInputStream(bytes)
        val result = mutableMapOf<Int, Any>()
        while (input.available() > 0) {
            val tag = AndroidTvProtocol.readVarint(input)
            result[tag ushr 3] = when (tag and 7) {
                0 -> AndroidTvProtocol.readVarint(input)
                2 -> ByteArray(AndroidTvProtocol.readVarint(input)).also { input.read(it) }
                else -> error("Unexpected wire type")
            }
        }
        return result
    }

    @Test fun `configure uses the actual RemoteDeviceInfo field numbers`() {
        val configure = fields(fields(AndroidTvProtocol.buildRemoteConfigure())[1] as ByteArray)
        val info = fields(configure[2] as ByteArray)
        assertEquals(1, info[3])
        assertEquals("1", String(info[4] as ByteArray))
        assertEquals("atvremote", String(info[5] as ByteArray))
        assertEquals("1.0.0", String(info[6] as ByteArray))
        assertTrue((configure[1] as Int) and 1 != 0) // advertise ping support
    }

    @Test fun `generated certificate is valid now and throughout its advertised lifetime`() {
        val cert = AndroidTvCertificateManager.getClientCertificate(ApplicationProvider.getApplicationContext<Context>())
        cert.checkValidity()
        cert.verify(cert.publicKey)
        assertTrue(cert.notAfter.after(cert.notBefore))
    }

    @Test fun `incorrect pin checksum is rejected locally`() {
        val cert = AndroidTvCertificateManager.getClientCertificate(ApplicationProvider.getApplicationContext<Context>())
        // At most one of the 256 checksum prefixes can match this fixed nonce.
        val accepted = (0..255).count { prefix ->
            runCatching { AndroidTvProtocol.computePairingSecret(cert, cert, "%02X1234".format(prefix)) }.isSuccess
        }
        assertEquals(1, accepted)
    }

    @Test fun `unreachable device is never reported as connected`() = runBlocking {
        val client = AndroidTvClient()
        try {
            assertFalse(client.connect("127.0.0.2"))
            assertFalse(client.isConnected)
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

}
