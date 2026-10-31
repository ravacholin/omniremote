package com.example.network

import android.content.Context
import android.util.Log
import com.example.data.local.TvTokenStore
import com.example.data.model.RemoteKey
import com.example.data.model.StreamingAppCatalog
import com.example.data.model.TvAppShortcut
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket

/** Android TV Remote Service v2 client with mutual TLS pairing and a persistent control channel. */
class AndroidTvClient(
    private val context: Context? = null,
    private val tokenStore: TvTokenStore? = null,
    private val onConnectionChanged: ((Boolean, String?) -> Unit)? = null,
    private val onPairingPromptNeeded: ((String) -> Unit)? = null,
    private val remotePort: Int = AndroidTvProtocol.PORT_REMOTE,
    private val pairingPort: Int = AndroidTvProtocol.PORT_PAIRING,
    private val onCommandRejected: ((String) -> Unit)? = null
) {
    private val remoteConnectMutex = Mutex()
    private val appLaunchMutex = Mutex()
    private val powerOffMutex = Mutex()
    private data class PendingAppLaunch(val link: String, val accepted: CompletableDeferred<Boolean>)
    @Volatile private var pendingAppLaunch: PendingAppLaunch? = null
    private data class PendingPowerOff(val socket: SSLSocket, val confirmed: CompletableDeferred<Boolean>)
    @Volatile private var pendingPowerOff: PendingPowerOff? = null
    @Volatile private var remotePowerOn: Boolean? = null
    private val writeLock = Any()
    private var pairingIp: String = ""
    private var activeDeviceId: Long? = null
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
    @Volatile
    var isConnected: Boolean = false
        private set
    @Volatile
    var isPaired: Boolean = false
        private set

    var supportsRemoteV2: Boolean = false
        private set

    // Active Remote v2 SSL Socket connection on port 6466
    @Volatile
    private var remoteSocket: SSLSocket? = null
    @Volatile
    private var remoteOutput: OutputStream? = null
    @Volatile
    private var remoteInput: InputStream? = null
    private var remoteReaderJob: Job? = null
    @Volatile
    private var negotiatedFeatures = 0
    @Volatile
    private var imeCounters = 0 to 0

    // Pending pairing session
    @Volatile
    private var pairingSocket: SSLSocket? = null
    @Volatile
    private var serverCertForPairing: X509Certificate? = null

    suspend fun connect(ipAddress: String, port: Int = 6466, deviceId: Long? = null): Boolean = withContext(Dispatchers.IO) {
        closeRemoteSocket()
        closePairingSocket()
        activeIp = ipAddress.trim()
        activeDeviceId = deviceId?.takeIf { it > 0 }
        if (activeIp.isBlank()) return@withContext false
        activePort = remotePort
        val ports = coroutineScope {
            listOf(remotePort, pairingPort).map { p -> async { p to isPortOpen(activeIp, p, 2000) } }.awaitAll()
        }.filter { it.second }.map { it.first }
        currentCoroutineContext().ensureActive()
        supportsRemoteV2 = ports.isNotEmpty()
        isPaired = tokenStore?.isAndroidTvPaired(activeIp) ?: false
        if (!supportsRemoteV2) {
            onConnectionChanged?.invoke(false,
                "No se encontró Android TV Remote Service en $activeIp. En el FlowBox, comprueba que esté encendido y conectado a la misma red, y que Android TV Remote Service esté habilitado y actualizado.")
            return@withContext false
        }
        // An IP change does not revoke the saved mutual TLS identity.
        // Try authentication before requiring the user to pair again.
        initRemoteV2Connection(activeIp)
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

    /**
     * Connects persistent SSL control session on port 6466 for Google TV Remote v2.
     */
    private suspend fun initRemoteV2Connection(ip: String): Boolean = remoteConnectMutex.withLock {
        val deviceId = activeDeviceId
        withContext(Dispatchers.IO) {
            // Several rapid commands can observe a lost connection before the first
            // reconnect finishes. Reuse its result instead of tearing it down again.
            if (isConnected && remoteSocket != null && activeIp == ip) return@withContext true
            try {
                currentCoroutineContext().ensureActive()
                closeRemoteSocket()
                val sslContext = AndroidTvCertificateManager.getOrCreateSslContext(context)
                val socket = localNetwork.tlsSocket(sslContext, ip, remotePort, 3000)
                remoteSocket = socket // Also close sockets whose TLS handshake is still pending.
                socket.tcpNoDelay = true
                socket.soTimeout = 8000
                socket.startHandshake()
                val serverCertificate = socket.session.peerCertificates.first() as X509Certificate
                val serverKey = android.util.Base64.encodeToString(serverCertificate.publicKey.encoded, android.util.Base64.NO_WRAP)
                val savedServerKey = deviceId?.let { tokenStore?.getAndroidTvServerKey(it) }
                check(savedServerKey == null || savedServerKey == serverKey) {
                    "Cambió la identidad de la TV. Revisá su IP y volvé a vincularla si corresponde."
                }
                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                remoteOutput = out
                remoteInput = input
                var features = AndroidTvProtocol.REMOTE_FEATURES
                fun handle(packet: ByteArray) {
                    AndroidTvProtocol.getFieldBytes(packet, 1)?.let { configure ->
                        features = (AndroidTvProtocol.getFieldVarint(configure, 1) ?: 0) and AndroidTvProtocol.REMOTE_FEATURES
                        require(features and 2 != 0) { "La TV no permite enviar teclas." }
                        negotiatedFeatures = features
                    }
                    AndroidTvProtocol.getFieldBytes(packet, 21)?.let { batch ->
                        if (remoteSocket === socket) {
                            imeCounters = (AndroidTvProtocol.getFieldVarint(batch, 1) ?: 0) to
                                (AndroidTvProtocol.getFieldVarint(batch, 2) ?: 0)
                        }
                    }
                    AndroidTvProtocol.getFieldBytes(packet, 40)?.let { start ->
                        synchronized(writeLock) {
                            if (remoteSocket === socket) {
                                // RemoteStart.started is also the device's power status. An omitted bool is false.
                                remotePowerOn = (AndroidTvProtocol.getFieldVarint(start, 1) ?: 0) != 0
                                if (remotePowerOn == false) {
                                    pendingPowerOff?.takeIf { it.socket === socket }?.confirmed?.complete(true)
                                }
                            }
                        }
                    }
                    AndroidTvProtocol.buildRemoteResponse(packet, features)?.let { writeRemotePacket(out, it) }
                    AndroidTvProtocol.getFieldBytes(packet, 3)?.let { error ->
                        // RemoteError describes a rejected command, not a broken TLS connection.
                        // During negotiation it still means that the session could not start.
                        check(isConnected) { "La TV rechazó el canal de control." }
                        val rejectedMessage = AndroidTvProtocol.getFieldBytes(error, 2)
                        val rejectedKey = rejectedMessage?.let { AndroidTvProtocol.getFieldBytes(it, 10) }
                        if (rejectedMessage == null || rejectedKey?.let { AndroidTvProtocol.getFieldVarint(it, 1) } == 26) {
                            pendingPowerOff?.takeIf { it.socket === socket }?.confirmed?.complete(false)
                        }
                        val rejectedApp = rejectedMessage?.let { AndroidTvProtocol.getFieldBytes(it, 90) }
                        val rejectedLink = rejectedApp?.let { AndroidTvProtocol.getFieldBytes(it, 1)?.toString(Charsets.UTF_8) }
                        val pending = pendingAppLaunch
                        if (pending != null && (rejectedLink == pending.link || rejectedMessage == null)) {
                            pending.accepted.complete(false)
                        } else {
                            Log.w(tag, "TV rejected a remote command; keeping the authenticated session")
                            onCommandRejected?.invoke(if (rejectedApp != null)
                                "La TV rechazó abrir la app. El mando sigue conectado."
                                else "La TV rechazó una orden. El mando sigue conectado.")
                        }
                    }
                }
                // The TV initiates configure -> active -> start. TLS alone is not readiness.
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    require(System.nanoTime() < deadline) { "La TV no inició el control remoto." }
                    val packet = AndroidTvProtocol.readPacket(input)
                    handle(packet)
                    if (AndroidTvProtocol.hasField(packet, 40)) break
                }
                socket.soTimeout = 0
                currentCoroutineContext().ensureActive()
                if (deviceId != null && tokenStore != null) {
                    check(tokenStore.verifyOrRememberAndroidTvServerKey(deviceId, serverKey)) {
                        "Cambió la identidad de la TV durante la conexión. Revisá su IP."
                    }
                }
                isPaired = true
                isConnected = true
                tokenStore?.setAndroidTvPaired(ip, true)
                onConnectionChanged?.invoke(true, null)
                remoteReaderJob = scope.launch {
                    try {
                        while (remoteSocket === socket) handle(AndroidTvProtocol.readPacket(input))
                    } catch (e: Exception) {
                        if (remoteSocket === socket) {
                            Log.w(tag, "Remote v2 reader failed; closing the control session", e)
                            closeRemoteSocket(socket, "Se perdió la conexión con la TV. Vuelve a conectar.")
                        }
                    }
                }
                true
            } catch (e: Exception) {
                closeRemoteSocket()
                currentCoroutineContext().ensureActive()
                Log.w(tag, "Remote v2 connection failed: ${e.message}")
                onConnectionChanged?.invoke(false, "No se pudo iniciar el control remoto: ${e.message}")
                if (e is javax.net.ssl.SSLHandshakeException) onPairingPromptNeeded?.invoke(ip)
                false
            }
        }
    }

    private fun writeRemotePacket(out: OutputStream, payload: ByteArray) = synchronized(writeLock) {
        AndroidTvProtocol.writePacket(out, payload)
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
        Log.d(tag, "Initiating Google TV Remote pairing handshake on $targetIp:6467...")

        var sessionSocket: SSLSocket? = null
        try {
            closePairingSocket()
            closeRemoteSocket()
            // A failed retry must not erase existing authorization.
            isPaired = tokenStore?.isAndroidTvPaired(targetIp) ?: false
            activeIp = targetIp
            pairingIp = targetIp
            supportsRemoteV2 = true
            val sslContext = AndroidTvCertificateManager.getOrCreateSslContext(context)
            val socket = localNetwork.tlsSocket(sslContext, targetIp, pairingPort, 6000)
            sessionSocket = socket
            pairingSocket = socket
            socket.tcpNoDelay = true
            socket.soTimeout = 12000
            currentCoroutineContext().ensureActive()
            socket.startHandshake()
            currentCoroutineContext().ensureActive()
            check(pairingSocket === socket) { "Pairing session replaced" }
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
            currentCoroutineContext().ensureActive()
            check(pairingSocket === socket) { "Pairing session replaced" }
            AndroidTvProtocol.validatePairingResponse(reqAck, AndroidTvProtocol.FIELD_PAIRING_REQUEST_ACK)
            Log.d(tag, "Received PairingRequestAck (${reqAck.size} bytes)")

            // 3. Send Options
            val optionsMsg = AndroidTvProtocol.buildOptionsMessage()
            AndroidTvProtocol.writePacket(out, optionsMsg)
            Log.d(tag, "Sent Options (${optionsMsg.size} bytes)")

            // 4. Read Options from TV
            val tvOptions = AndroidTvProtocol.readPacket(inStream)
            currentCoroutineContext().ensureActive()
            check(pairingSocket === socket) { "Pairing session replaced" }
            AndroidTvProtocol.validatePairingResponse(tvOptions, AndroidTvProtocol.FIELD_OPTIONS)
            Log.d(tag, "Received TV Options (${tvOptions.size} bytes)")

            // 5. Send Configuration
            val configMsg = AndroidTvProtocol.buildPairingConfiguration(isNumericOrHex = true)
            AndroidTvProtocol.writePacket(out, configMsg)
            Log.d(tag, "Sent Configuration (${configMsg.size} bytes)")

            // 6. Read ConfigurationAck
            val configAck = AndroidTvProtocol.readPacket(inStream)
            currentCoroutineContext().ensureActive()
            check(pairingSocket === socket) { "Pairing session replaced" }
            AndroidTvProtocol.validatePairingResponse(configAck, AndroidTvProtocol.FIELD_CONFIGURATION_ACK)
            Log.d(tag, "Received ConfigurationAck (${configAck.size} bytes) -> TV displaying code on screen now!")

            // Handshake reached PIN prompt stage! TV is now displaying the 6-character code
            withContext(Dispatchers.Main) {
                onShowPinPrompt()
            }
        } catch (e: Exception) {
            Log.e(tag, "Pairing handshake failed on port 6467: ${e.message}", e)
            closePairingSocket(sessionSocket)
            currentCoroutineContext().ensureActive()
            withContext(Dispatchers.Main) {
                val userMsg = when {
                    e.message?.contains("EHOSTUNREACH") == true || e.message?.contains("No route to host") == true ->
                        "No hay ruta de red hacia $targetIp:$pairingPort. No se ha solicitado ningún código. Comprueba la IP actual de la TV y que el router permita comunicar los dispositivos (red de invitados/aislamiento Wi-Fi)."
                    e.message?.contains("Connection refused") == true ->
                        "La TV rechazó el puerto de vinculación 6467. Comprueba que Android TV Remote Service esté disponible y habilitado."
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
        val socket = pairingSocket ?: run {
            Log.e(tag, "No active pairing socket")
            return@withContext false
        }
        val targetIp = pairingIp
        val targetDeviceId = activeDeviceId
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

            val secretAck = AndroidTvProtocol.readPacket(inStream)
            AndroidTvProtocol.validatePairingResponse(secretAck, AndroidTvProtocol.FIELD_SECRET_ACK)
            if (pairingSocket !== socket) return@withContext false
            if (targetDeviceId != null) {
                // Pairing and remote ports need not share a server certificate.
                // Only a validated PIN ACK permits registering a replacement remote key.
                tokenStore?.forgetAndroidTvServerKeyAfterPairing(targetDeviceId)
            }
            closePairingSocket(socket)
            isPaired = true
            tokenStore?.setAndroidTvPaired(targetIp, true)
            // Pairing stays saved even if the control service temporarily cannot start.
            if (activeIp == targetIp) initRemoteV2Connection(targetIp)
            true
        } catch (e: Exception) {
            Log.e(tag, "Error submitting pairing secret: ${e.message}", e)
            closePairingSocket(socket)
            currentCoroutineContext().ensureActive()
            false
        }
    }

    private fun closeRemoteSocket(expected: SSLSocket? = remoteSocket, error: String? = null) {
        // Abort transport before taking the write monitor: close must unblock a stalled write.
        // Closing an old socket is harmless; its cleanup must never reset a replacement session.
        try { expected?.close() } catch (_: Exception) {}
        synchronized(writeLock) {
            if (remoteSocket !== expected) return
            remoteSocket = null
            pendingAppLaunch?.accepted?.complete(false)
            pendingPowerOff?.confirmed?.complete(false)
            remotePowerOn = null
            remoteOutput = null
            remoteInput = null
            negotiatedFeatures = 0
            imeCounters = 0 to 0
            isConnected = false
            remoteReaderJob?.cancel()
            remoteReaderJob = null
            // Publish before a replacement can finish its RemoteStart negotiation.
            if (error != null) onConnectionChanged?.invoke(false, error)
        }
    }

    @Synchronized
    private fun closePairingSocket(expected: SSLSocket? = pairingSocket) {
        if (pairingSocket !== expected) return
        val socket = pairingSocket
        pairingSocket = null
        serverCertForPairing = null
        pairingIp = ""
        try { socket?.close() } catch (_: Exception) {}
    }

    fun cancelPairing() = closePairingSocket()

    fun disconnect() {
        closeRemoteSocket()
        closePairingSocket()
        isConnected = false
        isPaired = false
        onConnectionChanged?.invoke(false, null)
    }

    /**
     * Dispatches a short keypress over the authenticated Remote v2 session.
     */
    suspend fun sendKey(key: RemoteKey, canSend: () -> Boolean = { true }): Boolean = withContext(Dispatchers.IO) {
        if (!canSend()) return@withContext false
        if (activeIp.isBlank() || !isPaired) return@withContext false
        if (!isConnected && !initRemoteV2Connection(activeIp)) return@withContext false
        try {
            val out = remoteOutput ?: return@withContext false
            // A scheduled command may be cancelled while authentication/reconnection waits.
            if (!canSend()) return@withContext false
            // A short press is one atomic frame; concurrent taps cannot interleave packets.
            writeRemotePacket(out, AndroidTvProtocol.buildRemoteKeyInject(key.androidKeyCode))
            true
        } catch (e: Exception) {
            closeRemoteSocket()
            onConnectionChanged?.invoke(false, "No se pudo enviar la tecla. Vuelve a conectar la TV.")
            false
        }
    }

    /**
     * Use the same POWER key as the working remote button, only from a known on state.
     * Some HDMI players ignore SLEEP (223). Never toggle an already sleeping device or
     * retry after an uncertain write: another POWER could wake it again.
     */
    suspend fun powerOff(canSend: () -> Boolean = { true }): Boolean = powerOffMutex.withLock {
        withContext(Dispatchers.IO) {
            if (!canSend() || activeIp.isBlank() || !isPaired) return@withContext false
            if (!isConnected && !initRemoteV2Connection(activeIp)) return@withContext false
            val socket = remoteSocket ?: return@withContext false
            val pending = PendingPowerOff(socket, CompletableDeferred())
            val commandContext = currentCoroutineContext()
            try {
                synchronized(writeLock) {
                    commandContext.ensureActive()
                    if (!canSend() || remoteSocket !== socket || !isConnected ||
                        negotiatedFeatures and 32 == 0) return@withContext false
                    when (remotePowerOn) {
                        false -> return@withContext true
                        null -> return@withContext false
                        true -> Unit
                    }
                    val out = remoteOutput ?: return@withContext false
                    pendingPowerOff = pending
                    writeRemotePacket(out, AndroidTvProtocol.buildRemoteKeyInject(RemoteKey.POWER.androidKeyCode))
                }
                withTimeoutOrNull(5_000) { pending.confirmed.await() } == true
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                Log.w(tag, "Could not confirm Android TV standby", error)
                false
            } finally {
                if (pendingPowerOff === pending) pendingPowerOff = null
            }
        }
    }

    suspend fun sendText(text: String): Boolean = withContext(Dispatchers.IO) {
        if (text.isEmpty() || activeIp.isBlank() || !isPaired) return@withContext false
        if (!isConnected && !initRemoteV2Connection(activeIp)) return@withContext false
        try {
            synchronized(writeLock) {
                if (!isConnected || negotiatedFeatures and AndroidTvProtocol.FEATURE_IME == 0) {
                    return@withContext false
                }
                val out = remoteOutput ?: return@withContext false
                val (imeCounter, fieldCounter) = imeCounters
                writeRemotePacket(out, AndroidTvProtocol.buildRemoteImeBatchEdit(text, imeCounter, fieldCounter))
            }
            true
        } catch (e: Exception) {
            closeRemoteSocket()
            onConnectionChanged?.invoke(false, "No se pudo enviar el texto. Vuelve a conectar la TV.")
            false
        }
    }

    /** Sends an app-link request over the authenticated Remote v2 session. */
    suspend fun launchAppLink(appLink: String): Boolean = withContext(Dispatchers.IO) {
        if (appLink.isBlank() || activeIp.isBlank() || !isPaired) return@withContext false
        if (!isConnected && !initRemoteV2Connection(activeIp)) return@withContext false
        if (negotiatedFeatures and AndroidTvProtocol.APP_LINK_FEATURE == 0) return@withContext false
        try {
            val out = remoteOutput ?: return@withContext false
            writeRemotePacket(out, AndroidTvProtocol.buildRemoteAppLinkLaunch(appLink))
            true
        } catch (e: Exception) {
            closeRemoteSocket()
            currentCoroutineContext().ensureActive()
            onConnectionChanged?.invoke(false, "Could not send the app launch request. Reconnect the TV.")
            false
        }
    }

    suspend fun launchApp(shortcut: TvAppShortcut): Boolean {
        return launchAppByPackage(shortcut.androidPackageName)
    }

    suspend fun launchAppByPackage(
        packageName: String,
        deepLinkUrl: String = "",
        onStoreOpened: (() -> Unit)? = null
    ): Boolean = appLaunchMutex.withLock { withContext(Dispatchers.IO) {
        if (!packageName.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))) return@withContext false
        if (isPaired && (isConnected || initRemoteV2Connection(activeIp)) && negotiatedFeatures and AndroidTvProtocol.APP_LINK_FEATURE != 0) {
            try {
                for (link in StreamingAppCatalog.androidLaunchLinks(packageName, deepLinkUrl)) {
                    if (!isConnected) return@withContext false
                    val out = remoteOutput ?: return@withContext false
                    val pending = PendingAppLaunch(link, CompletableDeferred())
                    pendingAppLaunch = pending
                    val accepted = try {
                        writeRemotePacket(out, AndroidTvProtocol.buildRemoteAppLinkLaunch(link))
                        // Remote Service returns errors, but has no positive launch acknowledgement.
                        withTimeoutOrNull(900) { pending.accepted.await() } != false
                    } finally { if (pendingAppLaunch === pending) pendingAppLaunch = null }
                    if (accepted) {
                        if (link.startsWith("https://play.google.com/store/apps/details?")) onStoreOpened?.invoke()
                        return@withContext true
                    }
                }
                return@withContext false
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                // Only a transport failure, not RemoteError, invalidates the session.
                closeRemoteSocket()
                onConnectionChanged?.invoke(false, "No se pudo enviar la orden a la TV. Vuelve a conectar.")
                return@withContext false
            }
        }
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


        launched
    } }
}
