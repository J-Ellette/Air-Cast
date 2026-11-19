package com.example

import android.content.Context
import android.content.Intent
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.example.data.PairedDevicesRepository
import com.example.data.SpoofPreferencesRepository
import com.example.model.AirPlayProtocolMode
import com.example.model.AirPlayReceiver
import com.example.model.AppleSpoofPreset
import com.example.model.ConnectionStatus
import com.example.model.MirrorFpsCap
import com.example.model.MirrorResolution
import com.example.model.PairedDeviceRecord
import com.example.model.PairingAuthMethod
import com.example.model.ReceiverType
import com.example.model.SpoofProfile
import com.example.network.AirPlayClient
import com.example.network.AirPlayDiscoveryManager
import com.example.network.LocalMediaHttpServer
import com.example.network.NetworkUtils
import com.example.service.NsdScanService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.HttpURLConnection
import java.net.URL

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read app name from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("AirCast Bridge", appName)
    }

    @Test
    fun `spoof profile builds expected Apple AirPlay headers`() {
        val profile = SpoofProfile(
            preset = AppleSpoofPreset.MACBOOK_PRO,
            clientName = "Studio MacBook",
            modelIdentifier = AppleSpoofPreset.MACBOOK_PRO.modelIdentifier,
            userAgent = AppleSpoofPreset.MACBOOK_PRO.userAgent,
            protocolMode = AirPlayProtocolMode.AIRPLAY_2
        )
        val headers = profile.buildHttpHeadersMap()
        assertEquals("Studio MacBook", headers["X-Apple-Client-Name"])
        assertEquals("MacBookPro18,1", headers["X-Apple-Model"])
        assertEquals("1.1", headers["X-Apple-ProtocolVersion"])
        assertTrue(headers.containsKey("X-Apple-Device-ID"))
        assertTrue(headers.containsKey("DACP-ID"))
    }

    @Test
    fun `generated apple MAC follows valid format`() {
        val mac = NetworkUtils.generateAppleStyleMac()
        val parts = mac.split(":")
        assertEquals(6, parts.size)
        assertTrue(parts.all { it.length == 2 })
    }

    @Test
    fun `nsd txt parser classifies Roku streamer and Smart TV and excludes Apple TV and speakers`() {
        val rokuStick = AirPlayDiscoveryManager.buildReceiverFromTxt(
            serviceName = "Bedroom Roku Express",
            hostAddress = "192.168.1.45",
            port = 7000,
            serviceType = "_airplay._tcp.",
            attrs = mapOf(
                "model" to "Roku3941X",
                "deviceid" to "AA:BB:CC:DD:EE:FF",
                "features" to "0x5A7FFFF7,0x1E",
                "flags" to "0x244",
                "protovers" to "1.1"
            )
        )
        assertTrue(rokuStick != null)
        assertEquals(ReceiverType.STREAMING_STICK, rokuStick?.type)
        assertEquals("AirPlay 2", rokuStick?.protocolBadge)
        assertTrue(rokuStick?.capabilitiesSummary?.contains("Video") == true)
        assertTrue(rokuStick?.capabilitiesSummary?.contains("Mirror") == true)
        assertFalse(rokuStick?.requiresPin == true)

        val smartTv = AirPlayDiscoveryManager.buildReceiverFromTxt(
            serviceName = "Living Room Samsung Q80",
            hostAddress = "192.168.1.50",
            port = 7000,
            serviceType = "_airplay._tcp.",
            attrs = mapOf(
                "model" to "SamsungTV",
                "features" to "0x5A7FFFF7,0x1E",
                "flags" to "0x8",
                "protovers" to "1.1"
            )
        )
        assertTrue(smartTv != null)
        assertEquals(ReceiverType.SMART_TV, smartTv?.type)
        assertTrue(smartTv?.requiresPin == true)

        val excludedSpeaker = AirPlayDiscoveryManager.buildReceiverFromTxt(
            serviceName = "5855CA123456@Kitchen HomePod",
            hostAddress = "192.168.1.88",
            port = 7000,
            serviceType = "_raop._tcp.",
            attrs = mapOf("am" to "AudioAccessory5,1")
        )
        assertNull(excludedSpeaker)

        val excludedAppleTv = AirPlayDiscoveryManager.buildReceiverFromTxt(
            serviceName = "Living Room Apple TV",
            hostAddress = "192.168.1.89",
            port = 7000,
            serviceType = "_airplay._tcp.",
            attrs = mapOf("model" to "AppleTV11,1")
        )
        assertNull(excludedAppleTv)
    }

    @Test
    fun `nsd scan service starts and handles scan intents`() {
        val controller = Robolectric.buildService(NsdScanService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent(ApplicationProvider.getApplicationContext(), NsdScanService::class.java).apply {
            action = NsdScanService.ACTION_START_SCAN
        }, 0, 1)
        service.onStartCommand(Intent(ApplicationProvider.getApplicationContext(), NsdScanService::class.java).apply {
            action = NsdScanService.ACTION_STOP_SCAN
        }, 0, 2)
        controller.destroy()
    }

    @Test
    fun `airplay crypto tlv8 and chacha20 poly1305 roundtrip succeeds`() {
        val longPubKey = ByteArray(384) { (it % 251).toByte() }
        val encoded = com.example.network.AirPlayCrypto.encodeTlv8(
            com.example.network.AirPlayCrypto.TLV_STATE to byteArrayOf(0x03),
            com.example.network.AirPlayCrypto.TLV_PUBLIC_KEY to longPubKey
        )
        val decoded = com.example.network.AirPlayCrypto.decodeTlv8(encoded)
        assertEquals(0x03, decoded[com.example.network.AirPlayCrypto.TLV_STATE]?.first()?.toInt())
        assertTrue(longPubKey.contentEquals(decoded[com.example.network.AirPlayCrypto.TLV_PUBLIC_KEY]))

        val key = ByteArray(32) { (it + 1).toByte() }
        val nonce = com.example.network.AirPlayCrypto.hapNonce(label8 = "PS-Msg05")
        val plain = "AirCastBridge-PairSetup".toByteArray(Charsets.UTF_8)
        val sealed = com.example.network.AirPlayCrypto.chacha20Poly1305Encrypt(key, nonce, ByteArray(0), plain)
        val opened = com.example.network.AirPlayCrypto.chacha20Poly1305Decrypt(key, nonce, ByteArray(0), sealed)
        assertTrue(plain.contentEquals(opened))
    }

    @Test
    fun `srp6a binary plist and digest auth build valid payloads`() {
        val salt = ByteArray(16) { 0x42 }
        val serverB = ByteArray(384) { 0x11 }
        val srp = com.example.network.AirPlayCrypto.createSrpClientSession("4829", salt, serverB)
        assertTrue(srp.publicKeyA.isNotEmpty())
        assertEquals(64, srp.proofM1.size)
        assertEquals(64, srp.sessionKeyK.size)

        val bplist = com.example.network.AirPlayCrypto.encodeStringDictBinaryPlist(
            mapOf("pin" to "4829", "deviceID" to "A4:83:E7:4C:91:B2")
        )
        assertEquals("bplist00", String(bplist.copyOf(8), Charsets.US_ASCII))

        val playPlist = com.example.network.AirPlayCrypto.encodePlayBinaryPlist(
            "http://192.168.1.25:43210/media/abc123",
            12.5
        )
        assertEquals("bplist00", String(playPlist.copyOf(8), Charsets.US_ASCII))
        assertTrue(String(playPlist, Charsets.US_ASCII).contains("Content-Location"))
        assertTrue(String(playPlist, Charsets.US_ASCII).contains("Start-Position"))

        val digest = com.example.network.AirPlayCrypto.buildDigestAuthHeader(
            wwwAuthenticateHeader = """Digest realm="raop", nonce="abc123", qop="auth"""",
            username = "iTunes",
            password = "speaker-secret",
            method = "OPTIONS",
            uri = "*"
        )
        assertTrue(digest != null && digest.contains("""username="iTunes"""") && digest.contains("""realm="raop""""))
    }

    @Test
    fun `paired devices datastore disconnect clears session and forget removes all traces`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = PairedDevicesRepository(context)
        val prefsRepo = SpoofPreferencesRepository(context)
        val seedB64 = Base64.encodeToString(ByteArray(32) { 0x2A }, Base64.NO_WRAP)
        val ltpkB64 = Base64.encodeToString(ByteArray(32) { 0x5B }, Base64.NO_WRAP)
        val key = "192.168.1.90:7000"

        val record = PairedDeviceRecord(
            receiverKey = key,
            name = "Theater Roku Ultra",
            host = "192.168.1.90",
            port = 7000,
            type = ReceiverType.STREAMING_STICK,
            serviceType = "_airplay._tcp.",
            model = "Roku 4802X",
            authMethod = PairingAuthMethod.HAP_ED25519,
            clientPairingId = "AA:BB:CC:11:22:33",
            clientEd25519SeedBase64 = seedB64,
            accessoryLtpkBase64 = ltpkB64
        )
        repo.savePairedDevice(record)

        val savedMap = repo.pairedDevicesFlow.first()
        val rec = savedMap[key]
        assertTrue(rec != null)
        assertEquals("Theater Roku Ultra", rec?.name)
        assertEquals(ReceiverType.STREAMING_STICK, rec?.type)
        assertEquals(seedB64, rec?.clientEd25519SeedBase64)
        assertEquals(key, repo.lastConnectedKeyFlow.first())

        val fallback = repo.toReceiverFallback(rec!!)
        assertTrue(fallback.requiresPin)

        val client = AirPlayClient()
        client.restorePairedCredentials(savedMap)
        assertTrue(client.hasPairedCredentials("192.168.1.90", 7000))

        // Disconnecting clears session credentials so next connect requires PIN again
        client.disconnectSession("192.168.1.90", 7000)
        assertFalse(client.hasPairedCredentials("192.168.1.90", 7000))

        // Forgetting removes manual IP entry and saved pairing record
        prefsRepo.addManualReceiver(
            AirPlayReceiver(
                id = "manual_192.168.1.90_7000",
                name = "Theater Roku Ultra",
                host = "192.168.1.90",
                port = 7000,
                type = ReceiverType.STREAMING_STICK,
                serviceType = "_airplay._tcp.",
                isManual = true
            )
        )
        assertEquals(1, prefsRepo.manualReceiversFlow.first().size)
        prefsRepo.removeManualReceiverByEndpoint("192.168.1.90", 7000)
        assertTrue(prefsRepo.manualReceiversFlow.first().isEmpty())

        repo.forgetPairedDevice(key)
        assertTrue(repo.pairedDevicesFlow.first().isEmpty())
        assertNull(repo.lastConnectedKeyFlow.first())
    }

    @Test
    fun `local media server serves registered photo bytes`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val server = LocalMediaHttpServer(context.contentResolver)
        val port = server.start()
        try {
            assertTrue(port > 0)
            val sample = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
            val token = server.registerPhotoBytes(sample)
            val conn = URL("http://127.0.0.1:$port/photo/$token").openConnection() as HttpURLConnection
            assertEquals(200, conn.responseCode)
            val read = conn.inputStream.readBytes()
            assertEquals(sample.toList(), read.toList())
        } finally {
            server.stop()
        }
    }

    @Test
    fun `settings datastore persists mirror resolution and fps cap`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefsRepo = SpoofPreferencesRepository(context)
        val updated = SpoofProfile(
            mirrorResolution = MirrorResolution.SD_480P,
            mirrorFpsCap = MirrorFpsCap.FPS_15
        )
        assertEquals(854, updated.effectiveMirrorWidth)
        assertEquals(480, updated.effectiveMirrorHeight)
        assertEquals(15, updated.effectiveMirrorFps)
        assertEquals("480p • 15 fps", updated.mirrorProfileLabel)

        prefsRepo.saveSpoofProfile(updated)
        val loaded = prefsRepo.spoofProfileFlow.first()
        assertEquals(MirrorResolution.SD_480P, loaded.mirrorResolution)
        assertEquals(MirrorFpsCap.FPS_15, loaded.mirrorFpsCap)
        assertEquals(ConnectionStatus.entries.size, 3)
    }
}

