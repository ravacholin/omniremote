package com.example.network

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class AndroidTvProtocolValidationTest {
    private fun response(status: Int?, field: Int): ByteArray = ByteArrayOutputStream().apply {
        if (status != null) {
            AndroidTvProtocol.writeVarint(this, 16)
            AndroidTvProtocol.writeVarint(this, status)
        }
        AndroidTvProtocol.writeVarint(this, (field shl 3) or 2)
        write(0)
    }.toByteArray()

    @Test fun rejectsMissingStatus() {
        assertThrows(IllegalArgumentException::class.java) {
            AndroidTvProtocol.validatePairingResponse(response(null, 41), 41)
        }
    }
    @Test fun rejectsErrorEvenWithExpectedMessage() {
        assertThrows(IllegalArgumentException::class.java) {
            AndroidTvProtocol.validatePairingResponse(response(402, 41), 41)
        }
    }
    @Test fun rejectsWrongHandshakeStage() {
        assertThrows(IllegalArgumentException::class.java) {
            AndroidTvProtocol.validatePairingResponse(response(200, 11), 31)
        }
    }
    @Test fun acceptsExpectedSuccessfulAck() {
        AndroidTvProtocol.validatePairingResponse(response(200, 31), 31)
    }
    @Test fun packetsRoundTripWithMultibyteLength() {
        val payload = ByteArray(300) { it.toByte() }
        val output = ByteArrayOutputStream()
        AndroidTvProtocol.writePacket(output, payload)
        assertArrayEquals(payload, AndroidTvProtocol.readPacket(ByteArrayInputStream(output.toByteArray())))
    }
}
