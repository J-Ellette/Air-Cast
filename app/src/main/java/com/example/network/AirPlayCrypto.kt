package com.example.network

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Cryptographic primitives for Apple AirPlay 2 / HomeKit (HAP) pairing:
 * - TLV8 tag-length-value encoder/decoder
 * - SRP-6a (RFC 5054 3072-bit Group 15, SHA-512)
 * - HKDF-SHA-512
 * - RFC 8439 ChaCha20-Poly1305 AEAD
 * - RFC 7748 X25519 ECDH
 * - RFC 8032 Ed25519 Signatures
 * - Minimal Apple Binary Plist (bplist00) encoder
 */
object AirPlayCrypto {

    private val random = SecureRandom()

    // ---- TLV8 Codec (Apple HAP) ----

    const val TLV_METHOD = 0x00
    const val TLV_IDENTIFIER = 0x01
    const val TLV_SALT = 0x02
    const val TLV_PUBLIC_KEY = 0x03
    const val TLV_PROOF = 0x04
    const val TLV_ENCRYPTED_DATA = 0x05
    const val TLV_STATE = 0x06
    const val TLV_ERROR = 0x07
    const val TLV_RETRY_DELAY = 0x08
    const val TLV_SIGNATURE = 0x0A
    const val TLV_FLAGS = 0x13

    fun encodeTlv8(vararg items: Pair<Int, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((tag, value) in items) {
            if (value.isEmpty()) {
                out.write(tag and 0xFF)
                out.write(0)
            } else {
                var offset = 0
                while (offset < value.size) {
                    val chunk = minOf(255, value.size - offset)
                    out.write(tag and 0xFF)
                    out.write(chunk)
                    out.write(value, offset, chunk)
                    offset += chunk
                }
            }
        }
        return out.toByteArray()
    }

    fun decodeTlv8(bytes: ByteArray): Map<Int, ByteArray> {
        val map = LinkedHashMap<Int, ByteArrayOutputStream>()
        var idx = 0
        while (idx + 1 < bytes.size) {
            val tag = bytes[idx].toInt() and 0xFF
            val len = bytes[idx + 1].toInt() and 0xFF
            idx += 2
            val available = minOf(len, bytes.size - idx)
            if (available > 0) {
                val stream = map.getOrPut(tag) { ByteArrayOutputStream() }
                stream.write(bytes, idx, available)
                idx += available
            } else {
                map.getOrPut(tag) { ByteArrayOutputStream() }
            }
        }
        return map.mapValues { it.value.toByteArray() }
    }

    fun hapErrorDescription(code: Int): String = when (code) {
        0x01 -> "Unknown pairing error"
        0x02 -> "Incorrect PIN — authentication failed"
        0x03 -> "Receiver backoff — too many attempts, wait a moment"
        0x04 -> "Max peers paired on receiver"
        0x05 -> "Max PIN attempts exceeded"
        0x06 -> "Receiver pairing unavailable / busy"
        0x07 -> "Receiver pairing timed out"
        else -> "Pairing error code 0x${code.toString(16)}"
    }

    // ---- Hashing & HKDF-SHA-512 ----

    fun sha512(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-512")
        for (p in parts) md.update(p)
        return md.digest()
    }

    fun hkdfSha512(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        val actualSalt = if (salt.isEmpty()) ByteArray(64) else salt
        mac.init(SecretKeySpec(actualSalt, "HmacSHA512"))
        val prk = mac.doFinal(ikm)

        val out = ByteArrayOutputStream()
        var t = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            mac.init(SecretKeySpec(prk, "HmacSHA512"))
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            out.write(t)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }

    // ---- SRP-6a (RFC 5054 3072-bit Group 15, SHA-512) ----

