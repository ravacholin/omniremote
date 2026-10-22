package com.example.network

import android.util.Base64
import android.util.Log
import com.example.data.model.RemoteKey
import com.example.data.model.StreamingAppCatalog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.example.data.model.TvAppShortcut
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.security.SecureRandom
import com.example.data.local.TvTokenStore
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class SamsungTvClient(
    private val tokenStore: TvTokenStore? = null,
    private val onTokenReceived: ((String) -> Unit)? = null,
    private val onConnectionChanged: ((Boolean, String?) -> Unit)? = null,
    private val onWaitingAuthPrompt: (() -> Unit)? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var webSocket: WebSocket? = null
    private val appDiscoveryMutex = Mutex()
    @Volatile private var installedApps: List<SamsungInstalledApp> = emptyList()
    @Volatile private var pendingAppDiscovery: CompletableDeferred<List<SamsungInstalledApp>>? = null
    var isConnected = false
        private set

    private val connectionGeneration = java.util.concurrent.atomic.AtomicLong()

    private var currentIp: String = ""
    private var currentPort: Int = 8002
    private var currentToken: String = ""

    private val okHttpClient: OkHttpClient by lazy {
        createUnsafeOkHttpClient()
    }

    private fun createUnsafeOkHttpClient(): OkHttpClient {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, SecureRandom())

        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // No read timeout on persistent WebSockets
            .writeTimeout(10, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS) // Keepalive ping frames every 15s to keep TV connection continuously active
            .retryOnConnectionFailure(true)
            .build()
    }

    fun connect(ipAddress: String, port: Int = 8002, token: String = "") {
        disconnect()
        val generation = connectionGeneration.get()
        val sessionIp = ipAddress.trim()
        currentIp = sessionIp
        // Modern Samsung Smart TVs (Tizen 2016-2024) require secure WSS on port 8002.
        // Port 8001 is unencrypted and invalidates/conflicts with secure token sessions.
        currentPort = 8002

        // Resolve token: prioritize passed token, fallback to persistent store (by IP, ID, or last token)
        val resolvedToken = when {
            token.isNotBlank() -> token.trim()
            else -> tokenStore?.getSamsungToken(currentIp) ?: ""
        }
        currentToken = resolvedToken

        val appNameBase64 = Base64.encodeToString("OmniRemote".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val scheme = "wss"
        val tokenParam = if (currentToken.isNotBlank()) "&token=$currentToken" else ""
        val url = "$scheme://$currentIp:$currentPort/api/v2/channels/samsung.remote.control?name=$appNameBase64$tokenParam"

        Log.d("SamsungTvClient", "Connecting to Samsung TV at $currentIp:$currentPort (Has token: ${currentToken.isNotBlank()}, tokenLength: ${currentToken.length})")
        val request = Request.Builder().url(url).build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (generation != connectionGeneration.get()) return
                // Wait for ms.channel.connect before reporting an authorized session.
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (generation != connectionGeneration.get()) return

                try {
                    val json = JSONObject(text)
                    val event = json.optString("event")

                    // Extract token from multiple possible structures returned across Samsung Tizen versions
                    val receivedToken = extractTokenFromJson(json)
                    if (!receivedToken.isNullOrBlank()) {
                        Log.d("SamsungTvClient", "Received Samsung TV authorization token")
                        currentToken = receivedToken
                        tokenStore?.saveSamsungToken(sessionIp, receivedToken)
                        onTokenReceived?.invoke(receivedToken)
                        isConnected = true
                        onConnectionChanged?.invoke(true, null)
                    }

                    when (event) {
                        "ed.installedApp.get" -> {
                            installedApps = SamsungAppProtocol.installedApps(json)
                            pendingAppDiscovery?.complete(installedApps)
                        }
                        "ms.channel.connect" -> {
                            isConnected = true
                            webSocket.send(SamsungAppProtocol.requestApps())
                            onConnectionChanged?.invoke(true, null)
                        }
                        "ms.channel.unauthorized" -> {
                            isConnected = false
                            Log.w("SamsungTvClient", "Samsung TV requires authorization on TV screen")
                            onWaitingAuthPrompt?.invoke()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("SamsungTvClient", "Error parsing Samsung response", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (generation != connectionGeneration.get()) return
                isConnected = false
                Log.w("SamsungTvClient", "WebSocket failure on Samsung TV $currentIp:8002: ${t.message}")
                onConnectionChanged?.invoke(
                    false,
                    t.localizedMessage ?: "Failed to connect to Samsung TV ($currentIp:8002). Make sure TV is ON and on the same Wi-Fi."
                )
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (generation != connectionGeneration.get()) return
                isConnected = false
                onConnectionChanged?.invoke(false, "Connection closed: $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (generation != connectionGeneration.get()) return
                isConnected = false
                onConnectionChanged?.invoke(false, null)
            }
        })
    }

    private fun extractTokenFromJson(json: JSONObject): String? {
        // Direct data.token
        val dataObj = json.optJSONObject("data")
        var token = dataObj?.optString("token")
        if (!token.isNullOrBlank() && token != "null") return token

        // Root token
        token = json.optString("token")
        if (!token.isNullOrBlank() && token != "null") return token

        return null
    }

    fun disconnect() {
        connectionGeneration.incrementAndGet()
        installedApps = emptyList()
        pendingAppDiscovery?.complete(emptyList())
        pendingAppDiscovery = null
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        isConnected = false
    }

    fun sendKey(key: RemoteKey): Boolean {
        return when (key) {
            RemoteKey.PAUSE -> {
                // Ensure pause works on all Samsung Tizen apps (YouTube, Netflix, Prime, TV Plus)
                val ok1 = sendRawKey("KEY_PAUSE")
                val ok2 = sendRawKey("KEY_PLAYPAUSE")
                ok1 || ok2
            }
            RemoteKey.PLAY -> {
                val ok1 = sendRawKey("KEY_PLAY")
                val ok2 = sendRawKey("KEY_PLAYPAUSE")
                ok1 || ok2
            }
            RemoteKey.PLAY_PAUSE -> {
                val ok1 = sendRawKey("KEY_PLAYPAUSE")
                val ok2 = sendRawKey("KEY_PLAYBACK")
                ok1 || ok2
            }
            else -> sendRawKey(key.samsungKey)
        }
    }

    private fun sendRawKey(samsungKey: String): Boolean {
        val payload = JSONObject().apply {
            put("method", "ms.remote.control")
            put("params", JSONObject().apply {
                put("Cmd", "Click")
                put("DataOfCmd", samsungKey)
                put("Option", "false")
                put("TypeOfRemote", "SendRemoteKey")
            })
        }
        val sent = webSocket?.send(payload.toString()) ?: false
        if (!sent && currentIp.isNotBlank()) {
            // Reconnect if socket was dropped, always with port 8002 and persistent token
            connect(currentIp, 8002, currentToken)
        }
        return sent
    }

    suspend fun launchApp(ipAddress: String, shortcut: TvAppShortcut): Result<Unit> {
        return launchAppById(ipAddress, shortcut.samsungAppId)
    }

    suspend fun launchAppById(ipAddress: String, appId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            require(ipAddress == currentIp && isConnected) { "Conecta primero el televisor." }
            val generation = connectionGeneration.get()
            val definition = StreamingAppCatalog.apps.firstOrNull { it.samsungId == appId }
            val name = if (appId.startsWith("name:")) appId.removePrefix("name:") else definition?.name
            var installed = name?.let { SamsungAppProtocol.find(installedApps, it) }
            if (installed == null && appId.startsWith("name:")) {
                installed = appDiscoveryMutex.withLock {
                    SamsungAppProtocol.find(installedApps, name!!)?.let { return@withLock it }
                    val pending = CompletableDeferred<List<SamsungInstalledApp>>()
                    pendingAppDiscovery = pending
                    try {
                        check(webSocket?.send(SamsungAppProtocol.requestApps()) == true) { "No se pudo consultar las apps de la TV." }
                        val apps = withTimeoutOrNull(4000) { pending.await() }
                            ?: error("La TV no permite consultar sus apps instaladas; no se pudo identificar Flow.")
                        SamsungAppProtocol.find(apps, name)
                            ?: error("Flow no aparece entre las apps instaladas del televisor.")
                    } finally { if (pendingAppDiscovery === pending) pendingAppDiscovery = null }
                }
            }
            check(generation == connectionGeneration.get() && currentIp == ipAddress && isConnected) { "La conexión con la TV cambió." }
            val target = installed ?: SamsungInstalledApp(appId, name.orEmpty(), if (appId == "3201807016597") 1 else 2)
            if (webSocket?.send(SamsungAppProtocol.launch(target)) == true) return@withContext Result.success(Unit)
            // Legacy REST fallback: a rejected HTTP response must never report a successful launch.
            val request = Request.Builder().url("http://$ipAddress:8001/api/v2/applications/${target.id}")
                .post("".toRequestBody(null)).build()
            okHttpClient.newBuilder().readTimeout(4, TimeUnit.SECONDS).callTimeout(6, TimeUnit.SECONDS).build()
                .newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "La TV rechazó abrir la app (HTTP ${response.code})." }
                }
            Result.success(Unit)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Result.failure(e) }
    }
}
