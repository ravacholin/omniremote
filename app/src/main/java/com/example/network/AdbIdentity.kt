package com.example.network

import android.content.Context
import android.util.AtomicFile
import android.util.Base64
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.security.spec.RSAKeyGenParameterSpec
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import javax.crypto.Cipher

/** A separate, stable ADB identity. Never backed up or mixed with Remote v2 keys. */
class AdbIdentity private constructor(private val keys: KeyPair) {
    fun signToken(token: ByteArray): ByteArray {
        require(token.size == 20) { "Invalid ADB authentication token" }
        // adbd supplies the SHA-1 digest itself. SHA1withRSA would hash it a second time.
        val digestInfo = byteArrayOf(0x30,0x21,0x30,0x09,0x06,0x05,0x2b,0x0e,0x03,0x02,0x1a,0x05,0x00,0x04,0x14)
        return Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
            init(Cipher.ENCRYPT_MODE, keys.private)
            doFinal(digestInfo + token)
        }
    }

    fun publicKeyPayload(): ByteArray {
        val key = keys.public as RSAPublicKey
        val n = key.modulus
        val word = BigInteger.ONE.shiftLeft(32)
        val n0inv = n.mod(word).modInverse(word).negate().mod(word).toInt()
        fun littleEndian(value: BigInteger): ByteArray = value.toByteArray().reversedArray().copyOf(256)
        // android_pubkey: modulus words, -1/n[0], n, R^2 mod n, exponent.
        val encoded = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(64).putInt(n0inv).put(littleEndian(n))
            .put(littleEndian(BigInteger.ONE.shiftLeft(4096).mod(n)))
            .putInt(key.publicExponent.toInt()).array()
        return (Base64.encodeToString(encoded, Base64.NO_WRAP) + " OmniRemote@android\u0000").toByteArray(Charsets.UTF_8)
    }

    companion object {
        fun generate(): AdbIdentity = AdbIdentity(KeyPairGenerator.getInstance("RSA").run {
            initialize(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4)); generateKeyPair()
        })

        @Synchronized fun load(context: Context): AdbIdentity {
            val file = AtomicFile(File(context.noBackupFilesDir, "adb_identity.pk8"))
            if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
                // A corrupt identity must produce an error, never silently rotate all authorizations.
                val privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(file.readFully())) as RSAPrivateKey
                require(privateKey.modulus.bitLength() == 2048)
                // Android providers need not expose CRT parameters after PKCS#8 import.
                // All identities we generate use the explicit F4 public exponent.
                val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(privateKey.modulus, RSAKeyGenParameterSpec.F4))
                return AdbIdentity(KeyPair(publicKey, privateKey))
            }
            val identity = generate()
            val stream = file.startWrite()
            try {
                stream.write(identity.keys.private.encoded)
                file.finishWrite(stream)
            } catch (error: Exception) {
                file.failWrite(stream); throw error
            }
            return identity
        }
    }
}
