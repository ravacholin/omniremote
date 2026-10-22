package com.example.network

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey

/**
 * Low-level protobuf wire protocol implementation for Google TV / Android TV Remote Service v2.
 * Based on Google TV Pairing Protocol (Polo v2) on port 6467 and Remote Control Protocol v2 on port 6466.
 */
object AndroidTvProtocol {

    const val PORT_PAIRING = 6467
    const val PORT_REMOTE = 6466
    const val APP_LINK_FEATURE = 512
    const val FEATURE_IME = 4
    // Ping, keys, text input, power, volume and app links. Voice is not implemented.
    const val REMOTE_FEATURES = 1 or 2 or FEATURE_IME or 32 or 64 or APP_LINK_FEATURE

    // RemoteDirection (port 6466)
    const val REMOTE_DIRECTION_UNKNOWN = 0
    const val REMOTE_DIRECTION_START_LONG = 1
    const val REMOTE_DIRECTION_END_LONG = 2
    const val REMOTE_DIRECTION_SHORT = 3

    // Direction aliases for AndroidTvClient compatibility
    const val KEY_DIRECTION_START = 1
    const val KEY_DIRECTION_END = 2
    const val KEY_DIRECTION_SHORT = 3

    // Polo Message Types (port 6467 outer message field numbers)
    const val FIELD_PROTOCOL_VERSION = 1
    const val FIELD_STATUS = 2
    const val FIELD_PAIRING_REQUEST = 10
    const val FIELD_PAIRING_REQUEST_ACK = 11
    const val FIELD_OPTIONS = 20
    const val FIELD_CONFIGURATION = 30
    const val FIELD_CONFIGURATION_ACK = 31
    const val FIELD_SECRET = 40
    const val FIELD_SECRET_ACK = 41

    // Polo Status Codes
    const val STATUS_OK = 200
    const val STATUS_ERROR = 400
    const val STATUS_BAD_CONFIGURATION = 401
    const val STATUS_BAD_SECRET = 402

    // Polo Encodings and Roles
    const val ENCODING_TYPE_HEXADECIMAL = 3
    const val ROLE_TYPE_INPUT = 1

    /**
     * Reads a varint-framed packet from the SSL input stream.
     */
    fun readPacket(input: InputStream): ByteArray {
        val length = readVarint(input)
        if (length <= 0 || length > 65536) {
            throw IllegalArgumentException("Invalid packet length: $length")
        }
        val buffer = ByteArray(length)
        var totalRead = 0
        while (totalRead < length) {
            val count = input.read(buffer, totalRead, length - totalRead)
            if (count < 0) throw EOFException("Unexpected EOF while reading packet")
            totalRead += count
        }
        return buffer
    }

    /**
     * Writes a varint-framed packet to the SSL output stream.
     */
    fun writePacket(output: OutputStream, payload: ByteArray) = synchronized(output) {
        val header = ByteArrayOutputStream()
        writeVarint(header, payload.size)
        output.write(header.toByteArray())
        output.write(payload)
        output.flush()
    }

    // --- Pairing Messages (Port 6467 - Google TV Polo v2 Protocol) ---

    /**
     * Builds step 1: PairingRequest inside Polo OuterMessage
     * OuterMessage:
     *   field 1 (protocol_version): 2
     *   field 2 (status): 200 (STATUS_OK)
     *   field 10 (pairing_request):
     *     field 1 (service_name): "atvremote"
     *     field 2 (client_name): clientName
     */
    fun buildPairingRequest(clientName: String = "OmniRemote", serviceName: String = "atvremote"): ByteArray {
        val reqInner = ByteArrayOutputStream()
        writeFieldString(reqInner, 1, serviceName)
        writeFieldString(reqInner, 2, clientName)

        val outer = ByteArrayOutputStream()
        writeFieldVarint(outer, FIELD_PROTOCOL_VERSION, 2)
        writeFieldVarint(outer, FIELD_STATUS, STATUS_OK)
        writeFieldBytes(outer, FIELD_PAIRING_REQUEST, reqInner.toByteArray())
        return outer.toByteArray()
    }

