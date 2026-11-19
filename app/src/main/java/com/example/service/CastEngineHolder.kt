package com.example.service

import android.content.Context
import com.example.data.PairedDevicesRepository
import com.example.data.SpoofPreferencesRepository
import com.example.network.AirPlayClient
import com.example.network.AirPlayDiscoveryManager
import com.example.network.LocalMediaHttpServer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

data class MirrorLiveStats(
    val isActive: Boolean = false,
    val width: Int = 0,
    val height: Int = 0,
    val currentFps: Int = 0,
    val bitrateMbps: Float = 0f,
    val totalBytesSent: Long = 0L,
    val elapsedSec: Long = 0L
)

object CastEngineHolder {

    @Volatile
    private var initialized = false

    lateinit var preferencesRepository: SpoofPreferencesRepository
        private set
    lateinit var pairedDevicesRepository: PairedDevicesRepository
        private set
    lateinit var discoveryManager: AirPlayDiscoveryManager
        private set
    lateinit var mediaServer: LocalMediaHttpServer
        private set
    lateinit var airPlayClient: AirPlayClient
        private set

    private val _mirrorStats = MutableStateFlow(MirrorLiveStats())
    val mirrorStats: StateFlow<MirrorLiveStats> = _mirrorStats.asStateFlow()

    private val _latestPreviewFrame = MutableStateFlow<ByteArray?>(null)
    val latestPreviewFrame: StateFlow<ByteArray?> = _latestPreviewFrame.asStateFlow()

    private val _stopMediaRequested = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val stopMediaRequested: SharedFlow<Unit> = _stopMediaRequested.asSharedFlow()

    fun ensureInitialized(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val appCtx = context.applicationContext
            preferencesRepository = SpoofPreferencesRepository(appCtx)
            pairedDevicesRepository = PairedDevicesRepository(appCtx)
            discoveryManager = AirPlayDiscoveryManager(appCtx)
            mediaServer = LocalMediaHttpServer(appCtx.contentResolver).apply { start() }
            airPlayClient = AirPlayClient()
            initialized = true
        }
    }

    fun updateMirrorStats(stats: MirrorLiveStats) {
        _mirrorStats.value = stats
    }

    fun updatePreviewFrame(jpegBytes: ByteArray?) {
        _latestPreviewFrame.value = jpegBytes
    }

    fun requestStopMedia() {
        _stopMediaRequested.tryEmit(Unit)
    }
}
