package com.example.network

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

class LocalMediaHttpServer(
    private val contentResolver: ContentResolver
) {
    data class RegisteredMedia(
        val token: String,
        val uri: Uri,
        val mimeType: String,
        val sizeBytes: Long,
        val fileName: String
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    private val mediaRegistry = ConcurrentHashMap<String, RegisteredMedia>()
    private val photoRegistry = ConcurrentHashMap<String, ByteArray>()
    private val latestMirrorJpeg = AtomicReference<ByteArray?>(null)

    @Volatile
    var port: Int = 0
        private set

    fun start(): Int {
        if (serverSocket?.isClosed == false && port > 0) return port
        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(0))
        }
        serverSocket = socket
        port = socket.localPort

        acceptJob = scope.launch {
            while (isActive && !socket.isClosed) {
                try {
                    val client = socket.accept()
                    launch { handleClient(client) }
                } catch (_: Exception) {
                    break
                }
            }
        }
        return port
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        port = 0
    }

    fun registerMediaUri(uri: Uri, fallbackMime: String): RegisteredMedia {
        val token = UUID.randomUUID().toString().replace("-", "")
        var size = -1L
        var name = "media"
        runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
                    if (nameIdx >= 0 && !cursor.isNull(nameIdx)) name = cursor.getString(nameIdx)
                }
            }
        }
        if (size <= 0L) {
            runCatching {
                contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                    if (afd.length > 0) size = afd.length
                }
            }
        }
        if (size <= 0L) {
            runCatching {
                contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    if (pfd.statSize > 0) size = pfd.statSize
                }
            }
        }
        if (size <= 0L) {
            runCatching {
                contentResolver.openInputStream(uri)?.use { stream ->
                    var counted = 0L
                    val buf = ByteArray(16_384)
                    while (true) {
                        val r = stream.read(buf)
                        if (r == -1) break
                        counted += r
                    }
                    if (counted > 0L) size = counted
                }
            }
        }
        val resolvedMime = contentResolver.getType(uri) ?: fallbackMime
        val entry = RegisteredMedia(
            token = token,
            uri = uri,
            mimeType = resolvedMime,
            sizeBytes = size,
            fileName = name
        )
        mediaRegistry[token] = entry
        return entry
    }

    fun registerPhotoBytes(bytes: ByteArray): String {
        val token = UUID.randomUUID().toString().replace("-", "")
        photoRegistry[token] = bytes
        return token
    }

    fun updateMirrorFrame(jpegBytes: ByteArray) {
        latestMirrorJpeg.set(jpegBytes)
    }

    fun buildMediaUrl(hostIp: String, token: String): String = "http://$hostIp:$port/media/$token"
    fun buildPhotoUrl(hostIp: String, token: String): String = "http://$hostIp:$port/photo/$token"
    fun buildMirrorMjpegUrl(hostIp: String): String = "http://$hostIp:$port/mirror.mjpeg"
    fun buildMirrorHtmlUrl(hostIp: String): String = "http://$hostIp:$port/mirror.html"

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 15_000
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val path = parts[1].substringBefore("?")

            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    headers[line.substring(0, idx).trim().lowercase()] =
                        line.substring(idx + 1).trim()
                }
            }

            val out = BufferedOutputStream(client.getOutputStream())
            when {
                path.startsWith("/media/") -> {
                    val token = path.removePrefix("/media/")
                    serveMediaFile(token, method, headers["range"], out)
                }
                path.startsWith("/photo/") -> {
                    val token = path.removePrefix("/photo/")
                    servePhoto(token, out)
                }
                path == "/mirror.jpg" -> {
                    val frame = latestMirrorJpeg.get()
                    if (frame != null) {
                        val header = buildString {
                            append("HTTP/1.1 200 OK\r\n")
                            append("Content-Type: image/jpeg\r\n")
                            append("Content-Length: ${frame.size}\r\n")
                            append("Cache-Control: no-store\r\n")
                            append("Connection: close\r\n\r\n")
                        }
                        out.write(header.toByteArray())
                        out.write(frame)
                        out.flush()
                    } else {
                        writeNotFound(out)
                    }
                }
                path == "/mirror.mjpeg" -> serveMirrorMjpegStream(out)
                path == "/mirror.html" -> serveMirrorHtml(out)
                else -> writeNotFound(out)
            }
        }
    }

    private fun serveMediaFile(
        token: String,
        method: String,
        rangeHeader: String?,
        out: BufferedOutputStream
    ) {
        val item = mediaRegistry[token] ?: run {
            writeNotFound(out)
            return
        }
        val totalSize = item.sizeBytes
        if (totalSize > 0 && rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val rangeSpec = rangeHeader.removePrefix("bytes=").trim()
            val bounds = rangeSpec.split("-")
            val start = bounds.getOrNull(0)?.toLongOrNull() ?: 0L
            val end = bounds.getOrNull(1)?.toLongOrNull()?.coerceAtMost(totalSize - 1) ?: (totalSize - 1)
            if (start in 0..end) {
                val contentLength = (end - start) + 1
                val header = buildString {
                    append("HTTP/1.1 206 Partial Content\r\n")
                    append("Content-Type: ${item.mimeType}\r\n")
                    append("Accept-Ranges: bytes\r\n")
                    append("Content-Range: bytes $start-$end/$totalSize\r\n")
                    append("Content-Length: $contentLength\r\n")
                    append("Connection: close\r\n\r\n")
                }
                runCatching {
                    out.write(header.toByteArray())
                    if (method != "HEAD") {
                        streamRange(item.uri, start, contentLength, out)
                    }
                    out.flush()
                }
                return
            }
        }

        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: ${item.mimeType}\r\n")
            append("Accept-Ranges: bytes\r\n")
            if (totalSize > 0) append("Content-Length: $totalSize\r\n")
            append("Connection: close\r\n\r\n")
        }
        runCatching {
            out.write(header.toByteArray())
            if (method != "HEAD") {
                streamRange(item.uri, 0L, if (totalSize > 0) totalSize else Long.MAX_VALUE, out)
            }
            out.flush()
        }
    }

    private fun streamRange(uri: Uri, offset: Long, length: Long, out: BufferedOutputStream) {
        contentResolver.openInputStream(uri)?.use { raw ->
            val input = BufferedInputStream(raw, 65_536)
            var toSkip = offset
            while (toSkip > 0) {
                val skipped = input.skip(toSkip)
                if (skipped <= 0) {
                    if (input.read() == -1) return
                    toSkip--
                } else {
                    toSkip -= skipped
                }
            }
            val buffer = ByteArray(65_536)
            var remaining = length
            while (remaining > 0) {
                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                val read = input.read(buffer, 0, toRead)
                if (read == -1) break
                out.write(buffer, 0, read)
                remaining -= read
            }
        }
    }

    private fun servePhoto(token: String, out: BufferedOutputStream) {
        val bytes = photoRegistry[token] ?: run {
            writeNotFound(out)
            return
        }
        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: image/jpeg\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun serveMirrorMjpegStream(out: BufferedOutputStream) {
        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: multipart/x-mixed-replace; boundary=aircastframe\r\n")
            append("Cache-Control: no-cache, no-store, must-revalidate\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray())
        out.flush()

        var lastSentRef: ByteArray? = null
        while (serverSocket?.isClosed == false) {
            val frame = latestMirrorJpeg.get()
            if (frame != null && frame !== lastSentRef) {
                lastSentRef = frame
                val partHeader = buildString {
                    append("--aircastframe\r\n")
                    append("Content-Type: image/jpeg\r\n")
                    append("Content-Length: ${frame.size}\r\n\r\n")
                }
                out.write(partHeader.toByteArray())
                out.write(frame)
                out.write("\r\n".toByteArray())
                out.flush()
            }
            Thread.sleep(33)
        }
    }

    private fun serveMirrorHtml(out: BufferedOutputStream) {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="utf-8"/>
              <title>AirCast Bridge Mirror</title>
              <style>
                html, body { margin:0; padding:0; width:100%; height:100%; background:#000; display:flex; align-items:center; justify-content:center; overflow:hidden; }
                img { max-width:100%; max-height:100%; object-fit:contain; }
              </style>
            </head>
            <body>
              <img src="/mirror.mjpeg" alt="Mirror Stream"/>
            </body>
            </html>
        """.trimIndent().toByteArray()
        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Content-Length: ${html.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray())
        out.write(html)
        out.flush()
    }

    private fun writeNotFound(out: BufferedOutputStream) {
        val msg = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        out.write(msg.toByteArray())
        out.flush()
    }
}
