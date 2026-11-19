package com.example.data

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.AirPlayReceiver
import com.example.model.PairedDeviceRecord
import com.example.model.PairingAuthMethod
import com.example.model.ReceiverType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.pairedDevicesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "aircast_paired_devices"
)

class PairedDevicesRepository(private val context: Context) {

    private object Keys {
        val PAIRED_RECORDS = stringSetPreferencesKey("paired_device_records")
        val LAST_CONNECTED_KEY = stringPreferencesKey("last_connected_receiver_key")
        val AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect_enabled")
    }

    val pairedDevicesFlow: Flow<Map<String, PairedDeviceRecord>> =
        context.pairedDevicesDataStore.data.map { prefs ->
            val rawSet = prefs[Keys.PAIRED_RECORDS].orEmpty()
            val map = LinkedHashMap<String, PairedDeviceRecord>()
            rawSet.mapNotNull { decodeRecord(it) }
                .sortedByDescending { it.lastConnectedMs }
                .forEach { rec -> map[rec.receiverKey] = rec }
            map
        }

    val lastConnectedKeyFlow: Flow<String?> =
        context.pairedDevicesDataStore.data.map { prefs ->
            prefs[Keys.LAST_CONNECTED_KEY]?.takeIf { it.isNotBlank() }
        }

    val autoReconnectFlow: Flow<Boolean> =
        context.pairedDevicesDataStore.data.map { prefs ->
            prefs[Keys.AUTO_RECONNECT] ?: true
        }

    suspend fun savePairedDevice(record: PairedDeviceRecord) {
        context.pairedDevicesDataStore.edit { prefs ->
            val current = prefs[Keys.PAIRED_RECORDS].orEmpty().toMutableSet()
            val keyPrefix = "${encodeField(record.receiverKey)}|"
            current.removeAll { it.startsWith(keyPrefix) }
            current.add(encodeRecord(record))
            prefs[Keys.PAIRED_RECORDS] = current
            prefs[Keys.LAST_CONNECTED_KEY] = record.receiverKey
        }
    }

    suspend fun setLastConnectedReceiver(receiverKey: String?) {
        context.pairedDevicesDataStore.edit { prefs ->
            if (receiverKey.isNullOrBlank()) {
                prefs.remove(Keys.LAST_CONNECTED_KEY)
            } else {
                prefs[Keys.LAST_CONNECTED_KEY] = receiverKey
            }
        }
    }

    suspend fun touchLastConnected(receiverKey: String) {
        context.pairedDevicesDataStore.edit { prefs ->
            val current = prefs[Keys.PAIRED_RECORDS].orEmpty().toMutableSet()
            val existing = current.mapNotNull { decodeRecord(it) }
                .firstOrNull { it.receiverKey == receiverKey }
            if (existing != null) {
                val updated = existing.copy(lastConnectedMs = System.currentTimeMillis())
                val keyPrefix = "${encodeField(receiverKey)}|"
                current.removeAll { it.startsWith(keyPrefix) }
                current.add(encodeRecord(updated))
                prefs[Keys.PAIRED_RECORDS] = current
            }
            prefs[Keys.LAST_CONNECTED_KEY] = receiverKey
        }
    }

    suspend fun forgetPairedDevice(receiverKey: String) {
        context.pairedDevicesDataStore.edit { prefs ->
            val current = prefs[Keys.PAIRED_RECORDS].orEmpty().toMutableSet()
            val keyPrefix = "${encodeField(receiverKey)}|"
            current.removeAll { it.startsWith(keyPrefix) }
            prefs[Keys.PAIRED_RECORDS] = current
            if (prefs[Keys.LAST_CONNECTED_KEY] == receiverKey) {
                prefs.remove(Keys.LAST_CONNECTED_KEY)
            }
        }
    }

    suspend fun setAutoReconnect(enabled: Boolean) {
        context.pairedDevicesDataStore.edit { prefs ->
            prefs[Keys.AUTO_RECONNECT] = enabled
        }
    }

    suspend fun clearAllPairedDevices() {
        context.pairedDevicesDataStore.edit { prefs ->
            prefs.remove(Keys.PAIRED_RECORDS)
            prefs.remove(Keys.LAST_CONNECTED_KEY)
        }
    }

    fun toReceiverFallback(record: PairedDeviceRecord): AirPlayReceiver {
        return AirPlayReceiver(
            id = "paired_${record.receiverKey}",
            name = record.name,
            host = record.host,
            port = record.port,
            type = record.type,
            serviceType = record.serviceType,
            model = record.model,
            protocolBadge = when (record.authMethod) {
                PairingAuthMethod.HAP_ED25519, PairingAuthMethod.TRANSIENT_SRP -> "AirPlay 2"
                else -> "Saved"
            },
            requiresPin = true,
            isPaired = true,
            isManual = false,
            isReachable = true
        )
    }

    private fun encodeRecord(r: PairedDeviceRecord): String {
        return listOf(
            encodeField(r.receiverKey),
            encodeField(r.name),
            encodeField(r.host),
            r.port.toString(),
            r.type.name,
            encodeField(r.serviceType),
            encodeField(r.model),
            r.authMethod.name,
            encodeField(r.clientPairingId.orEmpty()),
            encodeField(r.clientEd25519SeedBase64.orEmpty()),
            encodeField(r.accessoryLtpkBase64.orEmpty()),
            "",
            r.pairedAtMs.toString(),
            r.lastConnectedMs.toString()
        ).joinToString("|")
    }

    private fun decodeRecord(raw: String): PairedDeviceRecord? {
        val parts = raw.split("|")
        if (parts.size < 14) return null
        val receiverKey = decodeField(parts[0]) ?: return null
        val name = decodeField(parts[1]) ?: return null
        val host = decodeField(parts[2]) ?: return null
        val port = parts[3].toIntOrNull() ?: return null
        val type = ReceiverType.entries.firstOrNull { it.name == parts[4] } ?: return null
        val serviceType = decodeField(parts[5]) ?: "_airplay._tcp."
        val model = decodeField(parts[6]) ?: "AirPlay Receiver"
        val authMethod = PairingAuthMethod.entries.firstOrNull { it.name == parts[7] }
            ?: PairingAuthMethod.HAP_ED25519
        val clientPairingId = decodeField(parts[8])?.takeIf { it.isNotEmpty() }
        val clientSeed = decodeField(parts[9])?.takeIf { it.isNotEmpty() }
        val accessoryLtpk = decodeField(parts[10])?.takeIf { it.isNotEmpty() }
        val pairedAt = parts[12].toLongOrNull() ?: 0L
        val lastConnected = parts[13].toLongOrNull() ?: 0L

        return PairedDeviceRecord(
            receiverKey = receiverKey,
            name = name,
            host = host,
            port = port,
            type = type,
            serviceType = serviceType,
            model = model,
            authMethod = authMethod,
            clientPairingId = clientPairingId,
            clientEd25519SeedBase64 = clientSeed,
            accessoryLtpkBase64 = accessoryLtpk,
            pairedAtMs = pairedAt,
            lastConnectedMs = lastConnected
        )
    }

    private fun encodeField(value: String): String {
        return Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    private fun decodeField(encoded: String): String? {
        return runCatching {
            String(Base64.decode(encoded, Base64.NO_WRAP), Charsets.UTF_8)
        }.getOrNull()
    }
}
