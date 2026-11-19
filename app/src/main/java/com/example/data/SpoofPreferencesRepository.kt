package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.AirPlayProtocolMode
import com.example.model.AirPlayReceiver
import com.example.model.AppleSpoofPreset
import com.example.model.MirrorFpsCap
import com.example.model.MirrorQuality
import com.example.model.MirrorResolution
import com.example.model.PhotoTransition
import com.example.model.ReceiverType
import com.example.model.SpoofProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.spoofDataStore: DataStore<Preferences> by preferencesDataStore(name = "aircast_prefs")

class SpoofPreferencesRepository(private val context: Context) {

    private object Keys {
        val PRESET_ID = stringPreferencesKey("preset_id")
        val CLIENT_NAME = stringPreferencesKey("client_name")
        val MODEL_IDENTIFIER = stringPreferencesKey("model_identifier")
        val USER_AGENT = stringPreferencesKey("user_agent")
        val DEVICE_MAC_ID = stringPreferencesKey("device_mac_id")
        val SESSION_UUID = stringPreferencesKey("session_uuid")
        val DACP_ID = stringPreferencesKey("dacp_id")
        val ACTIVE_REMOTE = stringPreferencesKey("active_remote")
        val PROTOCOL_MODE = stringPreferencesKey("protocol_mode")
        val MIRROR_QUALITY = stringPreferencesKey("mirror_quality")
        val MIRROR_RESOLUTION = stringPreferencesKey("mirror_resolution")
        val MIRROR_FPS_CAP = intPreferencesKey("mirror_fps_cap")
        val PHOTO_TRANSITION = stringPreferencesKey("photo_transition")
        val SLIDESHOW_SEC = intPreferencesKey("slideshow_sec")
        val MANUAL_RECEIVERS = stringSetPreferencesKey("manual_receivers")
    }

    val spoofProfileFlow: Flow<SpoofProfile> = context.spoofDataStore.data.map { prefs ->
        val presetId = prefs[Keys.PRESET_ID] ?: AppleSpoofPreset.IPHONE_15_PRO.id
        val preset = AppleSpoofPreset.entries.firstOrNull { it.id == presetId }
            ?: AppleSpoofPreset.IPHONE_15_PRO
        val protocolMode = prefs[Keys.PROTOCOL_MODE]?.let { name ->
            AirPlayProtocolMode.entries.firstOrNull { it.name == name }
        } ?: AirPlayProtocolMode.AIRPLAY_2
        val mirrorQuality = prefs[Keys.MIRROR_QUALITY]?.let { name ->
            MirrorQuality.entries.firstOrNull { it.name == name }
        } ?: MirrorQuality.FHD_1080P_30
        val defaultRes = when (mirrorQuality) {
            MirrorQuality.HD_720P_30, MirrorQuality.HD_720P_60 -> MirrorResolution.HD_720P
            MirrorQuality.FHD_1080P_30, MirrorQuality.FHD_1080P_60 -> MirrorResolution.FHD_1080P
        }
        val defaultFps = when (mirrorQuality) {
            MirrorQuality.HD_720P_60, MirrorQuality.FHD_1080P_60 -> MirrorFpsCap.FPS_60
            MirrorQuality.HD_720P_30, MirrorQuality.FHD_1080P_30 -> MirrorFpsCap.FPS_30
        }
        val mirrorResolution = prefs[Keys.MIRROR_RESOLUTION]?.let { name ->
            MirrorResolution.entries.firstOrNull { it.name == name }
        } ?: defaultRes
        val mirrorFpsCap = prefs[Keys.MIRROR_FPS_CAP]?.let { fps ->
            MirrorFpsCap.entries.firstOrNull { it.fps == fps }
        } ?: defaultFps
        val transition = prefs[Keys.PHOTO_TRANSITION]?.let { name ->
            PhotoTransition.entries.firstOrNull { it.name == name }
        } ?: PhotoTransition.DISSOLVE

        SpoofProfile(
            preset = preset,
            clientName = prefs[Keys.CLIENT_NAME] ?: preset.displayName,
            modelIdentifier = prefs[Keys.MODEL_IDENTIFIER] ?: preset.modelIdentifier,
            userAgent = prefs[Keys.USER_AGENT] ?: preset.userAgent,
            deviceMacId = prefs[Keys.DEVICE_MAC_ID] ?: "A4:83:E7:4C:91:B2",
            sessionUuid = prefs[Keys.SESSION_UUID] ?: UUID.randomUUID().toString().uppercase(),
            dacpId = prefs[Keys.DACP_ID] ?: "C84B92E10F4A11D8",
            activeRemote = prefs[Keys.ACTIVE_REMOTE] ?: "2948105421",
            protocolMode = protocolMode,
            mirrorQuality = mirrorQuality,
            mirrorResolution = mirrorResolution,
            mirrorFpsCap = mirrorFpsCap,
            photoTransition = transition,
            slideshowIntervalSec = prefs[Keys.SLIDESHOW_SEC] ?: 5
        )
    }

