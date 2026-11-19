package com.example.model

import java.util.UUID

enum class ReceiverType(val label: String) {
    STREAMING_STICK("Roku / Streamer"),
    SMART_TV("Smart TV")
}

enum class AirPlayProtocolMode(val label: String, val subtitle: String) {
    AIRPLAY_2("AirPlay 2", "HTTP/1.1 + RTSP multi-room & 60fps video"),
    AIRPLAY_1("AirPlay 1", "Legacy HTTP + RAOP for older receivers")
}

enum class MediaCategory(val label: String) {
    SCREEN("Screen Mirror"),
    VIDEO("Video"),
    PHOTOS("Photos"),
    MUSIC("Music")
}

enum class PhotoTransition(val headerValue: String, val label: String) {
    DISSOLVE("Dissolve", "Dissolve"),
    SLIDE_LEFT("SlideLeft", "Slide Left"),
    SLIDE_RIGHT("SlideRight", "Slide Right"),
    NONE("None", "Instant")
}

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED
}

enum class MirrorResolution(
    val label: String,
    val shortLabel: String,
    val width: Int,
    val height: Int
) {
    SD_480P("480p (Fast LAN)", "480p", 854, 480),
    HD_720P("720p HD", "720p", 1280, 720),
    FHD_1080P("1080p Full HD", "1080p", 1920, 1080)
}

enum class MirrorFpsCap(val fps: Int, val label: String) {
    FPS_15(15, "15 fps (Low Bandwidth)"),
    FPS_30(30, "30 fps (Balanced)"),
    FPS_60(60, "60 fps (Smooth)")
}

enum class MirrorQuality(val label: String, val width: Int, val height: Int, val fps: Int) {
    HD_720P_30("720p • 30 fps", 1280, 720, 30),
    HD_720P_60("720p • 60 fps", 1280, 720, 60),
    FHD_1080P_30("1080p • 30 fps", 1920, 1080, 30),
    FHD_1080P_60("1080p • 60 fps", 1920, 1080, 60)
}

enum class StreamStatus {
    IDLE,
    CONNECTING,
    PIN_REQUIRED,
    STREAMING,
    PAUSED,
    ERROR
}

enum class PairingAuthMethod {
    HAP_ED25519,
    TRANSIENT_SRP,
    AUTH_SETUP,
    BPLIST_PIN
}

data class PairedDeviceRecord(
    val receiverKey: String,
    val name: String,
    val host: String,
    val port: Int,
    val type: ReceiverType,
    val serviceType: String,
    val model: String,
    val authMethod: PairingAuthMethod,
    val clientPairingId: String? = null,
    val clientEd25519SeedBase64: String? = null,
    val accessoryLtpkBase64: String? = null,
    val pairedAtMs: Long = System.currentTimeMillis(),
    val lastConnectedMs: Long = System.currentTimeMillis()
)

data class AirPlayReceiver(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val type: ReceiverType,
    val serviceType: String = "_airplay._tcp.",
    val model: String = "Unknown",
    val deviceId: String = "",
    val featuresHex: String = "",
    val osVersion: String = "",
    val capabilitiesSummary: String = "",
    val protocolBadge: String = "",
    val requiresPin: Boolean = false,
    val isPaired: Boolean = false,
    val isManual: Boolean = false,
    val isReachable: Boolean = true,
    val lastProbeMs: Long? = null
)

enum class AppleSpoofPreset(
    val id: String,
    val displayName: String,
    val modelIdentifier: String,
    val userAgent: String,
    val mediaControlAgent: String,
    val raopAgent: String,
    val osBuild: String
) {
    IPHONE_15_PRO(
        id = "iphone16_2",
        displayName = "iPhone 15 Pro",
        modelIdentifier = "iPhone16,2",
        userAgent = "AirPlay/745.30",
        mediaControlAgent = "MediaControl/1.0",
        raopAgent = "AirPlay/745.30",
        osBuild = "iOS 17.5 (21F79)"
    ),
    IPAD_PRO_M2(
        id = "ipad14_5",
        displayName = "iPad Pro (M2)",
        modelIdentifier = "iPad14,5",
        userAgent = "AirPlay/745.30",
        mediaControlAgent = "MediaControl/1.0",
        raopAgent = "AirPlay/745.30",
        osBuild = "iPadOS 17.5 (21F79)"
    ),
    MACBOOK_PRO(
        id = "macbookpro18_1",
        displayName = "MacBook Pro 16\"",
        modelIdentifier = "MacBookPro18,1",
        userAgent = "iTunes/12.12.9 (Macintosh; OS X 14.5)",
        mediaControlAgent = "MediaControl/1.0",
        raopAgent = "iTunes/12.12.9 (Macintosh; OS X 14.5)",
        osBuild = "macOS 14.5 (23F79)"
    ),
    IPHONE_CLASSIC(
        id = "iphone13_2",
        displayName = "iPhone 12 (AirPlay 1)",
        modelIdentifier = "iPhone13,2",
        userAgent = "AirPlay/420.3",
        mediaControlAgent = "MediaControl/1.0",
        raopAgent = "iTunes/11.1.5",
        osBuild = "iOS 15.8"
    )
}

