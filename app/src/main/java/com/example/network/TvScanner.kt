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

class TvScanner(
    private val context: Context,
    private val portProbe: ((String, Int, Int) -> Boolean)? = null
) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(1200, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .build()

    fun getLocalIpAddress(): String? {
        try {
            val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            connectivity?.allNetworks?.forEach { network ->
                val caps = connectivity.getNetworkCapabilities(network)
                if (caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true ||
                    caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) == true) {
                    connectivity.getLinkProperties(network)?.linkAddresses?.firstOrNull {
                        it.address is Inet4Address && !it.address.isLoopbackAddress
                    }?.address?.hostAddress?.let { return it }
                }
            }
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

            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress
                        if (host != null && !host.startsWith("127.")) {
                            return host
                        }
                    }
                }
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
        val discoveredMap = mutableMapOf<String, DiscoveredTv>()

        // 1. SSDP / UPnP Multicast Search
        try {
            performSsdpDiscovery(discoveredMap)
        } catch (e: Exception) {
            Log.w("TvScanner", "SSDP discovery error: ${e.message}")
        }

        // 2. Active Subnet TCP Sweep on TV Ports (8001, 8002, 8008, 5555, 6466)
        val prefix = customSubnet?.trim()?.let {
            if (it.endsWith(".")) it else "$it."
        } ?: getSubnetPrefix()

        performSubnetSweep(prefix, discoveredMap, onProgress, onDeviceFound)

        discoveredMap.values.toList()
    }

    private fun performSsdpDiscovery(discoveredMap: MutableMap<String, DiscoveredTv>) {
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
                    val tv = probeDevice(ip)
                    if (tv != null) {
                        synchronized(discoveredMap) { discoveredMap[ip] = tv }
                        onDeviceFound?.invoke(tv)
                    }
                }
                synchronized(this@TvScanner) {
                    completed++
                    onProgress?.invoke(completed, total)
                }
            }
        }
        jobs.awaitAll()
    }

    internal fun probeDevice(ip: String): DiscoveredTv? {
        // Remote Service is authoritative, even if Cast or OEM HTTP services are absent.
        // Some boxes expose only the pairing service before their first authorization.
        if (isPortOpen(ip, 6466, 650) || isPortOpen(ip, 6467, 650)) {
            return DiscoveredTv(
                name = "Android TV / Google TV ($ip)", ipAddress = ip,
                deviceType = DeviceType.ANDROID_TV, port = 6466,
                modelInfo = "Android TV Remote Service • Compatible con FlowBox"
            )
        }
        if (isPortOpen(ip, 8001, 350) || isPortOpen(ip, 8002, 350)) {
            fetchSamsungDeviceInfo(ip)?.let { return it }
        }
        if (isPortOpen(ip, 8008, 350)) {
            fetchCastDeviceInfo(ip)?.let { return it }
        }
        // A web server or ADB port alone does not identify a controllable television.
        return null
    }

    fun isPortOpen(ip: String, port: Int, timeoutMs: Int = 300): Boolean {
        portProbe?.let { return it(ip, port, timeoutMs) }
        return try {
            Socket().use { socket ->
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