    private val SRP_N_HEX = (
        "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD1" +
            "29024E088A67CC74020BBEA63B139B22514A08798E3404DD" +
            "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245" +
            "E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
            "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3D" +
            "C2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F" +
            "83655D23DCA3AD961C62F356208552BB9ED529077096966D" +
            "670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
            "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9" +
            "DE2BCBF6955817183995497CEA956AE515D2261898FA0510" +
            "15728E5A8AAAC42DAD33170D04507A33A85521ABDF1CBA64" +
            "ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7" +
            "ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6B" +
            "F12FFA06D98A0864D87602733EC86A64521F2B18177B200C" +
            "BBE117577A615D6C770988C0BAD946E208E24FA074E5AB31" +
            "43DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF"
        )
    private val SRP_N = BigInteger(SRP_N_HEX, 16)
    private val SRP_G = BigInteger.valueOf(5L)
    private const val SRP_LEN = 384

    data class SrpSession(
        val publicKeyA: ByteArray,
        val proofM1: ByteArray,
        val expectedServerProofM2: ByteArray,
        val sessionKeyK: ByteArray
    )

    fun createSrpClientSession(
        pin: String,
        salt: ByteArray,
        serverPublicKeyB: ByteArray,
        username: String = "Pair-Setup"
    ): SrpSession {
        val nBytes = padTo(toUnsignedBytes(SRP_N), SRP_LEN)
        val gByte = byteArrayOf(5)
        val gPadded = padTo(gByte, SRP_LEN)

        val k = BigInteger(1, sha512(nBytes, gPadded))
        val bInt = BigInteger(1, serverPublicKeyB)

        val aBytes = ByteArray(32).also { random.nextBytes(it) }
        val aInt = BigInteger(1, aBytes)
        val aPubInt = SRP_G.modPow(aInt, SRP_N)
        val aPubBytes = toUnsignedBytes(aPubInt)

        val u = BigInteger(1, sha512(padTo(aPubBytes, SRP_LEN), padTo(serverPublicKeyB, SRP_LEN)))
        val innerHash = sha512("$username:$pin".toByteArray(Charsets.UTF_8))
        val x = BigInteger(1, sha512(salt, innerHash))

        val gx = SRP_G.modPow(x, SRP_N)
        val base = bInt.subtract(k.multiply(gx)).mod(SRP_N)
        val exp = aInt.add(u.multiply(x))
        val sInt = base.modPow(exp, SRP_N)
        val sBytes = toUnsignedBytes(sInt)
        val sessionK = sha512(sBytes)

        val hN = sha512(nBytes)
        val hG = sha512(gByte)
        val hXor = ByteArray(hN.size) { i -> (hN[i].toInt() xor hG[i].toInt()).toByte() }
        val hI = sha512(username.toByteArray(Charsets.UTF_8))

        val m1 = sha512(hXor, hI, salt, aPubBytes, serverPublicKeyB, sessionK)
        val m2 = sha512(aPubBytes, m1, sessionK)

        return SrpSession(
            publicKeyA = aPubBytes,
            proofM1 = m1,
            expectedServerProofM2 = m2,
            sessionKeyK = sessionK
        )
    }

    // ---- ChaCha20-Poly1305 (RFC 8439) ----

    fun chacha20Poly1305Encrypt(
        key: ByteArray,
        nonce12: ByteArray,
        aad: ByteArray,
        plaintext: ByteArray
    ): ByteArray {
        val otk = chacha20Block(key, 0, nonce12).copyOf(32)
        val ciphertext = chacha20Xor(key, 1, nonce12, plaintext)
        val tag = poly1305Tag(otk, aad, ciphertext)
        return ciphertext + tag
    }

    fun chacha20Poly1305Decrypt(
        key: ByteArray,
        nonce12: ByteArray,
        aad: ByteArray,
        ciphertextWithTag: ByteArray
    ): ByteArray? {
        if (ciphertextWithTag.size < 16) return null
        val ctLen = ciphertextWithTag.size - 16
        val ciphertext = ciphertextWithTag.copyOf(ctLen)
        val tag = ciphertextWithTag.copyOfRange(ctLen, ciphertextWithTag.size)
        val otk = chacha20Block(key, 0, nonce12).copyOf(32)
        val expectedTag = poly1305Tag(otk, aad, ciphertext)
        if (!constantTimeEquals(tag, expectedTag)) return null
        return chacha20Xor(key, 1, nonce12, ciphertext)
    }

