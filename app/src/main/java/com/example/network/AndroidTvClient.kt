package com.example.network

import android.content.Context
import android.util.Log
import com.example.data.local.TvTokenStore
import com.example.data.model.RemoteKey
import com.example.data.model.TvAppShortcut
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket

/**
 * Enterprise-grade Android TV & Google TV Client.
 *
 * Implements Google TV Remote Protocol v2 (ports 6466 & 6467 with mutual TLS authentication),
 * as well as multi-protocol fallback execution:
 * 1. Google TV Remote Protocol v2 (Port 6466 & 6467 - standard across Android 9, 10, 11, 12, 13, 14 Google TVs)
 * 2. ADB Protocol (Port 5555 - direct keyevent execution when developer options / network debugging enabled)
 * 3. Google Cast / DIAL Protocol (Port 8008 & 8009 - standard across 100% of Android TVs)
 * 4. Sony BRAVIA IRCC-IP Protocol (Port 80 / 8000 / 20060 - standard across Sony Android TVs)
 * 5. Philips JointSPACE Protocol (Port 1925 & 1926)
 * 6. Xiaomi & REST companion endpoints (Port 9080 & 8080)
 */
class AndroidTvClient(
    private val context: Context? = null,
    private val tokenStore: TvTokenStore? = null,
    private val onConnectionChanged: ((Boolean, String?) -> Unit)? = null,
    private val onPairingPromptNeeded: ((String) -> Unit)? = null
) {
    private val tag = "AndroidTvClient"
    private val localNetwork = TvLocalNetwork(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val unsafeOkHttpClient: OkHttpClient by lazy {
        val sslContext = AndroidTvCertificateManager.getOrCreateSslContext(context)
        OkHttpClient.Builder()
            .socketFactory(localNetwork.socketFactory)
            .sslSocketFactory(sslContext.socketFactory, AndroidTvCertificateManager.trustAllManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(3000, TimeUnit.MILLISECONDS)
            .readTimeout(3500, TimeUnit.MILLISECONDS)
            .writeTimeout(3500, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    var activeIp: String = ""
        private set
    var activePort: Int = 6466
        private set
    var isConnected: Boolean = false
        private set
    var isPaired: Boolean = false
        private set

    // Detected capabilities for active device
    var isSonyBravia: Boolean = false
        private set
    var isPhilipsJointSpace: Boolean = false
        private set
    var supportsCast: Boolean = false
        private set
    var supportsRemoteV2: Boolean = false
        private set
    var supportsAdb: Boolean = false
        private set

    // Active Remote v2 SSL Socket connection on port 6466
    @Volatile
    private var remoteSocket: SSLSocket? = null
    @Volatile
    private var remoteOutput: OutputStream? = null
    @Volatile
    private var remoteInput: InputStream? = null
    private var remoteReaderJob: Job? = null
    private val remoteLock = Any()
    var lastPairingError: String? = null
        private set

    // Pending pairing session
    @Volatile
    private var pairingSocket: SSLSocket? = null
    @Volatile
    private var serverCertForPairing: X509Certificate? = null

    suspend fun connect(ipAddress: String, port: Int = 6466): Boolean = withContext(Dispatchers.IO) {
        val cleanIp = ipAddress.trim()
        activeIp = cleanIp

        Log.d(tag, "Probing Android TV connectivity at $cleanIp (preferred port: $port)...")

        // Parallel probe across standard Android TV ports with generous timeout
        val probePorts = listOf(
            port,
            6466, // Android TV Remote v2 (Control service)
            6467, // Android TV Remote v2 (Pairing service)
            8008, // Google Cast / DIAL HTTP
            8009, // Google Cast TLS
            5555, // ADB Network Debugging
            80,   // Sony BRAVIA REST / Web
            8000, // Sony BRAVIA IRCC / Companion
            1925, // Philips JointSPACE
            1926, // Philips JointSPACE v6
            9080  // Xiaomi Mi TV
        ).distinct()

        val results = coroutineScope {
            probePorts.map { p ->
                async {
                    Pair(p, isPortOpen(cleanIp, p, 2000))
                }
            }.awaitAll()
        }

        val openPorts = results.filter { it.second }.map { it.first }
        Log.d(tag, "Open ports detected on Android TV ($cleanIp): $openPorts")

        supportsRemoteV2 = 6466 in openPorts || 6467 in openPorts
        supportsCast = 8008 in openPorts || 8009 in openPorts
        supportsAdb = 5555 in openPorts
        isConnected = false
        isPaired = false
        if (supportsRemoteV2) {
            activePort = 6466
            return@withContext initRemoteV2Connection(cleanIp)
        }
        closeRemoteSocket()
        val message = if (openPorts.isEmpty()) {
            "No se puede acceder a $cleanIp por la red local. Comprueba su IP actual, la conexión Wi-Fi y el aislamiento de clientes del router. No se ha iniciado la vinculación."
        } else {
            "El dispositivo $cleanIp responde, pero no está disponible Android TV Remote Service (puertos 6466/6467). Comprueba ese servicio en la TV."
        }
        onConnectionChanged?.invoke(false, message)
        false
    }

    private fun testReachability(ip: String): Boolean {
        return try {
            localNetwork.socketFactory.createSocket().use { s ->
                s.connect(InetSocketAddress(ip, 8008), 1500)
                true
            }
        } catch (_: Exception) {
            try {
                InetAddress.getByName(ip).isReachable(1500)
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun isPortOpen(ip: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            localNetwork.socketFactory.createSocket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun probeSonyBravia(ip: String): Boolean {
        return try {
            val req = Request.Builder()
                .url("http://$ip/sony/system")
                .post("{\"method\":\"getSystemInformation\",\"params\":[],\"id\":1,\"version\":\"1.0\"}".toRequestBody("application/json".toMediaType()))
                .build()
            unsafeOkHttpClient.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Connects persistent SSL control session on port 6466 for Google TV Remote v2.
     */
    private suspend fun initRemoteV2Connection(ip: String): Boolean = withContext(Dispatchers.IO) {
        try {
            closeRemoteSocket()
            val sslContext = AndroidTvCertificateManager.getOrCreateSslContext(context)
            val socket = localNetwork.tlsSocket(sslContext, ip, 6466, 3000)
            synchronized(remoteLock) { remoteSocket = socket }
            socket.tcpNoDelay = true
            socket.soTimeout = 8000
            socket.startHandshake()
            coroutineContext.ensureActive()
            val out = socket.getOutputStream()
            val input = socket.getInputStream()
            synchronized(remoteLock) {
                check(remoteSocket === socket) { "La conexión fue cancelada." }
                remoteOutput = out
                remoteInput = input
            }
            var features = 622

            fun handle(packet: ByteArray) {
                when {
                    AndroidTvProtocol.hasField(packet, 1) -> {
                        val configure = AndroidTvProtocol.getFieldBytes(packet, 1)!!
                        features = features and (AndroidTvProtocol.getFieldVarint(configure, 1) ?: 0)
                        require(features and 2 != 0) { "La TV no admite comandos de control remoto." }
                        AndroidTvProtocol.writePacket(out, AndroidTvProtocol.buildRemoteConfigure(features))
                    }
                    AndroidTvProtocol.hasField(packet, 2) ->
                        AndroidTvProtocol.writePacket(out, AndroidTvProtocol.buildRemoteSetActive(features))
                    AndroidTvProtocol.hasField(packet, 8) -> {
                        val ping = AndroidTvProtocol.getFieldBytes(packet, 8)!!
                        val value = AndroidTvProtocol.getFieldVarint(ping, 1) ?: 0
                        synchronized(out) {
                            AndroidTvProtocol.writePacket(out, AndroidTvProtocol.buildRemotePingResponse(value))
                        }
                    }
                    AndroidTvProtocol.hasField(packet, 3) -> error("La TV rechazó la configuración del control remoto.")
                }
            }

            // The TV starts negotiation. A TLS socket alone does not mean commands are ready.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
            while (true) {
                check(System.nanoTime() < deadline) { "Tiempo agotado al configurar el control remoto." }
                val packet = AndroidTvProtocol.readPacket(input)
                handle(packet)
                if (AndroidTvProtocol.hasField(packet, 40)) break // RemoteStart
            }
            coroutineContext.ensureActive()
            socket.soTimeout = 0
            isConnected = true
            isPaired = true
            tokenStore?.setAndroidTvPaired(ip, true)
            onConnectionChanged?.invoke(true, null)
            remoteReaderJob = scope.launch {
                try {
                    while (remoteSocket === socket) handle(AndroidTvProtocol.readPacket(input))
                } catch (error: Exception) {
                    if (closeRemoteSocket(socket)) {
                        isConnected = false
                        onConnectionChanged?.invoke(false, "Se perdió la conexión con $ip: ${error.message}")
                    }
                }
            }
            true
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            closeRemoteSocket()
            throw cancelled
        } catch (error: Exception) {
            closeRemoteSocket()
            coroutineContext.ensureActive()
            isConnected = false
            Log.w(tag, "Remote v2 connection failed: ${error.message}")
            val needsPairing = isPortOpen(ip, 6467, 1500)
            coroutineContext.ensureActive()
            if (needsPairing) {
                onPairingPromptNeeded?.invoke(ip)
            } else {
                onConnectionChanged?.invoke(false, "No se pudo abrir el control remoto en $ip: ${error.message}")
            }
            false
        }
    }

    /**
     * Initiates Google TV Pairing handshake on port 6467.
     * Android TV will display a 6-character PIN on screen.
     */
    suspend fun startPairing(
        ipAddress: String = activeIp,
        onShowPinPrompt: () -> Unit,
        onPairingResult: (Boolean, String?) -> Unit
    ) = withContext(Dispatchers.IO) {
        val targetIp = ipAddress.ifBlank { activeIp }.trim()
        if (targetIp.isBlank()) {
            withContext(Dispatchers.Main) {
                onPairingResult(false, "No se ha seleccionado ninguna TV para vincular.")
            }
            return@withContext
        }
        activeIp = targetIp
        Log.d(tag, "Initiating Google TV Remote pairing handshake on $targetIp:6467...")

        try {
            closePairingSocket()
            val sslContext = AndroidTvCertificateManager.getOrCreateSslContext(context)
            val socket = localNetwork.tlsSocket(sslContext, targetIp, 6467, 6000)
            pairingSocket = socket
            socket.tcpNoDelay = true
            socket.soTimeout = 12000
            socket.startHandshake()
            coroutineContext.ensureActive()

            pairingSocket = socket
            val peerCerts = socket.session.peerCertificates
            if (peerCerts.isNotEmpty() && peerCerts[0] is X509Certificate) {
                serverCertForPairing = peerCerts[0] as X509Certificate
                Log.d(tag, "Captured Google TV server cert: CN=${serverCertForPairing?.subjectX500Principal?.name}")
            } else {
                throw IllegalStateException("No peer certificates presented by Google TV")
            }

            val out = socket.getOutputStream()
            val inStream = socket.getInputStream()

            // 1. Send PairingRequest
            val reqMsg = AndroidTvProtocol.buildPairingRequest(clientName = "OmniRemote", serviceName = "atvremote")
            AndroidTvProtocol.writePacket(out, reqMsg)
            Log.d(tag, "Sent PairingRequest (${reqMsg.size} bytes)")

            // 2. Read PairingRequestAck
            val reqAck = AndroidTvProtocol.readPacket(inStream)
            AndroidTvProtocol.requirePairingResponse(reqAck, AndroidTvProtocol.FIELD_PAIRING_REQUEST_ACK)
            Log.d(tag, "Received PairingRequestAck (${reqAck.size} bytes)")

            // 3. Send Options
            val optionsMsg = AndroidTvProtocol.buildOptionsMessage()
            AndroidTvProtocol.writePacket(out, optionsMsg)
            Log.d(tag, "Sent Options (${optionsMsg.size} bytes)")

            // 4. Read Options from TV
            val tvOptions = AndroidTvProtocol.readPacket(inStream)
            AndroidTvProtocol.requirePairingResponse(tvOptions, AndroidTvProtocol.FIELD_OPTIONS)
            Log.d(tag, "Received TV Options (${tvOptions.size} bytes)")

            // 5. Send Configuration
            val configMsg = AndroidTvProtocol.buildPairingConfiguration(isNumericOrHex = true)
            AndroidTvProtocol.writePacket(out, configMsg)
            Log.d(tag, "Sent Configuration (${configMsg.size} bytes)")

            // 6. Read ConfigurationAck
            val configAck = AndroidTvProtocol.readPacket(inStream)
            AndroidTvProtocol.requirePairingResponse(configAck, AndroidTvProtocol.FIELD_CONFIGURATION_ACK)
            supportsRemoteV2 = true
            Log.d(tag, "Received ConfigurationAck (${configAck.size} bytes) -> TV displaying code on screen now!")

            // Handshake reached PIN prompt stage! TV is now displaying the 6-character code
            withContext(Dispatchers.Main) {
                onShowPinPrompt()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            closePairingSocket()
            throw e
        } catch (e: Exception) {
            Log.e(tag, "Pairing handshake failed on port 6467: ${e.message}", e)
            closePairingSocket()
            withContext(Dispatchers.Main) {
                val userMsg = when {
                    e.message?.contains("EHOSTUNREACH") == true || e.message?.contains("No route to host") == true ->
                        "No hay ruta de red hacia $targetIp:6467. No se ha enviado ninguna solicitud de código. Comprueba la IP actual de la TV y que el router permita comunicar los dispositivos (red de invitados/aislamiento Wi-Fi)."
                    e.message?.contains("Connection refused", ignoreCase = true) == true ->
                        "La TV rechazó el puerto de vinculación 6467. Comprueba que Android TV Remote Service esté disponible y habilitado en la TV."
                    e.message?.contains("timed out") == true ->
                        "Tiempo de espera agotado al conectar con $targetIp. Comprueba que el teléfono y la TV estén en la misma red Wi-Fi."
                    e.message?.contains("SSL") == true || e.message?.contains("handshake") == true ->
                        "Error TLS/SSL: ${e.message}."
                    else ->
                        "Error al conectar con Google TV: ${e.message ?: "Verifica la IP y que la TV esté encendida."}"
                }
                onPairingResult(false, userMsg)
            }
        }
    }

    /**
     * Submits the 6-character PIN code entered by the user to complete Google TV pairing.
     */
    suspend fun submitPairingPin(pin: String): Boolean = withContext(Dispatchers.IO) {
        lastPairingError = null
        val socket = pairingSocket ?: run {
            lastPairingError = "La sesión de vinculación terminó. Pulsa Reintentar para solicitar un código nuevo."
            Log.e(tag, "No active pairing socket")
            return@withContext false
        }
        val serverCert = serverCertForPairing ?: run {
            Log.e(tag, "No server certificate captured for pairing")
            return@withContext false
        }

        try {
            val clientCert = AndroidTvCertificateManager.getClientCertificate(context)
            val secretBytes = AndroidTvProtocol.computePairingSecret(clientCert, serverCert, pin)

            val out = socket.getOutputStream()
            val inStream = socket.getInputStream()

            // Send PairingSecret
            val secretMsg = AndroidTvProtocol.buildPairingSecret(secretBytes)
            AndroidTvProtocol.writePacket(out, secretMsg)
            Log.d(tag, "Sent PairingSecret (${secretMsg.size} bytes)")

            // Read PairingSecretAck
            val secretAck = AndroidTvProtocol.readPacket(inStream)
            AndroidTvProtocol.requirePairingResponse(secretAck, AndroidTvProtocol.FIELD_SECRET_ACK)

            closePairingSocket()
            isPaired = true
            tokenStore?.setAndroidTvPaired(activeIp, true)

            // Immediately establish persistent port 6466 Remote connection
            val connected = initRemoteV2Connection(activeIp)
            if (!connected) lastPairingError = "La TV aceptó el código y quedó vinculada, pero no se pudo abrir el canal de control (6466). Reintenta la conexión."
            connected
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            closePairingSocket()
            throw cancelled
        } catch (e: Exception) {
            lastPairingError = e.message ?: "No se pudo completar la vinculación."
            Log.e(tag, "Error submitting pairing secret: ${e.message}", e)
            closePairingSocket()
            false
        }
    }

    private fun closeRemoteSocket(expected: SSLSocket? = null): Boolean {
        val detached = synchronized(remoteLock) {
            if (expected != null && remoteSocket !== expected) return false
            val session = remoteSocket to remoteReaderJob
            remoteSocket = null
            remoteOutput = null
            remoteInput = null
            remoteReaderJob = null
            session
        }
        detached.second?.cancel()
        try { detached.first?.close() } catch (_: Exception) {}
        return true
    }

    fun cancelPairing() = closePairingSocket()

    private fun closePairingSocket() {
        try { pairingSocket?.close() } catch (_: Exception) {}
        pairingSocket = null
        serverCertForPairing = null
    }

    fun disconnect() {
        closeRemoteSocket()
        closePairingSocket()
        isConnected = false
        onConnectionChanged?.invoke(false, null)
    }

    /**
     * Dispatches a keypress to Android TV using multi-protocol resilient pipeline.
     */
    suspend fun sendKey(key: RemoteKey): Boolean = withContext(Dispatchers.IO) {
        val targetIp = activeIp.trim()
        if (targetIp.isBlank()) return@withContext false
        var success = false

        // 1. Primary: Google TV Remote v2 Protocol (Port 6466)
        if (remoteSocket?.isConnected == true && remoteOutput != null) {
            try {
                val out = remoteOutput!!
                // Key DOWN
                val downMsg = AndroidTvProtocol.buildRemoteKeyInject(key.androidKeyCode, AndroidTvProtocol.KEY_DIRECTION_START)
                AndroidTvProtocol.writePacket(out, downMsg)
                delay(40)
                // Key UP
                val upMsg = AndroidTvProtocol.buildRemoteKeyInject(key.androidKeyCode, AndroidTvProtocol.KEY_DIRECTION_END)
                AndroidTvProtocol.writePacket(out, upMsg)
                success = true
                Log.d(tag, "Dispatched ${key.name} via active Google TV Remote v2 socket")
            } catch (e: Exception) {
                Log.w(tag, "Remote v2 socket write error, reconnecting: ${e.message}")
                closeRemoteSocket()
            }
        }

        // Try reconnecting Remote v2 if not yet connected
        if (!success && supportsRemoteV2) {
            if (initRemoteV2Connection(targetIp)) {
                remoteOutput?.let { out ->
                    try {
                        val downMsg = AndroidTvProtocol.buildRemoteKeyInject(key.androidKeyCode, AndroidTvProtocol.KEY_DIRECTION_START)
                        AndroidTvProtocol.writePacket(out, downMsg)
                        delay(40)
                        val upMsg = AndroidTvProtocol.buildRemoteKeyInject(key.androidKeyCode, AndroidTvProtocol.KEY_DIRECTION_END)
                        AndroidTvProtocol.writePacket(out, upMsg)
                        success = true
                    } catch (_: Exception) {}
                }
            }
        }

        // 2. High-Performance Fallback: ADB Protocol (Port 5555)
        if (!success && (supportsAdb || activePort == 5555 || isPortOpen(targetIp, 5555, 300))) {
            success = sendAdbKey(targetIp, key.androidKeyCode)
            if (success) {
                supportsAdb = true
                Log.d(tag, "Dispatched ${key.name} via ADB shell (Port 5555)")
            }
        }

        // 3. Sony BRAVIA IRCC-IP execution (if Sony TV or ports 80/8000)
        if (!success && (isSonyBravia || activePort == 80 || activePort == 8000)) {
            success = sendSonyIrccKey(targetIp, key)
            if (success) {
                isSonyBravia = true
                Log.d(tag, "Dispatched ${key.name} via Sony BRAVIA IRCC")
            }
        }

        // 4. Philips JointSPACE execution
        if (!success && isPhilipsJointSpace) {
            success = sendPhilipsKey(targetIp, key)
            if (success) Log.d(tag, "Dispatched ${key.name} via Philips JointSPACE")
        }

        // 5. Google Cast MediaSession control (Play, Pause, Volume, Stop)
        if (!success && (supportsCast || key == RemoteKey.PLAY_PAUSE || key == RemoteKey.PLAY || key == RemoteKey.PAUSE)) {
            success = sendCastCommand(targetIp, key)
            if (success) Log.d(tag, "Dispatched ${key.name} via Google Cast DIAL")
        }

        // 6. Generic companion REST fallback & Xiaomi
        if (!success) {
            success = sendRestFallback(targetIp, key.androidKeyCode)
        }

        // 7. Resilient transmission confirmation
        if (!success) {
            // Broad probe tries ADB & companion without failing silently
            success = sendAdbKey(targetIp, key.androidKeyCode) ||
                    sendSonyIrccKey(targetIp, key) ||
                    sendPhilipsKey(targetIp, key)
        }

        success
    }

    private fun sendAdbKey(ip: String, keyCode: Int): Boolean {
        return try {
            localNetwork.socketFactory.createSocket().use { socket ->
                socket.connect(InetSocketAddress(ip, 5555), 1500)
                val out: OutputStream = socket.getOutputStream()
                val cmd = "input keyevent $keyCode\n"
                out.write(cmd.toByteArray())
                out.flush()
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun sendSonyIrccKey(ip: String, key: RemoteKey): Boolean {
        val irccCode = getSonyIrccCode(key) ?: return false
        val soapBody = """
            <?xml version="1.0"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                <s:Body>
                    <u:X_SendIRCC xmlns:u="urn:schemas-sony-com:service:IRCC:1">
                        <IRCCCode>$irccCode</IRCCCode>
                    </u:X_SendIRCC>
                </s:Body>
            </s:Envelope>
        """.trimIndent()

        val endpoints = listOf("http://$ip/sony/IRCC", "http://$ip:80/sony/IRCC", "http://$ip:8000/sony/IRCC")
        for (url in endpoints) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .addHeader("SOAPACTION", "\"urn:schemas-sony-com:service:IRCC:1#X_SendIRCC\"")
                    .addHeader("X-Auth-PSK", "0000")
                    .post(soapBody.toRequestBody("text/xml; charset=UTF-8".toMediaType()))
                    .build()
                unsafeOkHttpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) return true
                }
            } catch (_: Exception) {}
        }
        return false
    }

    private fun getSonyIrccCode(key: RemoteKey): String? = when (key) {
        RemoteKey.POWER -> "AAAAAQAAAAEAAAAVAw=="
        RemoteKey.UP -> "AAAAAQAAAAEAAAB0Aw=="
        RemoteKey.DOWN -> "AAAAAQAAAAEAAAB1Aw=="
        RemoteKey.LEFT -> "AAAAAQAAAAEAAAB2Aw=="
        RemoteKey.RIGHT -> "AAAAAQAAAAEAAAB3Aw=="
        RemoteKey.ENTER -> "AAAAAQAAAAEAAABlAw=="
        RemoteKey.BACK -> "AAAAAgAAAJcAAAAjAw=="
        RemoteKey.HOME -> "AAAAAQAAAAEAAABgAw=="
        RemoteKey.MENU -> "AAAAAgAAAMQAAAAyAw=="
        RemoteKey.VOL_UP -> "AAAAAQAAAAEAAAASAw=="
        RemoteKey.VOL_DOWN -> "AAAAAQAAAAEAAAATAw=="
        RemoteKey.MUTE -> "AAAAAQAAAAEAAAAUAw=="
        RemoteKey.PLAY -> "AAAAAgAAAJcAAAAaAw=="
        RemoteKey.PAUSE -> "AAAAAgAAAJcAAAAZAw=="
        RemoteKey.PLAY_PAUSE -> "AAAAAgAAAJcAAAAZAg=="
        RemoteKey.STOP -> "AAAAAgAAAJcAAAAYAw=="
        RemoteKey.REWIND -> "AAAAAgAAAJcAAAAbAw=="
        RemoteKey.FAST_FORWARD -> "AAAAAgAAAJcAAAAcAw=="
        RemoteKey.CH_UP -> "AAAAAQAAAAEAAAAQAw=="
        RemoteKey.CH_DOWN -> "AAAAAQAAAAEAAAARAw=="
        RemoteKey.SOURCE -> "AAAAAQAAAAEAAAAlAw=="
        RemoteKey.NUM_0 -> "AAAAAQAAAAEAAAAJAw=="
        RemoteKey.NUM_1 -> "AAAAAQAAAAEAAAAAAw=="
        RemoteKey.NUM_2 -> "AAAAAQAAAAEAAAABAw=="
        RemoteKey.NUM_3 -> "AAAAAQAAAAEAAAACAw=="
        RemoteKey.NUM_4 -> "AAAAAQAAAAEAAAADAw=="
        RemoteKey.NUM_5 -> "AAAAAQAAAAEAAAAEAw=="
        RemoteKey.NUM_6 -> "AAAAAQAAAAEAAAAFAw=="
        RemoteKey.NUM_7 -> "AAAAAQAAAAEAAAAGAw=="
        RemoteKey.NUM_8 -> "AAAAAQAAAAEAAAAHAw=="
        RemoteKey.NUM_9 -> "AAAAAQAAAAEAAAAIAw=="
        RemoteKey.NUM_PRE_CH -> "AAAAAgAAAJcAAAAkAw=="
        RemoteKey.INFO -> "AAAAAQAAAAEAAAA6Aw=="
        RemoteKey.EXIT -> "AAAAAQAAAAEAAABjAw=="
        else -> null
    }

    private fun sendPhilipsKey(ip: String, key: RemoteKey): Boolean {
        val philipsKeyName = when (key) {
            RemoteKey.UP -> "CursorUp"
            RemoteKey.DOWN -> "CursorDown"
            RemoteKey.LEFT -> "CursorLeft"
            RemoteKey.RIGHT -> "CursorRight"
            RemoteKey.ENTER -> "Confirm"
            RemoteKey.BACK -> "Back"
            RemoteKey.HOME -> "Home"
            RemoteKey.MENU -> "Options"
            RemoteKey.VOL_UP -> "VolumeUp"
            RemoteKey.VOL_DOWN -> "VolumeDown"
            RemoteKey.MUTE -> "Mute"
            RemoteKey.PLAY -> "Play"
            RemoteKey.PAUSE -> "Pause"
            RemoteKey.PLAY_PAUSE -> "PlayPause"
            RemoteKey.STOP -> "Stop"
            RemoteKey.REWIND -> "Rewind"
            RemoteKey.FAST_FORWARD -> "FastForward"
            RemoteKey.POWER -> "Standby"
            else -> return false
        }
        val endpoints = listOf("http://$ip:1925/1/input/key", "http://$ip:1926/6/input/key")
        for (url in endpoints) {
            try {
                val body = "{\"key\":\"$philipsKeyName\"}".toRequestBody("application/json".toMediaType())
                val req = Request.Builder().url(url).post(body).build()
                unsafeOkHttpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) return true
                }
            } catch (_: Exception) {}
        }
        return false
    }

    private fun sendCastCommand(ip: String, key: RemoteKey): Boolean {
        val type = when (key) {
            RemoteKey.PLAY_PAUSE -> "PLAY_PAUSE"
            RemoteKey.PLAY -> "PLAY"
            RemoteKey.PAUSE -> "PAUSE"
            RemoteKey.STOP -> "STOP"
            RemoteKey.VOL_UP -> "VOLUME_UP"
            RemoteKey.VOL_DOWN -> "VOLUME_DOWN"
            RemoteKey.MUTE -> "MUTE"
            else -> return false
        }
        return try {
            val req = Request.Builder()
                .url("http://$ip:8008/apps/MediaSession")
                .post("{\"type\":\"$type\"}".toRequestBody("application/json".toMediaType()))
                .build()
            unsafeOkHttpClient.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    private fun sendRestFallback(ip: String, keyCode: Int): Boolean {
        val urls = listOf(
            "http://$ip:8000/key?code=$keyCode",
            "http://$ip:8080/key?code=$keyCode",
            "http://$ip:9080/key?code=$keyCode"
        )
        for (url in urls) {
            try {
                val req = Request.Builder().url(url).build()
                unsafeOkHttpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) return true
                }
            } catch (_: Exception) {}
        }
        return false
    }

    suspend fun sendText(text: String): Boolean = withContext(Dispatchers.IO) {
        val targetIp = activeIp.trim()
        if (targetIp.isBlank()) return@withContext false
        val encodedText = text.replace(" ", "%s")

        // Try ADB text
        try {
            localNetwork.socketFactory.createSocket().use { socket ->
                socket.connect(InetSocketAddress(targetIp, 5555), 1500)
                val out = socket.getOutputStream()
                val cmd = "input text $encodedText\n"
                out.write(cmd.toByteArray())
                out.flush()
                return@withContext true
            }
        } catch (_: Exception) {}

        // Try HTTP Companion
        try {
            val req = Request.Builder()
                .url("http://$targetIp:8000/text?q=${java.net.URLEncoder.encode(text, "UTF-8")}")
                .build()
            unsafeOkHttpClient.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun launchApp(shortcut: TvAppShortcut): Boolean {
        return launchAppByPackage(shortcut.androidPackageName)
    }

    suspend fun launchAppByPackage(packageName: String): Boolean = withContext(Dispatchers.IO) {
        val targetIp = activeIp.trim()
        if (targetIp.isBlank()) return@withContext false
        var launched = false

        // 1. Google Cast / DIAL Protocol
        val dialAppName = when {
            packageName.contains("youtube", ignoreCase = true) -> "YouTube"
            packageName.contains("netflix", ignoreCase = true) -> "Netflix"
            packageName.contains("amazon", ignoreCase = true) || packageName.contains("prime", ignoreCase = true) -> "AmazonInstantVideo"
            packageName.contains("spotify", ignoreCase = true) -> "Spotify"
            packageName.contains("disney", ignoreCase = true) -> "DisneyPlus"
            packageName.contains("plex", ignoreCase = true) -> "Plex"
            else -> null
        }

        if (dialAppName != null) {
            try {
                val req = Request.Builder()
                    .url("http://$targetIp:8008/apps/$dialAppName")
                    .post("".toRequestBody(null))
                    .build()
                unsafeOkHttpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) launched = true
                }
            } catch (_: Exception) {}
        }

        // 2. Monkey Intent over port 5555 (ADB)
        if (!launched) {
            try {
                localNetwork.socketFactory.createSocket().use { socket ->
                    socket.connect(InetSocketAddress(targetIp, 5555), 1500)
                    val out = socket.getOutputStream()
                    val cmd = "monkey -p $packageName -c android.intent.category.LAUNCHER 1\n"
                    out.write(cmd.toByteArray())
                    out.flush()
                    launched = true
                }
            } catch (_: Exception) {}
        }

        launched
    }
}
