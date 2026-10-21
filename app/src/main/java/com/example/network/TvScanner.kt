package com.example.network

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.example.data.model.DeviceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Locale
import java.util.concurrent.TimeUnit

data class DiscoveredTv(
    val name: String,
    val ipAddress: String,
    val deviceType: DeviceType,
    val port: Int,
    val modelInfo: String = "",
    val macAddress: String = ""
)

class TvScanner(private val context: Context) {

    private val localNetwork = TvLocalNetwork(context)

    private val httpClient = OkHttpClient.Builder()
        .socketFactory(localNetwork.socketFactory)
        .connectTimeout(1200, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .build()

    fun getLocalIpAddress(): String? {
        localNetwork.localIpv4()?.let { return it }
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val wifiInfo = wifiManager?.connectionInfo
            val ipInt = wifiInfo?.ipAddress ?: 0
            if (ipInt != 0) {
                val ip = String.format(
                    Locale.US,
                    "%d.%d.%d.%d",
                    ipInt and 0xff,
                    ipInt shr 8 and 0xff,
                    ipInt shr 16 and 0xff,
                    ipInt shr 24 and 0xff
                )
                if (ip != "0.0.0.0") return ip
            }


        } catch (e: Exception) {
            Log.e("TvScanner", "Failed to get local IP", e)
        }
        return null
    }

    fun getSubnetPrefix(localIp: String? = getLocalIpAddress()): String {
        if (localIp.isNullOrBlank()) return "192.168.1."
        val parts = localIp.split(".")
        return if (parts.size >= 3) {
            "${parts[0]}.${parts[1]}.${parts[2]}."
        } else {
            "192.168.1."
        }
    }

    suspend fun scanLocalNetwork(
        customSubnet: String? = null,
        onProgress: ((Int, Int) -> Unit)? = null,
        onDeviceFound: ((DiscoveredTv) -> Unit)? = null
    ): List<DiscoveredTv> = withContext(Dispatchers.IO) {
        val discoveredMap = java.util.concurrent.ConcurrentHashMap<String, DiscoveredTv>()

        // 1. SSDP / UPnP Multicast Search
        try {
            performSsdpDiscovery(discoveredMap, onDeviceFound)
        } catch (e: Exception) {
            Log.w("TvScanner", "SSDP discovery error: ${e.message}")
        }

        // 2. Active Subnet TCP Sweep on TV Ports (8001, 8002, 8008, 5555, 6466)
        val prefix = customSubnet?.trim()?.let {
            if (it.endsWith(".")) it else "$it."
        } ?: getSubnetPrefix(getLocalIpAddress() ?: return@withContext discoveredMap.values.toList())

        performSubnetSweep(prefix, discoveredMap, onProgress, onDeviceFound)

        discoveredMap.values.toList()
    }

    private fun performSsdpDiscovery(discoveredMap: MutableMap<String, DiscoveredTv>, onDeviceFound: ((DiscoveredTv) -> Unit)?) {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val multicastLock = wifiManager?.createMulticastLock("OmniRemoteMulticast")
        multicastLock?.setReferenceCounted(true)
        multicastLock?.acquire()

        var socket: DatagramSocket? = null
        try {
            val ssdpQuery = "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 2\r\n" +
                    "ST: ssdp:all\r\n\r\n"

            val ssdpBytes = ssdpQuery.toByteArray()
            socket = DatagramSocket()
            localNetwork.bind(socket)
            socket.soTimeout = 1800

            val packet = DatagramPacket(
                ssdpBytes,
                ssdpBytes.size,
                InetAddress.getByName("239.255.255.250"),
                1900
            )
            socket.send(packet)

            val receiveBuf = ByteArray(2048)
            val startTime = System.currentTimeMillis()

            while (System.currentTimeMillis() - startTime < 2000) {
                try {
                    val receivePacket = DatagramPacket(receiveBuf, receiveBuf.size)
                    socket.receive(receivePacket)
                    val response = String(receivePacket.data, 0, receivePacket.length)
                    val ip = receivePacket.address.hostAddress ?: continue

                    if (response.contains("Samsung", ignoreCase = true) || response.contains("SEC", ignoreCase = true)) {
                        if (!discoveredMap.containsKey(ip)) {
                            // Query Samsung TV REST API for real model info
                            val tvInfo = fetchSamsungDeviceInfo(ip)
                            discoveredMap[ip] = tvInfo ?: DiscoveredTv(
                                name = "Samsung Smart TV",
                                ipAddress = ip,
                                deviceType = DeviceType.SAMSUNG_TIZEN,
                                port = 8002,
                                modelInfo = "Detected via SSDP"
                            )
                        }
                    } else if (response.contains("Eureka", ignoreCase = true) ||
                        response.contains("GoogleCast", ignoreCase = true) ||
                        response.contains("Android", ignoreCase = true)
                    ) {
                        if (!discoveredMap.containsKey(ip)) {
                            val castInfo = fetchCastDeviceInfo(ip)
                            discoveredMap[ip] = castInfo ?: DiscoveredTv(
                                name = "Android TV / Google TV",
                                ipAddress = ip,
                                deviceType = DeviceType.ANDROID_TV,
                                port = 8008,
                                modelInfo = "Detected via UPnP"
                            )
                        }
                    }
                    discoveredMap[ip]?.let { onDeviceFound?.invoke(it) }
                } catch (_: Exception) {
                    break
                }
            }
        } catch (e: Exception) {
            Log.d("TvScanner", "SSDP socket exception: ${e.message}")
        } finally {
            socket?.close()
            multicastLock?.release()
        }
    }

