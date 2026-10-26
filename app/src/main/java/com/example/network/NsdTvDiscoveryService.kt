package com.example.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.example.data.model.DeviceType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.util.concurrent.ConcurrentLinkedQueue

sealed interface NsdScanState {
    data object Idle : NsdScanState
    data class Scanning(val activeProtocols: List<String>) : NsdScanState
    data class Stopped(val foundCount: Int) : NsdScanState
    data class Error(val message: String) : NsdScanState
}

class NsdTvDiscoveryService(private val context: Context) {

    private val nsdManager: NsdManager? by lazy {
        context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    }

    private val wifiManager: WifiManager? by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    }

    private var multicastLock: WifiManager.MulticastLock? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Observable Discovered Devices via NSD / Network
    private val _discoveredTvs = MutableStateFlow<List<DiscoveredTv>>(emptyList())
    val discoveredTvs: StateFlow<List<DiscoveredTv>> = _discoveredTvs.asStateFlow()

    private val _scanState = MutableStateFlow<NsdScanState>(NsdScanState.Idle)
    val scanState: StateFlow<NsdScanState> = _scanState.asStateFlow()

    // Common mDNS service types used by Smart TVs (formatted without trailing dots for discoverServices compatibility)
    val serviceTypesToScan = listOf(
        "_googlecast._tcp",          // Android TV / Google TV / Chromecast
        "_androidtvremote2._tcp",    // Google TV / Android TV Remote Service v2
        "_samsungmsf._tcp",          // Samsung MultiScreen Framework (Tizen)
        "_dial-multiscreen-org._tcp",// DIAL protocol used by Samsung and Google Cast
        "_airplay._tcp",             // Apple AirPlay on Samsung & Android TVs
        "_adb._tcp",                 // Android TV Network Debugging
        "_ssh._tcp"                  // macOS Remote Login (Mac volume and media)
    )

    private val activeListeners = mutableMapOf<String, NsdManager.DiscoveryListener>()
    private val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
    @Volatile
    private var isResolving = false
    private var resolveTimeoutJob: Job? = null
    @Volatile private var scanGeneration = 0L
    @Volatile private var resolveGeneration = 0L

    private val fallbackScanner = TvScanner(context)
    private var subnetScanJob: Job? = null

    fun startDiscovery() {
        stopDiscovery()
        _discoveredTvs.value = emptyList()
        val generation = scanGeneration

        // Acquire Multicast lock for mDNS multicast UDP packets
        try {
            multicastLock = wifiManager?.createMulticastLock("OmniRemoteNsdMulticast")?.apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (e: Exception) {
            Log.w("NsdTvDiscoveryService", "Failed to acquire multicast lock: ${e.message}")
        }

        val manager = nsdManager
        if (manager == null) {
            _scanState.value = NsdScanState.Error("NSD service unavailable on this device")
            return
        }

        _scanState.value = NsdScanState.Scanning(serviceTypesToScan)
        Log.d("NsdTvDiscoveryService", "Starting NSD Discovery across ${serviceTypesToScan.size} protocols...")

        // Register discovery listener for each service type
        serviceTypesToScan.forEach { serviceType ->
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) {
                    Log.d("NsdTvDiscoveryService", "NSD discovery started for: $regType")
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    Log.d("NsdTvDiscoveryService", "NSD Service Found: ${serviceInfo.serviceName} (${serviceInfo.serviceType})")
                    if (generation == scanGeneration) queueResolve(serviceInfo)
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    Log.d("NsdTvDiscoveryService", "NSD Service Lost: ${serviceInfo.serviceName}")
                }

                override fun onDiscoveryStopped(serviceType: String) {
                    Log.d("NsdTvDiscoveryService", "NSD discovery stopped for: $serviceType")
                }

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.e("NsdTvDiscoveryService", "NSD Start Discovery Failed for $serviceType (Code: $errorCode)")
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.e("NsdTvDiscoveryService", "NSD Stop Discovery Failed for $serviceType (Code: $errorCode)")
                }
            }

            try {
                manager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
                activeListeners[serviceType] = listener
            } catch (e: Exception) {
                Log.e("NsdTvDiscoveryService", "Failed to discover $serviceType", e)
            }
        }

        // Also trigger subnet & SSDP network probe alongside NSD so TVs are found even if router filters mDNS
        subnetScanJob = scope.launch {
            try {
                val subnetResults = fallbackScanner.scanLocalNetwork(onDeviceFound = { if (generation == scanGeneration) addOrUpdateDiscoveredTv(it) })
                if (generation == scanGeneration) subnetResults.forEach { addOrUpdateDiscoveredTv(it) }
            } catch (e: Exception) {
                Log.d("NsdTvDiscoveryService", "Subnet probe complement info: ${e.message}")
            }
        }
    }

    fun stopDiscovery() {
        scanGeneration++
        resolveGeneration++
        subnetScanJob?.cancel()
        subnetScanJob = null

        val manager = nsdManager
        if (manager != null) {
            activeListeners.forEach { (type, listener) ->
                try {
                    manager.stopServiceDiscovery(listener)
                } catch (e: Exception) {
                    Log.d("NsdTvDiscoveryService", "Error stopping discovery for $type: ${e.message}")
                }
            }
        }
        activeListeners.clear()
        resolveQueue.clear()
        isResolving = false
        resolveTimeoutJob?.cancel()
        resolveTimeoutJob = null

        try {
            multicastLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (_: Exception) {}
        multicastLock = null

        _scanState.value = NsdScanState.Stopped(_discoveredTvs.value.size)
        Log.d("NsdTvDiscoveryService", "NSD discovery stopped. Total TVs found: ${_discoveredTvs.value.size}")
    }

    private fun queueResolve(serviceInfo: NsdServiceInfo) {
        resolveQueue.add(serviceInfo)
        processNextResolve()
    }

    @Synchronized
    private fun processNextResolve() {
        if (isResolving) return
        val nextService = resolveQueue.poll() ?: return

        isResolving = true
        val request = ++resolveGeneration
        val manager = nsdManager ?: run {
            isResolving = false
            return
        }

        // Watchdog timeout to prevent queue stalling if device does not reply to resolve
        resolveTimeoutJob?.cancel()
        resolveTimeoutJob = scope.launch {
            delay(6000)
            if (isResolving && request == resolveGeneration) {
                Log.w("NsdTvDiscoveryService", "Resolve timed out for ${nextService.serviceName}")
                resolveGeneration++
                isResolving = false
                processNextResolve()
            }
        }

        try {
            manager.resolveService(nextService, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    if (request != resolveGeneration) return
                    Log.w("NsdTvDiscoveryService", "Resolve failed for ${serviceInfo.serviceName}: code $errorCode")
                    resolveTimeoutJob?.cancel()
                    isResolving = false
                    processNextResolve()
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    if (request != resolveGeneration) return
                    resolveTimeoutJob?.cancel()
                    val hostAddr = getHostAddress(serviceInfo)
                    Log.d("NsdTvDiscoveryService", "Service successfully resolved: ${serviceInfo.serviceName} at $hostAddr:${serviceInfo.port}")
                    handleResolvedService(serviceInfo)
                    isResolving = false
                    processNextResolve()
                }
            })
        } catch (e: Exception) {
            Log.e("NsdTvDiscoveryService", "Exception during resolveService", e)
            resolveTimeoutJob?.cancel()
            isResolving = false
            processNextResolve()
        }
    }

    private fun getHostAddress(serviceInfo: NsdServiceInfo): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            serviceInfo.hostAddresses.firstOrNull { it is java.net.Inet4Address }?.hostAddress ?: serviceInfo.hostAddresses.firstOrNull()?.hostAddress ?: serviceInfo.host?.hostAddress
        } else {
            serviceInfo.host?.hostAddress
        }
    }

    private fun handleResolvedService(serviceInfo: NsdServiceInfo) {
        val ip = getHostAddress(serviceInfo) ?: return
        if (ip.startsWith("127.") || ip == "0.0.0.0") return

        val sType = serviceInfo.serviceType ?: ""
        val sName = serviceInfo.serviceName ?: "Smart TV"
        val port = serviceInfo.port

        val isSamsung = sType.contains("samsung", ignoreCase = true) ||
                sName.contains("samsung", ignoreCase = true) ||
                sType.contains("smartview", ignoreCase = true)

        val isAndroidOrGoogle = sType.contains("googlecast", ignoreCase = true) ||
                sType.contains("androidtv", ignoreCase = true) ||
                sName.contains("android", ignoreCase = true) ||
                sName.contains("google", ignoreCase = true) ||
                sName.contains("eureka", ignoreCase = true) ||
                sType.contains("adb", ignoreCase = true)

        val model = serviceInfo.attributes["model"]?.toString(Charsets.UTF_8)
        val deviceType = when {
            isMacService(sType, model) -> DeviceType.MACOS
            isSamsung -> DeviceType.SAMSUNG_TIZEN
            isAndroidOrGoogle -> DeviceType.ANDROID_TV
            else -> {
                // If service is DIAL or AirPlay, probe port 8001 (Samsung) vs 8008 (Google Cast)
                if (fallbackScanner.isPortOpen(ip, 8001, 300) || fallbackScanner.isPortOpen(ip, 8002, 300)) {
                    DeviceType.SAMSUNG_TIZEN
                } else {
                    DeviceType.ANDROID_TV
                }
            }
        }

        // Clean readable friendly name
        val friendlyName = serviceInfo.attributes["fn"]?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() } ?: sName
        val cleanName = friendlyName
            .replace("_googlecast._tcp", "")
            .replace("_samsungmsf._tcp", "")
            .replace("_androidtvremote2._tcp", "")
            .replace(".", "")
            .replace("-", " ")
            .trim()
            .ifBlank {
                when (deviceType) {
                    DeviceType.SAMSUNG_TIZEN -> "Samsung Smart TV"
                    DeviceType.ANDROID_TV -> "Google TV"
                    DeviceType.MACOS -> "Mac"
                }
            }

        val modelInfo = "Discovered via NsdManager (${sType.trimEnd('.')})"

        val discovered = DiscoveredTv(
            name = cleanName,
            ipAddress = ip,
            deviceType = deviceType,
            port = when {
                deviceType == DeviceType.MACOS -> if (sType.contains("ssh", ignoreCase = true) && port > 0) port else DeviceType.MACOS.defaultPort
                deviceType == DeviceType.SAMSUNG_TIZEN -> if (port > 0) port else 8002
                else -> if (port > 0) port else 8008
            },
            modelInfo = modelInfo
        )

        addOrUpdateDiscoveredTv(discovered)
    }

    @Synchronized
    private fun addOrUpdateDiscoveredTv(tv: DiscoveredTv) {
        val current = _discoveredTvs.value.toMutableList()
        val index = current.indexOfFirst { it.ipAddress == tv.ipAddress }
        if (index >= 0) {
            val existing = current[index]
            // A Mac also advertises AirPlay, which on its own would look like a TV.
            if (existing.deviceType == DeviceType.MACOS && tv.deviceType != DeviceType.MACOS) return
            val generic = tv.name.contains(tv.ipAddress) || tv.name == "Google TV"
            val betterName = if (generic) existing.name else tv.name
            val remotePort = if (existing.port == 6466 || tv.port == 6466) 6466 else tv.port
            current[index] = tv.copy(name = betterName, port = remotePort)
        } else {
            current.add(tv)
        }
        _discoveredTvs.value = current
    }

    companion object {
        /** Remote Login advertises _ssh._tcp; a Mac's AirPlay TXT record carries a Mac model. */
        internal fun isMacService(serviceType: String, model: String?): Boolean =
            serviceType.contains("_ssh._tcp", ignoreCase = true) ||
                model?.trim()?.let { it.startsWith("Mac", ignoreCase = true) || it.startsWith("iMac", ignoreCase = true) } == true
    }
}