    val manualReceiversFlow: Flow<List<AirPlayReceiver>> = context.spoofDataStore.data.map { prefs ->
        val rawSet = prefs[Keys.MANUAL_RECEIVERS].orEmpty()
        rawSet.mapNotNull { decodeReceiver(it) }.sortedBy { it.name.lowercase() }
    }

    suspend fun saveSpoofProfile(profile: SpoofProfile) {
        context.spoofDataStore.edit { prefs ->
            prefs[Keys.PRESET_ID] = profile.preset.id
            prefs[Keys.CLIENT_NAME] = profile.clientName
            prefs[Keys.MODEL_IDENTIFIER] = profile.modelIdentifier
            prefs[Keys.USER_AGENT] = profile.userAgent
            prefs[Keys.DEVICE_MAC_ID] = profile.deviceMacId
            prefs[Keys.SESSION_UUID] = profile.sessionUuid
            prefs[Keys.DACP_ID] = profile.dacpId
            prefs[Keys.ACTIVE_REMOTE] = profile.activeRemote
            prefs[Keys.PROTOCOL_MODE] = profile.protocolMode.name
            prefs[Keys.MIRROR_QUALITY] = profile.mirrorQuality.name
            prefs[Keys.MIRROR_RESOLUTION] = profile.mirrorResolution.name
            prefs[Keys.MIRROR_FPS_CAP] = profile.mirrorFpsCap.fps
            prefs[Keys.PHOTO_TRANSITION] = profile.photoTransition.name
            prefs[Keys.SLIDESHOW_SEC] = profile.slideshowIntervalSec
        }
    }

    suspend fun addManualReceiver(receiver: AirPlayReceiver) {
        context.spoofDataStore.edit { prefs ->
            val current = prefs[Keys.MANUAL_RECEIVERS].orEmpty().toMutableSet()
            current.removeAll { it.startsWith("${receiver.id}|") }
            current.add(encodeReceiver(receiver))
            prefs[Keys.MANUAL_RECEIVERS] = current
        }
    }

    suspend fun removeManualReceiver(receiverId: String) {
        context.spoofDataStore.edit { prefs ->
            val current = prefs[Keys.MANUAL_RECEIVERS].orEmpty().toMutableSet()
            current.removeAll { it.startsWith("$receiverId|") }
            prefs[Keys.MANUAL_RECEIVERS] = current
        }
    }

    suspend fun removeManualReceiverByEndpoint(host: String, port: Int) {
        context.spoofDataStore.edit { prefs ->
            val current = prefs[Keys.MANUAL_RECEIVERS].orEmpty().toMutableSet()
            current.removeAll { raw ->
                val parts = raw.split("|")
                parts.size >= 4 && parts[2] == host && (parts[3].toIntOrNull() ?: 7000) == port
            }
            prefs[Keys.MANUAL_RECEIVERS] = current
        }
    }

    suspend fun clearManualReceivers() {
        context.spoofDataStore.edit { prefs ->
            prefs.remove(Keys.MANUAL_RECEIVERS)
        }
    }

    private fun encodeReceiver(r: AirPlayReceiver): String {
        val safeName = r.name.replace("|", " ")
        return listOf(r.id, safeName, r.host, r.port.toString(), r.type.name).joinToString("|")
    }

    private fun decodeReceiver(raw: String): AirPlayReceiver? {
        val parts = raw.split("|")
        if (parts.size < 5) return null
        val port = parts[3].toIntOrNull() ?: 7000
        val type = ReceiverType.entries.firstOrNull { it.name == parts[4] } ?: return null
        return AirPlayReceiver(
            id = parts[0],
            name = parts[1],
            host = parts[2],
            port = port,
            type = type,
            serviceType = "_airplay._tcp.",
            model = "Manual Endpoint",
            isManual = true
        )
    }
}
