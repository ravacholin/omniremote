package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.TvTokenStore
import com.example.network.AndroidTvCertificateManager
import com.example.network.WakeOnLan
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PairingReliabilityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var store: TvTokenStore

    @Before fun setup() {
        context.getSharedPreferences("omni_remote_tokens", Context.MODE_PRIVATE).edit().clear().commit()
        // Robolectric recreates the Application for each test; reset the app singleton too.
        TvTokenStore::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            set(null, null)
        }
        store = TvTokenStore.getInstance(context)
        resetCertificateCache()
    }

    private fun resetCertificateCache() {
        for (name in listOf("cachedSslContext", "cachedCert", "cachedKey")) {
            AndroidTvCertificateManager::class.java.getDeclaredField(name).apply {
                isAccessible = true
                set(null, null)
            }
        }
    }

    @Test fun `unknown TV never receives another TVs token`() {
        store.saveSamsungToken("192.168.1.10", "living-room", 1)
        assertEquals("", store.getSamsungToken("192.168.1.20", 2))
    }

    @Test fun `hardware identity takes precedence over reused DHCP address`() {
        store.saveSamsungToken("192.168.1.10", "old-occupant", 1, "AA:BB:CC:DD:EE:10")
        store.saveSamsungToken("192.168.1.20", "actual-tv", 2, "AA:BB:CC:DD:EE:20")
        assertEquals("actual-tv", store.getSamsungToken("192.168.1.10", 2, "AA:BB:CC:DD:EE:20"))
    }

    @Test fun `new hardware at a recycled IP must authorize separately`() {
        store.saveSamsungToken("192.168.1.10", "old-tv", 1, "AA:BB:CC:DD:EE:10")
        assertEquals("", store.getSamsungToken("192.168.1.10", 2, "AA:BB:CC:DD:EE:20"))
        assertEquals("", store.getSamsungToken("192.168.1.10", 1, "AA:BB:CC:DD:EE:20"))
    }

    @Test fun `MAC identity survives separator changes and new IP`() {
        store.saveSamsungToken("192.168.1.10", "authorized", macAddress = "aa-bb-cc-dd-ee-10")
        assertEquals("authorized", TvTokenStore(context).getSamsungToken("192.168.1.99", macAddress = "AA:BB:CC:DD:EE:10"))
        assertEquals("", store.getSamsungToken("192.168.1.99", macAddress = "AA:BB:CC:DD:EE:99"))
    }

    @Test fun `token issued later wins over an older copy under the MAC`() {
        store.saveSamsungToken("192.168.1.10", "old", 1, "AA:BB:CC:DD:EE:10")
        // The TV issued a new token; the client saves it by IP before the MAC is known.
        store.saveSamsungToken("192.168.1.10", "new")
        assertEquals("new", TvTokenStore(context).getSamsungToken("192.168.1.10", 1, "AA:BB:CC:DD:EE:10"))
        store.saveSamsungToken("192.168.1.10", "newer", 1)
        assertEquals("newer", store.getSamsungToken("192.168.1.10", 1, "AA:BB:CC:DD:EE:10"))
        assertEquals("newer", store.getSamsungToken("192.168.1.10"))
    }

    @Test fun `resolving the MAC keeps the token the TV just issued`() {
        store.saveSamsungToken("192.168.1.10", "stale", macAddress = "AA:BB:CC:DD:EE:10")
        store.saveSamsungToken("192.168.1.10", "fresh", 1)
        store.saveSamsungToken("192.168.1.10", "fresh", 1, "AA:BB:CC:DD:EE:10")
        assertEquals("fresh", store.getSamsungToken("192.168.1.99", macAddress = "AA:BB:CC:DD:EE:10"))
        assertEquals("fresh", store.getSamsungToken("192.168.1.10", 1, "AA:BB:CC:DD:EE:10"))
    }

    @Test fun `tokens saved before sequence numbers are still read`() {
        context.getSharedPreferences("omni_remote_tokens", Context.MODE_PRIVATE).edit()
            .putString("samsung_token_ip_192.168.1.10", "legacy-ip")
            .putString("samsung_token_mac_AA:BB:CC:DD:EE:10", "legacy-mac")
            .commit()
        assertEquals("legacy-ip", store.getSamsungToken("192.168.1.10"))
        assertEquals("legacy-mac", store.getSamsungToken("192.168.1.10", macAddress = "AA:BB:CC:DD:EE:10"))
    }

    @Test fun `generated certificate is currently valid`() {
        AndroidTvCertificateManager.getClientCertificate(context).checkValidity()
    }

    @Test fun `TLS initialization keeps the same certificate without a context`() {
        val certificate = AndroidTvCertificateManager.getClientCertificate()
        AndroidTvCertificateManager.getOrCreateSslContext()
        assertArrayEquals(certificate.encoded, AndroidTvCertificateManager.getClientCertificate().encoded)
    }

    @Test fun `TLS identity survives recreating the process cache`() {
        val certificate = AndroidTvCertificateManager.getClientCertificate(context)
        resetCertificateCache()
        val restored = AndroidTvCertificateManager.getClientCertificate(context)
        assertArrayEquals(certificate.encoded, restored.encoded)
        restored.checkValidity()
    }

    @Test fun `damaged certificate is repaired without replacing the saved private key`() {
        val certificate = AndroidTvCertificateManager.getClientCertificate(context)
        val privateKey = store.getPrivateKeyPkcs8()!!
        store.saveCertData("damaged-certificate", privateKey)
        resetCertificateCache()
        val repaired = AndroidTvCertificateManager.getClientCertificate(context)
        repaired.checkValidity()
        assertArrayEquals(certificate.publicKey.encoded, repaired.publicKey.encoded)
        assertEquals(privateKey, store.getPrivateKeyPkcs8())
        resetCertificateCache()
        AndroidTvCertificateManager.getOrCreateSslContext(context)
        assertArrayEquals(repaired.encoded, AndroidTvCertificateManager.getClientCertificate(context).encoded)
    }

    @Test fun `unreadable private identity is preserved instead of silently rotating every TV authorization`() {
        AndroidTvCertificateManager.getClientCertificate(context)
        val certificate = store.getCertDer()!!
        store.saveCertData(certificate, "damaged-private-key")
        resetCertificateCache()
        assertThrows(IllegalStateException::class.java) {
            AndroidTvCertificateManager.getOrCreateSslContext(context)
        }
        assertEquals(certificate, store.getCertDer())
        assertEquals("damaged-private-key", store.getPrivateKeyPkcs8())
    }

    @Test fun `ephemeral cache cannot hide the persisted identity when context becomes available`() {
        val persisted = AndroidTvCertificateManager.getClientCertificate(context)
        resetCertificateCache()
        AndroidTvCertificateManager.getOrCreateSslContext()
        AndroidTvCertificateManager.getOrCreateSslContext(context)
        assertArrayEquals(persisted.encoded, AndroidTvCertificateManager.getClientCertificate(context).encoded)
        assertArrayEquals(persisted.encoded, android.util.Base64.decode(store.getCertDer(), android.util.Base64.DEFAULT))
    }

    @Test fun `expired certificate is renewed while keeping the authorized RSA key`() {
        val certBytes = javaClass.getResourceAsStream("/expired-tv-cert.der")!!.use { it.readBytes() }
        val keyBytes = javaClass.getResourceAsStream("/test-only-tv-key.pk8")!!.use { it.readBytes() }
        store.saveCertData(android.util.Base64.encodeToString(certBytes, android.util.Base64.NO_WRAP),
            android.util.Base64.encodeToString(keyBytes, android.util.Base64.NO_WRAP))
        val original = java.security.cert.CertificateFactory.getInstance("X.509")
            .generateCertificate(certBytes.inputStream())
        val renewed = AndroidTvCertificateManager.getClientCertificate(context)
        renewed.checkValidity()
        assertArrayEquals(original.publicKey.encoded, renewed.publicKey.encoded)
        assertArrayEquals(keyBytes, android.util.Base64.decode(store.getPrivateKeyPkcs8(), android.util.Base64.DEFAULT))
    }

    @Test fun `clearing tokens does not destroy shared Android TLS identity`() {
        store.saveCertData("certificate", "private-key")
        store.setAndroidTvPaired("192.168.1.10", true)
        store.clearAllTokens()
        assertEquals("certificate", store.getCertDer())
        assertEquals("private-key", store.getPrivateKeyPkcs8())
        assertFalse(store.isAndroidTvPaired("192.168.1.10"))
    }

    @Test fun `Android privacy placeholder is not accepted as TV MAC`() {
        assertNull(WakeOnLan.formatMacAddress("02:00:00:00:00:00"))
    }

    @Test fun `offline TV is not reported as connected`() = runBlocking {
        val client = com.example.network.AndroidTvClient(context, store)
        try {
            assertFalse(client.connect("127.0.0.1"))
            assertFalse(client.isConnected)
        } finally {
            client.disconnect()
        }
    }

    @Test fun `magic packet reaches UDP receiver with exact payload`() = runBlocking {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { receiver ->
            receiver.soTimeout = 2000
            val result = WakeOnLan.sendMagicPacket("AA:BB:CC:DD:EE:10", "127.0.0.1", receiver.localPort)
            assertTrue(result.isSuccess)
            val received = DatagramPacket(ByteArray(256), 256)
            receiver.receive(received)
            val expected = ByteArray(102) { index ->
                if (index < 6) 0xff.toByte() else byteArrayOf(0xaa.toByte(), 0xbb.toByte(), 0xcc.toByte(), 0xdd.toByte(), 0xee.toByte(), 0x10)[(index - 6) % 6]
            }
            assertArrayEquals(expected, received.data.copyOf(received.length))
        }
    }
}
