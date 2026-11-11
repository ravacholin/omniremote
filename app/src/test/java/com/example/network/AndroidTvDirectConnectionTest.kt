package com.example.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.RemoteKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AndroidTvDirectConnectionTest {
    @Test fun `first control connection authenticates and sends keys without touching pairing port`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ssl = AndroidTvCertificateManager.getOrCreateSslContext(context)
        val server = ssl.serverSocketFactory.createServerSocket(0, 10, InetAddress.getLoopbackAddress()) as SSLServerSocket
        server.needClientAuth = true
        server.soTimeout = 5000
        val pairing = ServerSocket(0, 10, InetAddress.getLoopbackAddress()).apply { soTimeout = 200 }
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val task = executor.submit {
            server.use {
                (server.accept() as SSLSocket).use { socket ->
                    socket.soTimeout = 5000
                    // A real server expects TLS on the first connection, not an empty probe.
                    socket.startHandshake()
                    AndroidTvProtocol.writePacket(socket.outputStream, byteArrayOf(10, 2, 8, 3))
                    assertTrue(AndroidTvProtocol.hasField(AndroidTvProtocol.readPacket(socket.inputStream), 1))
                    AndroidTvProtocol.writePacket(socket.outputStream, byteArrayOf(18, 2, 8, 3))
                    assertTrue(AndroidTvProtocol.hasField(AndroidTvProtocol.readPacket(socket.inputStream), 2))
                    AndroidTvProtocol.writePacket(socket.outputStream, byteArrayOf(0xC2.toByte(), 2, 2, 8, 1))
                    val key = AndroidTvProtocol.getFieldBytes(AndroidTvProtocol.readPacket(socket.inputStream), 10)!!
                    assertEquals(3, AndroidTvProtocol.getFieldVarint(key, 1))
                    release.await(5, TimeUnit.SECONDS)
                }
            }
        }
        val client = AndroidTvClient(context, remotePort = server.localPort, pairingPort = pairing.localPort)
        try {
            assertTrue("Control should authenticate on its first TCP connection", client.connect("127.0.0.1"))
            assertTrue(client.isConnected)
            assertTrue(client.supportsRemoteV2)
            assertTrue(client.sendKey(RemoteKey.HOME))
            try {
                pairing.accept().use { fail("Normal connection must not open the pairing service") }
            } catch (_: java.net.SocketTimeoutException) { }
            release.countDown()
            task.get(5, TimeUnit.SECONDS)
            Unit
        } finally {
            release.countDown()
            client.disconnect()
            server.close()
            pairing.close()
            executor.shutdownNow()
        }
    }
}
