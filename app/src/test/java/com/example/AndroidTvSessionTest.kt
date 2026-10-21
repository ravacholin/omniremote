package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.TvTokenStore
import com.example.network.AndroidTvCertificateManager
import com.example.network.AndroidTvClient
import com.example.network.AndroidTvProtocol as Protocol
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AndroidTvSessionTest {
    @Test fun `cleanup from an old pairing attempt keeps the new socket open`() {
        val client = AndroidTvClient()
        val ssl = javax.net.ssl.SSLContext.getDefault()
        val oldSocket = ssl.socketFactory.createSocket() as SSLSocket
        val newSocket = ssl.socketFactory.createSocket() as SSLSocket
        try {
            val field = AndroidTvClient::class.java.getDeclaredField("pairingSocket").apply { isAccessible = true }
            field.set(client, newSocket)
            val cleanup = AndroidTvClient::class.java.getDeclaredMethod("closePairingSocket", SSLSocket::class.java)
                .apply { isAccessible = true }
            cleanup.invoke(client, oldSocket)
            assertSame(newSocket, field.get(client))
            assertFalse(newSocket.isClosed)
            cleanup.invoke(client, newSocket)
            assertTrue(newSocket.isClosed)
            assertNull(field.get(client))
        } finally {
            oldSocket.close()
            newSocket.close()
        }
    }

    @Test fun `negotiated session answers pings and preserves pairing after disconnection`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = TvTokenStore(context)
        val client = AndroidTvClient(context, store)
        val ssl = AndroidTvCertificateManager.getOrCreateSslContext(context)
        val server = ssl.serverSocketFactory.createServerSocket(6466, 10, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        server.soTimeout = 10000
        val pong = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val worker = thread(isDaemon = true) {
            try {
                // connect() first checks reachability with a plain TCP probe.
                server.accept().close()
                (server.accept() as SSLSocket).use { socket ->
                    socket.soTimeout = 5000
                    socket.startHandshake()
                    val input = socket.inputStream
                    val output = socket.outputStream
                    Protocol.writePacket(output, byteArrayOf(0x0a, 0x00))
                    check(Protocol.hasField(Protocol.readPacket(input), 1))
                    Protocol.writePacket(output, byteArrayOf(0x12, 0x00))
                    check(Protocol.hasField(Protocol.readPacket(input), 2))
                    Protocol.writePacket(output, byteArrayOf(0xc2.toByte(), 0x02, 0x02, 0x08, 0x01))
                    Protocol.writePacket(output, byteArrayOf(0x42, 0x02, 0x08, 0x7b))
                    check(Protocol.readPacket(input).contentEquals(byteArrayOf(0x4a, 0x02, 0x08, 0x7b)))
                    pong.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
            } catch (error: Throwable) {
                failure.set(error)
                pong.countDown()
            }
        }
        try {
            val connected = client.connect("127.0.0.1")
            val logs = org.robolectric.shadows.ShadowLog.getLogs().joinToString("\n") { "${it.tag}: ${it.msg}" }
            assertTrue("TV simulation: ${failure.get()}; client logs:\n$logs", connected)
            assertTrue(pong.await(5, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("Simulated TV failed", it) }
            assertTrue(client.isConnected)
            assertTrue(store.isAndroidTvPaired("127.0.0.1"))
            client.disconnect()
            assertFalse(client.isConnected)
            assertTrue(store.isAndroidTvPaired("127.0.0.1"))
        } finally {
            client.disconnect()
            release.countDown()
            server.close()
            worker.join(2000)
        }
    }
}