    /**
     * Builds step 2: Options inside Polo OuterMessage
     * OuterMessage:
     *   field 1 (protocol_version): 2
     *   field 2 (status): 200 (STATUS_OK)
     *   field 20 (options):
     *     field 1 (input_encodings):
     *       field 1 (type): 3 (ENCODING_TYPE_HEXADECIMAL)
     *       field 2 (symbol_length): 6
     *     field 3 (preferred_role): 1 (ROLE_TYPE_INPUT)
     */
    fun buildOptionsMessage(): ByteArray {
        val encStream = ByteArrayOutputStream()
        writeFieldVarint(encStream, 1, ENCODING_TYPE_HEXADECIMAL)
        writeFieldVarint(encStream, 2, 6)

        val optionsInner = ByteArrayOutputStream()
        writeFieldBytes(optionsInner, 1, encStream.toByteArray()) // input_encodings
        writeFieldVarint(optionsInner, 3, ROLE_TYPE_INPUT)        // preferred_role

        val outer = ByteArrayOutputStream()
        writeFieldVarint(outer, FIELD_PROTOCOL_VERSION, 2)
        writeFieldVarint(outer, FIELD_STATUS, STATUS_OK)
        writeFieldBytes(outer, FIELD_OPTIONS, optionsInner.toByteArray())
        return outer.toByteArray()
    }

    /**
     * Builds step 3: Configuration inside Polo OuterMessage
     * OuterMessage:
     *   field 1 (protocol_version): 2
     *   field 2 (status): 200 (STATUS_OK)
     *   field 30 (configuration):
     *     field 1 (encoding):
     *       field 1 (type): 3 (ENCODING_TYPE_HEXADECIMAL)
     *       field 2 (symbol_length): 6
     *     field 2 (client_role): 1 (ROLE_TYPE_INPUT)
     */
    fun buildPairingConfiguration(isNumericOrHex: Boolean = true): ByteArray {
        val encStream = ByteArrayOutputStream()
        writeFieldVarint(encStream, 1, ENCODING_TYPE_HEXADECIMAL)
        writeFieldVarint(encStream, 2, 6)

        val configInner = ByteArrayOutputStream()
        writeFieldBytes(configInner, 1, encStream.toByteArray()) // encoding
        writeFieldVarint(configInner, 2, ROLE_TYPE_INPUT)        // client_role

        val outer = ByteArrayOutputStream()
        writeFieldVarint(outer, FIELD_PROTOCOL_VERSION, 2)
        writeFieldVarint(outer, FIELD_STATUS, STATUS_OK)
        writeFieldBytes(outer, FIELD_CONFIGURATION, configInner.toByteArray())
        return outer.toByteArray()
    }

