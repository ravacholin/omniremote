package com.example.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AndroidTvSessionTest {
    private fun server(port: Int) = (AndroidTvCertificateManager.getOrCreateSslContext(null)
        .serverSocketFactory.createServerSocket(port, 5, InetAddress.getByName("127.0.0.1")) as SSLServerSocket)
        .apply { soTimeout = 10000 }

    @Test fun negotiatesControlAndAnswersKeepAlive(): Unit = runBlocking {
        val result = CompletableFuture<Unit>()
        val listener = server(6466)
        val worker = thread(isDaemon = true) {
            try {
                // connect() first probes the TCP port, then opens mutual TLS.
                listener.accept().close()
                (listener.accept() as SSLSocket).use { socket ->
                    socket.soTimeout = 10000
                    socket.startHandshake()
                    val input = socket.inputStream
                    val output = socket.outputStream
                    AndroidTvProtocol.writePacket(output, byteArrayOf(0x0a, 3, 8, 0xee.toByte(), 4))
                    assertArrayEquals(AndroidTvProtocol.buildRemoteConfigure(), AndroidTvProtocol.readPacket(input))
                    AndroidTvProtocol.writePacket(output, byteArrayOf(0x12, 3, 8, 0xee.toByte(), 4))
                    assertArrayEquals(AndroidTvProtocol.buildRemoteSetActive(), AndroidTvProtocol.readPacket(input))
                    AndroidTvProtocol.writePacket(output, byteArrayOf(0xc2.toByte(), 2, 2, 8, 1))
                    AndroidTvProtocol.writePacket(output, byteArrayOf(0x42, 2, 8, 7))
                    assertArrayEquals(byteArrayOf(0x4a, 2, 8, 7), AndroidTvProtocol.readPacket(input))
                    result.complete(Unit)
                    // Keep the channel open until the client deliberately disconnects.
                    input.read()
                }
            } catch (error: Throwable) { result.completeExceptionally(error) }
        }
        val client = AndroidTvClient()
        try {
            assertTrue(client.connect("127.0.0.1"))
            assertTrue(client.isConnected)
            result.get(15, TimeUnit.SECONDS)
        } finally {
            client.disconnect()
            listener.close()
            worker.join(1000)
        }
    }

    @Test fun acceptedPinIsDistinguishedFromUnavailableControlChannel() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val listener = server(6467)
        val certificate = AndroidTvCertificateManager.getClientCertificate(null)
        val key = certificate.publicKey as java.security.interfaces.RSAPublicKey
        val digest = java.security.MessageDigest.getInstance("SHA-256").apply {
            repeat(2) {
                update(AndroidTvProtocol.bigIntegerToHexBytes(key.modulus))
                update(AndroidTvProtocol.bigIntegerToHexBytes(key.publicExponent))
            }
            update(AndroidTvProtocol.hexToBytes("1234"))
        }.digest()
        val pin = (digest[0].toInt() and 255).toString(16).padStart(2, '0') + "1234"
        val result = CompletableFuture<Unit>()
        val worker = thread(isDaemon = true) {
            try {
                (listener.accept() as SSLSocket).use { socket ->
                    socket.soTimeout = 10000
                    socket.startHandshake()
                    val input = socket.inputStream
                    val output = socket.outputStream
                    fun ack(field: Int) {
                        val bytes = java.io.ByteArrayOutputStream()
                        bytes.write(byteArrayOf(8, 2, 16, 0xc8.toByte(), 1))
                        AndroidTvProtocol.writeVarint(bytes, (field shl 3) or 2)
                        bytes.write(0)
                        AndroidTvProtocol.writePacket(output, bytes.toByteArray())
                    }
                    assertArrayEquals(AndroidTvProtocol.buildPairingRequest(), AndroidTvProtocol.readPacket(input))
                    ack(AndroidTvProtocol.FIELD_PAIRING_REQUEST_ACK)
                    assertArrayEquals(AndroidTvProtocol.buildOptionsMessage(), AndroidTvProtocol.readPacket(input))
                    ack(AndroidTvProtocol.FIELD_OPTIONS)
                    assertArrayEquals(AndroidTvProtocol.buildPairingConfiguration(), AndroidTvProtocol.readPacket(input))
                    ack(AndroidTvProtocol.FIELD_CONFIGURATION_ACK)
                    assertArrayEquals(AndroidTvProtocol.buildPairingSecret(digest), AndroidTvProtocol.readPacket(input))
                    ack(AndroidTvProtocol.FIELD_SECRET_ACK)
                }
                result.complete(Unit)
            } catch (error: Throwable) { result.completeExceptionally(error) }
        }
        val client = AndroidTvClient()
        var prompted = false
        try {
            client.startPairing("127.0.0.1", { prompted = true }) { _, error -> fail(error) }
            assertTrue(prompted)
            assertFalse(client.submitPairingPin(pin))
            result.get(15, TimeUnit.SECONDS)
            assertTrue(client.isPaired)
            assertTrue(client.lastPairingError.orEmpty().contains("aceptó el código"))
        } finally {
            client.disconnect()
            listener.close()
            worker.join(1000)
            Dispatchers.resetMain()
        }
    }

    @Test fun rejectedPairingRequestNeverClaimsPinIsVisible() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val listener = server(6467)
        val result = CompletableFuture<Unit>()
        val worker = thread(isDaemon = true) {
            try {
                (listener.accept() as SSLSocket).use { socket ->
                    socket.soTimeout = 10000
                    socket.startHandshake()
                    AndroidTvProtocol.readPacket(socket.inputStream)
                    AndroidTvProtocol.writePacket(socket.outputStream, byteArrayOf(8, 2, 16, 0x90.toByte(), 3))
                }
                result.complete(Unit)
            } catch (error: Throwable) { result.completeExceptionally(error) }
        }
        val client = AndroidTvClient()
        var prompted = false
        var failure: String? = null
        try {
            client.startPairing("127.0.0.1", { prompted = true }) { success, error ->
                assertFalse(success)
                failure = error
            }
            result.get(15, TimeUnit.SECONDS)
            assertFalse(prompted)
            assertNotNull(failure)
        } finally {
            client.disconnect()
            listener.close()
            worker.join(1000)
            Dispatchers.resetMain()
        }
    }
}