    fun hapNonce(prefix4: ByteArray = ByteArray(4), label8: String): ByteArray {
        val l = label8.toByteArray(Charsets.US_ASCII)
        val out = ByteArray(12)
        System.arraycopy(prefix4, 0, out, 0, minOf(4, prefix4.size))
        System.arraycopy(l, 0, out, 4, minOf(8, l.size))
        return out
    }

    fun hapCounterNonce(counter: Long): ByteArray {
        val out = ByteArray(12)
        for (i in 0 until 8) {
            out[4 + i] = ((counter ushr (i * 8)) and 0xFF).toByte()
        }
        return out
    }

    private fun chacha20Xor(key: ByteArray, initialCounter: Int, nonce12: ByteArray, input: ByteArray): ByteArray {
        val out = ByteArray(input.size)
        var counter = initialCounter
        var offset = 0
        while (offset < input.size) {
            val block = chacha20Block(key, counter++, nonce12)
            val take = minOf(64, input.size - offset)
            for (i in 0 until take) {
                out[offset + i] = (input[offset + i].toInt() xor block[i].toInt()).toByte()
            }
            offset += take
        }
        return out
    }

    private fun chacha20Block(key: ByteArray, counter: Int, nonce12: ByteArray): ByteArray {
        val state = IntArray(16)
        state[0] = 0x61707865
        state[1] = 0x3320646e
        state[2] = 0x79622d32
        state[3] = 0x6b206574
        for (i in 0 until 8) state[4 + i] = leInt(key, i * 4)
        state[12] = counter
        state[13] = leInt(nonce12, 0)
        state[14] = leInt(nonce12, 4)
        state[15] = leInt(nonce12, 8)

        val working = state.copyOf()
        repeat(10) {
            quarterRound(working, 0, 4, 8, 12)
            quarterRound(working, 1, 5, 9, 13)
            quarterRound(working, 2, 6, 10, 14)
            quarterRound(working, 3, 7, 11, 15)
            quarterRound(working, 0, 5, 10, 15)
            quarterRound(working, 1, 6, 11, 12)
            quarterRound(working, 2, 7, 8, 13)
            quarterRound(working, 3, 4, 9, 14)
        }
        val out = ByteArray(64)
        for (i in 0 until 16) {
            val v = working[i] + state[i]
            out[i * 4] = (v and 0xFF).toByte()
            out[i * 4 + 1] = ((v ushr 8) and 0xFF).toByte()
            out[i * 4 + 2] = ((v ushr 16) and 0xFF).toByte()
            out[i * 4 + 3] = ((v ushr 24) and 0xFF).toByte()
        }
        return out
    }

    private fun quarterRound(s: IntArray, a: Int, b: Int, c: Int, d: Int) {
        s[a] += s[b]; s[d] = Integer.rotateLeft(s[d] xor s[a], 16)
        s[c] += s[d]; s[b] = Integer.rotateLeft(s[b] xor s[c], 12)
        s[a] += s[b]; s[d] = Integer.rotateLeft(s[d] xor s[a], 8)
        s[c] += s[d]; s[b] = Integer.rotateLeft(s[b] xor s[c], 7)
    }