    /**
     * Computes the SHA-256 secret for Google TV pairing.
     * Takes RSA public modulus and exponent from client and server certificates,
     * plus the 4-character nonce from the 6-character user PIN code.
     *
     * Protocol formula:
     * h = sha256()
     * h.update(client_modulus_hex_bytes)
     * h.update(client_exponent_hex_bytes)
     * h.update(server_modulus_hex_bytes)
     * h.update(server_exponent_hex_bytes)
     * h.update(pin[2:]_hex_bytes)
     * result = h.digest()
     *
     * Checksum verification:
     * result[0] must equal the first byte in hex of the PIN: pin[0:2]
     */
    fun computePairingSecret(
        clientCert: X509Certificate,
        serverCert: X509Certificate,
        pinCode: String
    ): ByteArray {
        val cleanPin = pinCode.trim().uppercase()
        if (!cleanPin.matches(Regex("[0-9A-F]{6}"))) {
            throw IllegalArgumentException("El código debe tener exactamente 6 caracteres")
        }

        val clientRsa = clientCert.publicKey as? RSAPublicKey
            ?: throw IllegalArgumentException("Client certificate must have an RSA public key")
        val serverRsa = serverCert.publicKey as? RSAPublicKey
            ?: throw IllegalArgumentException("Server certificate must have an RSA public key")

        val clientModulusBytes = bigIntegerToHexBytes(clientRsa.modulus)
        val clientExponentBytes = bigIntegerToHexBytes(clientRsa.publicExponent, forceLeadingZeroForOdd = true)
        val serverModulusBytes = bigIntegerToHexBytes(serverRsa.modulus)
        val serverExponentBytes = bigIntegerToHexBytes(serverRsa.publicExponent, forceLeadingZeroForOdd = true)

        val nonceStr = cleanPin.substring(2)
        val nonceBytes = hexToBytes(nonceStr)

        val expectedFirstByte = cleanPin.substring(0, 2).toInt(16).toByte()

        // 1. Primary Polo formula: clientModulus, clientExponent, serverModulus, serverExponent, nonce
        val md = MessageDigest.getInstance("SHA-256")
        md.update(clientModulusBytes)
        md.update(clientExponentBytes)
        md.update(serverModulusBytes)
        md.update(serverExponentBytes)
        md.update(nonceBytes)
        val digest = md.digest()

        if (digest[0] == expectedFirstByte) {
            return digest
        }

        throw IllegalArgumentException("El código no coincide con esta TV. Verifica los 6 caracteres de la pantalla.")
    }

    /**
     * Builds step 4: Secret inside Polo OuterMessage
     * OuterMessage:
     *   field 1 (protocol_version): 2
     *   field 2 (status): 200 (STATUS_OK)
     *   field 40 (secret):
     *     field 1 (secret): 32-byte sha256 hash
     */
    fun buildPairingSecret(secretBytes: ByteArray): ByteArray {
        val secretInner = ByteArrayOutputStream()
        writeFieldBytes(secretInner, 1, secretBytes)

        val outer = ByteArrayOutputStream()
        writeFieldVarint(outer, FIELD_PROTOCOL_VERSION, 2)
        writeFieldVarint(outer, FIELD_STATUS, STATUS_OK)
        writeFieldBytes(outer, FIELD_SECRET, secretInner.toByteArray())
        return outer.toByteArray()
    }

    // --- Remote Control Messages (Port 6466) ---

    /** Builds RemoteConfigure using the RemoteDeviceInfo schema from remotemessage.proto. */
    fun buildRemoteConfigure(features: Int = REMOTE_FEATURES): ByteArray {
        val devInfo = ByteArrayOutputStream()
        writeFieldString(devInfo, 1, "OmniRemote")
        writeFieldString(devInfo, 2, "OmniRemote")
        writeFieldVarint(devInfo, 3, 1)
        writeFieldString(devInfo, 4, "1")
        writeFieldString(devInfo, 5, "atvremote")
        writeFieldString(devInfo, 6, "1.0.0")

        val config = ByteArrayOutputStream()
        writeFieldVarint(config, 1, features)
        writeFieldBytes(config, 2, devInfo.toByteArray())

        val remoteMsg = ByteArrayOutputStream()
        writeFieldBytes(remoteMsg, 1, config.toByteArray()) // field 1 = remote_configure
        return remoteMsg.toByteArray()
    }

    /**
     * Builds RemoteSetActive message:
     * RemoteMessage:
     *   field 2 (remote_set_active):
     *     field 1 (active): negotiated feature mask
     */
    fun buildRemoteSetActive(active: Int = REMOTE_FEATURES): ByteArray {
        val out = ByteArrayOutputStream()
        writeFieldVarint(out, 1, active)

        val remoteMsg = ByteArrayOutputStream()
        writeFieldBytes(remoteMsg, 2, out.toByteArray()) // field 2 = remote_set_active
        return remoteMsg.toByteArray()
    }