    private suspend fun performSubnetSweep(
        subnetPrefix: String,
        discoveredMap: MutableMap<String, DiscoveredTv>,
        onProgress: ((Int, Int) -> Unit)?,
        onDeviceFound: ((DiscoveredTv) -> Unit)?
    ) = coroutineScope {
        val semaphore = Semaphore(35) // 35 concurrent probes
        var completed = 0
        val total = 254

        val jobs = (1..254).map { i ->
            async(Dispatchers.IO) {
                val ip = "$subnetPrefix$i"
                semaphore.withPermit {
                    // Pairing is available on some TVs before the control port responds.
                    if (isPortOpen(ip, 6466, 500) || isPortOpen(ip, 6467, 500)) {
                        val tv = fetchCastDeviceInfo(ip)?.copy(port = 6466) ?: DiscoveredTv(
                            name = "Android TV ($ip)", ipAddress = ip,
                            deviceType = DeviceType.ANDROID_TV, port = 6466,
                            modelInfo = "Android TV Remote Service v2"
                        )
                        discoveredMap[ip] = tv
                    } else if (isPortOpen(ip, 8001, 280) || isPortOpen(ip, 8002, 280)) {
                        val samsungInfo = fetchSamsungDeviceInfo(ip)
                        val tv = samsungInfo ?: DiscoveredTv(
                            name = "Samsung Smart TV ($ip)",
                            ipAddress = ip,
                            deviceType = DeviceType.SAMSUNG_TIZEN,
                            port = if (isPortOpen(ip, 8002, 200)) 8002 else 8001,
                            modelInfo = "Samsung Tizen Smart TV"
                        )
                        synchronized(discoveredMap) {
                            discoveredMap[ip] = tv
                        }
                    } else if (isPortOpen(ip, 8008, 280)) {
                        // Check Google Cast / Google TV DIAL port 8008
                        val castInfo = fetchCastDeviceInfo(ip)
                        val tv = castInfo ?: DiscoveredTv(
                            name = "Google TV ($ip)",
                            ipAddress = ip,
                            deviceType = DeviceType.ANDROID_TV,
                            port = 8008,
                            modelInfo = "Google TV / Android TV"
                        )
                        synchronized(discoveredMap) {
                            discoveredMap[ip] = tv
                        }
                    } else if (isPortOpen(ip, 6466, 280)) {
                        // Google TV Remote Control v2
                        val tv = DiscoveredTv(
                            name = "Google TV ($ip)",
                            ipAddress = ip,
                            deviceType = DeviceType.ANDROID_TV,
                            port = 6466,
                            modelInfo = "Google TV Remote v2"
                        )
                        synchronized(discoveredMap) {
                            discoveredMap[ip] = tv
                        }
                    } else if (isPortOpen(ip, 5555, 280)) {
                        // Check Google TV ADB port
                        val tv = DiscoveredTv(
                            name = "Google TV ($ip)",
                            ipAddress = ip,
                            deviceType = DeviceType.ANDROID_TV,
                            port = 5555,
                            modelInfo = "Google TV (ADB Ready)"
                        )
                        synchronized(discoveredMap) {
                            discoveredMap[ip] = tv
                        }
                    }
                    discoveredMap[ip]?.let { onDeviceFound?.invoke(it) }
                }
                synchronized(this@TvScanner) {
                    completed++
                    onProgress?.invoke(completed, total)
                }
            }
        }
        jobs.awaitAll()
    }

    fun isPortOpen(ip: String, port: Int, timeoutMs: Int = 300): Boolean {
        return try {
            localNetwork.socketFactory.createSocket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    fun fetchSamsungDeviceInfo(ip: String): DiscoveredTv? {
        try {
            val url = "http://$ip:8001/api/v2/"
            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyString = response.body?.string() ?: return null
                    val json = JSONObject(bodyString)
                    val deviceObj = json.optJSONObject("device") ?: return null

                    val name = deviceObj.optString("name").ifBlank {
                        deviceObj.optString("modelName", "Samsung Smart TV")
                    }
                    val modelName = deviceObj.optString("modelName", "")
                    val mac = WakeOnLan.extractMacFromSamsungJson(bodyString) ?: ""

                    return DiscoveredTv(
                        name = name,
                        ipAddress = ip,
                        deviceType = DeviceType.SAMSUNG_TIZEN,
                        port = 8002,
                        modelInfo = if (modelName.isNotBlank()) "Model: $modelName" else "Tizen Smart TV",
                        macAddress = mac
                    )
                }
            }
        } catch (e: Exception) {
            Log.d("TvScanner", "Samsung device info query error: ${e.message}")
        }
        return null
    }

    fun fetchCastDeviceInfo(ip: String): DiscoveredTv? {
        try {
            val url = "http://$ip:8008/setup/eureka_info?params=name,device_info"
            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyString = response.body?.string() ?: return null
                    val json = JSONObject(bodyString)
                    val rawName = json.optString("name", "").ifBlank { "Google TV" }
                    val devInfo = json.optJSONObject("device_info")
                    val manufacturer = devInfo?.optString("manufacturer", "Google") ?: "Google"
                    val modelName = devInfo?.optString("model_name", "Google TV") ?: "Google TV"
                    val mac = WakeOnLan.extractMacFromCastJson(bodyString) ?: ""

                    return DiscoveredTv(
                        name = rawName,
                        ipAddress = ip,
                        deviceType = DeviceType.ANDROID_TV,
                        port = 8008,
                        modelInfo = "$modelName ($manufacturer) • Google TV",
                        macAddress = mac
                    )
                }
            }
        } catch (e: Exception) {
            Log.d("TvScanner", "Cast device info query error: ${e.message}")
        }
        return null
    }

    suspend fun testConnection(ipAddress: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        isPortOpen(ipAddress, port, 1500)
    }
}
