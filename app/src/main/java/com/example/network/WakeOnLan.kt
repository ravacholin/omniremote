package com.example.network

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.example.data.model.DeviceType
import com.example.data.model.TvDeviceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class PowerOnResult(
    val success: Boolean,
    val resolvedMac: String?,
    val packetsSent: Int,
    val protocolsTriggered: List<String>,
    val message: String
)

/**
 * Sends standard WoL packets to local broadcasts and the saved TV address.
 * MAC discovery must run while the TV is reachable. Standby network wake also
 * requires support and the appropriate power/network settings on the TV.
 * A successful UDP send confirms transmission, not that the TV woke up.
 */
object WakeOnLan {

    private const val TAG = "TvPowerManager"

    private val fastHttpClient = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .build()

    private val insecureHttpClient: OkHttpClient by lazy {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, SecureRandom())
        OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(1500, TimeUnit.MILLISECONDS)
            .readTimeout(1500, TimeUnit.MILLISECONDS)
            .build()
    }

    /**
     * Normalizes and validates a MAC address string into standard "XX:XX:XX:XX:XX:XX" uppercase.
     * Returns null if invalid or dummy (e.g. all 0s or all Fs).
     */
    fun formatMacAddress(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val clean = raw.trim().uppercase().replace("-", ":")
        val hexOnly = clean.replace(":", "").replace(".", "")
        if (hexOnly.length == 12 && hexOnly.matches(Regex("^[0-9A-F]{12}$"))) {
            if (hexOnly == "000000000000" || hexOnly == "FFFFFFFFFFFF" || hexOnly == "020000000000") return null
            return hexOnly.chunked(2).joinToString(":")
        }
        return null
    }

    /**
     * Resolves the hardware MAC address of a target IP from the device's ARP cache.
     */
    fun resolveMacFromArp(ipAddress: String): String? {
        val cleanIp = ipAddress.trim()
        try {
            // Send quick UDP/TCP packet to warm kernel ARP resolution
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(cleanIp, 80), 120)
                }
            } catch (_: Exception) {}

            val arpEntries = File("/proc/net/arp")
            if (arpEntries.exists() && arpEntries.canRead()) {
                var foundMac: String? = null
                arpEntries.forEachLine { line ->
                    if (foundMac == null) {
                        val tokens = line.split(Regex("\\s+"))
                        if (tokens.size >= 4 && tokens[0] == cleanIp) {
                            val candidateMac = formatMacAddress(tokens[3])
                            if (candidateMac != null) {
                                Log.d(TAG, "Resolved MAC for $cleanIp from ARP table: $candidateMac")
                                foundMac = candidateMac
                            }
                        }
                    }
                }
                if (foundMac != null) return foundMac
            }
        } catch (e: Exception) {
            Log.d(TAG, "ARP resolution notice: ${e.message}")
        }
        return null
    }

    /**
     * Queries the TV directly over the local network to extract its physical MAC address
     * using vendor REST APIs while the TV is responsive.
     */
    suspend fun fetchMacFromTv(ipAddress: String, deviceType: DeviceType? = null): String? = withContext(Dispatchers.IO) {
        val cleanIp = ipAddress.trim()
        if (cleanIp.isBlank()) return@withContext null

        // 1. Samsung Tizen API: Port 8001 (HTTP)
        if (deviceType == null || deviceType == DeviceType.SAMSUNG_TIZEN) {
            try {
                val req = Request.Builder()
                    .url("http://$cleanIp:8001/api/v2/")
                    .build()
                fastHttpClient.newCall(req).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        val mac = extractMacFromSamsungJson(body)
                        if (mac != null) {
                            Log.d(TAG, "Resolved MAC for $cleanIp from Samsung API 8001: $mac")
                            return@withContext mac
                        }
                    }
                }
            } catch (_: Exception) {}

            // Samsung Tizen API: Port 8002 (HTTPS)
            try {
                val req = Request.Builder()
                    .url("https://$cleanIp:8002/api/v2/")
                    .build()
                insecureHttpClient.newCall(req).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        val mac = extractMacFromSamsungJson(body)
                        if (mac != null) {
                            Log.d(TAG, "Resolved MAC for $cleanIp from Samsung API 8002: $mac")
                            return@withContext mac
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // 2. Google TV / Android TV / Google Cast setup API: Port 8008 (HTTP)
        if (deviceType == null || deviceType == DeviceType.ANDROID_TV) {
            try {
                val req = Request.Builder()
                    .url("http://$cleanIp:8008/setup/eureka_info?params=name,device_info")
                    .build()
                fastHttpClient.newCall(req).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        val mac = extractMacFromCastJson(body)
                        if (mac != null) {
                            Log.d(TAG, "Resolved MAC for $cleanIp from Cast API 8008: $mac")
                            return@withContext mac
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // 3. ARP fallback (if readable)
        val fromArp = resolveMacFromArp(cleanIp)
        if (fromArp != null) {
            return@withContext fromArp
        }

        null
    }

    /**
     * Extracts physical MAC address from Samsung REST API JSON response.
     */
    fun extractMacFromSamsungJson(body: String): String? {
        try {
            val json = JSONObject(body)
            val device = json.optJSONObject("device")
            val candidates = listOf(
                device?.optString("wifiMac"),
                device?.optString("mac"),
                device?.optString("wiredMac"),
                device?.optString("ethernetMac"),
                json.optString("wifiMac"),
                json.optString("mac")
            )
            for (cand in candidates) {
                val formatted = formatMacAddress(cand)
                if (formatted != null) return formatted
            }
        } catch (_: Exception) {}
        return null
    }

    /**
     * Extracts physical MAC address from Google Cast eureka_info JSON response.
     */
    fun extractMacFromCastJson(body: String): String? {
        try {
            val json = JSONObject(body)
            val deviceInfo = json.optJSONObject("device_info")
            val candidates = listOf(
                deviceInfo?.optString("mac_address"),
                deviceInfo?.optString("wifi_mac_address"),
                deviceInfo?.optString("ethernet_mac_address"),
                json.optString("mac_address")
            )
            for (cand in candidates) {
                val formatted = formatMacAddress(cand)
                if (formatted != null) return formatted
            }
        } catch (_: Exception) {}
        return null
    }

    /**
     * Generates a standard Wake-on-LAN Magic Packet payload (6x 0xFF + 16x MAC).
     */
    fun createMagicPacketBytes(macAddress: String): ByteArray {
        val cleanMac = formatMacAddress(macAddress)
            ?: throw IllegalArgumentException("Invalid MAC address: $macAddress")
        val hexOnly = cleanMac.replace(":", "")

        val macBytes = ByteArray(6)
        for (i in 0 until 6) {
            macBytes[i] = hexOnly.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }

        val packetBytes = ByteArray(6 + 16 * 6)
        for (i in 0 until 6) {
            packetBytes[i] = 0xFF.toByte()
        }
        for (i in 0 until 16) {
            System.arraycopy(macBytes, 0, packetBytes, 6 + i * 6, 6)
        }
        return packetBytes
    }

    /**
     * Sends a Wake-on-LAN magic packet burst across multiple addresses, interfaces, and ports.
     * Uses repeated transmissions to improve delivery on local networks.
     */
    suspend fun sendMagicPacketBurst(
        macAddress: String,
        targetIp: String? = null,
        subnetPrefix: String? = null,
        context: Context? = null,
        count: Int = 12,
        intervalMs: Long = 50
    ): Int = withContext(Dispatchers.IO) {
        val cleanMac = formatMacAddress(macAddress)
        if (cleanMac == null) {
            Log.e(TAG, "Cannot send WoL packet: invalid MAC address '$macAddress'")
            return@withContext 0
        }

        val payload = try {
            createMagicPacketBytes(cleanMac)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create magic packet: ${e.message}")
            return@withContext 0
        }

        // Build list of target destinations:
        val targetAddresses = mutableSetOf<InetAddress>()

        // 1. All active network interface broadcast addresses (192.168.1.255, 10.0.0.255, etc.)
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val netIf = interfaces.nextElement()
                if (netIf.isLoopback || !netIf.isUp) continue
                for (interfaceAddress in netIf.interfaceAddresses) {
                    interfaceAddress.broadcast?.let { targetAddresses.add(it) }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Network interface enumeration notice: ${e.message}")
        }

        // 2. Global Broadcast 255.255.255.255 (standard WoL)
        try {
            targetAddresses.add(InetAddress.getByName("255.255.255.255"))
        } catch (_: Exception) {}

        // 3. Subnet Broadcast if provided
        if (!subnetPrefix.isNullOrBlank()) {
            val cleanPrefix = if (subnetPrefix.endsWith(".")) subnetPrefix else "$subnetPrefix."
            try {
                targetAddresses.add(InetAddress.getByName("${cleanPrefix}255"))
            } catch (_: Exception) {}
        }

        // 4. Also try the last known IP; sleeping hosts may no longer be in the ARP cache.
        if (!targetIp.isNullOrBlank()) {
            try {
                targetAddresses.add(InetAddress.getByName(targetIp.trim()))
            } catch (_: Exception) {}
        }

        val ports = listOf(9, 7)
        var packetsSent = 0

        // Retain multicast reception while performing the wake operation.
        val wifiManager = context?.applicationContext?.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val multicastLock = try {
            wifiManager?.createMulticastLock("wol_wake_multicast_lock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
            null
        }

        try {
            repeat(count) {
                try {
                    DatagramSocket().use { socket ->
                        socket.broadcast = true
                        for (addr in targetAddresses) {
                            for (port in ports) {
                                try {
                                    val packet = DatagramPacket(payload, payload.size, addr, port)
                                    socket.send(packet)
                                    packetsSent++
                                } catch (_: Exception) {}
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Socket transmission exception: ${e.message}")
                }
                if (intervalMs > 0) delay(intervalMs)
            }
        } finally {
            try {
                if (multicastLock?.isHeld == true) {
                    multicastLock.release()
                }
            } catch (_: Exception) {}
        }

        Log.d(TAG, "Sent $packetsSent Wake-on-LAN packets for MAC $cleanMac to targets: $targetAddresses")
        packetsSent
    }

    /**
     * Complete multi-vector power-on execution for any TV device.
     */
    suspend fun powerOnTv(
        device: TvDeviceEntity,
        localSubnet: String? = null,
        context: Context? = null
    ): PowerOnResult = withContext(Dispatchers.IO) {
        val triggeredProtocols = mutableListOf<String>()
        var totalPackets = 0

        // 1. Resolve MAC address if missing
        var effectiveMac = formatMacAddress(device.macAddress)
        if (effectiveMac == null) {
            // Attempt live network fetch
            val fromNet = fetchMacFromTv(device.ipAddress, device.deviceType)
            if (fromNet != null) {
                effectiveMac = fromNet
                Log.d(TAG, "Found MAC via Network API for ${device.name}: $effectiveMac")
            }
        }

        // 2. If MAC is still missing, we cannot construct the WoL magic packet
        if (effectiveMac == null) {
            val warning = "No se puede encender: falta la dirección MAC de la TV. Ingrésala manualmente tocando 'Editar MAC' o enciende la TV con su control una vez para detectarla automáticamente."
            Log.w(TAG, warning)
            return@withContext PowerOnResult(
                success = false,
                resolvedMac = null,
                packetsSent = 0,
                protocolsTriggered = emptyList(),
                message = warning
            )
        }

        // 3. Transmit Wake-on-LAN & Wake-on-WLAN Burst
        val sent = sendMagicPacketBurst(
            macAddress = effectiveMac,
            targetIp = device.ipAddress,
            subnetPrefix = localSubnet,
            context = context,
            count = 12,
            intervalMs = 50
        )
        if (sent > 0) {
            totalPackets += sent
            triggeredProtocols.add("Wake-on-LAN (MAC: $effectiveMac)")
        }

        // 4. Brand-Specific Network Power Vectors
        when (device.deviceType) {
            DeviceType.SAMSUNG_TIZEN -> {
                // Samsung Tizen: Wake ping to HTTP API
                try {
                    val req = Request.Builder()
                        .url("http://${device.ipAddress}:8001/api/v2/")
                        .get()
                        .build()
                    fastHttpClient.newCall(req).execute().close()
                    triggeredProtocols.add("Samsung Wake HTTP Ping")
                } catch (_: Exception) {}

                // Samsung Tizen: Wake probe on port 8002
                try {
                    Socket().use { s ->
                        s.connect(InetSocketAddress(device.ipAddress, 8002), 400)
                    }
                    triggeredProtocols.add("Samsung Port 8002 Probe")
                } catch (_: Exception) {}
            }

            DeviceType.ANDROID_TV -> {
                // Android TV / Google TV: Google Cast Wake-on-Cast
                try {
                    val req = Request.Builder()
                        .url("http://${device.ipAddress}:8008/setup/eureka_info")
                        .get()
                        .build()
                    fastHttpClient.newCall(req).execute().close()
                    triggeredProtocols.add("Google Cast Wake-on-Cast (Port 8008)")
                } catch (_: Exception) {}

                // Sony BRAVIA IRCC Wakeup
                try {
                    val soapBody = """
                        <?xml version="1.0"?>
                        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                            <s:Body>
                                <u:X_SendIRCC xmlns:u="urn:schemas-sony-com:service:IRCC:1">
                                    <IRCCCode>AAAAAQAAAAEAAAAVAw==</IRCCCode>
                                </u:X_SendIRCC>
                            </s:Body>
                        </s:Envelope>
                    """.trimIndent()
                    val req = Request.Builder()
                        .url("http://${device.ipAddress}:80/sony/IRCC")
                        .addHeader("SOAPACTION", "\"urn:schemas-sony-com:service:IRCC:1#X_SendIRCC\"")
                        .addHeader("X-Auth-PSK", "0000")
                        .post(soapBody.toRequestBody("text/xml; charset=UTF-8".toMediaType()))
                        .build()
                    fastHttpClient.newCall(req).execute().close()
                    triggeredProtocols.add("Sony BRAVIA IRCC Wake")
                } catch (_: Exception) {}
            }

            // Computers wake from the magic packet alone ("Wake for network access" on a Mac,
            // "Allow this device to wake the computer" on a Windows network adapter).
            DeviceType.MACOS, DeviceType.WINDOWS -> Unit
        }

        val success = totalPackets > 0
        val message = if (success) {
            "Se enviaron $totalPackets paquetes Wake-on-LAN a ${device.name} (MAC: $effectiveMac). Esperando inicio de la TV..."
        } else {
            "No se pudo enviar ningún paquete Wake-on-LAN. Comprueba la conexión a la red local."
        }

        PowerOnResult(
            success = success,
            resolvedMac = effectiveMac,
            packetsSent = totalPackets,
            protocolsTriggered = triggeredProtocols,
            message = message
        )
    }

    /**
     * Legacy backward-compatible signature.
     */
    suspend fun sendMagicPacket(
        macAddress: String,
        broadcastIp: String = "255.255.255.255",
        port: Int = 9
    ): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val payload = createMagicPacketBytes(macAddress)
                val address = InetAddress.getByName(broadcastIp)
                val packet = DatagramPacket(payload, payload.size, address, port)
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    socket.send(packet)
                }
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
}