    /**
     * Builds RemoteKeyInject message:
     * RemoteMessage:
     *   field 10 (remote_key_inject):
     *     field 1 (key_code): keyCode
     *     field 2 (direction): direction (3 = SHORT)
     */
    fun buildRemoteKeyInject(keyCode: Int, direction: Int = REMOTE_DIRECTION_SHORT): ByteArray {
        val keyInject = ByteArrayOutputStream()
        writeFieldVarint(keyInject, 1, keyCode)
        writeFieldVarint(keyInject, 2, direction)

        val remoteMsg = ByteArrayOutputStream()
        writeFieldBytes(remoteMsg, 10, keyInject.toByteArray()) // field 10 = remote_key_inject
        return remoteMsg.toByteArray()
    }

    /** RemoteMessage field 90 contains RemoteAppLinkLaunchRequest (app_link = 1). */
    fun buildRemoteAppLinkLaunch(appLink: String): ByteArray {
        require(appLink.isNotBlank())
        val request = ByteArrayOutputStream()
        writeFieldString(request, 1, appLink)
        val message = ByteArrayOutputStream()
        writeFieldBytes(message, 90, request.toByteArray())
        return message.toByteArray()
    }

    /**
     * Builds RemotePingResponse message to keep remote socket alive
     */
    fun buildRemotePingResponse(val1: Int): ByteArray {
        val ping = ByteArrayOutputStream()
        writeFieldVarint(ping, 1, val1)

        val remoteMsg = ByteArrayOutputStream()
        writeFieldBytes(remoteMsg, 9, ping.toByteArray()) // field 9 = remote_ping_response
        return remoteMsg.toByteArray()
    }

    /** Native IME insertion, using the counters last reported by the TV. */
    fun buildRemoteImeBatchEdit(text: String, imeCounter: Int, fieldCounter: Int): ByteArray {
        require(text.isNotEmpty()) { "Text cannot be empty" }
        val value = ByteArrayOutputStream()
        // Android cursor offsets use UTF-16 code units (String.length), including for emoji.
        writeFieldVarint(value, 1, text.length - 1)
        writeFieldVarint(value, 2, text.length - 1)
        writeFieldString(value, 3, text)
        val edit = ByteArrayOutputStream()
        writeFieldVarint(edit, 1, 1) // insert
        writeFieldBytes(edit, 2, value.toByteArray())
        val batch = ByteArrayOutputStream()
        writeFieldVarint(batch, 1, imeCounter)
        writeFieldVarint(batch, 2, fieldCounter)
        writeFieldBytes(batch, 3, edit.toByteArray())
        return ByteArrayOutputStream().apply {
            writeFieldBytes(this, 21, batch.toByteArray())
        }.toByteArray()
    }

    fun buildRemoteResponse(packet: ByteArray, features: Int = REMOTE_FEATURES): ByteArray? = when {
        hasField(packet, 1) -> buildRemoteConfigure(features)
        hasField(packet, 2) -> buildRemoteSetActive(features)
        hasField(packet, 8) -> getFieldBytes(packet, 8)?.let { ping ->
            buildRemotePingResponse(getFieldVarint(ping, 1) ?: 0)
        }
        else -> null
    }

    fun isSuccessfulPairingReply(packet: ByteArray, expectedField: Int): Boolean =
        runCatching { validatePairingResponse(packet, expectedField) }.isSuccess

    // --- Message Parsing Helpers ---

    /**
     * Returns true if the protobuf bytes contain the specified field tag.
     */
    fun hasField(data: ByteArray, targetFieldNumber: Int): Boolean {
        var offset = 0
        while (offset < data.size) {
            val (tag, newOffset) = decodeVarint(data, offset)
            offset = newOffset
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07

            if (fieldNumber == targetFieldNumber) return true

            offset = skipField(data, offset, wireType)
            if (offset < 0) break
        }
        return false
    }

    /**
     * Reads a varint field value from protobuf bytes if present.
     */
    fun getFieldVarint(data: ByteArray, targetFieldNumber: Int): Int? {
        var offset = 0
        while (offset < data.size) {
            val (tag, newOffset) = decodeVarint(data, offset)
            offset = newOffset
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07

            if (fieldNumber == targetFieldNumber && wireType == 0) {
                val (value, _) = decodeVarint(data, offset)
                return value
            }

            offset = skipField(data, offset, wireType)
            if (offset < 0) break
        }
        return null
    }

