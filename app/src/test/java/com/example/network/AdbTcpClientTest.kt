package com.example.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.DataInputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AdbTcpClientTest {
    private val identity = AdbIdentity.generate()

    @Test fun `persistent identity survives restart and signs actual ADB challenges`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val first = AdbIdentity.load(context)
        val second = AdbIdentity.load(context)
        assertArrayEquals(first.publicKeyPayload(), second.publicKeyPayload())
        val token = ByteArray(20) { it.toByte() }
        val payload = String(first.publicKeyPayload(), Charsets.UTF_8)
        assertTrue(payload.endsWith(" OmniRemote@android\u0000"))
        val key = java.util.Base64.getDecoder().decode(payload.substringBefore(' '))
        assertEquals(524, key.size)
        val data = ByteBuffer.wrap(key).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(64, data.int)
        val inverse = data.int.toLong() and 0xffffffffL
        val modulus = ByteArray(256).also(data::get).reversedArray()
        val rr = ByteArray(256).also(data::get).reversedArray()
        val n = BigInteger(1, modulus)
        assertEquals(BigInteger.valueOf(0xffffffffL), n.multiply(BigInteger.valueOf(inverse)).and(BigInteger.valueOf(0xffffffffL)))
        assertEquals(BigInteger.valueOf(2).modPow(BigInteger.valueOf(4096), n), BigInteger(1, rr))
        data.position(520)
        assertEquals(65537, data.int)
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(BigInteger(1, modulus), BigInteger.valueOf(65537)))
        val decoded = Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
            init(Cipher.DECRYPT_MODE, publicKey); doFinal(second.signToken(token))
        }
        assertArrayEquals(byteArrayOf(0x30,0x21,0x30,0x09,0x06,0x05,0x2b,0x0e,0x03,0x02,0x1a,0x05,0x00,0x04,0x14) + token, decoded)
    }

    @Test fun `authorizes launches leanback activity and acknowledges fragmented shell output`() = runBlocking {
        daemon({ socket ->
            val input = DataInputStream(socket.getInputStream()); val out = socket.getOutputStream()
            assertEquals("CNXN", read(input).command)
            val token = ByteArray(20) { (it + 1).toByte() }
            send(out, "AUTH", 1, 0, token)
            val signature = read(input)
            assertEquals("AUTH", signature.command); assertEquals(2, signature.a)
            assertArrayEquals(identity.signToken(token), signature.body)
            send(out, "AUTH", 1, 0, token)
            val publicKey = read(input)
            assertEquals(3, publicKey.a); assertArrayEquals(identity.publicKeyPayload(), publicKey.body)
            send(out, "CNXN", 0x01000000, 4096, "device::\u0000".toByteArray())
            val open = read(input)
            assertEquals("OPEN", open.command)
            val command = String(open.body).trimEnd('\u0000')
            assertTrue(command.startsWith("shell:am start -W "))
            assertTrue(command.contains("-c android.intent.category.LEANBACK_LAUNCHER"))
            assertTrue(command.contains("-p com.tribalapps.tvdimmer"))
            assertFalse(command.contains("play.google.com"))
            send(out, "OKAY", 7, open.a)
            for (chunk in listOf("Starting: Intent {}\r\nSta", "tus: ok\r\n__OMNI_ADB_EXIT__:0\r\n")) {
                send(out, "WRTE", 7, open.a, chunk.toByteArray())
                val ack = read(input); assertEquals("OKAY", ack.command)
                assertEquals(open.a, ack.a); assertEquals(7, ack.b)
            }
            send(out, "CLSE", 7, open.a)
            assertEquals("CLSE", read(input).command)
        }) { port -> AdbTcpClient(identity).launch("127.0.0.1", port, "com.tribalapps.tvdimmer") }
    }

    @Test fun `missing app or rejected command never reports launch success`() = runBlocking {
        for (reply in listOf("Error: Activity not found\n__OMNI_ADB_EXIT__:1\n", "", "Status: ok\n__OMNI_ADB_EXIT__:1\n")) {
            try {
                daemon({ socket ->
                    val input = DataInputStream(socket.getInputStream()); val out = socket.getOutputStream()
                    read(input); send(out, "CNXN", 0x01000000, 4096)
                    val open = read(input); send(out, "OKAY", 7, open.a)
                    if (reply.isNotEmpty()) { send(out, "WRTE", 7, open.a, reply.toByteArray()); read(input) }
                    send(out, "CLSE", 7, open.a); read(input)
                }) { port -> AdbTcpClient(identity).launch("127.0.0.1", port, "com.tribalapps.tvdimmer") }
                fail("An unsuccessful shell command must fail")
            } catch (_: AdbException) { }
        }
    }

    @Test fun `malformed and oversized packets are rejected before allocation`() = runBlocking {
        for ((size, magic) in listOf(-1 to -0x4e584e44, 1_000_000 to -0x4e584e44, 0 to 0)) {
            try {
                daemon({ socket ->
                    read(DataInputStream(socket.getInputStream()))
                    socket.getOutputStream().write(ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                        .put("CNXN".toByteArray()).putInt(0x01000000).putInt(4096).putInt(size).putInt(0).putInt(magic).array())
                }) { port -> AdbTcpClient(identity).launch("127.0.0.1", port, "com.tribalapps.tvdimmer") }
                fail("Must reject malformed ADB packet")
            } catch (_: AdbException) { }
        }
    }

    @Test fun `cancellation closes socket while waiting for TV authorization`() = runBlocking {
        val connected = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        daemon({ socket ->
            val input = DataInputStream(socket.getInputStream()); read(input)
            connected.complete(Unit)
            assertEquals(-1, input.read()); closed.complete(Unit)
        }) { port ->
            val job = launch { AdbTcpClient(identity).launch("127.0.0.1", port, "com.tribalapps.tvdimmer") }
            withTimeout(3000) { connected.await() }
            job.cancelAndJoin()
            withTimeout(3000) { closed.await() }
        }
    }

    @Test fun `stalled authorization expires with a useful failure`() = runBlocking {
        daemon({ socket ->
            val input = DataInputStream(socket.getInputStream()); read(input)
            assertEquals(-1, input.read())
        }) { port ->
            try {
                AdbTcpClient(identity, timeoutMs = 200).launch("127.0.0.1", port, "com.tribalapps.tvdimmer")
                fail("Authorization needs a deadline")
            } catch (error: AdbException) { assertTrue(error.message!!.contains("autoriza", ignoreCase = true)) }
        }
    }

    @Test fun `rejects invalid packages and ports before connecting`() = runBlocking {
        for (pkg in listOf("", "pkg;reboot", "com.test\nreboot", "com.test/Activity")) {
            try { AdbTcpClient(identity).launch("127.0.0.1", 5555, pkg); fail(pkg) } catch (_: IllegalArgumentException) { }
        }
        try { AdbTcpClient(identity).launch("127.0.0.1", 0, "com.test"); fail() } catch (_: IllegalArgumentException) { }
    }

    @Test fun `rejected authorization never opens a shell`() = runBlocking {
        daemon({ socket ->
            val input = DataInputStream(socket.getInputStream()); val out = socket.getOutputStream()
            read(input)
            repeat(2) { send(out, "AUTH", 1, 0, ByteArray(20)); assertEquals("AUTH", read(input).command) }
            send(out, "AUTH", 1, 0, ByteArray(20))
            assertEquals(-1, input.read())
        }) { port ->
            try { AdbTcpClient(identity).launch("127.0.0.1", port, "com.tribalapps.tvdimmer"); fail("Rejected authorization") }
            catch (error: AdbException) { assertTrue(error.message!!.contains("autorización")) }
        }
    }

    @Test fun `wrong stream identifier or damaged checksum closes without acknowledging output`() = runBlocking {
        for (wrongId in listOf(true, false)) {
            daemon({ socket ->
                val input = DataInputStream(socket.getInputStream()); val out = socket.getOutputStream()
                read(input); send(out, "CNXN", 0x01000000, 4096)
                val open = read(input); send(out, "OKAY", 7, open.a)
                if (wrongId) send(out, "WRTE", 8, open.a, "output".toByteArray())
                else {
                    val number = 0x45545257 // WRTE, little endian
                    out.write(ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).putInt(number)
                        .putInt(7).putInt(open.a).putInt(1).putInt(0).putInt(number.inv()).array())
                    out.write('x'.code); out.flush()
                }
                assertEquals(-1, input.read())
            }) { port ->
                try { AdbTcpClient(identity).launch("127.0.0.1", port, "com.tribalapps.tvdimmer"); fail("Untrusted output") }
                catch (_: AdbException) { }
            }
        }
    }

    private suspend fun daemon(handler: (Socket) -> Unit, use: suspend (Int) -> Unit) {
        ServerSocket(0).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            val future = executor.submit {
                server.accept().use { socket -> socket.soTimeout = 4000; handler(socket) }
            }
            try { use(server.localPort) } finally {
                try { future.get(5, TimeUnit.SECONDS) } finally { executor.shutdownNow() }
            }
        }
    }

    private data class Packet(val command: String, val a: Int, val b: Int, val body: ByteArray)
    private fun read(input: DataInputStream): Packet {
        val header = ByteArray(24).also(input::readFully)
        val data = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = String(header.copyOfRange(0, 4)); data.int
        val a = data.int; val b = data.int; val size = data.int
        val checksum = data.int; val magic = data.int
        assertEquals(ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).int.inv(), magic)
        val body = ByteArray(size).also(input::readFully)
        assertEquals(body.sumOf { it.toInt() and 255 }, checksum)
        return Packet(command, a, b, body)
    }
    private fun send(out: OutputStream, command: String, a: Int, b: Int, body: ByteArray = byteArrayOf()) {
        val number = ByteBuffer.wrap(command.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).int
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(number).putInt(a).putInt(b).putInt(body.size).putInt(body.sumOf { it.toInt() and 255 }).putInt(number.inv()).array()
        // Exercise readFully on split headers, not just split shell output.
        out.write(header, 0, 7); out.flush(); out.write(header, 7, 17); out.write(body); out.flush()
    }
}
