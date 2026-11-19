package com.example.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Binder
import android.os.IBinder

/**
 * Background LAN network scanning service powered by Android NSD (Network Service Discovery)
 * and Roku ECP SSDP. Automatically monitors Wi-Fi/Ethernet connectivity transitions and discovers
 * Roku, plug-in streamers, and AirPlay-enabled Smart TVs on the local subnet.
 */
class NsdScanService : Service() {

    companion object {
        const val ACTION_START_SCAN = "com.example.action.NSD_START_SCAN"
        const val ACTION_STOP_SCAN = "com.example.action.NSD_STOP_SCAN"
        const val ACTION_RESCAN = "com.example.action.NSD_RESCAN"

        fun createStartIntent(context: Context): Intent =
            Intent(context, NsdScanService::class.java).apply { action = ACTION_START_SCAN }

        fun createStopIntent(context: Context): Intent =
            Intent(context, NsdScanService::class.java).apply { action = ACTION_STOP_SCAN }

        fun createRescanIntent(context: Context): Intent =
            Intent(context, NsdScanService::class.java).apply { action = ACTION_RESCAN }
    }

    inner class LocalBinder : Binder() {
        fun getService(): NsdScanService = this@NsdScanService
    }

    private val binder = LocalBinder()
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var autoScanEnabled: Boolean = true

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        CastEngineHolder.ensureInitialized(this)
        CastEngineHolder.discoveryManager.onDiscoveryEvent = { summary, details ->
            CastEngineHolder.airPlayClient.appendSystemLog(summary, details)
        }
        registerWifiNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SCAN -> {
                autoScanEnabled = false
                CastEngineHolder.discoveryManager.stopDiscovery()
                CastEngineHolder.airPlayClient.appendSystemLog(
                    "NSD Service Paused",
                    "Local network receiver scanning stopped by user"
                )
            }
            ACTION_RESCAN -> {
                autoScanEnabled = true
                CastEngineHolder.discoveryManager.startDiscovery()
            }
            ACTION_START_SCAN, null -> {
                autoScanEnabled = true
                if (!CastEngineHolder.discoveryManager.isScanning.value) {
                    CastEngineHolder.discoveryManager.startDiscovery()
                }
            }
        }
        return START_STICKY
    }

    fun triggerRescan() {
        autoScanEnabled = true
        CastEngineHolder.discoveryManager.startDiscovery()
    }

    fun pauseScanning() {
        autoScanEnabled = false
        CastEngineHolder.discoveryManager.stopDiscovery()
    }

    private fun registerWifiNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        connectivityManager = cm
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (autoScanEnabled) {
                    CastEngineHolder.airPlayClient.appendSystemLog(
                        "Wi-Fi Network Available",
                        "Auto-starting NSD AirPlay receiver scan"
                    )
                    CastEngineHolder.discoveryManager.startDiscovery()
                }
            }

            override fun onLost(network: Network) {
                CastEngineHolder.airPlayClient.appendSystemLog(
                    "Wi-Fi Network Lost",
                    "Pausing NSD discovery until LAN reconnects"
                )
                CastEngineHolder.discoveryManager.stopDiscovery()
            }
        }
        networkCallback = callback
        runCatching { cm.registerNetworkCallback(request, callback) }
    }

    override fun onDestroy() {
        networkCallback?.let { cb ->
            runCatching { connectivityManager?.unregisterNetworkCallback(cb) }
        }
        networkCallback = null
        CastEngineHolder.discoveryManager.onDiscoveryEvent = null
        CastEngineHolder.discoveryManager.stopDiscovery()
        super.onDestroy()
    }
}
