package com.example.network

import android.util.Base64
import com.example.model.AirPlayProtocolMode
import com.example.model.AirPlayReceiver
import com.example.model.LogDirection
import com.example.model.PairedDeviceRecord
import com.example.model.PairingAuthMethod
import com.example.model.PhotoTransition
import com.example.model.ProtocolLogEntry
import com.example.model.ReceiverType
import com.example.model.SpoofProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

sealed class AirPlayResult<out T> {
    data class Success<T>(val data: T, val statusCode: Int = 200) : AirPlayResult<T>()
    data class PinRequired(val message: String) : AirPlayResult<Nothing>()
    data class Failure(val message: String, val statusCode: Int = -1) : AirPlayResult<Nothing>()
}

data class ProbeInfo(
    val reachable: Boolean,
    val latencyMs: Long,
    val serverBanner: String,
    val requiresPin: Boolean,
    val inferredModel: String?
)

data class PlaybackInfoSnapshot(
    val positionSec: Double,
    val durationSec: Double,
    val rate: Double
)

class AirPlayClient {

    private class PersistentConnection(
        val host: String,
        val port: Int,
        val socket: Socket,
        val input: BufferedInputStream,
        val output: OutputStream,
        var writeKey: ByteArray? = null,
        var readKey: ByteArray? = null,
        var writeCounter: Long = 0L,
        var readCounter: Long = 0L,
        var isPaired: Boolean = false
    ) {
        private val readBuffer = ByteArrayOutputStream()
        private var readOffset = 0

        val isOpen: Boolean
            get() = !socket.isClosed && socket.isConnected

        fun enableHapEncryption(writeKey: ByteArray, readKey: ByteArray) {
            this.writeKey = writeKey
            this.readKey = readKey
            this.writeCounter = 0L
            this.readCounter = 0L
            this.isPaired = true
        }

        fun sendRaw(data: ByteArray) {
            val wk = writeKey
            if (wk == null) {
                output.write(data)
                output.flush()
                return
            }
            var offset = 0
            while (offset < data.size) {
                val chunkLen = minOf(1024, data.size - offset)
                val chunk = data.copyOfRange(offset, offset + chunkLen)
                val aad = byteArrayOf((chunkLen and 0xFF).toByte(), ((chunkLen ushr 8) and 0xFF).toByte())
                val nonce = AirPlayCrypto.hapCounterNonce(writeCounter++)
                val encrypted = AirPlayCrypto.chacha20Poly1305Encrypt(wk, nonce, aad, chunk)
                output.write(aad)
                output.write(encrypted)
                offset += chunkLen
            }
            output.flush()
        }

        fun readLineAscii(): String? {
            val lineOut = ByteArrayOutputStream()
            while (true) {
                val b = readNextByte()
                if (b == -1) {
                    return if (lineOut.size() == 0) null else lineOut.toString(Charsets.UTF_8.name())
                }
                if (b == '\n'.code) break
                if (b != '\r'.code) lineOut.write(b)
            }
            return lineOut.toString(Charsets.UTF_8.name())
        }

        fun readExactBytes(count: Int): ByteArray {
            val out = ByteArray(count)
            var idx = 0
            while (idx < count) {
                val b = readNextByte()
                if (b == -1) break
                out[idx++] = b.toByte()
            }
            return if (idx == count) out else out.copyOf(idx)
        }

        private fun readNextByte(): Int {
            val buf = readBuffer.toByteArray()
            if (readOffset < buf.size) {
                val b = buf[readOffset++].toInt() and 0xFF
                if (readOffset >= buf.size) {
                    readBuffer.reset()
                    readOffset = 0
                }
                return b
            }

            val rk = readKey
            if (rk == null) {
                return input.read()
            }

            // Read next HAP encrypted frame
            val lenBytes = readPlainFromStream(2) ?: return -1
            val plainLen = (lenBytes[0].toInt() and 0xFF) or ((lenBytes[1].toInt() and 0xFF) shl 8)
            val cipherAndTag = readPlainFromStream(plainLen + 16) ?: return -1
            val nonce = AirPlayCrypto.hapCounterNonce(readCounter++)
            val decrypted = AirPlayCrypto.chacha20Poly1305Decrypt(rk, nonce, lenBytes, cipherAndTag)
                ?: return -1
            readBuffer.reset()
            readBuffer.write(decrypted)
            readOffset = 0
            return if (decrypted.isEmpty()) -1 else (decrypted[readOffset++].toInt() and 0xFF)
        }

        private fun readPlainFromStream(count: Int): ByteArray? {
            val result = ByteArray(count)
            var off = 0
            while (off < count) {
                val r = input.read(result, off, count - off)
                if (r == -1) return null
                off += r
            }
            return result
        }

        fun closeQuietly() {
            runCatching { socket.close() }
        }
    }

    private data class PairedIdentity(
        val clientPairingId: String,
        val clientKeyPair: AirPlayCrypto.Ed25519KeyPair,
        val accessoryPublicKey: ByteArray? = null,
        val authMethod: PairingAuthMethod = PairingAuthMethod.HAP_ED25519
    )

    private val cseqCounter = AtomicInteger(1)
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val activeConnections = ConcurrentHashMap<String, PersistentConnection>()
    private val pairedIdentities = ConcurrentHashMap<String, PairedIdentity>()
    private val pairingAuthMethods = ConcurrentHashMap<String, PairingAuthMethod>()

    var onPairingPersisted: ((PairedDeviceRecord) -> Unit)? = null

    private val _protocolLogs = MutableStateFlow<List<ProtocolLogEntry>>(emptyList())
    val protocolLogs: StateFlow<List<ProtocolLogEntry>> = _protocolLogs.asStateFlow()

    fun hasPairedCredentials(host: String, port: Int): Boolean {
        val key = connectionKey(host, port)
        return pairedIdentities.containsKey(key) ||
            pairingAuthMethods.containsKey(key)
    }

    fun restorePairedCredentials(records: Map<String, PairedDeviceRecord>) {
        pairedIdentities.clear()
        pairingAuthMethods.clear()
        records.forEach { (key, rec) ->
            pairingAuthMethods[key] = rec.authMethod
            val seedB64 = rec.clientEd25519SeedBase64
            val pairingId = rec.clientPairingId
            if (!seedB64.isNullOrBlank() && !pairingId.isNullOrBlank()) {
                runCatching {
                    val seedBytes = Base64.decode(seedB64, Base64.NO_WRAP)
                    if (seedBytes.size == 32) {
                        val keyPair = AirPlayCrypto.generateEd25519KeyPair(seedBytes)
                        val ltpk = rec.accessoryLtpkBase64?.let { b64 ->
                            runCatching { Base64.decode(b64, Base64.NO_WRAP) }.getOrNull()
                        }
                        pairedIdentities[key] = PairedIdentity(
                            clientPairingId = pairingId,
                            clientKeyPair = keyPair,
                            accessoryPublicKey = ltpk,
                            authMethod = rec.authMethod
                        )
                    }
                }
            }
        }
    }

    fun disconnectSession(host: String, port: Int) {
        val key = connectionKey(host, port)
        pairedIdentities.remove(key)
        pairingAuthMethods.remove(key)
        closePersistentConnection(host, port)
        appendSystemLog("Session Disconnected", "Closed AirPlay control channel to $key (PIN required on next connect)")
    }

