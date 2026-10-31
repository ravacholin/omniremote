package com.example.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.TvTokenStore
import com.example.data.model.DeviceType
import com.example.data.model.RemoteKey
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.*
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AndroidTvSessionTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Before fun mainDispatcher() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun resetMainDispatcher() { Dispatchers.resetMain() }

    private fun bytes(field: Int, value: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        AndroidTvProtocol.writeVarint(this, (field shl 3) or 2)
        AndroidTvProtocol.writeVarint(this, value.size)
        write(value)
    }.toByteArray()

    private fun ack(field: Int) = byteArrayOf(8, 2, 16, 0xC8.toByte(), 1) + bytes(field, byteArrayOf())
    private fun server(): SSLServerSocket = (AndroidTvCertificateManager.getOrCreateSslContext(context)
        .serverSocketFactory.createServerSocket(0, 10, InetAddress.getByName("127.0.0.1")) as SSLServerSocket).apply {
        needClientAuth = true
        soTimeout = 10000
    }

    @Test fun `successful PIN pairing allows the remote port to register its own server identity`() = runBlocking {
        val store = TvTokenStore.getInstance(context)
        val deviceId = 986L
        store.saveAndroidTvServerKey(deviceId, "previous-remote-server-key")
        val pairServer = server()
        val executor = Executors.newSingleThreadExecutor()
        val serverTask = executor.submit {
            (pairServer.accept() as SSLSocket).use { socket ->
                socket.soTimeout = 5000
                for ((request, response) in listOf(10 to 11, 20 to 20, 30 to 31, 40 to 41)) {
                    assertTrue(AndroidTvProtocol.hasField(AndroidTvProtocol.readPacket(socket.inputStream), request))
                    AndroidTvProtocol.writePacket(socket.outputStream, ack(response))
                }
            }
        }
        val client = AndroidTvClient(context, store, pairingPort = pairServer.localPort)
        AndroidTvClient::class.java.getDeclaredField("activeDeviceId").apply { isAccessible = true; set(client, deviceId) }
        val rsa = AndroidTvCertificateManager.getClientCertificate(context).publicKey as RSAPublicKey
        val hash = MessageDigest.getInstance("SHA-256").apply {
            repeat(2) {
                update(AndroidTvProtocol.bigIntegerToHexBytes(rsa.modulus))
                update(AndroidTvProtocol.bigIntegerToHexBytes(rsa.publicExponent))
            }
            update(byteArrayOf(0x12, 0x34))
        }.digest()
        try {
            var prompted = false
            client.startPairing("127.0.0.1", { prompted = true }, { _, _ -> })
            assertTrue(prompted)
            assertTrue(client.submitPairingPin("%02X1234".format(hash[0].toInt() and 255)))
            serverTask.get(5, TimeUnit.SECONDS)
            assertNull("Pairing and remote services may use different server keys", store.getAndroidTvServerKey(deviceId))
        } finally {
            client.disconnect()
            pairServer.close()
            executor.shutdownNow()
        }
    }

    @Test fun `app rejection preserves paired TLS ping and keys while real EOF disconnects`() = runBlocking {
        val executor = Executors.newFixedThreadPool(2)
        val pairServer = server()
        val remoteServer = server()
        val allowStart = CountDownLatch(1)
        val configured = CountDownLatch(1)
        val release = CountDownLatch(1)
        val store = TvTokenStore.getInstance(context)
        store.setAndroidTvPaired("127.0.0.1", false)
        val cert = AndroidTvCertificateManager.getClientCertificate(context)
        val rsa = cert.publicKey as RSAPublicKey
        val hash = MessageDigest.getInstance("SHA-256").apply {
            repeat(2) {
                update(AndroidTvProtocol.bigIntegerToHexBytes(rsa.modulus))
                update(AndroidTvProtocol.bigIntegerToHexBytes(rsa.publicExponent))
            }
            update(byteArrayOf(0x12, 0x34))
        }.digest()
        val code = "%02X1234".format(hash[0].toInt() and 255)
        val pairingTask = executor.submit {
            (pairServer.accept() as SSLSocket).use { socket ->
                socket.soTimeout = 10000
                val input = socket.inputStream
                val output = socket.outputStream
                for ((request, response) in listOf(10 to 11, 20 to 20, 30 to 31)) {
                    assertTrue(AndroidTvProtocol.hasField(AndroidTvProtocol.readPacket(input), request))
                    AndroidTvProtocol.writePacket(output, ack(response))
                }
                val secret = AndroidTvProtocol.getFieldBytes(AndroidTvProtocol.readPacket(input), 40)!!
                assertArrayEquals(hash, AndroidTvProtocol.getFieldBytes(secret, 1))
                AndroidTvProtocol.writePacket(output, ack(41))
            }
        }
        val remoteTask = executor.submit {
            (remoteServer.accept() as SSLSocket).use { socket ->
                socket.soTimeout = 10000
                val input = socket.inputStream
                val output = socket.outputStream
                AndroidTvProtocol.writePacket(output, bytes(1, byteArrayOf(8, 0xE3.toByte(), 4)))
                val cfg = AndroidTvProtocol.getFieldBytes(AndroidTvProtocol.readPacket(input), 1)!!
                assertEquals(611, AndroidTvProtocol.getFieldVarint(cfg, 1))
                AndroidTvProtocol.writePacket(output, bytes(2, byteArrayOf(8, 0xE3.toByte(), 4)))
                assertArrayEquals(bytes(2, byteArrayOf(8, 0xE3.toByte(), 4)), AndroidTvProtocol.readPacket(input))
                configured.countDown()
                assertTrue(allowStart.await(10, TimeUnit.SECONDS))
                AndroidTvProtocol.writePacket(output, bytes(40, byteArrayOf(8, 1)))
                AndroidTvProtocol.writePacket(output, bytes(8, byteArrayOf(8, 55)))
                // Ping reply and tap may arrive in either order, but each must be a complete frame.
                val packets = List(3) { AndroidTvProtocol.readPacket(input) }
                assertTrue(packets.any { it.contentEquals(bytes(9, byteArrayOf(8, 55))) })
                assertTrue(packets.any { it.contentEquals(bytes(10, byteArrayOf(8, 3, 16, 3))) })
                val appRequest = packets.first { AndroidTvProtocol.hasField(it, 90) }
                val appLink = AndroidTvProtocol.getFieldBytes(
                    AndroidTvProtocol.getFieldBytes(appRequest, 90)!!, 1
                )!!.toString(Charsets.UTF_8)
                assertEquals("https://play.google.com/store/apps/details?id=com.tribalapps.tvdimmer", appLink)
                val appPacket = AndroidTvProtocol.readPacket(input)
                val launch = AndroidTvProtocol.getFieldBytes(appPacket, 90)!!
                assertEquals("https://www.disneyplus.com", AndroidTvProtocol.getFieldBytes(launch, 1)!!.toString(Charsets.UTF_8))
                for (expected in listOf("https://tv.apple.com", "pplusintl://www.paramountplus.com/shows/", "https://play.hbomax.com")) {
                    val packet = AndroidTvProtocol.readPacket(input)
                    val appLink = AndroidTvProtocol.getFieldBytes(packet, 90)!!
                    assertEquals(expected, AndroidTvProtocol.getFieldBytes(appLink, 1)!!.toString(Charsets.UTF_8))
                }
                val flowStore = AndroidTvProtocol.getFieldBytes(AndroidTvProtocol.readPacket(input), 90)!!
                assertEquals("https://play.google.com/store/apps/details?id=ar.com.flow.androidtv",
                    AndroidTvProtocol.getFieldBytes(flowStore, 1)!!.toString(Charsets.UTF_8))
                val rejectedApp = AndroidTvProtocol.readPacket(input)
                assertTrue(AndroidTvProtocol.hasField(rejectedApp, 90))
                AndroidTvProtocol.writePacket(output, bytes(3, byteArrayOf(8, 1) + bytes(2, rejectedApp)))
                // Some Remote Service versions omit the rejected command from RemoteError.
                AndroidTvProtocol.readPacket(input)
                AndroidTvProtocol.writePacket(output, bytes(3, byteArrayOf(8, 1)))
                AndroidTvProtocol.writePacket(output, bytes(8, byteArrayOf(8, 56)))
                val afterRejection = List(2) { AndroidTvProtocol.readPacket(input) }
                assertTrue(afterRejection.any { it.contentEquals(bytes(9, byteArrayOf(8, 56))) })
                assertTrue(afterRejection.any { it.contentEquals(bytes(10, byteArrayOf(8, 3, 16, 3))) })
                release.await(10, TimeUnit.SECONDS)
            }
        }
        val disconnected = CountDownLatch(1)
        val client = AndroidTvClient(context, store, remotePort = remoteServer.localPort, pairingPort = pairServer.localPort,
            onConnectionChanged = { connected, _ -> if (!connected) disconnected.countDown() })
        try {
            var prompted = false
            var pairingError: String? = null
            client.startPairing("127.0.0.1", { prompted = true }, { _, error -> pairingError = error })
            if (!prompted) { pairingTask.get(10, TimeUnit.SECONDS); fail(pairingError) }
            assertTrue(prompted)
            assertFalse(client.isConnected)
            val result = async(Dispatchers.Default) { client.submitPairingPin(code) }
            assertTrue(configured.await(10, TimeUnit.SECONDS))
            assertFalse(client.isConnected) // TLS + configure alone cannot show Connected.
            allowStart.countDown()
            assertTrue(result.await())
            assertTrue(client.isConnected)
            assertTrue(store.isAndroidTvPaired("127.0.0.1"))
            assertTrue(client.sendKey(RemoteKey.HOME))
            var dimmerStoreOpened = false
            assertTrue(client.launchAppByPackage("com.tribalapps.tvdimmer") { dimmerStoreOpened = true })
            assertTrue("TvDimmer must report that Open needs selecting on the TV", dimmerStoreOpened)
            assertTrue(client.launchAppByPackage("com.disney.disneyplus"))
            for (pkg in listOf("com.apple.atve.androidtv.appletv", "com.cbs.ca", "com.wbd.stream")) {
                assertTrue(client.launchAppByPackage(pkg, "market://launch?id=$pkg"))
            }
            var storeOpened = false
            assertTrue(client.launchAppByPackage("ar.com.flow.androidtv") { storeOpened = true })
            assertTrue("A store fallback must explicitly require confirmation on the TV", storeOpened)
            assertFalse("Rejected app links must report an app error", client.launchAppByPackage("com.example.missing", "test://not-installed"))
            assertFalse(client.launchAppByPackage("com.example.missing", "test://no-error-echo"))
            assertTrue("An app rejection must keep the paired remote connected", client.isConnected)
            assertTrue(client.sendKey(RemoteKey.HOME))
            assertEquals("App errors must not emit a disconnect callback", 1L, disconnected.count)
            pairingTask.get(10, TimeUnit.SECONDS)
            release.countDown()
            remoteTask.get(10, TimeUnit.SECONDS)
            assertTrue("A real closed socket must still report disconnected", disconnected.await(3, TimeUnit.SECONDS))
            assertFalse(client.isConnected)
        } finally {
            allowStart.countDown()
            release.countDown()
            client.disconnect()
            pairServer.close()
            remoteServer.close()
            executor.shutdownNow()
        }
        Unit
    }

    @Test fun `pairing service alone identifies an Android TV box without Cast`() {
        val probed = mutableListOf<Int>()
        val scanner = TvScanner(context) { _, port, _ -> probed.add(port); port == 6467 }
        val device = scanner.probeDevice("192.168.0.25")!!
        assertEquals(DeviceType.ANDROID_TV, device.deviceType)
        assertEquals(6466, device.port)
        assertEquals(listOf(6466, 6467), probed)
    }

    @Test fun `generic web and adb ports do not create a false TV`() {
        val scanner = TvScanner(context) { _, port, _ -> port in listOf(80, 8000, 5555) }
        assertNull(scanner.probeDevice("192.168.0.1"))
    }

    @Test fun `pairing rejects missing status errors and wrong acknowledgements`() {
        for (packet in listOf(byteArrayOf(), bytes(41, byteArrayOf()), ack(11), byteArrayOf(16, 0x92.toByte(), 3) + bytes(41, byteArrayOf()))) {
            assertTrue(runCatching { AndroidTvProtocol.validatePairingResponse(packet, 41) }.isFailure)
        }
        AndroidTvProtocol.validatePairingResponse(ack(41), 41)
    }

    @Test fun `malformed protobuf fields fail instead of hanging or accepting truncated replies`() {
        for (packet in listOf(byteArrayOf(0x80.toByte()), byteArrayOf(0x0A, 10, 1), byteArrayOf(0x0F))) {
            assertTrue(runCatching { AndroidTvProtocol.getFieldBytes(packet, 41) }.isFailure)
        }
    }
}
