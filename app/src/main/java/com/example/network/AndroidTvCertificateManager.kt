package com.example.network

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.example.data.local.TvTokenStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.Security
import java.security.spec.RSAPrivateCrtKeySpec
import java.security.spec.RSAPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

/**
 * Manages TLS identity credentials and certificates for Android TV Remote Service v2.
 * Google TV / Android TV Remote Service requires mutual TLS (client certificate authentication)
 * on ports 6467 (Pairing) and 6466 (Remote Control Service).
 */
object AndroidTvCertificateManager {

    private const val TAG = "AndroidTvCertManager"
    private const val ALIAS = "OmniRemoteClientCert"

    @Volatile
    private var cachedSslContext: SSLContext? = null
    @Volatile
    private var cachedCert: X509Certificate? = null
    @Volatile
    private var cachedKey: PrivateKey? = null

    private var cachedIdentityPersisted = false

    val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    @Synchronized
    fun getOrCreateSslContext(context: Context? = null): SSLContext {
        preparePersistentIdentity(context)
        cachedSslContext?.let { return it }

        val (cert, key) = getOrCreateKeyPairAndCertificate(context)
        cachedCert = cert
        cachedKey = key
        cachedIdentityPersisted = context != null

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        val inMemoryKeyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("client", key, "omni_password".toCharArray(), arrayOf(cert))
        }
        kmf.init(inMemoryKeyStore, "omni_password".toCharArray())

