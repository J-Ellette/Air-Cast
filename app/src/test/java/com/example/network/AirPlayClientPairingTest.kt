package com.example.network

import com.example.model.AirPlayProtocolMode
import com.example.model.AirPlayReceiver
import com.example.model.ReceiverType
import com.example.model.SpoofProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AirPlayClientPairingTest {

    @Test
    fun `airplay 2 pairing does not accept a legacy HTTP success as PIN verification`() {
        val server = ServerSocket(0)
        val requests = mutableListOf<String>()
        val serverThread = thread(isDaemon = true) {
            server.accept().use { socket ->
                val input = BufferedInputStream(socket.getInputStream())
                val output = socket.getOutputStream()
                while (true) {
                    val requestLine = readHttpRequestLine(input) ?: break
                    requests.add(requestLine)
                    val body = "not-a-HAP-response".toByteArray(Charsets.UTF_8)
                    output.write(
                        (
                            "HTTP/1.1 200 OK\r\n" +
                                "Content-Length: ${body.size}\r\n" +
                                "Connection: keep-alive\r\n\r\n"
                            ).toByteArray(Charsets.US_ASCII)
                    )
                    output.write(body)
                    output.flush()
                }
            }
        }

        val receiver = AirPlayReceiver(
            id = "test",
            name = "Test AirPlay 2 receiver",
            host = "127.0.0.1",
            port = server.localPort,
            type = ReceiverType.SMART_TV
        )
        val client = AirPlayClient()
        val result = try {
            runBlocking {
                client.submitPairingPin(
                    receiver = receiver,
                    profile = SpoofProfile(protocolMode = AirPlayProtocolMode.AIRPLAY_2),
                    pin = "1234"
                )
            }
        } finally {
            client.disconnectSession(receiver.host, receiver.port)
            server.close()
            serverThread.join(5_000)
        }

        assertTrue(result is AirPlayResult.Failure)
        assertEquals(listOf("POST /pair-setup HTTP/1.1"), requests)
        assertTrue(!serverThread.isAlive)
    }

    private fun readHttpRequestLine(input: BufferedInputStream): String? {
        fun readLine(): String? {
            val line = ByteArrayOutputStream()
            while (true) {
                val next = input.read()
                if (next == -1) {
                    return if (line.size() == 0) null else line.toString(Charsets.US_ASCII.name())
                }
                if (next == '\n'.code) break
                if (next != '\r'.code) line.write(next)
            }
            return line.toString(Charsets.US_ASCII.name())
        }

        val requestLine = readLine() ?: return null
        var contentLength = 0
        while (true) {
            val header = readLine() ?: return requestLine
            if (header.isEmpty()) break
            if (header.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = header.substringAfter(':').trim().toIntOrNull() ?: 0
            }
        }
        repeat(contentLength) {
            if (input.read() == -1) return requestLine
        }
        return requestLine
    }
}
