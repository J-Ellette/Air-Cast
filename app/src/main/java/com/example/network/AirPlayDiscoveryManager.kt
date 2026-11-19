package com.example.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.example.model.AirPlayReceiver
import com.example.model.ReceiverType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class AirPlayDiscoveryManager(context: Context) {

    private data class PendingResolve(
        val serviceInfo: NsdServiceInfo,
        val serviceType: String
    )

    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val callbackExecutor = Executors.newSingleThreadExecutor()

    private var multicastLock: WifiManager.MulticastLock? = null
    private var airplayListener: NsdManager.DiscoveryListener? = null
    private var ssdpScanJob: Job? = null

    private var resolveQueueChannel: Channel<PendingResolve>? = null
    private var resolveWorkerJob: Job? = null
    private val serviceInfoCallbacks = ConcurrentHashMap<String, Any>()

    @Volatile
    var onDiscoveryEvent: ((summary: String, details: String) -> Unit)? = null

    private val _discoveredReceivers = MutableStateFlow<List<AirPlayReceiver>>(emptyList())
    val discoveredReceivers: StateFlow<List<AirPlayReceiver>> = _discoveredReceivers.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _scanStatusMessage = MutableStateFlow("NSD service ready")
    val scanStatusMessage: StateFlow<String> = _scanStatusMessage.asStateFlow()

    fun startDiscovery() {
        val manager = nsdManager ?: run {
            _scanStatusMessage.value = "Network Service Discovery unavailable on this device"
            return
        }
        stopDiscovery()

        try {
            multicastLock = wifiManager?.createMulticastLock("AirCastBridge_mDNS")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: SecurityException) {
            // Proceed even if multicast lock is denied
        }

        startResolveWorker()

        _isScanning.value = true
        _scanStatusMessage.value = "Scanning LAN for Roku, streamers & Smart TVs"
        onDiscoveryEvent?.invoke(
            "Discovery Started",
            "Protocols: _airplay._tcp. (DNS-SD) + Roku ECP (SSDP)"
        )

        val apListener = createDiscoveryListener("_airplay._tcp.")
        airplayListener = apListener

        runCatching {
            manager.discoverServices("_airplay._tcp.", NsdManager.PROTOCOL_DNS_SD, apListener)
        }.onFailure { err ->
            onDiscoveryEvent?.invoke("NSD _airplay._tcp. error", err.message ?: "Unknown")
        }

        startRokuSsdpProbe()
    }

    fun stopDiscovery() {
        val manager = nsdManager
        airplayListener?.let { listener ->
            runCatching { manager?.stopServiceDiscovery(listener) }
        }
        airplayListener = null
        ssdpScanJob?.cancel()
        ssdpScanJob = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && manager != null) {
            serviceInfoCallbacks.values.forEach { cb ->
                @Suppress("UNCHECKED_CAST")
                runCatching {
                    manager.unregisterServiceInfoCallback(cb as NsdManager.ServiceInfoCallback)
                }
            }
        }
        serviceInfoCallbacks.clear()

        resolveWorkerJob?.cancel()
        resolveWorkerJob = null
        resolveQueueChannel?.close()
        resolveQueueChannel = null

        runCatching {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        }
        multicastLock = null
        _isScanning.value = false
        if (_discoveredReceivers.value.isEmpty()) {
            _scanStatusMessage.value = "Scan paused"
        } else {
            _scanStatusMessage.value = "Scan paused • ${_discoveredReceivers.value.size} device(s) cached"
        }
    }

    fun forgetDiscoveredReceiver(receiver: AirPlayReceiver) {
        _discoveredReceivers.update { existing ->
            existing.filterNot { it.host == receiver.host && it.port == receiver.port }
        }
        updateStatusCount()
    }

    private fun startRokuSsdpProbe() {
        ssdpScanJob?.cancel()
        ssdpScanJob = scope.launch {
            runCatching {
                val query = (
                    "M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: 239.255.255.250:1900\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 2\r\n" +
                        "ST: roku:ecp\r\n\r\n"
                    ).toByteArray(StandardCharsets.UTF_8)

                DatagramSocket().use { socket ->
                    socket.soTimeout = 2500
                    val target = InetAddress.getByName("239.255.255.250")
                    val packet = DatagramPacket(query, query.size, target, 1900)
                    socket.send(packet)

                    val seenHosts = HashSet<String>()
                    val deadline = System.currentTimeMillis() + 3000L
                    val buf = ByteArray(4096)
                    while (System.currentTimeMillis() < deadline) {
                        val recv = DatagramPacket(buf, buf.size)
                        try {
                            socket.receive(recv)
                        } catch (_: Exception) {
                            break
                        }
                        val host = recv.address?.hostAddress ?: continue
                        if (!seenHosts.add(host)) continue
                        val raw = String(recv.data, 0, recv.length, StandardCharsets.UTF_8)
                        val location = raw.lines()
                            .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
                            ?.substringAfter(":")?.trim()
                            ?: "http://$host:8060/"
                        launch { inspectRokuEcpDevice(host, location) }
                    }
                }
            }
        }
    }

    private fun inspectRokuEcpDevice(host: String, locationUrl: String) {
        val deviceInfoUrl = locationUrl.trimEnd('/') + "/query/device-info"
        val xml = runCatching {
            val conn = (URL(deviceInfoUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 2000
                readTimeout = 2000
                requestMethod = "GET"
            }
            try {
                if (conn.responseCode in 200..299) {
                    conn.inputStream.bufferedReader().readText()
                } else null
            } finally {
                conn.disconnect()
            }
        }.getOrNull() ?: return

        val friendlyName = extractXmlTag(xml, "friendly-device-name")
            ?: extractXmlTag(xml, "user-device-name")
            ?: extractXmlTag(xml, "model-name")
            ?: "Roku ($host)"
        val modelName = extractXmlTag(xml, "model-name") ?: "Roku Player"
        val isTv = extractXmlTag(xml, "is-tv").equals("true", ignoreCase = true)
        val supportsAirPlay = extractXmlTag(xml, "supports-airplay").equals("true", ignoreCase = true) ||
            isTcpPortOpen(host, 7000)

        if (!supportsAirPlay) return

        val receiver = AirPlayReceiver(
            id = "$host:7000:_airplay._tcp.",
            name = friendlyName,
            host = host,
            port = 7000,
            type = if (isTv) ReceiverType.SMART_TV else ReceiverType.STREAMING_STICK,
            serviceType = "_airplay._tcp.",
            model = modelName,
            capabilitiesSummary = "Video • Photos • Mirror",
            protocolBadge = "AirPlay 2",
            requiresPin = false,
            isManual = false,
            isReachable = true
        )

        _discoveredReceivers.update { existing ->
            val alreadyHasNsd = existing.any { it.host == host && it.port == 7000 }
            if (alreadyHasNsd) existing else (existing + receiver).sortedBy { it.name.lowercase() }
        }
        updateStatusCount()
        onDiscoveryEvent?.invoke(
            "Roku ECP Detected ${receiver.name}",
            "${receiver.host}:${receiver.port} • ${receiver.model}"
        )
    }

    private fun extractXmlTag(xml: String, tag: String): String? {
        return Regex("<$tag>([^<]+)</$tag>", RegexOption.IGNORE_CASE)
            .find(xml)?.groupValues?.getOrNull(1)?.trim()
    }

    private fun isTcpPortOpen(host: String, port: Int): Boolean {
        return runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), 1000)
                true
            }
        }.getOrDefault(false)
    }

    private fun startResolveWorker() {
        resolveWorkerJob?.cancel()
        resolveQueueChannel?.close()
        val channel = Channel<PendingResolve>(Channel.UNLIMITED)
        resolveQueueChannel = channel

        resolveWorkerJob = scope.launch {
            for (pending in channel) {
                resolveLegacySequential(pending.serviceInfo, pending.serviceType)
            }
        }
    }

    private fun createDiscoveryListener(serviceType: String): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                _isScanning.value = true
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                onDiscoveryEvent?.invoke(
                    "NSD Service Found",
                    "${serviceInfo.serviceName} ($serviceType)"
                )
                enqueueOrRegisterService(serviceInfo, serviceType)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val cleanedName = cleanServiceName(serviceInfo.serviceName, serviceType)
                onDiscoveryEvent?.invoke(
                    "NSD Service Lost",
                    "$cleanedName ($serviceType)"
                )
                _discoveredReceivers.update { list ->
                    list.filterNot { it.name == cleanedName && it.serviceType == serviceType }
                }
                updateStatusCount()
            }

            override fun onDiscoveryStopped(serviceType: String) {
                if (airplayListener == null) {
                    _isScanning.value = false
                }
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                _scanStatusMessage.value = "NSD notice ($serviceType code $errorCode)"
                onDiscoveryEvent?.invoke(
                    "NSD Start Failed",
                    "$serviceType errorCode=$errorCode"
                )
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
    }

    private fun enqueueOrRegisterService(serviceInfo: NsdServiceInfo, serviceType: String) {
        val manager = nsdManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val key = "${serviceInfo.serviceName}|$serviceType"
            if (serviceInfoCallbacks.containsKey(key)) return
            val callback = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    serviceInfoCallbacks.remove(key)
                    resolveQueueChannel?.trySend(PendingResolve(serviceInfo, serviceType))
                }

                override fun onServiceUpdated(updatedInfo: NsdServiceInfo) {
                    handleResolvedServiceInfo(updatedInfo, serviceType)
                }

                override fun onServiceLost() {
                    val cleanedName = cleanServiceName(serviceInfo.serviceName, serviceType)
                    _discoveredReceivers.update { list ->
                        list.filterNot { it.name == cleanedName && it.serviceType == serviceType }
                    }
                    updateStatusCount()
                }

                override fun onServiceInfoCallbackUnregistered() {
                    serviceInfoCallbacks.remove(key)
                }
            }
            serviceInfoCallbacks[key] = callback
            runCatching {
                manager.registerServiceInfoCallback(serviceInfo, callbackExecutor, callback)
            }.onFailure {
                serviceInfoCallbacks.remove(key)
                resolveQueueChannel?.trySend(PendingResolve(serviceInfo, serviceType))
            }
        } else {
            resolveQueueChannel?.trySend(PendingResolve(serviceInfo, serviceType))
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolveLegacySequential(
        serviceInfo: NsdServiceInfo,
        serviceType: String,
        attempt: Int = 0
    ) {
        val manager = nsdManager ?: return
        val deferred = CompletableDeferred<Int?>()

        runCatching {
            manager.resolveService(
                serviceInfo,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        deferred.complete(errorCode)
                    }

                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        handleResolvedServiceInfo(resolved, serviceType)
                        deferred.complete(null)
                    }
                }
            )
        }.onFailure {
            deferred.complete(-1)
        }

        val failureCode = withTimeoutOrNull(4_000L) { deferred.await() }
        if (failureCode == NsdManager.FAILURE_ALREADY_ACTIVE && attempt < 2) {
            delay(350L)
            resolveLegacySequential(serviceInfo, serviceType, attempt + 1)
        }
    }

    private fun handleResolvedServiceInfo(resolved: NsdServiceInfo, serviceType: String) {
        val hostAddress = selectPreferredHostAddress(resolved) ?: return
        val attrs = resolved.attributes.mapValues { (_, bytes) ->
            bytes?.let { String(it, StandardCharsets.UTF_8) } ?: ""
        }
        val receiver = buildReceiverFromTxt(
            serviceName = resolved.serviceName ?: "Streaming Receiver",
            hostAddress = hostAddress,
            port = resolved.port,
            serviceType = serviceType,
            attrs = attrs
        ) ?: return

        _discoveredReceivers.update { existing ->
            val filtered = existing.filterNot {
                it.host == receiver.host && it.port == receiver.port && it.serviceType == receiver.serviceType
            }
            (filtered + receiver).sortedBy { it.name.lowercase() }
        }
        updateStatusCount()
        onDiscoveryEvent?.invoke(
            "NSD Resolved ${receiver.name}",
            "${receiver.host}:${receiver.port} • ${receiver.protocolBadge} • ${receiver.capabilitiesSummary}"
        )
    }

    private fun updateStatusCount() {
        val count = _discoveredReceivers.value.size
        _scanStatusMessage.value = if (_isScanning.value) {
            if (count == 0) "Scanning LAN for Roku, streamers & Smart TVs"
            else "NSD live • $count device(s) detected"
        } else {
            "Scan paused • $count cached"
        }
    }

    @Suppress("DEPRECATION")
    private fun selectPreferredHostAddress(resolved: NsdServiceInfo): String? {
        val candidates: List<InetAddress> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                resolved.hostAddresses.ifEmpty { listOfNotNull(resolved.host) }
            } else {
                listOfNotNull(resolved.host)
            }

        val ipv4SiteLocal = candidates.firstOrNull {
            it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress
        }
        val ipv4Any = candidates.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
        val nonLoopback = candidates.firstOrNull { !it.isLoopbackAddress }
        return (ipv4SiteLocal ?: ipv4Any ?: nonLoopback)?.hostAddress
    }

    companion object {
        fun buildReceiverFromTxt(
            serviceName: String,
            hostAddress: String,
            port: Int,
            serviceType: String,
            attrs: Map<String, String>
        ): AirPlayReceiver? {
            val cleanName = cleanServiceName(serviceName, serviceType)
            val model = attrs["model"] ?: attrs["am"] ?: "Smart Receiver"
            val receiverType = classifyReceiver(serviceType, model, cleanName) ?: return null

            val deviceId = attrs["deviceid"] ?: ""
            val features = attrs["features"] ?: ""
            val osVersion = attrs["osvers"] ?: ""
            val flagsVal = attrs["flags"]?.removePrefix("0x")?.toLongOrNull(16) ?: 0L
            val requiresPin = (flagsVal and 0x8L) != 0L || (flagsVal and 0x80L) != 0L ||
                attrs["pw"] == "true"

            val decodedCapabilities = decodeCapabilities(serviceType, features, attrs)
            val protocolBadge = inferProtocolBadge(serviceType, features, attrs)

            return AirPlayReceiver(
                id = "${hostAddress}:${port}:$serviceType",
                name = cleanName,
                host = hostAddress,
                port = port,
                type = receiverType,
                serviceType = serviceType,
                model = model,
                deviceId = deviceId,
                featuresHex = features,
                osVersion = osVersion,
                capabilitiesSummary = decodedCapabilities,
                protocolBadge = protocolBadge,
                requiresPin = requiresPin,
                isManual = false,
                isReachable = true
            )
        }

        fun cleanServiceName(raw: String, serviceType: String): String {
            return if (serviceType == "_raop._tcp." && raw.contains("@")) {
                raw.substringAfter("@")
            } else {
                raw
            }
        }

        fun classifyReceiver(
            serviceType: String,
            model: String,
            name: String
        ): ReceiverType? {
            val m = model.lowercase()
            val n = name.lowercase()
            // Exclude speakers/audio-only devices and Apple TV boxes
            val isExcludedAppleTv = m.contains("appletv") || n.contains("apple tv")
            val isExcludedSpeaker = serviceType == "_raop._tcp." ||
                m.contains("audioaccessory") ||
                n.contains("homepod") ||
                n.contains("sonos") ||
                n.contains("airport") ||
                (n.contains("speaker") && !n.contains("tv")) ||
                (n.contains("soundbar") && !n.contains("roku"))
            if (isExcludedAppleTv || isExcludedSpeaker) return null

            val isTv = n.contains(" tv") || n.endsWith("tv") || m.contains("tv") ||
                n.contains("samsung") || n.contains("lg ") || n.contains("bravia") ||
                n.contains("sony") || n.contains("vizio") || n.contains("oled") ||
                n.contains("qled") || n.contains("hisense") || n.contains("tcl") ||
                n.contains("roku tv")
            val isPlugInStreamer = !isTv && (
                m.contains("roku") || n.contains("roku") ||
                    n.contains("streaming stick") || n.contains("stick") ||
                    n.contains("express") || n.contains("ultra") ||
                    n.contains("premiere") || n.contains("streambar") ||
                    n.contains("fire tv") || n.contains("firetv") ||
                    n.contains("firestick") || n.contains("chromecast") ||
                    n.contains("google tv") || n.contains("android tv") ||
                    n.contains("shield") || n.contains("onn") ||
                    n.contains("mi box") || n.contains("dongle") ||
                    n.contains("streamer")
                )

            return if (isPlugInStreamer) ReceiverType.STREAMING_STICK else ReceiverType.SMART_TV
        }

        fun parseFeaturesMask(featuresHex: String): Long {
            if (featuresHex.isBlank()) return 0L
            val parts = featuresHex.split(",")
            val low = parts.getOrNull(0)?.trim()?.removePrefix("0x")?.toLongOrNull(16) ?: 0L
            val high = parts.getOrNull(1)?.trim()?.removePrefix("0x")?.toLongOrNull(16) ?: 0L
            return low or (high shl 32)
        }

        fun decodeCapabilities(
            serviceType: String,
            featuresHex: String,
            attrs: Map<String, String>
        ): String {
            if (serviceType == "_raop._tcp." && featuresHex.isBlank()) {
                val codecs = attrs["cn"]?.split(",")?.mapNotNull { code ->
                    when (code.trim()) {
                        "0" -> "PCM"
                        "1" -> "ALAC"
                        "2" -> "AAC"
                        "3" -> "AAC-ELD"
                        else -> null
                    }
                }.orEmpty()
                return if (codecs.isNotEmpty()) {
                    "Audio (${codecs.joinToString("/")})"
                } else {
                    "Audio Stream"
                }
            }
            val mask = parseFeaturesMask(featuresHex)
            if (mask == 0L) {
                return if (serviceType == "_raop._tcp.") "Audio Stream" else "Video • Photos • Mirror"
            }
            val caps = mutableListOf<String>()
            if ((mask and (1L shl 0)) != 0L) caps.add("Video")
            if ((mask and (1L shl 1)) != 0L) caps.add("Photos")
            if ((mask and (1L shl 7)) != 0L) caps.add("Mirror")
            if ((mask and (1L shl 9)) != 0L || (mask and (1L shl 10)) != 0L) caps.add("Audio")
            return if (caps.isEmpty()) "AirPlay Media" else caps.joinToString(" • ")
        }

        fun inferProtocolBadge(
            serviceType: String,
            featuresHex: String,
            attrs: Map<String, String>
        ): String {
            val mask = parseFeaturesMask(featuresHex)
            val protoVers = attrs["protovers"]?.toDoubleOrNull() ?: 0.0
            val isAirPlay2 = protoVers >= 1.1 || (mask and (1L shl 48)) != 0L || (mask and (1L shl 38)) != 0L
            return when {
                isAirPlay2 -> "AirPlay 2"
                serviceType == "_raop._tcp." -> "RAOP"
                else -> "AirPlay 1"
            }
        }
    }
}