data class SpoofProfile(
    val preset: AppleSpoofPreset = AppleSpoofPreset.IPHONE_15_PRO,
    val clientName: String = "iPhone 15 Pro",
    val modelIdentifier: String = AppleSpoofPreset.IPHONE_15_PRO.modelIdentifier,
    val userAgent: String = AppleSpoofPreset.IPHONE_15_PRO.userAgent,
    val deviceMacId: String = "A4:83:E7:4C:91:B2",
    val sessionUuid: String = UUID.randomUUID().toString().uppercase(),
    val dacpId: String = "C84B92E10F4A11D8",
    val activeRemote: String = "2948105421",
    val protocolMode: AirPlayProtocolMode = AirPlayProtocolMode.AIRPLAY_2,
    val mirrorQuality: MirrorQuality = MirrorQuality.FHD_1080P_30,
    val mirrorResolution: MirrorResolution = MirrorResolution.FHD_1080P,
    val mirrorFpsCap: MirrorFpsCap = MirrorFpsCap.FPS_30,
    val photoTransition: PhotoTransition = PhotoTransition.DISSOLVE,
    val slideshowIntervalSec: Int = 5
) {
    val effectiveMirrorWidth: Int get() = mirrorResolution.width
    val effectiveMirrorHeight: Int get() = mirrorResolution.height
    val effectiveMirrorFps: Int get() = mirrorFpsCap.fps
    val mirrorProfileLabel: String get() = "${mirrorResolution.shortLabel} • ${mirrorFpsCap.fps} fps"

    fun buildHttpHeadersMap(includeMediaControl: Boolean = false): LinkedHashMap<String, String> {
        val map = LinkedHashMap<String, String>()
        map["User-Agent"] = if (includeMediaControl) preset.mediaControlAgent else userAgent
        map["X-Apple-Device-ID"] = deviceMacId
        map["X-Apple-Session-ID"] = sessionUuid
        map["X-Apple-Client-Name"] = clientName
        map["X-Apple-Model"] = modelIdentifier
        map["X-Apple-OS-Build"] = preset.osBuild
        map["DACP-ID"] = dacpId
        map["Active-Remote"] = activeRemote
        map["X-Apple-ProtocolVersion"] = if (protocolMode == AirPlayProtocolMode.AIRPLAY_2) "1.1" else "1.0"
        return map
    }
}

data class ReconnectPromptState(
    val receiver: AirPlayReceiver,
    val category: MediaCategory,
    val attemptsMade: Int,
    val lastError: String
)

data class PickedMediaItem(
    val id: String,
    val uriString: String,
    val title: String,
    val subtitle: String,
    val mimeType: String,
    val category: MediaCategory,
    val durationMs: Long = 0L,
    val sizeBytes: Long = 0L,
    val artworkBytes: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PickedMediaItem) return false
        return id == other.id && uriString == other.uriString && category == other.category
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + uriString.hashCode()
        result = 31 * result + category.hashCode()
        return result
    }
}

data class PlaybackTelemetry(
    val status: StreamStatus = StreamStatus.IDLE,
    val activeCategory: MediaCategory? = null,
    val currentTitle: String = "",
    val currentSubtitle: String = "",
    val positionSec: Double = 0.0,
    val durationSec: Double = 0.0,
    val volumePercent: Int = 75,
    val bitrateMbps: Float = 0f,
    val resolutionLabel: String = "",
    val fps: Int = 0,
    val elapsedSec: Long = 0L,
    val bytesStreamed: Long = 0L,
    val errorMessage: String? = null
)

enum class LogDirection { OUT, IN, SYS }

data class ProtocolLogEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: String,
    val direction: LogDirection,
    val summary: String,
    val details: String
)