    fun getFieldBytes(data: ByteArray, targetFieldNumber: Int): ByteArray? {
        var offset = 0
        while (offset < data.size) {
            val (tag, next) = decodeVarint(data, offset)
            offset = next
            if (tag ushr 3 == targetFieldNumber && tag and 7 == 2) {
                val (length, start) = decodeVarint(data, offset)
                require(length >= 0 && length <= data.size - start) { "Truncated protobuf field" }
                return data.copyOfRange(start, start + length)
            }
            offset = skipField(data, offset, tag and 7)
        }
        return null
    }

    fun validatePairingResponse(data: ByteArray, expectedField: Int) {
        val status = getFieldVarint(data, FIELD_STATUS)
        require(status == STATUS_OK) { "La TV rechazó la vinculación (estado $status)." }
        require(getFieldBytes(data, expectedField) != null) {
            "Respuesta de vinculación inesperada (paso $expectedField)."
        }
    }

    private fun decodeVarint(data: ByteArray, startOffset: Int): Pair<Int, Int> {
        var result = 0
        var shift = 0
        var offset = startOffset
        while (offset < data.size && shift < 32) {
            val b = data[offset++].toInt() and 0xFF
            require(shift != 28 || b and 0xF0 == 0) { "Protobuf varint overflow" }
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) return Pair(result, offset)
            shift += 7
        }
        throw IllegalArgumentException("Truncated protobuf varint")
    }

    private fun skipField(data: ByteArray, offset: Int, wireType: Int): Int {
        val end = when (wireType) {
            0 -> decodeVarint(data, offset).second
            1 -> offset + 8
            2 -> {
                val (length, start) = decodeVarint(data, offset)
                require(length >= 0 && length <= data.size - start) { "Truncated protobuf field" }
                start + length
            }
            5 -> offset + 4
            else -> throw IllegalArgumentException("Unsupported protobuf wire type $wireType")
        }
        require(end in offset..data.size) { "Truncated protobuf field" }
        return end
    }

    // --- Helper Utilities ---

    fun bigIntegerToHexBytes(value: BigInteger, forceLeadingZeroForOdd: Boolean = true): ByteArray {
        var hex = value.toString(16).uppercase()
        if (forceLeadingZeroForOdd && hex.length % 2 != 0) {
            hex = "0$hex"
        }
        return hexToBytes(hex)
    }

    fun hexToBytes(hex: String): ByteArray {
        val clean = if (hex.length % 2 != 0) "0$hex" else hex
        val bytes = ByteArray(clean.length / 2)
        for (i in bytes.indices) {
            val idx = i * 2
            bytes[i] = clean.substring(idx, idx + 2).toInt(16).toByte()
        }
        return bytes
    }

    private fun writeFieldVarint(out: ByteArrayOutputStream, fieldNumber: Int, value: Int) {
        val tag = (fieldNumber shl 3) or 0
        writeVarint(out, tag)
        writeVarint(out, value)
    }

    private fun writeFieldString(out: ByteArrayOutputStream, fieldNumber: Int, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeFieldBytes(out, fieldNumber, bytes)
    }

    private fun writeFieldBytes(out: ByteArrayOutputStream, fieldNumber: Int, bytes: ByteArray) {
        val tag = (fieldNumber shl 3) or 2
        writeVarint(out, tag)
        writeVarint(out, bytes.size)
        out.write(bytes)
    }

    fun writeVarint(out: ByteArrayOutputStream, value: Int) {
        var v = value
        while ((v and 0x7F.inv()) != 0) {
            out.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        out.write(v and 0x7F)
    }

    fun readVarint(input: InputStream): Int {
        var result = 0
        var shift = 0
        while (shift < 32) {
            val b = input.read()
            if (b == -1) throw EOFException("Unexpected EOF while reading varint")
            result = result or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) return result
            shift += 7
        }
        throw IllegalArgumentException("Varint too long")
    }
}