        val sslContext = SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, arrayOf<TrustManager>(trustAllManager), SecureRandom())
        }

        cachedSslContext = sslContext
        return sslContext
    }

    @Synchronized
    fun getClientCertificate(context: Context? = null): X509Certificate {
        preparePersistentIdentity(context)
        cachedCert?.let { return it }
        val (cert, key) = getOrCreateKeyPairAndCertificate(context)
        cachedKey = key
        cachedCert = cert
        cachedIdentityPersisted = context != null
        return cert
    }

    /** A contextless caller must not hide the disk identity from later production callers. */
    private fun preparePersistentIdentity(context: Context?) {
        if (context == null || cachedIdentityPersisted || cachedCert == null || cachedKey == null) return
        val store = TvTokenStore.getInstance(context)
        if (store.getCertDer() == null && store.getPrivateKeyPkcs8() == null) {
            store.saveCertData(Base64.encodeToString(cachedCert!!.encoded, Base64.NO_WRAP),
                Base64.encodeToString(cachedKey!!.encoded, Base64.NO_WRAP))
            cachedIdentityPersisted = true
        } else {
            cachedSslContext = null
            cachedCert = null
            cachedKey = null
        }
    }

    @Synchronized
    private fun getOrCreateKeyPairAndCertificate(context: Context?): Pair<X509Certificate, PrivateKey> {
        val existingCert = cachedCert
        val existingKey = cachedKey
        if (existingCert != null && existingKey != null) return existingCert to existingKey
        // 1. Check persistent token store first: ensures the EXACT same client certificate and private key
        // are used permanently across all launches, re-connections, and app restarts.
        if (context != null) {
            val tokenStore = TvTokenStore.getInstance(context)
            val certBase64 = tokenStore.getCertDer()
            val keyBase64 = tokenStore.getPrivateKeyPkcs8()
            if (certBase64 != null || keyBase64 != null) {
                // The RSA private key is the shared authorization identity. Never replace it
                // just because its certificate is damaged, missing, or outside its validity.
                val (key, publicKey) = try {
                    check(!keyBase64.isNullOrBlank()) { "Missing saved Android TV private key" }
                    val encoded = PKCS8EncodedKeySpec(Base64.decode(keyBase64, Base64.DEFAULT))
                    // Some Conscrypt versions expose imported CRT keys as plain RSA
                    // private keys. Ask providers for the key spec instead of casting.
                    Security.getProviders("KeyFactory.RSA").firstNotNullOfOrNull { provider ->
                        runCatching {
                            val factory = KeyFactory.getInstance("RSA", provider)
                            val privateKey = factory.generatePrivate(encoded)
                            val spec = factory.getKeySpec(privateKey, RSAPrivateCrtKeySpec::class.java)
                            privateKey to factory.generatePublic(RSAPublicKeySpec(spec.modulus, spec.publicExponent))
                        }.getOrNull()
                    } ?: error("No provider could restore the saved RSA key")
                } catch (e: Exception) {
                    throw IllegalStateException("Could not restore Android TV private identity; saved credentials were preserved", e)
                }
                val restored = runCatching {
                    val certBytes = Base64.decode(certBase64, Base64.DEFAULT)
                    val cert = CertificateFactory.getInstance("X.509")
                        .generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate
                    cert.checkValidity()
                    check(cert.publicKey.encoded.contentEquals(publicKey.encoded)) { "Saved certificate and key do not match" }
                    cert
                }.getOrNull()
                val certificate = restored ?: generateSelfSignedCertDer(KeyPair(publicKey, key)).also {
                    tokenStore.saveCertData(Base64.encodeToString(it.encoded, Base64.NO_WRAP), keyBase64!!)
                    Log.d(TAG, "Repaired client certificate using the existing RSA key")
                }
                return certificate to key
            }
        }

        // 2. Generate standard RSA 2048 keypair + Self-signed DER X.509 v3 Certificate
        val kpg = KeyPairGenerator.getInstance("RSA").apply {
            initialize(2048, SecureRandom())
        }
        val keyPair = kpg.generateKeyPair()
        val cert = generateSelfSignedCertDer(keyPair)

        // 3. Immediately persist into TvTokenStore so subsequent connects reuse this exact credential
        if (context != null) {
            try {
                val tokenStore = TvTokenStore.getInstance(context)
                val certB64 = Base64.encodeToString(cert.encoded, Base64.NO_WRAP)
                val keyB64 = Base64.encodeToString(keyPair.private.encoded, Base64.NO_WRAP)
                tokenStore.saveCertData(certB64, keyB64)
                Log.d(TAG, "Generated and permanently persisted new client TLS certificate and RSA key in TvTokenStore")
            } catch (e: Exception) {
                throw IllegalStateException("Could not persist client TLS identity", e)
            }
        }

        return Pair(cert, keyPair.private)
    }

    /**
     * Generates a fully compliant, self-signed X.509 v3 certificate in DER format without third-party dependencies.
     */
    private fun generateSelfSignedCertDer(keyPair: KeyPair): X509Certificate {
        val tbsStream = ByteArrayOutputStream()

        // 1. Version: [0] EXPLICIT INTEGER v3 (2) -> A0 03 02 01 02
        tbsStream.write(byteArrayOf(0xA0.toByte(), 0x03, 0x02, 0x01, 0x02))

        // 2. Serial Number: INTEGER
        val serialBytes = BigInteger.valueOf(System.currentTimeMillis()).toByteArray()
        writeDerElement(tbsStream, 0x02, serialBytes)

        // 3. Signature Algorithm: sha256WithRSAEncryption (1.2.840.113549.1.1.11)
        val sha256WithRsaOid = byteArrayOf(
            0x30, 0x0D,
            0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B,
            0x05, 0x00
        )
        tbsStream.write(sha256WithRsaOid)

        // 4. Issuer: CN=OmniRemote
        val issuerBytes = buildSimpleName("OmniRemote")
        tbsStream.write(issuerBytes)

        // 5. Validity: NotBefore (yesterday) to NotAfter (+25 years)
        val notBefore = Date(System.currentTimeMillis() - 86400000L)
        val notAfter = Date(System.currentTimeMillis() + 25L * 365 * 86400000L)
        val validityBytes = buildValidity(notBefore, notAfter)
        tbsStream.write(validityBytes)

        // 6. Subject: CN=OmniRemote
        tbsStream.write(issuerBytes)

        // 7. SubjectPublicKeyInfo (from keyPair.public.encoded)
        tbsStream.write(keyPair.public.encoded)

        val tbsBytes = tbsStream.toByteArray()
        val tbsSeq = wrapDerSequence(tbsBytes)

        // Sign TBSCertificate
        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(keyPair.private)
        sig.update(tbsSeq)
        val signatureValue = sig.sign()

        // Wrap signature as BIT STRING
        val bitStringStream = ByteArrayOutputStream()
        bitStringStream.write(0x00) // unused bits = 0
        bitStringStream.write(signatureValue)
        val sigBitString = wrapDerElement(0x03, bitStringStream.toByteArray())

        // Final Certificate: SEQUENCE { TBSCertificate, AlgorithmIdentifier, SignatureValue }
        val certStream = ByteArrayOutputStream()
        certStream.write(tbsSeq)
        certStream.write(sha256WithRsaOid)
        certStream.write(sigBitString)

        val finalDer = wrapDerSequence(certStream.toByteArray())
        val cf = CertificateFactory.getInstance("X.509")
        return cf.generateCertificate(ByteArrayInputStream(finalDer)) as X509Certificate
    }

    private fun buildSimpleName(cn: String): ByteArray {
        val cnBytes = cn.toByteArray(Charsets.UTF_8)
        val utf8String = wrapDerElement(0x0C, cnBytes)
        // OID for id-at-commonName = 2.5.4.3 -> 06 03 55 04 03
        val oidCn = byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03)
        val attrTypeAndVal = wrapDerSequence(oidCn + utf8String)
        val rdn = wrapDerElement(0x31, attrTypeAndVal) // SET
        return wrapDerSequence(rdn)
    }

    private fun buildValidity(notBefore: Date, notAfter: Date): ByteArray {
        // X.509 UTCTime only represents 1950–2049. A 25-year certificate now
        // ends after that range and must use GeneralizedTime for its expiry.
        fun encodeTime(date: Date): ByteArray {
            val year = SimpleDateFormat("yyyy", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(date).toInt()
            val utc = year in 1950..2049
            val formatter = SimpleDateFormat(if (utc) "yyMMddHHmmss'Z'" else "yyyyMMddHHmmss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            return wrapDerElement(if (utc) 0x17 else 0x18, formatter.format(date).toByteArray(Charsets.US_ASCII))
        }
        val nbElem = encodeTime(notBefore)
        val naElem = encodeTime(notAfter)
        return wrapDerSequence(nbElem + naElem)
    }

    private fun wrapDerSequence(contents: ByteArray): ByteArray {
        return wrapDerElement(0x30, contents)
    }

    private fun wrapDerElement(tag: Int, contents: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        writeDerLength(out, contents.size)
        out.write(contents)
        return out.toByteArray()
    }

    private fun writeDerElement(out: ByteArrayOutputStream, tag: Int, contents: ByteArray) {
        out.write(tag)
        writeDerLength(out, contents.size)
        out.write(contents)
    }

    private fun writeDerLength(out: ByteArrayOutputStream, length: Int) {
        if (length < 128) {
            out.write(length)
        } else if (length < 256) {
            out.write(0x81)
            out.write(length)
        } else if (length < 65536) {
            out.write(0x82)
            out.write((length shr 8) and 0xFF)
            out.write(length and 0xFF)
        } else {
            out.write(0x83)
            out.write((length shr 16) and 0xFF)
            out.write((length shr 8) and 0xFF)
            out.write(length and 0xFF)
        }
    }
}
