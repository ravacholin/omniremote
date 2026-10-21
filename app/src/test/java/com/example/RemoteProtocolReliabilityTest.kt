package com.example

import com.example.network.AndroidTvProtocol as Protocol
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class RemoteProtocolReliabilityTest {
    @Test fun `device info uses the Remote v2 protobuf field types`() {
        val config = Protocol.getFieldBytes(Protocol.buildRemoteConfigure(), 1)!!
        val info = Protocol.getFieldBytes(config, 2)!!
        assertEquals("OmniRemote", Protocol.getFieldBytes(info, 1)?.toString(Charsets.UTF_8))
        assertEquals("atvremote", Protocol.getFieldBytes(info, 5)?.toString(Charsets.UTF_8))
        assertEquals(1, Protocol.getFieldVarint(info, 3))
    }

    @Test fun `ping requests echo their sequence in a ping response`() {
        val response = Protocol.buildRemoteResponse(byteArrayOf(0x42, 0x02, 0x08, 0x7b))
        assertArrayEquals(byteArrayOf(0x4a, 0x02, 0x08, 0x7b), response)
    }

    @Test fun `configure and active messages are answered during negotiation`() {
        assertTrue(Protocol.hasField(Protocol.buildRemoteResponse(byteArrayOf(0x0a, 0x00))!!, 1))
        assertTrue(Protocol.hasField(Protocol.buildRemoteResponse(byteArrayOf(0x12, 0x00))!!, 2))
        assertNull(Protocol.buildRemoteResponse(byteArrayOf(0x52, 0x00)))
    }

    @Test fun `pairing success requires explicit OK status and expected ack`() {
        val ok = byteArrayOf(0x08, 0x02, 0x10, 0xc8.toByte(), 0x01, 0xca.toByte(), 0x02, 0x00)
        assertTrue(Protocol.isSuccessfulPairingReply(ok, Protocol.FIELD_SECRET_ACK))
        assertFalse(Protocol.isSuccessfulPairingReply(byteArrayOf(), Protocol.FIELD_SECRET_ACK))
        assertFalse(Protocol.isSuccessfulPairingReply(ok, Protocol.FIELD_CONFIGURATION_ACK))
        val rejected = ok.copyOf().also { it[3] = 0x92.toByte(); it[4] = 0x03 }
        assertFalse(Protocol.isSuccessfulPairingReply(rejected, Protocol.FIELD_SECRET_ACK))
    }

    @Test fun `concurrent packet writes never interleave header and payload`() {
        val headerWritten = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val bytes = ByteArrayOutputStream()
        val output = object : java.io.OutputStream() {
            override fun write(value: Int) = synchronized(bytes) { bytes.write(value) }
            override fun write(data: ByteArray) {
                synchronized(bytes) { bytes.write(data) }
                if (Thread.currentThread().name == "first" && data.size == 1) {
                    headerWritten.countDown()
                    secondStarted.await(1, TimeUnit.SECONDS)
                    Thread.sleep(50)
                }
            }
        }
        val first = thread(name = "first") { Protocol.writePacket(output, byteArrayOf(11, 12)) }
        assertTrue(headerWritten.await(2, TimeUnit.SECONDS))
        val second = thread {
            secondStarted.countDown()
            Protocol.writePacket(output, byteArrayOf(21, 22))
        }
        first.join(2000)
        second.join(2000)
        assertArrayEquals(byteArrayOf(2, 11, 12, 2, 21, 22), bytes.toByteArray())
    }
}