    fun forgetPairedCredentials(host: String, port: Int) {
        val key = connectionKey(host, port)
        pairedIdentities.remove(key)
        pairingAuthMethods.remove(key)
        closePersistentConnection(host, port)
    }

    fun clearAllCredentials() {
        val keys = activeConnections.keys.toList()
        pairedIdentities.clear()
        pairingAuthMethods.clear()
        keys.forEach { k -> activeConnections.remove(k)?.closeQuietly() }
    }

    private fun emitPairedRecord(
        receiver: AirPlayReceiver,
        authMethod: PairingAuthMethod,
        clientPairingId: String? = null,
        edKeys: AirPlayCrypto.Ed25519KeyPair? = null,
        accessoryLtpk: ByteArray? = null
    ) {
        val key = connectionKey(receiver.host, receiver.port)
        pairingAuthMethods[key] = authMethod
        val now = System.currentTimeMillis()
        val record = PairedDeviceRecord(
            receiverKey = key,
            name = receiver.name,
            host = receiver.host,
            port = receiver.port,
            type = receiver.type,
            serviceType = receiver.serviceType,
            model = receiver.model,
            authMethod = authMethod,
            clientPairingId = clientPairingId,
            clientEd25519SeedBase64 = edKeys?.let { Base64.encodeToString(it.seed32, Base64.NO_WRAP) },
            accessoryLtpkBase64 = accessoryLtpk?.let { Base64.encodeToString(it, Base64.NO_WRAP) },
            pairedAtMs = now,
            lastConnectedMs = now
        )
        onPairingPersisted?.invoke(record)
    }

    fun clearLogs() {
        _protocolLogs.value = emptyList()
    }

    fun appendSystemLog(summary: String, details: String) {
        addLog(LogDirection.SYS, summary, details)
    }

    private fun connectionKey(host: String, port: Int): String = "$host:$port"

    private fun getOrCreateEd25519Keys(deviceMacId: String): AirPlayCrypto.Ed25519KeyPair {
        val seed = AirPlayCrypto.sha512("AirCastBridge:$deviceMacId".toByteArray(Charsets.UTF_8))
            .copyOf(32)
        return AirPlayCrypto.generateEd25519KeyPair(seed)
    }

    private fun openFreshPersistentConnection(host: String, port: Int, timeoutMs: Int = 8000): PersistentConnection {
        val key = connectionKey(host, port)
        activeConnections.remove(key)?.closeQuietly()
        val socket = Socket()
        socket.connect(InetSocketAddress(host, port), timeoutMs)
        socket.soTimeout = timeoutMs
        socket.keepAlive = true
        val conn = PersistentConnection(
            host = host,
            port = port,
            socket = socket,
            input = BufferedInputStream(socket.getInputStream()),
            output = socket.getOutputStream()
        )
        activeConnections[key] = conn
        return conn
    }

    private fun getOrOpenPersistentConnection(host: String, port: Int, timeoutMs: Int = 8000): PersistentConnection {
        val key = connectionKey(host, port)
        val existing = activeConnections[key]
        if (existing != null && existing.isOpen) {
            existing.socket.soTimeout = timeoutMs
            return existing
        }
        return openFreshPersistentConnection(host, port, timeoutMs)
    }

    private fun closePersistentConnection(host: String, port: Int) {
        activeConnections.remove(connectionKey(host, port))?.closeQuietly()
    }

    suspend fun probeReceiver(
        receiver: AirPlayReceiver,
        profile: SpoofProfile
    ): AirPlayResult<ProbeInfo> = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val headers = profile.buildHttpHeadersMap(includeMediaControl = true).apply {
            put("Host", "${receiver.host}:${receiver.port}")
            put("Connection", "close")
        }

        val response = executeSingleShotRequest(
            host = receiver.host,
            port = receiver.port,
            requestLine = "GET /info HTTP/1.1",
            headers = headers,
            body = null,
            timeoutMs = 3500
        )

