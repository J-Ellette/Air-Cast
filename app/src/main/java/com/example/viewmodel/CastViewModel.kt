package com.example.viewmodel

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.model.AirPlayProtocolMode
import com.example.model.AirPlayReceiver
import com.example.model.AppleSpoofPreset
import com.example.model.ConnectionStatus
import com.example.model.MediaCategory
import com.example.model.MirrorFpsCap
import com.example.model.MirrorQuality
import com.example.model.MirrorResolution
import com.example.model.PairedDeviceRecord
import com.example.model.PhotoTransition
import com.example.model.PickedMediaItem
import com.example.model.PlaybackTelemetry
import com.example.model.ProtocolLogEntry
import com.example.model.ReceiverType
import com.example.model.ReconnectPromptState
import com.example.model.SpoofProfile
import com.example.model.StreamStatus
import com.example.network.AirPlayResult
import com.example.network.NetworkUtils
import com.example.service.CastEngineHolder
import com.example.service.MediaCastService
import com.example.service.MirrorLiveStats
import com.example.service.ScreenCastService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.UUID

data class CastUiState(
    val wifiConnected: Boolean = false,
    val localIpAddress: String? = null,
    val serverPort: Int = 0,
    val isScanning: Boolean = false,
    val scanStatusMessage: String = "Ready to scan",
    val receivers: List<AirPlayReceiver> = emptyList(),
    val selectedReceiver: AirPlayReceiver? = null,
    val isProbingReceiver: Boolean = false,
    val isRequestingPin: Boolean = false,
    val spoofProfile: SpoofProfile = SpoofProfile(),
    val telemetry: PlaybackTelemetry = PlaybackTelemetry(),
    val mirrorStats: MirrorLiveStats = MirrorLiveStats(),
    val mirrorPreviewBytes: ByteArray? = null,
    val mediaQueue: List<PickedMediaItem> = emptyList(),
    val currentQueueIndex: Int = 0,
    val slideshowEnabled: Boolean = false,
    val pinDialogReceiver: AirPlayReceiver? = null,
    val pinDialogError: String? = null,
    val showAddManualDialog: Boolean = false,
    val showStreamUrlDialog: Boolean = false,
    val showSettingsDialog: Boolean = false,
    val statusBannerMessage: String? = null,
    val pairedDevicesCount: Int = 0,
    val reconnectAttempt: Int = 0,
    val reconnectPrompt: ReconnectPromptState? = null,
    val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED
) {
    val activeServerMirrorUrl: String?
        get() = if (localIpAddress != null && serverPort > 0) {
            "http://$localIpAddress:$serverPort/mirror.html"
        } else null
}

class CastViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        const val MAX_RECONNECT_ATTEMPTS = 3
    }

    init {
        CastEngineHolder.ensureInitialized(application)
    }

    private val prefsRepo = CastEngineHolder.preferencesRepository
    private val pairedDevicesRepo = CastEngineHolder.pairedDevicesRepository
    private val discovery = CastEngineHolder.discoveryManager
    private val mediaServer = CastEngineHolder.mediaServer
    private val airPlayClient = CastEngineHolder.airPlayClient

    private val _selectedReceiver = MutableStateFlow<AirPlayReceiver?>(null)
    private val _isProbing = MutableStateFlow(false)
    private val _isRequestingPin = MutableStateFlow(false)
    private val _telemetry = MutableStateFlow(PlaybackTelemetry())
    private val _mediaQueue = MutableStateFlow<List<PickedMediaItem>>(emptyList())
    private val _currentQueueIndex = MutableStateFlow(0)
    private val _slideshowEnabled = MutableStateFlow(false)
    private val _pinDialogReceiver = MutableStateFlow<AirPlayReceiver?>(null)
    private val _pinDialogError = MutableStateFlow<String?>(null)
    private val _showAddManualDialog = MutableStateFlow(false)
    private val _showStreamUrlDialog = MutableStateFlow(false)
    private val _showSettingsDialog = MutableStateFlow(false)
    private val _bannerMessage = MutableStateFlow<String?>(null)
    private val _reconnectAttempt = MutableStateFlow(0)
    private val _reconnectPrompt = MutableStateFlow<ReconnectPromptState?>(null)
    private val _networkInfo = MutableStateFlow(readNetworkSnapshot())

    val protocolLogs: StateFlow<List<ProtocolLogEntry>> = airPlayClient.protocolLogs

    private var playbackPollJob: Job? = null
    private var slideshowJob: Job? = null
    private var lastMirroredFramePumpJob: Job? = null
    private var mirrorAirPlayFramePushJob: Job? = null
    private var reconnectJob: Job? = null
    private var wasConnectedWithoutPin: Boolean = false
    private var lastActiveStreamUrl: String? = null
    private var lastActiveStreamTitle: String = ""

    private val combinedReceivers = combine(
        discovery.discoveredReceivers,
        prefsRepo.manualReceiversFlow,
        pairedDevicesRepo.pairedDevicesFlow
    ) { discovered, manual, pairedMap ->
        val discoveredAndManual = (discovered + manual).distinctBy { "${it.host}:${it.port}" }
        val existingKeys = discoveredAndManual.map { "${it.host}:${it.port}" }.toSet()
        val pairedFallbacks = pairedMap.values
            .filter { it.receiverKey !in existingKeys }
            .map { pairedDevicesRepo.toReceiverFallback(it) }

        (discoveredAndManual + pairedFallbacks)
            .map { rx ->
                val key = "${rx.host}:${rx.port}"
                if (pairedMap.containsKey(key)) {
                    rx.copy(isPaired = true, requiresPin = true)
                } else {
                    rx.copy(requiresPin = true)
                }
            }
            .sortedWith(
                compareByDescending<AirPlayReceiver> { it.isPaired }
                    .thenByDescending { !it.isManual }
                    .thenBy { it.name.lowercase() }
            )
    }

    val uiState: StateFlow<CastUiState> = combine(
        _networkInfo,
        discovery.isScanning,
        discovery.scanStatusMessage,
        combinedReceivers,
        _selectedReceiver,
        _isProbing,
        prefsRepo.spoofProfileFlow,
        _telemetry,
        CastEngineHolder.mirrorStats,
        CastEngineHolder.latestPreviewFrame,
        _mediaQueue,
        _currentQueueIndex,
        _slideshowEnabled,
        _pinDialogReceiver,
        _pinDialogError,
        _showAddManualDialog,
        _showStreamUrlDialog,
        _bannerMessage,
        _isRequestingPin,
        pairedDevicesRepo.pairedDevicesFlow,
        _showSettingsDialog,
        _reconnectAttempt,
        _reconnectPrompt
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val net = values[0] as Triple<Boolean, String?, Int>
        val scanning = values[1] as Boolean
        val scanMsg = values[2] as String
        @Suppress("UNCHECKED_CAST")
        val receivers = values[3] as List<AirPlayReceiver>
        val selected = values[4] as AirPlayReceiver?
        val probing = values[5] as Boolean
        val profile = values[6] as SpoofProfile
        val telemetry = values[7] as PlaybackTelemetry
        val mirrorStats = values[8] as MirrorLiveStats
        val previewBytes = values[9] as ByteArray?
        @Suppress("UNCHECKED_CAST")
        val queue = values[10] as List<PickedMediaItem>
        val queueIdx = values[11] as Int
        val slideshow = values[12] as Boolean
        val pinTarget = values[13] as AirPlayReceiver?
        val pinErr = values[14] as String?
        val showManual = values[15] as Boolean
        val showUrl = values[16] as Boolean
        val banner = values[17] as String?
        val requestingPin = values[18] as Boolean
        @Suppress("UNCHECKED_CAST")
        val pairedMap = values[19] as Map<String, PairedDeviceRecord>
        val showSettings = values[20] as Boolean
        val reconnectAttempt = values[21] as Int
        val reconnectPrompt = values[22] as ReconnectPromptState?

        val derivedConnectionStatus = when {
            probing || requestingPin || pinTarget != null || reconnectAttempt > 0 ||
                telemetry.status == StreamStatus.CONNECTING -> ConnectionStatus.CONNECTING
            selected != null -> ConnectionStatus.CONNECTED
            else -> ConnectionStatus.DISCONNECTED
        }

        CastUiState(
            wifiConnected = net.first,
            localIpAddress = net.second,
            serverPort = net.third,
            isScanning = scanning,
            scanStatusMessage = scanMsg,
            receivers = receivers,
            selectedReceiver = selected,
            isProbingReceiver = probing,
            isRequestingPin = requestingPin,
            spoofProfile = profile,
            telemetry = telemetry,
            mirrorStats = mirrorStats,
            mirrorPreviewBytes = previewBytes,
            mediaQueue = queue,
            currentQueueIndex = queueIdx,
            slideshowEnabled = slideshow,
            pinDialogReceiver = pinTarget,
            pinDialogError = pinErr,
            showAddManualDialog = showManual,
            showStreamUrlDialog = showUrl,
            showSettingsDialog = showSettings,
            statusBannerMessage = banner,
            pairedDevicesCount = pairedMap.size,
            reconnectAttempt = reconnectAttempt,
            reconnectPrompt = reconnectPrompt,
            connectionStatus = derivedConnectionStatus
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = CastUiState()
    )

    init {
        airPlayClient.onPairingPersisted = { record ->
            viewModelScope.launch {
                pairedDevicesRepo.savePairedDevice(record)
            }
        }
        startDiscovery()
        viewModelScope.launch {
            CastEngineHolder.stopMediaRequested.collect {
                stopAllCasting()
            }
        }
        viewModelScope.launch {
            while (isActive) {
                _networkInfo.value = readNetworkSnapshot()
                delay(4_000)
            }
        }
    }

    private fun readNetworkSnapshot(): Triple<Boolean, String?, Int> {
        val ctx = getApplication<Application>()
        val wifi = NetworkUtils.isWifiConnected(ctx)
        val ip = NetworkUtils.getLocalWifiIpAddress()
        val port = mediaServer.start()
        return Triple(wifi, ip, port)
    }

    fun refreshNetworkAndScan() {
        _networkInfo.value = readNetworkSnapshot()
        val ctx = getApplication<Application>()
        ctx.startService(com.example.service.NsdScanService.createRescanIntent(ctx))
    }

    fun startDiscovery() {
        val ctx = getApplication<Application>()
        ctx.startService(com.example.service.NsdScanService.createStartIntent(ctx))
    }

    fun stopDiscovery() {
        val ctx = getApplication<Application>()
        ctx.startService(com.example.service.NsdScanService.createStopIntent(ctx))
    }

    fun toggleDiscovery() {
        if (uiState.value.isScanning) {
            stopDiscovery()
        } else {
            refreshNetworkAndScan()
        }
    }

    fun dismissBanner() {
        _bannerMessage.value = null
    }

    fun selectReceiver(receiver: AirPlayReceiver) {
        val previous = _selectedReceiver.value
        if (previous != null && (previous.host != receiver.host || previous.port != receiver.port)) {
            stopAllCasting()
            airPlayClient.disconnectSession(previous.host, previous.port)
            _selectedReceiver.value = null
            _telemetry.value = PlaybackTelemetry()
        }
        // Always require 4-digit PIN entry every time we connect to a PIN-protected TV or streamer
        openPinPairingDialog(receiver)
    }

    fun connectWithoutPin(receiver: AirPlayReceiver) {
        val previous = _selectedReceiver.value
        if (previous != null && (previous.host != receiver.host || previous.port != receiver.port)) {
            stopAllCasting()
            airPlayClient.disconnectSession(previous.host, previous.port)
            _selectedReceiver.value = null
            _telemetry.value = PlaybackTelemetry()
        }
        _pinDialogReceiver.value = null
        _pinDialogError.value = null
        _isRequestingPin.value = false

        viewModelScope.launch {
            _isProbing.value = true
            when (val res = airPlayClient.connectWithoutPin(receiver, uiState.value.spoofProfile)) {
                is AirPlayResult.Success -> {
                    wasConnectedWithoutPin = true
                    _reconnectAttempt.value = 0
                    _reconnectPrompt.value = null
                    _selectedReceiver.value = receiver.copy(
                        requiresPin = false,
                        isReachable = true
                    )
                    pairedDevicesRepo.setLastConnectedReceiver("${receiver.host}:${receiver.port}")
                    _bannerMessage.value = "Connected to ${receiver.name} (No PIN)"
                }
                is AirPlayResult.PinRequired -> {
                    _bannerMessage.value = "${receiver.name} requires a 4-digit PIN"
                    openPinPairingDialog(receiver)
                }
                is AirPlayResult.Failure -> {
                    _bannerMessage.value = "Could not connect without PIN: ${res.message}"
                }
            }
            _isProbing.value = false
        }
    }

    fun disconnectReceiver() {
        val current = _selectedReceiver.value ?: return
        viewModelScope.launch {
            reconnectJob?.cancel()
            _reconnectAttempt.value = 0
            _reconnectPrompt.value = null
            wasConnectedWithoutPin = false
            lastActiveStreamUrl = null
            stopAllCasting()
            airPlayClient.disconnectSession(current.host, current.port)
            pairedDevicesRepo.setLastConnectedReceiver(null)
            _selectedReceiver.value = null
            _telemetry.value = PlaybackTelemetry()
            _bannerMessage.value = "Disconnected from ${current.name}"
        }
    }

    fun forgetDevice(receiver: AirPlayReceiver) {
        val key = "${receiver.host}:${receiver.port}"
        viewModelScope.launch {
            val selected = _selectedReceiver.value
            if (selected != null && selected.host == receiver.host && selected.port == receiver.port) {
                reconnectJob?.cancel()
                _reconnectAttempt.value = 0
                _reconnectPrompt.value = null
                wasConnectedWithoutPin = false
                lastActiveStreamUrl = null
                stopAllCasting()
                _selectedReceiver.value = null
                _telemetry.value = PlaybackTelemetry()
            }
            val pinTarget = _pinDialogReceiver.value
            if (pinTarget != null && pinTarget.host == receiver.host && pinTarget.port == receiver.port) {
                _pinDialogReceiver.value = null
                _pinDialogError.value = null
                _isRequestingPin.value = false
            }
            airPlayClient.forgetPairedCredentials(receiver.host, receiver.port)
            pairedDevicesRepo.forgetPairedDevice(key)
            prefsRepo.removeManualReceiver(receiver.id)
            prefsRepo.removeManualReceiverByEndpoint(receiver.host, receiver.port)
            discovery.forgetDiscoveredReceiver(receiver)
            _bannerMessage.value = "Forgot ${receiver.name}"
        }
    }

    fun forgetAllDevices() {
        viewModelScope.launch {
            reconnectJob?.cancel()
            _reconnectAttempt.value = 0
            _reconnectPrompt.value = null
            wasConnectedWithoutPin = false
            lastActiveStreamUrl = null
            stopAllCasting()
            _selectedReceiver.value = null
            _pinDialogReceiver.value = null
            _pinDialogError.value = null
            _isRequestingPin.value = false
            _telemetry.value = PlaybackTelemetry()
            airPlayClient.clearAllCredentials()
            pairedDevicesRepo.clearAllPairedDevices()
            prefsRepo.clearManualReceivers()
            _bannerMessage.value = "Forgot all saved TVs and streamers"
        }
    }

    fun openAddManualDialog() {
        _showAddManualDialog.value = true
    }

    fun closeAddManualDialog() {
        _showAddManualDialog.value = false
    }

    fun addManualReceiver(
        name: String,
        host: String,
        portText: String,
        type: ReceiverType,
        requiresPin: Boolean = true
    ) {
        val cleanHost = host.trim()
        if (cleanHost.isEmpty()) return
        val port = portText.trim().toIntOrNull() ?: 7000
        val cleanName = name.trim().ifEmpty { "${type.label} ($cleanHost)" }
        val receiver = AirPlayReceiver(
            id = "manual_${cleanHost}_$port",
            name = cleanName,
            host = cleanHost,
            port = port,
            type = type,
            serviceType = "_airplay._tcp.",
            model = if (type == ReceiverType.STREAMING_STICK) "Roku / Streaming Stick" else "Smart TV",
            requiresPin = requiresPin,
            isManual = true
        )
        viewModelScope.launch {
            prefsRepo.addManualReceiver(receiver)
            _showAddManualDialog.value = false
            if (requiresPin) {
                selectReceiver(receiver)
            } else {
                connectWithoutPin(receiver)
            }
        }
    }

    fun openStreamUrlDialog() {
        _showStreamUrlDialog.value = true
    }

    fun closeStreamUrlDialog() {
        _showStreamUrlDialog.value = false
    }

    fun openPinPairingDialog(receiver: AirPlayReceiver) {
        _pinDialogReceiver.value = receiver
        _pinDialogError.value = null
        requestPinChallenge(receiver)
    }

    fun retryPinPairing() {
        val receiver = _pinDialogReceiver.value ?: return
        _pinDialogError.value = null
        requestPinChallenge(receiver)
    }

    private fun requestPinChallenge(receiver: AirPlayReceiver) {
        viewModelScope.launch {
            _isRequestingPin.value = true
            when (val res = airPlayClient.startPinPairing(receiver, uiState.value.spoofProfile)) {
                is AirPlayResult.Failure -> {
                    _pinDialogError.value = "Could not prompt PIN on ${receiver.name}: ${res.message}. Tap Resend PIN or enter the TV passcode."
                }
                else -> Unit
            }
            _isRequestingPin.value = false
        }
    }

    fun dismissPinPairingDialog() {
        val target = _pinDialogReceiver.value
        val active = _selectedReceiver.value
        if (target != null && (active == null || active.host != target.host || active.port != target.port)) {
            airPlayClient.disconnectSession(target.host, target.port)
        }
        _pinDialogReceiver.value = null
        _pinDialogError.value = null
        _isRequestingPin.value = false
    }

    fun submitPairingPin(pin: String) {
        val target = _pinDialogReceiver.value ?: return
        viewModelScope.launch {
            _pinDialogError.value = null
            _isProbing.value = true
            when (val res = airPlayClient.submitPairingPin(target, uiState.value.spoofProfile, pin)) {
                is AirPlayResult.Success -> {
                    wasConnectedWithoutPin = false
                    _reconnectAttempt.value = 0
                    _reconnectPrompt.value = null
                    _pinDialogReceiver.value = null
                    _selectedReceiver.value = target.copy(requiresPin = true, isPaired = true, isReachable = true)
                    pairedDevicesRepo.setLastConnectedReceiver("${target.host}:${target.port}")
                    _bannerMessage.value = "Connected to ${target.name} (PIN verified)"
                }
                is AirPlayResult.Failure -> {
                    _pinDialogError.value = res.message
                }
                is AirPlayResult.PinRequired -> {
                    _pinDialogError.value = res.message
                }
            }
            _isProbing.value = false
        }
    }

    // ---- SETTINGS MENU & RECONNECT PROMPT ----

    fun openSettingsDialog() {
        _showSettingsDialog.value = true
    }

    fun closeSettingsDialog() {
        _showSettingsDialog.value = false
    }

    fun updateMirrorResolution(resolution: MirrorResolution) {
        viewModelScope.launch {
            val current = uiState.value.spoofProfile
            val syncedQuality = syncMirrorQuality(resolution, current.mirrorFpsCap)
            val updated = current.copy(
                mirrorResolution = resolution,
                mirrorQuality = syncedQuality
            )
            prefsRepo.saveSpoofProfile(updated)
            airPlayClient.appendSystemLog(
                "Mirror Resolution -> ${resolution.shortLabel}",
                "Capture target ${resolution.width}x${resolution.height} @ ${current.effectiveMirrorFps} fps"
            )
            if (_telemetry.value.activeCategory == MediaCategory.SCREEN &&
                _telemetry.value.status == StreamStatus.STREAMING
            ) {
                _bannerMessage.value = "Mirror resolution set to ${resolution.shortLabel} (restart Mirror to apply size change)"
            }
        }
    }

    fun updateMirrorFpsCap(fpsCap: MirrorFpsCap) {
        viewModelScope.launch {
            val current = uiState.value.spoofProfile
            val syncedQuality = syncMirrorQuality(current.mirrorResolution, fpsCap)
            val updated = current.copy(
                mirrorFpsCap = fpsCap,
                mirrorQuality = syncedQuality
            )
            prefsRepo.saveSpoofProfile(updated)
            airPlayClient.appendSystemLog(
                "Mirror FPS Cap -> ${fpsCap.fps} fps",
                "Stream pacing updated to ${fpsCap.label}"
            )
        }
    }

    private fun syncMirrorQuality(
        resolution: MirrorResolution,
        fpsCap: MirrorFpsCap
    ): MirrorQuality {
        val highFps = fpsCap == MirrorFpsCap.FPS_60
        return when (resolution) {
            MirrorResolution.SD_480P,
            MirrorResolution.HD_720P -> if (highFps) MirrorQuality.HD_720P_60 else MirrorQuality.HD_720P_30
            MirrorResolution.FHD_1080P -> if (highFps) MirrorQuality.FHD_1080P_60 else MirrorQuality.FHD_1080P_30
        }
    }

    fun retryAfterReconnectPrompt() {
        val prompt = _reconnectPrompt.value ?: return
        _reconnectPrompt.value = null
        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            when (prompt.category) {
                MediaCategory.SCREEN -> {
                    val ok = attemptAutoReconnectStream(
                        receiver = prompt.receiver,
                        category = MediaCategory.SCREEN,
                        initialError = prompt.lastError
                    ) {
                        val frame = CastEngineHolder.latestPreviewFrame.value ?: ByteArray(0)
                        if (frame.isNotEmpty()) {
                            airPlayClient.castPhoto(prompt.receiver, uiState.value.spoofProfile, frame, PhotoTransition.NONE)
                        } else {
                            AirPlayResult.Success(Unit, 200)
                        }
                    }
                    if (ok) {
                        startMirrorAirPlayFramePusher(prompt.receiver, uiState.value.spoofProfile)
                    }
                }
                MediaCategory.VIDEO, MediaCategory.MUSIC -> {
                    val resumeUrl = lastActiveStreamUrl
                    if (resumeUrl != null) {
                        val resumePos = _telemetry.value.positionSec
                        val ok = attemptAutoReconnectStream(
                            receiver = prompt.receiver,
                            category = prompt.category,
                            initialError = prompt.lastError
                        ) {
                            airPlayClient.castMediaUrl(
                                prompt.receiver,
                                uiState.value.spoofProfile,
                                resumeUrl,
                                resumePos
                            )
                        }
                        if (ok) {
                            startPlaybackPolling(prompt.receiver, prompt.category)
                        }
                    }
                }
                MediaCategory.PHOTOS -> {
                    val idx = _currentQueueIndex.value
                    castPhotoAtIndex(idx, prompt.receiver)
                }
            }
        }
    }

    fun enterPinFromReconnectPrompt() {
        val prompt = _reconnectPrompt.value ?: return
        _reconnectPrompt.value = null
        _reconnectAttempt.value = 0
        openPinPairingDialog(prompt.receiver)
    }

    fun dismissReconnectPrompt(disconnect: Boolean) {
        _reconnectPrompt.value = null
        _reconnectAttempt.value = 0
        if (disconnect) {
            disconnectReceiver()
        }
    }

    private suspend fun attemptAutoReconnectStream(
        receiver: AirPlayReceiver,
        category: MediaCategory,
        initialError: String,
        resumeAction: suspend () -> AirPlayResult<*>
    ): Boolean {
        var lastError = initialError
        for (attempt in 1..MAX_RECONNECT_ATTEMPTS) {
            val currentSelected = _selectedReceiver.value
            if (currentSelected == null ||
                currentSelected.host != receiver.host ||
                currentSelected.port != receiver.port
            ) {
                _reconnectAttempt.value = 0
                return false
            }

            _reconnectAttempt.value = attempt
            _telemetry.update {
                it.copy(
                    status = StreamStatus.CONNECTING,
                    errorMessage = "Connection dropped. Reconnecting ($attempt/$MAX_RECONNECT_ATTEMPTS)..."
                )
            }
            _bannerMessage.value = "Connection lost — auto-reconnecting (attempt $attempt of $MAX_RECONNECT_ATTEMPTS)..."
            airPlayClient.appendSystemLog(
                "Auto-Reconnect ($attempt/$MAX_RECONNECT_ATTEMPTS)",
                "Attempting to recover ${category.label} stream to ${receiver.name}"
            )

            delay(900L * attempt)

            when (val reconnectRes = airPlayClient.reconnectDroppedSession(
                receiver = receiver,
                profile = uiState.value.spoofProfile,
                wasConnectedWithoutPin = wasConnectedWithoutPin
            )) {
                is AirPlayResult.Success -> {
                    when (val resumeRes = resumeAction()) {
                        is AirPlayResult.Success -> {
                            _reconnectAttempt.value = 0
                            _reconnectPrompt.value = null
                            _telemetry.update {
                                it.copy(status = StreamStatus.STREAMING, errorMessage = null)
                            }
                            _bannerMessage.value = "Reconnected to ${receiver.name}"
                            return true
                        }
                        is AirPlayResult.PinRequired -> {
                            lastError = resumeRes.message
                        }
                        is AirPlayResult.Failure -> {
                            lastError = resumeRes.message
                        }
                    }
                }
                is AirPlayResult.PinRequired -> {
                    lastError = reconnectRes.message
                }
                is AirPlayResult.Failure -> {
                    lastError = reconnectRes.message
                }
            }
        }

        _reconnectAttempt.value = 0
        _telemetry.update {
            it.copy(
                status = StreamStatus.ERROR,
                errorMessage = "Dropped after $MAX_RECONNECT_ATTEMPTS reconnect attempts: $lastError"
            )
        }
        _reconnectPrompt.value = ReconnectPromptState(
            receiver = receiver,
            category = category,
            attemptsMade = MAX_RECONNECT_ATTEMPTS,
            lastError = lastError
        )
        _bannerMessage.value = "Reconnect failed after $MAX_RECONNECT_ATTEMPTS attempts"
        return false
    }

    // ---- SCREEN MIRRORING ----

    fun startScreenMirroring(resultCode: Int, data: Intent) {
        val ctx = getApplication<Application>()
        val profile = uiState.value.spoofProfile
        val receiver = _selectedReceiver.value
        val captureWidth = profile.effectiveMirrorWidth
        val captureHeight = profile.effectiveMirrorHeight
        val captureFps = profile.effectiveMirrorFps

        _reconnectAttempt.value = 0
        _reconnectPrompt.value = null

        val serviceIntent = ScreenCastService.createStartIntent(
            context = ctx,
            resultCode = resultCode,
            data = data,
            width = captureWidth,
            height = captureHeight,
            fps = captureFps,
            receiverName = receiver?.name ?: "Local Mirror Bridge"
        )
        ContextCompat.startForegroundService(ctx, serviceIntent)

        _telemetry.value = PlaybackTelemetry(
            status = StreamStatus.STREAMING,
            activeCategory = MediaCategory.SCREEN,
            currentTitle = "Live Device Screen",
            currentSubtitle = receiver?.let { "AirPlay Mirror -> ${it.name}" } ?: "Local LAN Mirror Server Active",
            resolutionLabel = profile.mirrorProfileLabel,
            fps = captureFps
        )

        if (receiver != null) {
            val ip = NetworkUtils.getLocalWifiIpAddress() ?: "127.0.0.1"
            mediaServer.start()
            val mirrorUrl = mediaServer.buildMirrorHtmlUrl(ip)
            airPlayClient.appendSystemLog(
                "Screen Mirror Started",
                "VirtualDisplay ${captureWidth}x${captureHeight}@${captureFps}fps -> AirPlay live frames + $mirrorUrl"
            )
            startMirrorTelemetrySync()
            startMirrorAirPlayFramePusher(receiver, profile)
        } else {
            startMirrorTelemetrySync()
        }
    }

    private fun startMirrorAirPlayFramePusher(receiver: AirPlayReceiver, initialProfile: SpoofProfile) {
        mirrorAirPlayFramePushJob?.cancel()
        mirrorAirPlayFramePushJob = viewModelScope.launch {
            var lastSentRef: ByteArray? = null
            var deliveredFrames = 0
            var consecutiveErrors = 0

            while (isActive && _telemetry.value.activeCategory == MediaCategory.SCREEN) {
                val activeProfile = uiState.value.spoofProfile
                val frame = CastEngineHolder.latestPreviewFrame.value
                if (frame != null && frame !== lastSentRef) {
                    lastSentRef = frame
                    when (val res = airPlayClient.castPhoto(receiver, activeProfile, frame, PhotoTransition.NONE)) {
                        is AirPlayResult.Success -> {
                            deliveredFrames++
                            consecutiveErrors = 0
                        }
                        is AirPlayResult.PinRequired -> {
                            _telemetry.update {
                                it.copy(
                                    status = StreamStatus.PIN_REQUIRED,
                                    errorMessage = "${receiver.name} requires AirPlay PIN verification"
                                )
                            }
                            _bannerMessage.value = "${receiver.name} requested a 4-digit PIN for mirroring"
                            openPinPairingDialog(receiver)
                            break
                        }
                        is AirPlayResult.Failure -> {
                            consecutiveErrors++
                            if (consecutiveErrors >= 2) {
                                val recovered = attemptAutoReconnectStream(
                                    receiver = receiver,
                                    category = MediaCategory.SCREEN,
                                    initialError = res.message
                                ) {
                                    val latestFrame = CastEngineHolder.latestPreviewFrame.value ?: frame
                                    airPlayClient.castPhoto(receiver, uiState.value.spoofProfile, latestFrame, PhotoTransition.NONE)
                                }
                                if (recovered) {
                                    deliveredFrames++
                                    consecutiveErrors = 0
                                } else {
                                    break
                                }
                            }
                        }
                    }
                }
                val fpsCap = uiState.value.spoofProfile.effectiveMirrorFps.coerceIn(10, 60)
                val frameIntervalMs = (1000L / fpsCap).coerceAtLeast(16L)
                delay(frameIntervalMs)
            }
        }
    }

    private fun startMirrorTelemetrySync() {
        lastMirroredFramePumpJob?.cancel()
        lastMirroredFramePumpJob = viewModelScope.launch {
            CastEngineHolder.mirrorStats.collect { stats ->
                if (_telemetry.value.activeCategory == MediaCategory.SCREEN) {
                    if (stats.isActive &&
                        _reconnectAttempt.value == 0 &&
                        _telemetry.value.status != StreamStatus.ERROR &&
                        _telemetry.value.status != StreamStatus.PIN_REQUIRED &&
                        _telemetry.value.status != StreamStatus.CONNECTING
                    ) {
                        _telemetry.update { current ->
                            current.copy(
                                status = StreamStatus.STREAMING,
                                fps = stats.currentFps,
                                bitrateMbps = stats.bitrateMbps,
                                bytesStreamed = stats.totalBytesSent,
                                elapsedSec = stats.elapsedSec,
                                resolutionLabel = "${stats.width}x${stats.height}"
                            )
                        }
                    }
                }
            }
        }
    }

    fun stopScreenMirroring() {
        val ctx = getApplication<Application>()
        ctx.startService(ScreenCastService.createStopIntent(ctx))
        reconnectJob?.cancel()
        _reconnectAttempt.value = 0
        _reconnectPrompt.value = null
        lastMirroredFramePumpJob?.cancel()
        mirrorAirPlayFramePushJob?.cancel()
        viewModelScope.launch {
            _selectedReceiver.value?.let { receiver ->
                airPlayClient.stopPlayback(receiver, uiState.value.spoofProfile)
            }
            _telemetry.value = PlaybackTelemetry()
        }
    }

    // ---- VIDEO STREAMING ----

    fun handlePickedVideo(uri: Uri) {
        val receiver = requireReceiverOrWarn() ?: return
        viewModelScope.launch {
            val item = withContext(Dispatchers.IO) {
                extractMediaItem(uri, MediaCategory.VIDEO, "video/mp4")
            }
            _mediaQueue.value = listOf(item)
            _currentQueueIndex.value = 0
            streamQueuedItem(item, receiver)
        }
    }

    fun castDirectStreamUrl(url: String, title: String) {
        val cleanUrl = url.trim()
        if (cleanUrl.isEmpty()) return
        val receiver = requireReceiverOrWarn() ?: return
        val item = PickedMediaItem(
            id = UUID.randomUUID().toString(),
            uriString = cleanUrl,
            title = title.trim().ifEmpty { "Network Video Stream" },
            subtitle = cleanUrl,
            mimeType = "video/mp4",
            category = MediaCategory.VIDEO
        )
        _mediaQueue.value = listOf(item)
        _currentQueueIndex.value = 0
        _showStreamUrlDialog.value = false
        viewModelScope.launch {
            streamUrlToReceiver(item, cleanUrl, receiver)
        }
    }

    // ---- MUSIC STREAMING ----

    fun handlePickedMusic(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val receiver = requireReceiverOrWarn() ?: return
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) {
                uris.map { extractMediaItem(it, MediaCategory.MUSIC, "audio/mpeg") }
            }
            _mediaQueue.value = items
            _currentQueueIndex.value = 0
            streamQueuedItem(items.first(), receiver)
        }
    }

    // ---- PHOTO CASTING ----

    fun handlePickedPhotos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val receiver = requireReceiverOrWarn() ?: return
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) {
                uris.mapIndexed { idx, uri ->
                    val name = queryDisplayName(uri) ?: "Photo ${idx + 1}"
                    PickedMediaItem(
                        id = "${uri}_$idx",
                        uriString = uri.toString(),
                        title = name,
                        subtitle = "${idx + 1} of ${uris.size}",
                        mimeType = "image/jpeg",
                        category = MediaCategory.PHOTOS
                    )
                }
            }
            _mediaQueue.value = items
            _currentQueueIndex.value = 0
            castPhotoAtIndex(0, receiver)
        }
    }

    fun selectQueueIndex(index: Int) {
        val queue = _mediaQueue.value
        if (index !in queue.indices) return
        val receiver = _selectedReceiver.value ?: return
        _currentQueueIndex.value = index
        val item = queue[index]
        viewModelScope.launch {
            if (item.category == MediaCategory.PHOTOS) {
                castPhotoAtIndex(index, receiver)
            } else {
                streamQueuedItem(item, receiver)
            }
        }
    }

    fun nextInQueue() {
        val queue = _mediaQueue.value
        if (queue.isEmpty()) return
        val next = (_currentQueueIndex.value + 1) % queue.size
        selectQueueIndex(next)
    }

    fun previousInQueue() {
        val queue = _mediaQueue.value
        if (queue.isEmpty()) return
        val prev = if (_currentQueueIndex.value - 1 < 0) queue.lastIndex else _currentQueueIndex.value - 1
        selectQueueIndex(prev)
    }

    fun toggleSlideshow() {
        val enable = !_slideshowEnabled.value
        _slideshowEnabled.value = enable
        slideshowJob?.cancel()
        if (enable) {
            slideshowJob = viewModelScope.launch {
                while (isActive && _slideshowEnabled.value) {
                    val intervalSec = uiState.value.spoofProfile.slideshowIntervalSec.coerceIn(2, 30)
                    delay(intervalSec * 1000L)
                    if (_mediaQueue.value.isNotEmpty()) {
                        nextInQueue()
                    }
                }
            }
        }
    }

    private suspend fun castPhotoAtIndex(index: Int, receiver: AirPlayReceiver) {
        val item = _mediaQueue.value.getOrNull(index) ?: return
        _telemetry.value = PlaybackTelemetry(
            status = StreamStatus.CONNECTING,
            activeCategory = MediaCategory.PHOTOS,
            currentTitle = item.title,
            currentSubtitle = "Photo ${index + 1} of ${_mediaQueue.value.size} • ${receiver.name}"
        )

        val jpegBytes = withContext(Dispatchers.IO) {
            encodePhotoToJpeg(Uri.parse(item.uriString))
        }
        if (jpegBytes == null) {
            _telemetry.update { it.copy(status = StreamStatus.ERROR, errorMessage = "Unable to decode selected image") }
            return
        }

        val ctx = getApplication<Application>()
        ContextCompat.startForegroundService(
            ctx,
            MediaCastService.createStartIntent(ctx, item.title, receiver.name)
        )

        val profile = uiState.value.spoofProfile
        when (val res = airPlayClient.castPhoto(receiver, profile, jpegBytes, profile.photoTransition)) {
            is AirPlayResult.Success -> {
                _telemetry.value = PlaybackTelemetry(
                    status = StreamStatus.STREAMING,
                    activeCategory = MediaCategory.PHOTOS,
                    currentTitle = item.title,
                    currentSubtitle = "Photo ${index + 1} of ${_mediaQueue.value.size} • ${receiver.name}",
                    bytesStreamed = jpegBytes.size.toLong()
                )
            }
            is AirPlayResult.PinRequired -> {
                _telemetry.update { it.copy(status = StreamStatus.PIN_REQUIRED) }
                openPinPairingDialog(receiver)
            }
            is AirPlayResult.Failure -> {
                val recovered = attemptAutoReconnectStream(
                    receiver = receiver,
                    category = MediaCategory.PHOTOS,
                    initialError = res.message
                ) {
                    airPlayClient.castPhoto(receiver, uiState.value.spoofProfile, jpegBytes, profile.photoTransition)
                }
                if (recovered) {
                    _telemetry.value = PlaybackTelemetry(
                        status = StreamStatus.STREAMING,
                        activeCategory = MediaCategory.PHOTOS,
                        currentTitle = item.title,
                        currentSubtitle = "Photo ${index + 1} of ${_mediaQueue.value.size} • ${receiver.name}",
                        bytesStreamed = jpegBytes.size.toLong()
                    )
                }
            }
        }
    }

    private suspend fun streamQueuedItem(item: PickedMediaItem, receiver: AirPlayReceiver) {
        val localIp = NetworkUtils.getLocalWifiIpAddress()
        if (localIp == null) {
            _bannerMessage.value = "Connect to Wi-Fi so ${receiver.name} can reach your phone's stream"
            _telemetry.update {
                it.copy(status = StreamStatus.ERROR, errorMessage = "No Wi-Fi IPv4 address available")
            }
            return
        }
        mediaServer.start()
        val registered = withContext(Dispatchers.IO) {
            mediaServer.registerMediaUri(Uri.parse(item.uriString), item.mimeType)
        }
        val streamUrl = mediaServer.buildMediaUrl(localIp, registered.token)
        streamUrlToReceiver(item, streamUrl, receiver)
    }

    private suspend fun streamUrlToReceiver(
        item: PickedMediaItem,
        streamUrl: String,
        receiver: AirPlayReceiver
    ) {
        lastActiveStreamUrl = streamUrl
        lastActiveStreamTitle = item.title
        _reconnectAttempt.value = 0
        _reconnectPrompt.value = null

        _telemetry.value = PlaybackTelemetry(
            status = StreamStatus.CONNECTING,
            activeCategory = item.category,
            currentTitle = item.title,
            currentSubtitle = "${item.subtitle} • ${receiver.name}",
            durationSec = item.durationMs / 1000.0,
            volumePercent = _telemetry.value.volumePercent
        )

        val ctx = getApplication<Application>()
        ContextCompat.startForegroundService(
            ctx,
            MediaCastService.createStartIntent(ctx, item.title, receiver.name)
        )

        val profile = uiState.value.spoofProfile
        when (val res = airPlayClient.castMediaUrl(receiver, profile, streamUrl, 0.0)) {
            is AirPlayResult.Success -> {
                _telemetry.update {
                    it.copy(status = StreamStatus.STREAMING, errorMessage = null)
                }
                startPlaybackPolling(receiver, item.category)
            }
            is AirPlayResult.PinRequired -> {
                _telemetry.update { it.copy(status = StreamStatus.PIN_REQUIRED) }
                openPinPairingDialog(receiver)
            }
            is AirPlayResult.Failure -> {
                val recovered = attemptAutoReconnectStream(
                    receiver = receiver,
                    category = item.category,
                    initialError = res.message
                ) {
                    airPlayClient.castMediaUrl(receiver, uiState.value.spoofProfile, streamUrl, 0.0)
                }
                if (recovered) {
                    startPlaybackPolling(receiver, item.category)
                }
            }
        }
    }

    private fun startPlaybackPolling(receiver: AirPlayReceiver, category: MediaCategory) {
        playbackPollJob?.cancel()
        playbackPollJob = viewModelScope.launch {
            var consecutivePollFailures = 0
            while (isActive) {
                delay(1_500)
                val status = _telemetry.value.status
                if (status != StreamStatus.STREAMING && status != StreamStatus.PAUSED) break
                when (val info = airPlayClient.getPlaybackInfo(receiver, uiState.value.spoofProfile)) {
                    is AirPlayResult.Success -> {
                        consecutivePollFailures = 0
                        _telemetry.update { current ->
                            current.copy(
                                positionSec = info.data.positionSec,
                                durationSec = if (info.data.durationSec > 0.0) info.data.durationSec else current.durationSec
                            )
                        }
                    }
                    is AirPlayResult.PinRequired -> {
                        _telemetry.update { it.copy(status = StreamStatus.PIN_REQUIRED) }
                        openPinPairingDialog(receiver)
                        break
                    }
                    is AirPlayResult.Failure -> {
                        consecutivePollFailures++
                        if (consecutivePollFailures >= 2) {
                            val resumeUrl = lastActiveStreamUrl
                            if (resumeUrl != null) {
                                val resumePos = _telemetry.value.positionSec
                                val recovered = attemptAutoReconnectStream(
                                    receiver = receiver,
                                    category = category,
                                    initialError = info.message
                                ) {
                                    airPlayClient.castMediaUrl(
                                        receiver,
                                        uiState.value.spoofProfile,
                                        resumeUrl,
                                        resumePos
                                    )
                                }
                                if (recovered) {
                                    consecutivePollFailures = 0
                                } else {
                                    break
                                }
                            } else {
                                break
                            }
                        }
                    }
                }
            }
        }
    }

    fun togglePlayPause() {
        val receiver = _selectedReceiver.value ?: return
        val currentlyPaused = _telemetry.value.status == StreamStatus.PAUSED
        val targetRate = if (currentlyPaused) 1.0 else 0.0
        viewModelScope.launch {
            val res = airPlayClient.setPlaybackRate(receiver, uiState.value.spoofProfile, targetRate)
            if (res is AirPlayResult.Success) {
                _telemetry.update {
                    it.copy(status = if (currentlyPaused) StreamStatus.STREAMING else StreamStatus.PAUSED)
                }
            }
        }
    }

    fun seekToPosition(seconds: Double) {
        val receiver = _selectedReceiver.value ?: return
        _telemetry.update { it.copy(positionSec = seconds) }
        viewModelScope.launch {
            airPlayClient.seekTo(receiver, uiState.value.spoofProfile, seconds)
        }
    }

    fun setVolume(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        _telemetry.update { it.copy(volumePercent = clamped) }
        val receiver = _selectedReceiver.value ?: return
        viewModelScope.launch {
            airPlayClient.setVolume(receiver, uiState.value.spoofProfile, clamped)
        }
    }

    fun stopAllCasting() {
        reconnectJob?.cancel()
        _reconnectAttempt.value = 0
        _reconnectPrompt.value = null
        playbackPollJob?.cancel()
        slideshowJob?.cancel()
        lastMirroredFramePumpJob?.cancel()
        mirrorAirPlayFramePushJob?.cancel()
        _slideshowEnabled.value = false

        val ctx = getApplication<Application>()
        ctx.startService(ScreenCastService.createStopIntent(ctx))
        ctx.startService(MediaCastService.createStopIntent(ctx))

        viewModelScope.launch {
            _selectedReceiver.value?.let { receiver ->
                airPlayClient.stopPlayback(receiver, uiState.value.spoofProfile)
            }
            _telemetry.value = PlaybackTelemetry()
        }
    }

    // ---- APPLE SPOOF LAB ----

    fun selectSpoofPreset(preset: AppleSpoofPreset) {
        viewModelScope.launch {
            val current = uiState.value.spoofProfile
            val updated = current.copy(
                preset = preset,
                clientName = preset.displayName,
                modelIdentifier = preset.modelIdentifier,
                userAgent = preset.userAgent,
                protocolMode = if (preset == AppleSpoofPreset.IPHONE_CLASSIC) {
                    AirPlayProtocolMode.AIRPLAY_1
                } else {
                    AirPlayProtocolMode.AIRPLAY_2
                }
            )
            prefsRepo.saveSpoofProfile(updated)
            airPlayClient.appendSystemLog(
                "Spoof Identity -> ${preset.displayName}",
                "Model=${preset.modelIdentifier}, UA=${preset.userAgent}"
            )
        }
    }

    fun updateClientName(name: String) {
        viewModelScope.launch {
            val updated = uiState.value.spoofProfile.copy(clientName = name)
            prefsRepo.saveSpoofProfile(updated)
        }
    }

    fun updateUserAgent(ua: String) {
        viewModelScope.launch {
            val updated = uiState.value.spoofProfile.copy(userAgent = ua)
            prefsRepo.saveSpoofProfile(updated)
        }
    }

    fun updateProtocolMode(mode: AirPlayProtocolMode) {
        viewModelScope.launch {
            val updated = uiState.value.spoofProfile.copy(protocolMode = mode)
            prefsRepo.saveSpoofProfile(updated)
        }
    }

    fun updateMirrorQuality(quality: MirrorQuality) {
        viewModelScope.launch {
            val res = when (quality) {
                MirrorQuality.HD_720P_30, MirrorQuality.HD_720P_60 -> MirrorResolution.HD_720P
                MirrorQuality.FHD_1080P_30, MirrorQuality.FHD_1080P_60 -> MirrorResolution.FHD_1080P
            }
            val fps = when (quality) {
                MirrorQuality.HD_720P_60, MirrorQuality.FHD_1080P_60 -> MirrorFpsCap.FPS_60
                MirrorQuality.HD_720P_30, MirrorQuality.FHD_1080P_30 -> MirrorFpsCap.FPS_30
            }
            val updated = uiState.value.spoofProfile.copy(
                mirrorQuality = quality,
                mirrorResolution = res,
                mirrorFpsCap = fps
            )
            prefsRepo.saveSpoofProfile(updated)
        }
    }

    fun updatePhotoTransition(transition: PhotoTransition) {
        viewModelScope.launch {
            val updated = uiState.value.spoofProfile.copy(photoTransition = transition)
            prefsRepo.saveSpoofProfile(updated)
        }
    }

    fun updateSlideshowInterval(seconds: Int) {
        viewModelScope.launch {
            val updated = uiState.value.spoofProfile.copy(slideshowIntervalSec = seconds.coerceIn(2, 30))
            prefsRepo.saveSpoofProfile(updated)
        }
    }

    fun regenerateHardwareIdentifiers() {
        viewModelScope.launch {
            val current = uiState.value.spoofProfile
            val updated = current.copy(
                deviceMacId = NetworkUtils.generateAppleStyleMac(),
                sessionUuid = NetworkUtils.generateSessionUuid(),
                dacpId = NetworkUtils.generateDacpId(),
                activeRemote = NetworkUtils.generateActiveRemote()
            )
            prefsRepo.saveSpoofProfile(updated)
            airPlayClient.appendSystemLog(
                "Rotated Apple Spoof IDs",
                "MAC=${updated.deviceMacId} | Session=${updated.sessionUuid.take(8)}..."
            )
            _bannerMessage.value = "Generated new Apple MAC & AirPlay session IDs"
        }
    }

    fun clearProtocolLogs() {
        airPlayClient.clearLogs()
    }

    private fun requireReceiverOrWarn(): AirPlayReceiver? {
        val current = _selectedReceiver.value
        if (current == null) {
            _bannerMessage.value = "Select or add a Roku, plug-in streamer, or Smart TV first"
        }
        return current
    }

    private fun extractMediaItem(
        uri: Uri,
        category: MediaCategory,
        fallbackMime: String
    ): PickedMediaItem {
        val ctx = getApplication<Application>()
        val cr = ctx.contentResolver
        val displayName = queryDisplayName(uri) ?: "Untitled ${category.label}"
        val mime = cr.getType(uri) ?: fallbackMime
        var title = displayName
        var artist = if (category == MediaCategory.MUSIC) "Local Audio" else "Local Video"
        var durationMs = 0L
        var artwork: ByteArray? = null

        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(ctx, uri)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }?.let {
                title = it
            }
            val extractedArtist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            val extractedAlbum = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            if (!extractedArtist.isNullOrBlank()) {
                artist = if (!extractedAlbum.isNullOrBlank()) "$extractedArtist • $extractedAlbum" else extractedArtist
            }
            durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            artwork = mmr.embeddedPicture
        } catch (_: Exception) {
            // Fall back to display name
        } finally {
            runCatching { mmr.release() }
        }

        return PickedMediaItem(
            id = "${uri}_${System.nanoTime()}",
            uriString = uri.toString(),
            title = title,
            subtitle = artist,
            mimeType = mime,
            category = category,
            durationMs = durationMs,
            artworkBytes = artwork
        )
    }

    private fun queryDisplayName(uri: Uri): String? {
        val cr = getApplication<Application>().contentResolver
        return runCatching {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }

    private fun encodePhotoToJpeg(uri: Uri): ByteArray? {
        val cr = getApplication<Application>().contentResolver
        return runCatching {
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, boundsOpts) }
            val maxDim = 1920
            var sample = 1
            while (boundsOpts.outWidth / sample > maxDim || boundsOpts.outHeight / sample > maxDim) {
                sample *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOpts) }
                ?: return null
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 88, baos)
            bitmap.recycle()
            baos.toByteArray()
        }.getOrNull()
    }

    override fun onCleared() {
        discovery.stopDiscovery()
        playbackPollJob?.cancel()
        slideshowJob?.cancel()
        super.onCleared()
    }
}