    private fun poly1305Tag(otk32: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray {
        val rBytes = otk32.copyOfRange(0, 16).apply {
            this[3] = (this[3].toInt() and 15).toByte()
            this[7] = (this[7].toInt() and 15).toByte()
            this[11] = (this[11].toInt() and 15).toByte()
            this[15] = (this[15].toInt() and 15).toByte()
            this[4] = (this[4].toInt() and 252).toByte()
            this[8] = (this[8].toInt() and 252).toByte()
            this[12] = (this[12].toInt() and 252).toByte()
        }
        val r = BigInteger(1, rBytes.reversedArray())
        val s = BigInteger(1, otk32.copyOfRange(16, 32).reversedArray())
        val p = BigInteger.ONE.shiftLeft(130).subtract(BigInteger.valueOf(5))

        val macData = ByteArrayOutputStream().apply {
            write(aad)
            val aadPad = (16 - (aad.size % 16)) % 16
            repeat(aadPad) { write(0) }
            write(ciphertext)
            val ctPad = (16 - (ciphertext.size % 16)) % 16
            repeat(ctPad) { write(0) }
            write(leLong(aad.size.toLong()))
            write(leLong(ciphertext.size.toLong()))
        }.toByteArray()

        var acc = BigInteger.ZERO
        var offset = 0
        while (offset < macData.size) {
            val chunkLen = minOf(16, macData.size - offset)
            val blockLe = macData.copyOfRange(offset, offset + chunkLen)
            val withHighBit = ByteArray(chunkLen + 1)
            for (i in 0 until chunkLen) withHighBit[chunkLen - i] = blockLe[i]
            withHighBit[0] = 1
            val n = BigInteger(1, withHighBit)
            acc = acc.add(n).multiply(r).mod(p)
            offset += chunkLen
        }
        val tagInt = acc.add(s)
        val tagOut = ByteArray(16)
        val rawBe = tagInt.toByteArray().reversedArray()
        for (i in 0 until minOf(16, rawBe.size)) {
            tagOut[i] = rawBe[i]
        }
        return tagOut
    }

    // ---- X25519 (RFC 7748) ----

    private val P25519 = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))

    fun generateX25519KeyPair(): Pair<ByteArray, ByteArray> {
        val priv = ByteArray(32).also { random.nextBytes(it) }
        priv[0] = (priv[0].toInt() and 248).toByte()
        priv[31] = (priv[31].toInt() and 127).toByte()
        priv[31] = (priv[31].toInt() or 64).toByte()
        val base = ByteArray(32).apply { this[0] = 9 }
        val pub = x25519(priv, base)
        return priv to pub
    }

    fun x25519(scalar32: ByteArray, uBytes32: ByteArray): ByteArray {
        val kBytes = scalar32.copyOf(32)
        kBytes[0] = (kBytes[0].toInt() and 248).toByte()
        kBytes[31] = (kBytes[31].toInt() and 127).toByte()
        kBytes[31] = (kBytes[31].toInt() or 64).toByte()

        val uClamped = uBytes32.copyOf(32)
        uClamped[31] = (uClamped[31].toInt() and 127).toByte()
        val x1 = BigInteger(1, uClamped.reversedArray()).mod(P25519)
        val k = BigInteger(1, kBytes.reversedArray())
        val a24 = BigInteger.valueOf(121665L)

        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = x1
        var z3 = BigInteger.ONE
        var swap = 0

        for (t in 254 downTo 0) {
            val kt = if (k.testBit(t)) 1 else 0
            swap = swap xor kt
            if (swap != 0) {
                val tx = x2; x2 = x3; x3 = tx
                val tz = z2; z2 = z3; z3 = tz
            }
            swap = kt

            val a = x2.add(z2).mod(P25519)
            val aa = a.multiply(a).mod(P25519)
            val b = x2.subtract(z2).mod(P25519)
            val bb = b.multiply(b).mod(P25519)
            val e = aa.subtract(bb).mod(P25519)
            val c = x3.add(z3).mod(P25519)
            val d = x3.subtract(z3).mod(P25519)
            val da = d.multiply(a).mod(P25519)
            val cb = c.multiply(b).mod(P25519)
            x3 = da.add(cb).mod(P25519).let { it.multiply(it).mod(P25519) }
            z3 = x1.multiply(da.subtract(cb).mod(P25519).let { it.multiply(it).mod(P25519) }).mod(P25519)
            x2 = aa.multiply(bb).mod(P25519)
            z2 = e.multiply(aa.add(a24.multiply(e)).mod(P25519)).mod(P25519)
        }
        if (swap != 0) {
            val tx = x2; x2 = x3; x3 = tx
            val tz = z2; z2 = z3; z3 = tz
        }
        val res = x2.multiply(z2.modPow(P25519.subtract(BigInteger.TWO), P25519)).mod(P25519)
        val out = ByteArray(32)
        val le = res.toByteArray().reversedArray()
        for (i in 0 until minOf(32, le.size)) out[i] = le[i]
        return out
    }

    // ---- Ed25519 (RFC 8032) ----

    private val ED_D = BigInteger("-4513249062541557337682894930092624173785641285191125241628941591882900924598840740")
        .mod(P25519)
    private val ED_L = BigInteger.ONE.shiftLeft(252)
        .add(BigInteger("27742317777372353535851937790883648493"))
    private val ED_BY = BigInteger.valueOf(4)
        .multiply(BigInteger.valueOf(5).modInverse(P25519)).mod(P25519)
    private val ED_BX = BigInteger("15112221349535400772501151409588531511454012693041857206046113283949847762202")
    private val ED_BASE = EdPoint(
        ED_BX,
        ED_BY,
        BigInteger.ONE,
        ED_BX.multiply(ED_BY).mod(P25519)
    )

    private data class EdPoint(
        val x: BigInteger,
        val y: BigInteger,
        val z: BigInteger,
        val t: BigInteger
    )

    data class Ed25519KeyPair(
        val seed32: ByteArray,
        val publicKey32: ByteArray
    )

    fun generateEd25519KeyPair(seed32: ByteArray = ByteArray(32).also { random.nextBytes(it) }): Ed25519KeyPair {
        val h = sha512(seed32)
        val a = clampEdScalar(h.copyOf(32))
        val pubPoint = edScalarMult(ED_BASE, a)
        return Ed25519KeyPair(seed32.copyOf(32), edEncodePoint(pubPoint))
    }

    fun ed25519Sign(keyPair: Ed25519KeyPair, message: ByteArray): ByteArray {
        val h = sha512(keyPair.seed32)
        val a = clampEdScalar(h.copyOf(32))
        val prefix = h.copyOfRange(32, 64)
        val rDigest = sha512(prefix, message)
        val r = BigInteger(1, rDigest.reversedArray()).mod(ED_L)
        val rEncoded = edEncodePoint(edScalarMult(ED_BASE, r))
        val kDigest = sha512(rEncoded, keyPair.publicKey32, message)
        val k = BigInteger(1, kDigest.reversedArray()).mod(ED_L)
        val s = r.add(k.multiply(a)).mod(ED_L)
        val sEncoded = ByteArray(32)
        val sLe = s.toByteArray().reversedArray()
        for (i in 0 until minOf(32, sLe.size)) sEncoded[i] = sLe[i]
        return rEncoded + sEncoded
    }

    private fun clampEdScalar(raw32: ByteArray): BigInteger {
        raw32[0] = (raw32[0].toInt() and 248).toByte()
        raw32[31] = (raw32[31].toInt() and 63).toByte()
        raw32[31] = (raw32[31].toInt() or 64).toByte()
        return BigInteger(1, raw32.reversedArray())
    }

    private fun edAdd(p: EdPoint, q: EdPoint): EdPoint {
        val a = p.y.subtract(p.x).multiply(q.y.subtract(q.x)).mod(P25519)
        val b = p.y.add(p.x).multiply(q.y.add(q.x)).mod(P25519)
        val c = BigInteger.TWO.multiply(p.t).multiply(q.t).multiply(ED_D).mod(P25519)
        val d = BigInteger.TWO.multiply(p.z).multiply(q.z).mod(P25519)
        val e = b.subtract(a).mod(P25519)
        val f = d.subtract(c).mod(P25519)
        val g = d.add(c).mod(P25519)
        val h = b.add(a).mod(P25519)
        return EdPoint(
            x = e.multiply(f).mod(P25519),
            y = g.multiply(h).mod(P25519),
            z = f.multiply(g).mod(P25519),
            t = e.multiply(h).mod(P25519)
        )
    }

    private fun edScalarMult(point: EdPoint, scalar: BigInteger): EdPoint {
        var r = EdPoint(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)
        var q = point
        var k = scalar
        while (k > BigInteger.ZERO) {
            if (k.testBit(0)) r = edAdd(r, q)
            q = edAdd(q, q)
            k = k.shiftRight(1)
        }
        return r
    }

    private fun edEncodePoint(p: EdPoint): ByteArray {
        val zInv = p.z.modInverse(P25519)
        val x = p.x.multiply(zInv).mod(P25519)
        val y = p.y.multiply(zInv).mod(P25519)
        val out = ByteArray(32)
        val yLe = y.toByteArray().reversedArray()
        for (i in 0 until minOf(32, yLe.size)) out[i] = yLe[i]
        if (x.testBit(0)) {
            out[31] = (out[31].toInt() or 0x80).toByte()
        }
        return out
    }

    // ---- Minimal Binary Plist (bplist00) Encoder ----

    fun encodeStringDictBinaryPlist(entries: Map<String, String>): ByteArray {
        val body = ByteArrayOutputStream()
        body.write("bplist00".toByteArray(Charsets.US_ASCII))
        val offsets = mutableListOf<Int>()

        // Object 0: dict with N entries
        val count = entries.size
        val dictStart = body.size()
        offsets.add(dictStart)
        if (count < 15) {
            body.write(0xD0 or count)
        } else {
            body.write(0xDF)
            body.write(0x10)
            body.write(count)
        }
        // key refs: 1..count, val refs: count+1..2*count
        for (i in 1..count) body.write(i)
        for (i in (count + 1)..(2 * count)) body.write(i)

        // Keys
        for (key in entries.keys) {
            offsets.add(body.size())
            writePlistAsciiString(body, key)
        }
        // Values
        for (value in entries.values) {
            offsets.add(body.size())
            writePlistAsciiString(body, value)
        }

        val offsetTableOffset = body.size()
        for (off in offsets) {
            body.write((off ushr 8) and 0xFF)
            body.write(off and 0xFF)
        }

        // Trailer (32 bytes)
        repeat(6) { body.write(0) } // unused
        body.write(2) // offsetIntSize = 2 bytes
        body.write(1) // objectRefSize = 1 byte
        writeLongBe(body, offsets.size.toLong())
        writeLongBe(body, 0L) // topObject = 0
        writeLongBe(body, offsetTableOffset.toLong())
        return body.toByteArray()
    }

    fun encodePlayBinaryPlist(contentLocation: String, startPosition: Double): ByteArray {
        val body = ByteArrayOutputStream()
        body.write("bplist00".toByteArray(Charsets.US_ASCII))
        val offsets = mutableListOf<Int>()

        // Object 0: dict with 2 entries ("Content-Location", "Start-Position")
        offsets.add(body.size())
        body.write(0xD2)
        body.write(1) // key 1
        body.write(2) // key 2
        body.write(3) // val 1
        body.write(4) // val 2

        // Object 1: "Content-Location"
        offsets.add(body.size())
        writePlistAsciiString(body, "Content-Location")

        // Object 2: "Start-Position"
        offsets.add(body.size())
        writePlistAsciiString(body, "Start-Position")

        // Object 3: contentLocation string
        offsets.add(body.size())
        writePlistAsciiString(body, contentLocation)

        // Object 4: real 64-bit float (0x23)
        offsets.add(body.size())
        body.write(0x23)
        writeLongBe(body, java.lang.Double.doubleToRawLongBits(startPosition))

        val offsetTableOffset = body.size()
        for (off in offsets) {
            body.write((off ushr 8) and 0xFF)
            body.write(off and 0xFF)
        }

        // Trailer (32 bytes)
        repeat(6) { body.write(0) }
        body.write(2) // offsetIntSize = 2 bytes
        body.write(1) // objectRefSize = 1 byte
        writeLongBe(body, offsets.size.toLong())
        writeLongBe(body, 0L)
        writeLongBe(body, offsetTableOffset.toLong())
        return body.toByteArray()
    }

    private fun writePlistAsciiString(out: ByteArrayOutputStream, s: String) {
        val bytes = s.toByteArray(Charsets.US_ASCII)
        when {
            bytes.size < 15 -> out.write(0x50 or bytes.size)
            bytes.size < 256 -> {
                out.write(0x5F)
                out.write(0x10)
                out.write(bytes.size)
            }
            else -> {
                out.write(0x5F)
                out.write(0x11)
                out.write((bytes.size ushr 8) and 0xFF)
                out.write(bytes.size and 0xFF)
            }
        }
        out.write(bytes)
    }

    private fun writeLongBe(out: ByteArrayOutputStream, v: Long) {
        for (shift in 56 downTo 0 step 8) {
            out.write(((v ushr shift) and 0xFF).toInt())
        }
    }

    // ---- Helpers ----

    private fun toUnsignedBytes(n: BigInteger): ByteArray {
        val raw = n.toByteArray()
        return if (raw.size > 1 && raw[0] == 0.toByte()) {
            raw.copyOfRange(1, raw.size)
        } else {
            raw
        }
    }

    private fun padTo(bytes: ByteArray, targetLen: Int): ByteArray {
        if (bytes.size >= targetLen) return bytes
        val padded = ByteArray(targetLen)
        System.arraycopy(bytes, 0, padded, targetLen - bytes.size, bytes.size)
        return padded
    }

    private fun leInt(b: ByteArray, offset: Int): Int =
        (b[offset].toInt() and 0xFF) or
            ((b[offset + 1].toInt() and 0xFF) shl 8) or
            ((b[offset + 2].toInt() and 0xFF) shl 16) or
            ((b[offset + 3].toInt() and 0xFF) shl 24)

    private fun leLong(v: Long): ByteArray = ByteArray(8) { i -> ((v ushr (i * 8)) and 0xFF).toByte() }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    // ---- HTTP / RTSP Digest Auth (for Password-Protected AirPlay Speakers) ----

    fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun buildDigestAuthHeader(
        wwwAuthenticateHeader: String,
        username: String,
        password: String,
        method: String,
        uri: String
    ): String? {
        val realm = Regex("""realm="([^"]*)"""").find(wwwAuthenticateHeader)?.groupValues?.getOrNull(1)
            ?: return null
        val nonce = Regex("""nonce="([^"]*)"""").find(wwwAuthenticateHeader)?.groupValues?.getOrNull(1)
            ?: return null
        val qop = Regex("""qop="([^"]*)"""").find(wwwAuthenticateHeader)?.groupValues?.getOrNull(1)

        val ha1 = md5Hex("$username:$realm:$password")
        val ha2 = md5Hex("$method:$uri")

        return if (qop != null && qop.contains("auth")) {
            val cnonce = ByteArray(8).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
            val nc = "00000001"
            val response = md5Hex("$ha1:$nonce:$nc:$cnonce:auth:$ha2")
            """Digest username="$username", realm="$realm", nonce="$nonce", uri="$uri", qop=auth, nc=$nc, cnonce="$cnonce", response="$response""""
        } else {
            val response = md5Hex("$ha1:$nonce:$ha2")
            """Digest username="$username", realm="$realm", nonce="$nonce", uri="$uri", response="$response""""
        }
    }
}
