package com.example.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.TvTokenStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AndroidTvTextTest {
    private fun bytes(field: Int, value: ByteArray) = ByteArrayOutputStream().apply {
        AndroidTvProtocol.writeVarint(this, (field shl 3) or 2)
        AndroidTvProtocol.writeVarint(this, value.size)
        write(value)
    }.toByteArray()

    private fun withTv(features: Int, verify: (AndroidTvClient, SSLSocket) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ssl = AndroidTvCertificateManager.getOrCreateSslContext(context)
        val server = ssl.serverSocketFactory.createServerSocket(0, 10, InetAddress.getLoopbackAddress()) as SSLServerSocket
        server.needClientAuth = true
        server.soTimeout = 10000
        val executor = Executors.newSingleThreadExecutor()
        val ready = CountDownLatch(1)
        val release = CountDownLatch(1)
        var connection: SSLSocket? = null
        val task = executor.submit {
            server.accept().close() // Reachability probe.
            (server.accept() as SSLSocket).use { socket ->
                socket.soTimeout = 5000
                connection = socket
                val output = socket.outputStream
                AndroidTvProtocol.writePacket(output, bytes(1, byteArrayOf(8, features.toByte())))
                val configure = AndroidTvProtocol.getFieldBytes(AndroidTvProtocol.readPacket(socket.inputStream), 1)!!
                assertEquals(features, AndroidTvProtocol.getFieldVarint(configure, 1))
                AndroidTvProtocol.writePacket(output, bytes(2, byteArrayOf()))
                val active = AndroidTvProtocol.getFieldBytes(AndroidTvProtocol.readPacket(socket.inputStream), 2)!!
                assertEquals(features, AndroidTvProtocol.getFieldVarint(active, 1))
                AndroidTvProtocol.writePacket(output, bytes(21, byteArrayOf(8, 7, 16, 11)))
                AndroidTvProtocol.writePacket(output, bytes(40, byteArrayOf(8, 1)))
                ready.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        val client = AndroidTvClient(context, TvTokenStore(context), remotePort = server.localPort, pairingPort = 1)
        try {
            assertTrue(runBlocking { client.connect("127.0.0.1") })
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            verify(client, connection!!)
            release.countDown()
            task.get(10, TimeUnit.SECONDS)
        } finally {
            client.disconnect()
            release.countDown()
            server.close()
            executor.shutdownNow()
        }
    }

    @Test fun `text uses paired TLS IME with TV counters and preserves unicode spaces and punctuation`() {
        withTv(103) { client, socket ->
            val text = "Hola café 😀 %s & ' TV"
            assertTrue(runBlocking { client.sendText(text) })
            val batch = AndroidTvProtocol.getFieldBytes(AndroidTvProtocol.readPacket(socket.inputStream), 21)!!
            assertEquals(7, AndroidTvProtocol.getFieldVarint(batch, 1))
            assertEquals(11, AndroidTvProtocol.getFieldVarint(batch, 2))
            val edit = AndroidTvProtocol.getFieldBytes(batch, 3)!!
            assertEquals(1, AndroidTvProtocol.getFieldVarint(edit, 1))
            val value = AndroidTvProtocol.getFieldBytes(edit, 2)!!
            assertEquals(text.length - 1, AndroidTvProtocol.getFieldVarint(value, 1))
            assertEquals(text.length - 1, AndroidTvProtocol.getFieldVarint(value, 2))
            assertEquals(text, AndroidTvProtocol.getFieldBytes(value, 3)!!.toString(Charsets.UTF_8))

            // A new text field updates counters after connection, via the persistent reader.
            AndroidTvProtocol.writePacket(socket.outputStream, bytes(21, byteArrayOf(8, 23, 16, 29)))
            AndroidTvProtocol.writePacket(socket.outputStream, bytes(8, byteArrayOf(8, 55)))
            assertArrayEquals(bytes(9, byteArrayOf(8, 55)), AndroidTvProtocol.readPacket(socket.inputStream))
            assertFalse(runBlocking { client.sendText("") })
            assertTrue(runBlocking { client.sendText("next") })
            val next = AndroidTvProtocol.getFieldBytes(AndroidTvProtocol.readPacket(socket.inputStream), 21)!!
            assertEquals(23, AndroidTvProtocol.getFieldVarint(next, 1))
            assertEquals(29, AndroidTvProtocol.getFieldVarint(next, 2))
        }
    }

    @Test fun `text fails when TV does not negotiate IME`() {
        withTv(99) { client, _ ->
            assertFalse(runBlocking { client.sendText("hello") })
            assertTrue(client.isConnected)
        }
    }

    @Test fun `empty text and disconnected text fail`() = runBlocking {
        val client = AndroidTvClient()
        assertFalse(client.sendText("hello"))
        assertFalse(client.sendText(""))
    }
}