        val elapsed = System.currentTimeMillis() - start
        when (response) {
            is RawHttpResponse.Ok -> {
                val requiresPin = response.statusCode == 401 || response.statusCode == 403
                val serverBanner = response.headers["server"] ?: response.statusLine
                val bodyStr = response.body.toString(Charsets.UTF_8)
                val modelMatch = Regex("<key>model</key>\\s*<string>([^<]+)</string>")
                    .find(bodyStr)?.groupValues?.getOrNull(1)
                AirPlayResult.Success(
                    ProbeInfo(
                        reachable = true,
                        latencyMs = elapsed,
                        serverBanner = serverBanner,
                        requiresPin = requiresPin,
                        inferredModel = modelMatch
                    ),
                    response.statusCode
                )
            }
            is RawHttpResponse.Error -> AirPlayResult.Failure(response.reason)
        }
    }

    /**
     * Opens a persistent keep-alive TCP socket and requests PIN display via POST /pair-pin-start.
     * Keeping this socket open is mandatory for Apple TV and AirPlay 2 TVs because the PIN session
     * is bound to the TCP connection where /pair-pin-start was initiated.
     */
    suspend fun startPinPairing(
        receiver: AirPlayReceiver,
        profile: SpoofProfile
    ): AirPlayResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val key = connectionKey(receiver.host, receiver.port)
            pairedIdentities.remove(key)
            pairingAuthMethods.remove(key)
            val conn = openFreshPersistentConnection(receiver.host, receiver.port, timeoutMs = 10_000)
            val headers = buildPairingHeaders(receiver, profile, contentLength = 0)
            val resp = executeOnPersistentConnection(conn, "POST /pair-pin-start HTTP/1.1", headers)
            when (resp) {
                is RawHttpResponse.Ok -> {
                    // Note: some AirPlay 2 TVs return 200 OK, while some Smart TVs that already
                    // display the PIN return 403/404 on /pair-pin-start and expect /pair-setup M1 next.
                    // Keep the socket alive in both cases.
                    AirPlayResult.Success(Unit, resp.statusCode)
                }
                is RawHttpResponse.Error -> {
                    closePersistentConnection(receiver.host, receiver.port)
                    AirPlayResult.Failure(resp.reason)
                }
            }
        } catch (e: Exception) {
            AirPlayResult.Failure(e.message ?: "Unable to connect for PIN pairing")
        }
    }

    /**
     * Executes Apple HAP Pair-Setup (M1..M6 with SRP-6a 3072-bit SHA-512) + Pair-Verify (M1..M4)
     * over the persistent keep-alive socket, with transient-flag and binary-plist fallbacks.
     */
    suspend fun submitPairingPin(
        receiver: AirPlayReceiver,
        profile: SpoofProfile,
        pin: String
    ): AirPlayResult<Unit> = withContext(Dispatchers.IO) {
        val cleanPin = pin.trim()
        if (cleanPin.length != 4) {
            return@withContext AirPlayResult.Failure("Enter the 4-digit PIN displayed on your TV")
        }

        try {
            var conn = getOrOpenPersistentConnection(receiver.host, receiver.port, timeoutMs = 12_000)

            // Attempt 1: Standard HAP Pair-Setup M1 (Method=0x00, State=0x01)
            var usedTransient = false
            var m1Body = AirPlayCrypto.encodeTlv8(
                AirPlayCrypto.TLV_STATE to byteArrayOf(0x01),
                AirPlayCrypto.TLV_METHOD to byteArrayOf(0x00)
            )
            var m2Resp = sendTlvRequest(conn, receiver, profile, "/pair-setup", m1Body)

            // If rejected (400/403) or dropped, try Transient Pair-Setup (Flags=0x10) WITHOUT re-calling
            // /pair-pin-start so the TV keeps the active PIN on screen.
            val m1Status = (m2Resp as? RawHttpResponse.Ok)?.statusCode ?: -1
            if (m2Resp is RawHttpResponse.Error || m1Status == 403 || m1Status == 400) {
                usedTransient = true
                if (!conn.isOpen || m2Resp is RawHttpResponse.Error) {
                    conn = openFreshPersistentConnection(receiver.host, receiver.port, timeoutMs = 12_000)
                }
                m1Body = AirPlayCrypto.encodeTlv8(
                    AirPlayCrypto.TLV_STATE to byteArrayOf(0x01),
                    AirPlayCrypto.TLV_METHOD to byteArrayOf(0x00),
                    AirPlayCrypto.TLV_FLAGS to byteArrayOf(0x10, 0x00, 0x00, 0x00)
                )
                m2Resp = sendTlvRequest(conn, receiver, profile, "/pair-setup", m1Body)
            }

            if (m2Resp is RawHttpResponse.Ok && m2Resp.statusCode in 200..299 && m2Resp.body.isNotEmpty()) {
                val m2Tlv = AirPlayCrypto.decodeTlv8(m2Resp.body)
                val errByte = m2Tlv[AirPlayCrypto.TLV_ERROR]?.firstOrNull()?.toInt()?.and(0xFF)
                if (errByte != null) {
                    return@withContext AirPlayResult.Failure(AirPlayCrypto.hapErrorDescription(errByte), m2Resp.statusCode)
                }

                val salt = m2Tlv[AirPlayCrypto.TLV_SALT]
                val serverPubB = m2Tlv[AirPlayCrypto.TLV_PUBLIC_KEY]
                if (salt != null && serverPubB != null) {
                    return@withContext completeHapSrpSetup(
                        conn = conn,
                        receiver = receiver,
                        profile = profile,
                        pin = cleanPin,
                        salt = salt,
                        serverPubB = serverPubB,
                        isTransient = usedTransient
                    )
                }
            }

            if (profile.protocolMode == AirPlayProtocolMode.AIRPLAY_2) {
                closePersistentConnection(receiver.host, receiver.port)
                return@withContext AirPlayResult.Failure(
                    "The receiver did not complete AirPlay 2 PIN pairing. No verified HAP session was established."
                )
            }

            // Fallback for AirPlay 1 / third-party receivers expecting bplist00 PIN payload
            if (!conn.isOpen) {
                conn = openFreshPersistentConnection(receiver.host, receiver.port, timeoutMs = 12_000)
            }
            val bplistPayload = AirPlayCrypto.encodeStringDictBinaryPlist(
                mapOf(
                    "pin" to cleanPin,
                    "deviceID" to profile.deviceMacId,
                    "model" to profile.modelIdentifier
                )
            )
            val bplistHeaders = buildPairingHeaders(
                receiver = receiver,
                profile = profile,
                contentLength = bplistPayload.size,
                contentType = "application/x-apple-binary-plist"
            )
            val bplistResp = executeOnPersistentConnection(
                conn,
                "POST /pair-setup HTTP/1.1",
                bplistHeaders,
                bplistPayload
            )
            when (bplistResp) {
                is RawHttpResponse.Ok -> {
                    if (bplistResp.statusCode in 200..299) {
                        conn.isPaired = true
                        emitPairedRecord(
                            receiver = receiver,
                            authMethod = PairingAuthMethod.BPLIST_PIN,
                            clientPairingId = profile.deviceMacId
                        )
                        AirPlayResult.Success(Unit, bplistResp.statusCode)
                    } else {
                        val code = bplistResp.statusCode
                        val detail = if (code == 403) {
                            "PIN rejected or pairing blocked (HTTP 403). Check TV AirPlay settings and re-enter the active PIN."
                        } else {
                            "Receiver returned HTTP $code during PIN pairing"
                        }
                        AirPlayResult.Failure(detail, code)
                    }
                }
                is RawHttpResponse.Error -> AirPlayResult.Failure(bplistResp.reason)
            }
        } catch (e: Exception) {
            AirPlayResult.Failure(e.message ?: "Pairing error")
        }
    }

    private fun completeHapSrpSetup(
        conn: PersistentConnection,
        receiver: AirPlayReceiver,
        profile: SpoofProfile,
        pin: String,
        salt: ByteArray,
        serverPubB: ByteArray,
        isTransient: Boolean,
        authMethod: PairingAuthMethod = if (isTransient) PairingAuthMethod.TRANSIENT_SRP else PairingAuthMethod.HAP_ED25519
    ): AirPlayResult<Unit> {
        val srp = AirPlayCrypto.createSrpClientSession(
            pin = pin,
            salt = salt,
            serverPublicKeyB = serverPubB
        )

        // M3: Send SRP Public Key A + Proof M1
        val m3Body = AirPlayCrypto.encodeTlv8(
            AirPlayCrypto.TLV_STATE to byteArrayOf(0x03),
            AirPlayCrypto.TLV_PUBLIC_KEY to srp.publicKeyA,
            AirPlayCrypto.TLV_PROOF to srp.proofM1
        )
        val m4Resp = sendTlvRequest(conn, receiver, profile, "/pair-setup", m3Body)
        if (m4Resp !is RawHttpResponse.Ok) {
            return AirPlayResult.Failure((m4Resp as RawHttpResponse.Error).reason)
        }
        if (m4Resp.statusCode !in 200..299) {
            return AirPlayResult.Failure("PIN verification failed (HTTP ${m4Resp.statusCode})", m4Resp.statusCode)
        }

        val m4Tlv = AirPlayCrypto.decodeTlv8(m4Resp.body)
        val m4Err = m4Tlv[AirPlayCrypto.TLV_ERROR]?.firstOrNull()?.toInt()?.and(0xFF)
        if (m4Err != null) {
            return AirPlayResult.Failure(AirPlayCrypto.hapErrorDescription(m4Err), m4Resp.statusCode)
        }

        val serverProof = m4Tlv[AirPlayCrypto.TLV_PROOF]
        if (serverProof == null || !serverProof.contentEquals(srp.expectedServerProofM2)) {
            return AirPlayResult.Failure(
                "Incorrect PIN or proof mismatch on ${receiver.name}. Tap Resend PIN and re-enter the 4-digit code."
            )
        }

        // Transient AirPlay 2 Pairing (Flags=0x10): M4 completes pairing and control encryption
        // keys are derived directly from srp.sessionKeyK (no M5/M6, no /pair-verify).
        if (isTransient) {
            if (activateTransientControlEncryption(conn, receiver, profile, srp.sessionKeyK)) {
                conn.isPaired = true
                emitPairedRecord(
                    receiver = receiver,
                    authMethod = PairingAuthMethod.TRANSIENT_SRP,
                    clientPairingId = profile.deviceMacId
                )
                appendSystemLog("Transient HAP Active", "Encrypted AirPlay 2 session established with ${receiver.name}")
                return AirPlayResult.Success(Unit, 200)
            }
            return AirPlayResult.Failure("PIN accepted at M4, but receiver closed control session. Tap Resend PIN and retry.")
        }

        // Standard HAP M5: Exchange Ed25519 long-term keys encrypted with HKDF(K) + ChaCha20-Poly1305
        val edKeys = getOrCreateEd25519Keys(profile.deviceMacId)
        val pairingId = profile.deviceMacId.toByteArray(Charsets.UTF_8)
        val pairSetupEncryptKey = AirPlayCrypto.hkdfSha512(
            ikm = srp.sessionKeyK,
            salt = "Pair-Setup-Encrypt-Salt".toByteArray(Charsets.UTF_8),
            info = "Pair-Setup-Encrypt-Info".toByteArray(Charsets.UTF_8),
            length = 32
        )
        val controllerSignKey = AirPlayCrypto.hkdfSha512(
            ikm = srp.sessionKeyK,
            salt = "Pair-Setup-Controller-Sign-Salt".toByteArray(Charsets.UTF_8),
            info = "Pair-Setup-Controller-Sign-Info".toByteArray(Charsets.UTF_8),
            length = 32
        )
        val deviceInfo = controllerSignKey + pairingId + edKeys.publicKey32
        val signature = AirPlayCrypto.ed25519Sign(edKeys, deviceInfo)
        val subTlv5 = AirPlayCrypto.encodeTlv8(
            AirPlayCrypto.TLV_IDENTIFIER to pairingId,
            AirPlayCrypto.TLV_PUBLIC_KEY to edKeys.publicKey32,
            AirPlayCrypto.TLV_SIGNATURE to signature
        )
        val encrypted5 = AirPlayCrypto.chacha20Poly1305Encrypt(
            key = pairSetupEncryptKey,
            nonce12 = AirPlayCrypto.hapNonce(label8 = "PS-Msg05"),
            aad = ByteArray(0),
            plaintext = subTlv5
        )
        val m5Body = AirPlayCrypto.encodeTlv8(
            AirPlayCrypto.TLV_STATE to byteArrayOf(0x05),
            AirPlayCrypto.TLV_ENCRYPTED_DATA to encrypted5
        )
        val m6Resp = sendTlvRequest(conn, receiver, profile, "/pair-setup", m5Body)
        if (m6Resp is RawHttpResponse.Ok && m6Resp.statusCode in 200..299) {
            val m6Tlv = AirPlayCrypto.decodeTlv8(m6Resp.body)
            val m6Err = m6Tlv[AirPlayCrypto.TLV_ERROR]?.firstOrNull()?.toInt()?.and(0xFF)
            if (m6Err != null) {
                return AirPlayResult.Failure(AirPlayCrypto.hapErrorDescription(m6Err), m6Resp.statusCode)
            }
            val enc6 = m6Tlv[AirPlayCrypto.TLV_ENCRYPTED_DATA]
            val accessoryLtpk = enc6?.let {
                AirPlayCrypto.chacha20Poly1305Decrypt(
                    key = pairSetupEncryptKey,
                    nonce12 = AirPlayCrypto.hapNonce(label8 = "PS-Msg06"),
                    aad = ByteArray(0),
                    ciphertextWithTag = it
                )
            }?.let { plain6 ->
                AirPlayCrypto.decodeTlv8(plain6)[AirPlayCrypto.TLV_PUBLIC_KEY]
            }

            var verifyConn = conn
            var verifyOk = runPairVerify(verifyConn, receiver, profile, edKeys, profile.deviceMacId)
            if (!verifyOk) {
                verifyConn = openFreshPersistentConnection(receiver.host, receiver.port, timeoutMs = 10_000)
                verifyOk = runPairVerify(verifyConn, receiver, profile, edKeys, profile.deviceMacId)
            }
            if (!verifyOk) {
                closePersistentConnection(receiver.host, receiver.port)
                return AirPlayResult.Failure(
                    "PIN matched, but /pair-verify failed on ${receiver.name}. Try connecting again or check TV AirPlay settings."
                )
            }

            verifyConn.isPaired = true
            pairedIdentities[connectionKey(receiver.host, receiver.port)] = PairedIdentity(
                clientPairingId = profile.deviceMacId,
                clientKeyPair = edKeys,
                accessoryPublicKey = accessoryLtpk,
                authMethod = authMethod
            )
            emitPairedRecord(
                receiver = receiver,
                authMethod = authMethod,
                clientPairingId = profile.deviceMacId,
                edKeys = edKeys,
                accessoryLtpk = accessoryLtpk
            )
            appendSystemLog("AirPlay 2 HAP Verified", "Encrypted control channel active with ${receiver.name}")
            return AirPlayResult.Success(Unit, 200)
        }

        // If M5 is rejected by a receiver that actually expects transient keys from srp.sessionKeyK
        if (activateTransientControlEncryption(conn, receiver, profile, srp.sessionKeyK)) {
            conn.isPaired = true
            emitPairedRecord(
                receiver = receiver,
                authMethod = PairingAuthMethod.TRANSIENT_SRP,
                clientPairingId = profile.deviceMacId
            )
            appendSystemLog("Transient Fallback Active", "Control encryption established with ${receiver.name}")
            return AirPlayResult.Success(Unit, 200)
        }

        closePersistentConnection(receiver.host, receiver.port)
        val m6Code = (m6Resp as? RawHttpResponse.Ok)?.statusCode ?: 0
        return AirPlayResult.Failure(
            "Pairing did not complete on ${receiver.name} (M6 status HTTP $m6Code). Try Resend PIN or No-PIN mode."
        )
    }

    private fun activateTransientControlEncryption(
        conn: PersistentConnection,
        receiver: AirPlayReceiver,
        profile: SpoofProfile,
        sessionKeyK: ByteArray
    ): Boolean {
        val writeKey = AirPlayCrypto.hkdfSha512(
            ikm = sessionKeyK,
            salt = "Control-Salt".toByteArray(Charsets.UTF_8),
            info = "Control-Write-Encryption-Key".toByteArray(Charsets.UTF_8),
            length = 32
        )
        val readKey = AirPlayCrypto.hkdfSha512(
            ikm = sessionKeyK,
            salt = "Control-Salt".toByteArray(Charsets.UTF_8),
            info = "Control-Read-Encryption-Key".toByteArray(Charsets.UTF_8),
            length = 32
        )
        conn.enableHapEncryption(writeKey, readKey)

        // Probe encrypted control channel to confirm cipher keys match the receiver
        val probeHeaders = profile.buildHttpHeadersMap(includeMediaControl = true).apply {
            put("Host", "${receiver.host}:${receiver.port}")
            put("Connection", "keep-alive")
        }
        val probe = executeOnPersistentConnection(conn, "GET /info HTTP/1.1", probeHeaders)
        return probe is RawHttpResponse.Ok && probe.statusCode != 401 && probe.statusCode != 403
    }

    /**
     * Connects to a screenless / PIN-less AirPlay receiver without requiring a user-entered PIN.
     * Tries: (1) open /info & /server-info control channel, (2) Curve25519 /auth-setup,
     * and (3) Transient HAP Pair-Setup with default "0000" for screenless AirPlay 2 devices.
     */
    suspend fun connectWithoutPin(
        receiver: AirPlayReceiver,
        profile: SpoofProfile
    ): AirPlayResult<String> = withContext(Dispatchers.IO) {
        val key = connectionKey(receiver.host, receiver.port)
        pairedIdentities.remove(key)
        pairingAuthMethods.remove(key)
        closePersistentConnection(receiver.host, receiver.port)

        try {
            val conn = openFreshPersistentConnection(receiver.host, receiver.port, timeoutMs = 5_000)
            val infoHeaders = profile.buildHttpHeadersMap(includeMediaControl = true).apply {
                put("Host", "${receiver.host}:${receiver.port}")
                put("Connection", "keep-alive")
            }

            val infoResp = executeOnPersistentConnection(conn, "GET /info HTTP/1.1", infoHeaders)
            if (infoResp is RawHttpResponse.Ok && infoResp.statusCode in 200..299) {
                // Verify media control isn't gated by /playback-info or /server-info returning 401/403
                val playbackCheck = executeOnPersistentConnection(conn, "GET /playback-info HTTP/1.1", infoHeaders)
                if (playbackCheck is RawHttpResponse.Ok && playbackCheck.statusCode != 401 && playbackCheck.statusCode != 403) {
                    conn.isPaired = true
                    appendSystemLog("Connected Without PIN", "Open AirPlay control channel active on ${receiver.name}")
                    return@withContext AirPlayResult.Success("Connected without PIN (open channel)", infoResp.statusCode)
                }
            }

            // AirPlay 1 / legacy /server-info check
            val freshForServerInfo = if (conn.isOpen) conn else openFreshPersistentConnection(receiver.host, receiver.port, 5_000)
            val serverInfoResp = executeOnPersistentConnection(freshForServerInfo, "GET /server-info HTTP/1.1", infoHeaders)
            if (serverInfoResp is RawHttpResponse.Ok && serverInfoResp.statusCode in 200..299) {
                freshForServerInfo.isPaired = true
                appendSystemLog("Connected Without PIN", "Legacy AirPlay /server-info open on ${receiver.name}")
                return@withContext AirPlayResult.Success("Connected without PIN (AirPlay 1)", serverInfoResp.statusCode)
            }

            // Try Curve25519 /auth-setup (used by many screenless dongles/speakers)
            val authConn = openFreshPersistentConnection(receiver.host, receiver.port, 5_000)
            val (_, ePub) = AirPlayCrypto.generateX25519KeyPair()
            val authPayload = byteArrayOf(0x01) + ePub
            val authResp = executeOnPersistentConnection(
                authConn,
                "POST /auth-setup HTTP/1.1",
                buildPairingHeaders(receiver, profile, authPayload.size, "application/octet-stream"),
                authPayload
            )
            if (authResp is RawHttpResponse.Ok && authResp.statusCode in 200..299) {
                authConn.isPaired = true
                pairingAuthMethods[key] = PairingAuthMethod.AUTH_SETUP
                appendSystemLog("Connected Without PIN", "Curve25519 /auth-setup succeeded on ${receiver.name}")
                return@withContext AirPlayResult.Success("Connected without PIN (/auth-setup)", authResp.statusCode)
            }

            // Try Screenless AirPlay 2 Transient Pair-Setup with default "0000"
            val hapConn = openFreshPersistentConnection(receiver.host, receiver.port, 5_000)
            val m1Body = AirPlayCrypto.encodeTlv8(
                AirPlayCrypto.TLV_STATE to byteArrayOf(0x01),
                AirPlayCrypto.TLV_METHOD to byteArrayOf(0x00),
                AirPlayCrypto.TLV_FLAGS to byteArrayOf(0x10, 0x00, 0x00, 0x00)
            )
            val m2Resp = sendTlvRequest(hapConn, receiver, profile, "/pair-setup", m1Body)
            if (m2Resp is RawHttpResponse.Ok && m2Resp.statusCode in 200..299 && m2Resp.body.isNotEmpty()) {
                val m2Tlv = AirPlayCrypto.decodeTlv8(m2Resp.body)
                val salt = m2Tlv[AirPlayCrypto.TLV_SALT]
                val serverB = m2Tlv[AirPlayCrypto.TLV_PUBLIC_KEY]
                if (salt != null && serverB != null) {
                    val zeroPinRes = completeHapSrpSetup(
                        conn = hapConn,
                        receiver = receiver,
                        profile = profile,
                        pin = "0000",
                        salt = salt,
                        serverPubB = serverB,
                        isTransient = true
                    )
                    if (zeroPinRes is AirPlayResult.Success) {
                        return@withContext AirPlayResult.Success("Connected to screenless device (0000)", 200)
                    }
                }
            }

            closePersistentConnection(receiver.host, receiver.port)
            AirPlayResult.PinRequired("${receiver.name} enforces a 4-digit on-screen PIN")
        } catch (e: Exception) {
            closePersistentConnection(receiver.host, receiver.port)
            AirPlayResult.Failure(e.message ?: "Unable to connect to ${receiver.name}")
        }
    }

    /**
     * Attempts to recover an unexpectedly dropped active stream socket without altering saved startup rules.
     */
    suspend fun reconnectDroppedSession(
        receiver: AirPlayReceiver,
        profile: SpoofProfile,
        wasConnectedWithoutPin: Boolean
    ): AirPlayResult<String> = withContext(Dispatchers.IO) {
        val key = connectionKey(receiver.host, receiver.port)
        val paired = pairedIdentities[key]
        val existing = activeConnections[key]

        try {
            if (existing != null && existing.isOpen && existing.isPaired) {
                val headers = profile.buildHttpHeadersMap(includeMediaControl = true).apply {
                    put("Host", "${receiver.host}:${receiver.port}")
                    put("Connection", "keep-alive")
                }
                val ping = executeOnPersistentConnection(existing, "GET /info HTTP/1.1", headers)
                if (ping is RawHttpResponse.Ok && ping.statusCode != 401 && ping.statusCode != 403) {
                    return@withContext AirPlayResult.Success("Active socket verified", ping.statusCode)
                }
                closePersistentConnection(receiver.host, receiver.port)
            }

            if (paired != null) {
                val freshConn = openFreshPersistentConnection(receiver.host, receiver.port, timeoutMs = 4500)
                if (runPairVerify(freshConn, receiver, profile, paired.clientKeyPair, paired.clientPairingId)) {
                    freshConn.isPaired = true
                    appendSystemLog("Stream Reconnected (Pair-Verify)", "Re-established encrypted channel to ${receiver.name}")
                    return@withContext AirPlayResult.Success("Re-verified HAP session", 200)
                }
                closePersistentConnection(receiver.host, receiver.port)
            }

            if (wasConnectedWithoutPin) {
                return@withContext connectWithoutPin(receiver, profile)
            }

            // Probe if receiver accepts open control / info resume
            val conn = openFreshPersistentConnection(receiver.host, receiver.port, timeoutMs = 4500)
            val headers = profile.buildHttpHeadersMap(includeMediaControl = true).apply {
                put("Host", "${receiver.host}:${receiver.port}")
                put("Connection", "keep-alive")
            }
            val probe = executeOnPersistentConnection(conn, "GET /playback-info HTTP/1.1", headers)
            if (probe is RawHttpResponse.Ok && probe.statusCode != 401 && probe.statusCode != 403) {
                conn.isPaired = true
                return@withContext AirPlayResult.Success("Re-established AirPlay socket", probe.statusCode)
            }
            closePersistentConnection(receiver.host, receiver.port)
            AirPlayResult.PinRequired("${receiver.name} closed session; PIN verification needed")
        } catch (e: Exception) {
            closePersistentConnection(receiver.host, receiver.port)
            AirPlayResult.Failure(e.message ?: "${receiver.name} unreachable")
        }
    }

    /**
     * Silently re-authenticates with a previously paired receiver using credentials restored from DataStore.
     */
    suspend fun reconnectWithSavedCredentials(
        receiver: AirPlayReceiver,
        profile: SpoofProfile
    ): AirPlayResult<String> = withContext(Dispatchers.IO) {
        val key = connectionKey(receiver.host, receiver.port)
        val paired = pairedIdentities[key]
        val method = pairingAuthMethods[key]

        try {
            if (paired != null) {
                val existing = activeConnections[key]
                if (existing != null && existing.isOpen && existing.isPaired) {
                    return@withContext AirPlayResult.Success("Resumed active paired session", 200)
                }
                val freshConn = openFreshPersistentConnection(receiver.host, receiver.port, timeoutMs = 4500)
                if (runPairVerify(freshConn, receiver, profile, paired.clientKeyPair, paired.clientPairingId)) {
                    freshConn.isPaired = true
                    appendSystemLog("Auto-Reconnected (HAP Pair-Verify)", "Restored encrypted session with ${receiver.name} without PIN")
                    return@withContext AirPlayResult.Success("Verified paired identity (no PIN needed)", 200)
                }
                closePersistentConnection(receiver.host, receiver.port)
                if (paired.authMethod == PairingAuthMethod.HAP_ED25519) {
                    return@withContext AirPlayResult.PinRequired("Saved pairing expired or revoked on ${receiver.name}")
                }
            }

            if (method == PairingAuthMethod.AUTH_SETUP) {
                val conn = openFreshPersistentConnection(receiver.host, receiver.port, timeoutMs = 4500)
                val (_, ePub) = AirPlayCrypto.generateX25519KeyPair()
                val authPayload = byteArrayOf(0x01) + ePub
                val authResp = executeOnPersistentConnection(
                    conn,
                    "POST /auth-setup HTTP/1.1",
                    buildPairingHeaders(receiver, profile, authPayload.size, "application/octet-stream"),
                    authPayload
                )
                if (authResp is RawHttpResponse.Ok && authResp.statusCode in 200..299) {
                    conn.isPaired = true
                    appendSystemLog("Auto-Reconnected (/auth-setup)", "Resumed session with ${receiver.name}")
                    return@withContext AirPlayResult.Success("Resumed Curve25519 /auth-setup session", authResp.statusCode)
                }
            }

            when (val probe = probeReceiver(receiver, profile)) {
                is AirPlayResult.Success -> {
                    if (probe.data.requiresPin) {
                        AirPlayResult.PinRequired("${receiver.name} requests fresh pairing")
                    } else {
                        AirPlayResult.Success("Reconnected to ${receiver.name} (${probe.data.latencyMs}ms)", 200)
                    }
                }
                is AirPlayResult.PinRequired -> probe
                is AirPlayResult.Failure -> probe
            }
        } catch (e: Exception) {
            AirPlayResult.Failure(e.message ?: "${receiver.name} unreachable")
        }
    }

    private fun runPairVerify(
        conn: PersistentConnection,
        receiver: AirPlayReceiver,
        profile: SpoofProfile,
        edKeys: AirPlayCrypto.Ed25519KeyPair,
        clientPairingId: String
    ): Boolean {
        val (ePriv, ePub) = AirPlayCrypto.generateX25519KeyPair()
        val v1Body = AirPlayCrypto.encodeTlv8(
            AirPlayCrypto.TLV_STATE to byteArrayOf(0x01),
            AirPlayCrypto.TLV_PUBLIC_KEY to ePub
        )
        val v2Resp = sendTlvRequest(conn, receiver, profile, "/pair-verify", v1Body)
        if (v2Resp !is RawHttpResponse.Ok || v2Resp.statusCode !in 200..299) return false

        val v2Tlv = AirPlayCrypto.decodeTlv8(v2Resp.body)
        val serverEPub = v2Tlv[AirPlayCrypto.TLV_PUBLIC_KEY] ?: return false
        val sharedSecret = AirPlayCrypto.x25519(ePriv, serverEPub)
        val verifyEncKey = AirPlayCrypto.hkdfSha512(
            ikm = sharedSecret,
            salt = "Pair-Verify-Encrypt-Salt".toByteArray(Charsets.UTF_8),
            info = "Pair-Verify-Encrypt-Info".toByteArray(Charsets.UTF_8),
            length = 32
        )

        val pairingId = clientPairingId.toByteArray(Charsets.UTF_8)
        val deviceInfo = ePub + pairingId + serverEPub
        val sig = AirPlayCrypto.ed25519Sign(edKeys, deviceInfo)
        val subTlv3 = AirPlayCrypto.encodeTlv8(
            AirPlayCrypto.TLV_IDENTIFIER to pairingId,
            AirPlayCrypto.TLV_SIGNATURE to sig
        )
        val enc3 = AirPlayCrypto.chacha20Poly1305Encrypt(
            key = verifyEncKey,
            nonce12 = AirPlayCrypto.hapNonce(label8 = "PV-Msg03"),
            aad = ByteArray(0),
            plaintext = subTlv3
        )
        val v3Body = AirPlayCrypto.encodeTlv8(
            AirPlayCrypto.TLV_STATE to byteArrayOf(0x03),
            AirPlayCrypto.TLV_ENCRYPTED_DATA to enc3
        )
        val v4Resp = sendTlvRequest(conn, receiver, profile, "/pair-verify", v3Body)
        if (v4Resp !is RawHttpResponse.Ok || v4Resp.statusCode !in 200..299) return false

        val writeKey = AirPlayCrypto.hkdfSha512(
            ikm = sharedSecret,
            salt = "Control-Salt".toByteArray(Charsets.UTF_8),
            info = "Control-Write-Encryption-Key".toByteArray(Charsets.UTF_8),
            length = 32
        )
        val readKey = AirPlayCrypto.hkdfSha512(
            ikm = sharedSecret,
            salt = "Control-Salt".toByteArray(Charsets.UTF_8),
            info = "Control-Read-Encryption-Key".toByteArray(Charsets.UTF_8),
            length = 32
        )
        conn.enableHapEncryption(writeKey, readKey)
        return true
    }

    suspend fun castPhoto(
        receiver: AirPlayReceiver,
        profile: SpoofProfile,
        jpegBytes: ByteArray,
        transition: PhotoTransition
    ): AirPlayResult<Unit> = withContext(Dispatchers.IO) {
        val assetKey = UUID.randomUUID().toString().uppercase()
        val headers = profile.buildHttpHeadersMap(includeMediaControl = true).apply {
            put("Host", "${receiver.host}:${receiver.port}")
            put("X-Apple-AssetKey", assetKey)
            put("X-Apple-Transition", transition.headerValue)
            put("Content-Type", "image/jpeg")
            put("Content-Length", jpegBytes.size.toString())
            put("Connection", "keep-alive")
        }
        when (val resp = executeControlRequest(receiver, profile, "PUT /photo HTTP/1.1", headers, jpegBytes)) {
            is RawHttpResponse.Ok -> mapControlResponse(resp)
            is RawHttpResponse.Error -> AirPlayResult.Failure(resp.reason)
        }
    }

    suspend fun castMediaUrl(
        receiver: AirPlayReceiver,
        profile: SpoofProfile,
        mediaUrl: String,
        startPosition: Double = 0.0
    ): AirPlayResult<Unit> = withContext(Dispatchers.IO) {
        val preferBplist = profile.protocolMode == AirPlayProtocolMode.AIRPLAY_2
        val firstResp = sendPlayPayload(receiver, profile, mediaUrl, startPosition, useBinaryPlist = preferBplist)
        if (firstResp is RawHttpResponse.Ok && firstResp.statusCode in 200..299) {
            return@withContext AirPlayResult.Success(Unit, firstResp.statusCode)
        }

        val firstCode = (firstResp as? RawHttpResponse.Ok)?.statusCode ?: -1
        if (firstCode == 400 || firstCode == 404 || firstCode == 415 || firstCode == 501) {
            val fallbackResp = sendPlayPayload(
                receiver,
                profile,
                mediaUrl,
                startPosition,
                useBinaryPlist = !preferBplist
            )
            return@withContext when (fallbackResp) {
                is RawHttpResponse.Ok -> mapControlResponse(fallbackResp)
                is RawHttpResponse.Error -> AirPlayResult.Failure(fallbackResp.reason)
            }
        }

        when (firstResp) {
            is RawHttpResponse.Ok -> mapControlResponse(firstResp)
            is RawHttpResponse.Error -> AirPlayResult.Failure(firstResp.reason)
        }
    }

    private fun sendPlayPayload(
        receiver: AirPlayReceiver,
        profile: SpoofProfile,
        mediaUrl: String,
        startPosition: Double,
        useBinaryPlist: Boolean
    ): RawHttpResponse {
        val bodyBytes: ByteArray
        val contentType: String
        if (useBinaryPlist) {
            bodyBytes = AirPlayCrypto.encodePlayBinaryPlist(mediaUrl, startPosition)
            contentType = "application/x-apple-binary-plist"
        } else {
            bodyBytes = "Content-Location: $mediaUrl\nStart-Position: $startPosition\n"
                .toByteArray(Charsets.UTF_8)
            contentType = "text/parameters"
        }
        val headers = profile.buildHttpHeadersMap(includeMediaControl = true).apply {
            put("Host", "${receiver.host}:${receiver.port}")
            put("Content-Type", contentType)
            put("Content-Length", bodyBytes.size.toString())
            put("Connection", "keep-alive")
        }
        return executeControlRequest(receiver, profile, "POST /play HTTP/1.1", headers, bodyBytes)
    }

    suspend fun setPlaybackRate(
        receiver: AirPlayReceiver,
        profile: SpoofProfile,
        rate: Double
    ): AirPlayResult<Unit> = withContext(Dispatchers.IO) {
        val headers = profile.buildHttpHeadersMap(includeMediaControl = true).apply {
            put("Host", "${receiver.host}:${receiver.port}")
            put("Content-Length", "0")
            put("Connection", "keep-alive")
        }
        when (val resp = executeControlRequest(receiver, profile, "POST /rate?value=$rate HTTP/1.1", headers)) {
            is RawHttpResponse.Ok -> mapControlResponse(resp)
            is RawHttpResponse.Error -> AirPlayResult.Failure(resp.reason)
        }
    }

    suspend fun seekTo(
        receiver: AirPlayReceiver,
        profile: SpoofProfile,
        positionSec: Double
    ): AirPlayResult<Unit> = withContext(Dispatchers.IO) {
        val formatted = String.format(Locale.US, "%.2f", positionSec)
        val headers = profile.buildHttpHeadersMap(includeMediaControl = true).apply {
            put("Host", "${receiver.host}:${receiver.port}")
            put("Content-Length", "0")
            put("Connection", "keep-alive")
        }
        when (val resp = executeControlRequest(receiver, profile, "POST /scrub?position=$formatted HTTP/1.1", headers)) {
            is RawHttpResponse.Ok -> mapControlResponse(resp)
            is RawHttpResponse.Error -> AirPlayResult.Failure(resp.reason)
        }
    }

    suspend fun setVolume(
        receiver: AirPlayReceiver,
        profile: SpoofProfile,
        volumePercent: Int
    ): AirPlayResult<Unit> = withContext(Dispatchers.IO) {
        val normalized = (volumePercent.coerceIn(0, 100)) / 100.0
        val formatted = String.format(Locale.US, "%.2f", normalized)
        val headers = profile.buildHttpHeadersMap(includeMediaControl = true).apply {
            put("Host", "${receiver.host}:${receiver.port}")
            put("Content-Length", "0")
            put("Connection", "keep-alive")
        }
        when (val resp = executeControlRequest(receiver, profile, "POST /volume?value=$formatted HTTP/1.1", headers)) {
            is RawHttpResponse.Ok -> mapControlResponse(resp)
            is RawHttpResponse.Error -> AirPlayResult.Failure(resp.reason)
        }
    }

    suspend fun getPlaybackInfo(
        receiver: AirPlayReceiver,
        profile: SpoofProfile
    ): AirPlayResult<PlaybackInfoSnapshot> = withContext(Dispatchers.IO) {
        val headers = profile.buildHttpHeadersMap(includeMediaControl = true).apply {
            put("Host", "${receiver.host}:${receiver.port}")
            put("Connection", "keep-alive")
        }
        when (val resp = executeControlRequest(receiver, profile, "GET /playback-info HTTP/1.1", headers)) {
            is RawHttpResponse.Ok -> {
                val body = resp.body.toString(Charsets.UTF_8)
                val pos = Regex("<key>position</key>\\s*<real>([0-9.]+)</real>").find(body)
                    ?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: 0.0
                val dur = Regex("<key>duration</key>\\s*<real>([0-9.]+)</real>").find(body)
                    ?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: 0.0
                val rate = Regex("<key>rate</key>\\s*<real>([0-9.]+)</real>").find(body)
                    ?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: 1.0
                AirPlayResult.Success(PlaybackInfoSnapshot(pos, dur, rate), resp.statusCode)
            }
            is RawHttpResponse.Error -> AirPlayResult.Failure(resp.reason)
        }
    }

    suspend fun stopPlayback(
        receiver: AirPlayReceiver,
        profile: SpoofProfile
    ): AirPlayResult<Unit> = withContext(Dispatchers.IO) {
        val headers = profile.buildHttpHeadersMap(includeMediaControl = true).apply {
            put("Host", "${receiver.host}:${receiver.port}")
            put("Content-Length", "0")
            put("Connection", "close")
        }
        when (val resp = executeControlRequest(receiver, profile, "POST /stop HTTP/1.1", headers)) {
            is RawHttpResponse.Ok -> mapControlResponse(resp)
            is RawHttpResponse.Error -> AirPlayResult.Failure(resp.reason)
        }
    }

    private fun buildPairingHeaders(
        receiver: AirPlayReceiver,
        profile: SpoofProfile,
        contentLength: Int,
        contentType: String? = null
    ): LinkedHashMap<String, String> {
        return profile.buildHttpHeadersMap(includeMediaControl = false).apply {
            put("Host", "${receiver.host}:${receiver.port}")
            if (profile.protocolMode == AirPlayProtocolMode.AIRPLAY_2) {
                put("X-Apple-HKP", "3")
            }
            if (contentType != null) {
                put("Content-Type", contentType)
            }
            put("Content-Length", contentLength.toString())
            put("Connection", "keep-alive")
        }
    }

    private fun sendTlvRequest(
        conn: PersistentConnection,
        receiver: AirPlayReceiver,
        profile: SpoofProfile,
        path: String,
        tlvBody: ByteArray
    ): RawHttpResponse {
        val headers = buildPairingHeaders(
            receiver = receiver,
            profile = profile,
            contentLength = tlvBody.size,
            contentType = "application/octet-stream"
        )
        return executeOnPersistentConnection(conn, "POST $path HTTP/1.1", headers, tlvBody)
    }

    private fun executeControlRequest(
        receiver: AirPlayReceiver,
        profile: SpoofProfile,
        requestLine: String,
        headers: Map<String, String>,
        body: ByteArray? = null
    ): RawHttpResponse {
        val key = connectionKey(receiver.host, receiver.port)
        val existing = activeConnections[key]
        if (existing != null && existing.isOpen && existing.isPaired) {
            val resp = executeOnPersistentConnection(existing, requestLine, headers, body)
            if (resp is RawHttpResponse.Ok && resp.statusCode != 401 && resp.statusCode != 403) {
                return resp
            }
            if (resp is RawHttpResponse.Ok && (resp.statusCode == 401 || resp.statusCode == 403)) {
                closePersistentConnection(receiver.host, receiver.port)
                if (pairedIdentities[key] == null) return resp
            } else if (resp is RawHttpResponse.Error) {
                closePersistentConnection(receiver.host, receiver.port)
            }
        }

        val paired = pairedIdentities[key]
        if (paired != null) {
            runCatching {
                val freshConn = openFreshPersistentConnection(receiver.host, receiver.port)
                if (runPairVerify(freshConn, receiver, profile, paired.clientKeyPair, paired.clientPairingId)) {
                    freshConn.isPaired = true
                    return executeOnPersistentConnection(freshConn, requestLine, headers, body)
                }
            }
        }

        return executeSingleShotRequest(receiver.host, receiver.port, requestLine, headers, body)
    }

    private fun executeOnPersistentConnection(
        conn: PersistentConnection,
        requestLine: String,
        headers: Map<String, String>,
        body: ByteArray? = null
    ): RawHttpResponse {
        val headerBlock = buildString {
            append(requestLine).append("\r\n")
            headers.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
            append("\r\n")
        }
        addLog(
            direction = LogDirection.OUT,
            summary = requestLine,
            details = headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        )

        return try {
            val headerBytes = headerBlock.toByteArray(Charsets.UTF_8)
            val fullPayload = if (body != null && body.isNotEmpty()) headerBytes + body else headerBytes
            conn.sendRaw(fullPayload)

            val statusLine = conn.readLineAscii()
                ?: return RawHttpResponse.Error("Empty response from ${conn.host}:${conn.port}")
            val statusCode = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: 200
            val respHeaders = linkedMapOf<String, String>()
            while (true) {
                val line = conn.readLineAscii() ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    respHeaders[line.substring(0, idx).trim().lowercase()] =
                        line.substring(idx + 1).trim()
                }
            }
            val contentLength = respHeaders["content-length"]?.toIntOrNull() ?: 0
            val respBody = if (contentLength > 0) {
                conn.readExactBytes(minOf(contentLength, 262_144))
            } else {
                ByteArray(0)
            }

            if (respHeaders["connection"]?.equals("close", ignoreCase = true) == true) {
                closePersistentConnection(conn.host, conn.port)
            }

            addLog(
                direction = LogDirection.IN,
                summary = statusLine,
                details = respHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" }
            )

            RawHttpResponse.Ok(statusLine, statusCode, respHeaders, respBody)
        } catch (e: Exception) {
            closePersistentConnection(conn.host, conn.port)
            val msg = e.message ?: "Connection failed to ${conn.host}:${conn.port}"
            addLog(LogDirection.IN, "Socket Error (${conn.host}:${conn.port})", msg)
            RawHttpResponse.Error(msg)
        }
    }

    private fun mapControlResponse(resp: RawHttpResponse.Ok): AirPlayResult<Unit> {
        return when {
            resp.statusCode in 200..299 -> AirPlayResult.Success(Unit, resp.statusCode)
            resp.statusCode == 401 || resp.statusCode == 403 ->
                AirPlayResult.PinRequired("AirPlay PIN or pairing verification required (HTTP ${resp.statusCode})")
            else -> AirPlayResult.Failure("Receiver returned HTTP ${resp.statusCode}", resp.statusCode)
        }
    }

    private sealed class RawHttpResponse {
        data class Ok(
            val statusLine: String,
            val statusCode: Int,
            val headers: Map<String, String>,
            val body: ByteArray
        ) : RawHttpResponse()

        data class Error(val reason: String) : RawHttpResponse()
    }

    private fun executeSingleShotRequest(
        host: String,
        port: Int,
        requestLine: String,
        headers: Map<String, String>,
        body: ByteArray? = null,
        timeoutMs: Int = 5000
    ): RawHttpResponse {
        val headerBlock = buildString {
            append(requestLine).append("\r\n")
            headers.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
            append("\r\n")
        }
        addLog(
            direction = LogDirection.OUT,
            summary = requestLine,
            details = headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        )

        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                socket.soTimeout = timeoutMs
                val out = socket.getOutputStream()
                out.write(headerBlock.toByteArray(Charsets.UTF_8))
                if (body != null && body.isNotEmpty()) {
                    out.write(body)
                }
                out.flush()

                val input = BufferedInputStream(socket.getInputStream())
                val statusLine = readAsciiLine(input)
                    ?: return RawHttpResponse.Error("Empty response from $host:$port")
                val statusCode = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: 200
                val respHeaders = linkedMapOf<String, String>()
                while (true) {
                    val line = readAsciiLine(input) ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        respHeaders[line.substring(0, idx).trim().lowercase()] =
                            line.substring(idx + 1).trim()
                    }
                }
                val contentLength = respHeaders["content-length"]?.toIntOrNull() ?: 0
                val respBody = if (contentLength > 0) {
                    readFixedBytes(input, minOf(contentLength, 262_144))
                } else {
                    ByteArray(0)
                }

                addLog(
                    direction = LogDirection.IN,
                    summary = statusLine,
                    details = respHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" }
                )

                RawHttpResponse.Ok(statusLine, statusCode, respHeaders, respBody)
            }
        } catch (e: Exception) {
            val msg = e.message ?: "Connection failed to $host:$port"
            addLog(LogDirection.IN, "Socket Error ($host:$port)", msg)
            RawHttpResponse.Error(msg)
        }
    }

    private fun readAsciiLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        var b = input.read()
        if (b == -1) return null
        while (b != -1) {
            if (b == '\n'.code) break
            if (b != '\r'.code) buffer.write(b)
            b = input.read()
        }
        return buffer.toString(Charsets.UTF_8.name())
    }

    private fun readFixedBytes(input: InputStream, count: Int): ByteArray {
        val result = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(result, offset, count - offset)
            if (read == -1) break
            offset += read
        }
        return if (offset == count) result else result.copyOf(offset)
    }

    private fun addLog(direction: LogDirection, summary: String, details: String) {
        val entry = ProtocolLogEntry(
            timestamp = timeFormat.format(Date()),
            direction = direction,
            summary = summary,
            details = details
        )
        _protocolLogs.update { current ->
            (listOf(entry) + current).take(60)
        }
    }
}
