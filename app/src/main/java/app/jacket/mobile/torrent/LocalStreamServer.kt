package app.jacket.mobile.torrent

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Loopback-only HTTP byte-range server for in-progress torrents. It never binds a LAN interface.
 * Every requested chunk is gated by libtorrent piece availability before reading the file.
 */
class LocalStreamServer(
    private val metadata: (String, Int) -> TorrentStreamTarget,
    private val awaitRange: (String, Int, Long, Long) -> File,
    cacheDirectory: File = File(System.getProperty("java.io.tmpdir") ?: ".", "jacket-stream-cache")
) : Closeable {
    private val streamCache = StreamDiskCache(cacheDirectory)
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var acceptThread: Thread? = null
    @Volatile private var workers: ExecutorService? = null
    @Volatile var port: Int = 0
        private set
    private val accessToken = newAccessToken()
    private val clients = ConcurrentHashMap.newKeySet<Socket>()

    val isRunning: Boolean get() = serverSocket?.isClosed == false

    @Synchronized
    fun start(): Int {
        if (isRunning) return port
        var bound: ServerSocket? = null
        var lastError: Exception? = null
        for (candidate in FIRST_PORT..LAST_PORT) {
            try {
                bound = ServerSocket(candidate, 32, InetAddress.getByName("127.0.0.1")).apply {
                    reuseAddress = true
                    soTimeout = 1000
                }
                port = bound!!.localPort
                break
            } catch (error: Exception) {
                lastError = error
            }
        }
        serverSocket = bound ?: throw IllegalStateException(
            "Не удалось запустить локальный видеосервер на портах $FIRST_PORT–$LAST_PORT",
            lastError
        )
        workers = ThreadPoolExecutor(
            MAX_CLIENTS,
            MAX_CLIENTS,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(MAX_QUEUED_CLIENTS)
        ) { runnable ->
            Thread(runnable, "JacketStreamClient").apply { isDaemon = true }
        }
        acceptThread = Thread({ acceptLoop() }, "JacketStreamAccept").apply {
            isDaemon = true
            start()
        }
        return port
    }

    fun url(infoHash: String, fileIndex: Int): String {
        require(infoHash.matches(Regex("(?i)([a-f0-9]{40}|[a-f0-9]{64})")))
        require(fileIndex >= 0)
        check(isRunning) { "Локальный видеосервер не запущен" }
        return "http://127.0.0.1:$port/stream/$accessToken/${infoHash.lowercase()}/$fileIndex"
    }

    private fun acceptLoop() {
        while (isRunning) {
            try {
                val socket = serverSocket?.accept() ?: break
                socket.soTimeout = CLIENT_IDLE_TIMEOUT_MS
                clients.add(socket)
                try {
                    workers?.execute {
                        try {
                            handle(socket)
                        } finally {
                            clients.remove(socket)
                        }
                    } ?: run {
                        clients.remove(socket)
                        socket.close()
                    }
                } catch (_: java.util.concurrent.RejectedExecutionException) {
                    clients.remove(socket)
                    socket.close()
                }
            } catch (_: java.net.SocketTimeoutException) {
                // Periodically re-check isRunning.
            } catch (_: Exception) {
                if (isRunning) continue else break
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            try {
                val input = BufferedInputStream(client.getInputStream())
                val requestLine = try {
                    readBoundedLine(input, MAX_REQUEST_LINE_BYTES)
                } catch (_: RequestTooLargeException) {
                    return sendError(client.getOutputStream(), 431, "Request Header Fields Too Large")
                } ?: return
                val request = requestLine.split(' ')
                if (request.size != 3 || request[1].isBlank() ||
                    request[2] !in setOf("HTTP/1.0", "HTTP/1.1")
                ) {
                    return sendError(client.getOutputStream(), 400, "Bad Request")
                }
                val method = request[0].uppercase()
                if (method != "GET" && method != "HEAD" && method != "OPTIONS") {
                    return sendError(client.getOutputStream(), 405, "Method Not Allowed", "Allow: GET, HEAD, OPTIONS\r\n")
                }

                val headers = LinkedHashMap<String, String>()
                var totalHeaderBytes = requestLine.length + 2
                while (true) {
                    val line = try {
                        readBoundedLine(input, MAX_HEADER_LINE_BYTES)
                    } catch (_: RequestTooLargeException) {
                        return sendError(client.getOutputStream(), 431, "Request Header Fields Too Large")
                    } ?: return sendError(client.getOutputStream(), 400, "Bad Request")
                    totalHeaderBytes += line.length + 2
                    if (totalHeaderBytes > MAX_HEADER_BYTES) {
                        return sendError(client.getOutputStream(), 431, "Request Header Fields Too Large")
                    }
                    if (line.isEmpty()) break
                    val separator = line.indexOf(':')
                    if (separator <= 0) return sendError(client.getOutputStream(), 400, "Bad Request")
                    val name = line.substring(0, separator).trim()
                    val value = line.substring(separator + 1).trim()
                    if (name.isEmpty() || !name.all { it.isLetterOrDigit() || it == '-' } ||
                        value.any { it.code < 0x20 && it != '\t' }
                    ) {
                        return sendError(client.getOutputStream(), 400, "Bad Request")
                    }
                    headers[name.lowercase()] = value
                }

                if (!validHost(headers["host"])) return sendError(client.getOutputStream(), 403, "Forbidden")
                val route = LocalStreamRouteParser.parse(request[1])
                    ?: return sendError(client.getOutputStream(), 404, "Not Found")
                if (!validAccessToken(route.token)) return sendError(client.getOutputStream(), 403, "Forbidden")
                if (method == "OPTIONS") {
                    val corsHeaders = LocalStreamCorsPolicy.responseHeaders(headers["origin"])
                    if (corsHeaders.isEmpty()) return sendError(client.getOutputStream(), 403, "Forbidden")
                    writeHeaders(
                        client.getOutputStream(),
                        204,
                        "No Content",
                        corsHeaders + listOf("Content-Length: 0", "Connection: close")
                    )
                    return
                }
                val fileIndex = route.fileIndex
                val id = route.infoHash
                val target = try {
                    metadata(id, fileIndex)
                } catch (error: Exception) {
                    val notFound = error.message?.contains("не найден", true) == true
                    return sendError(
                        client.getOutputStream(),
                        if (notFound) 404 else 503,
                        if (notFound) "Not Found" else "Stream unavailable"
                    )
                }
                if (target.sizeBytes <= 0L) return sendError(client.getOutputStream(), 416, "Range Not Satisfiable")

                val range = try {
                    RangeParser.parse(headers["range"], target.sizeBytes)
                } catch (_: IllegalArgumentException) {
                    val out = client.getOutputStream()
                    writeHeaders(out, 416, "Range Not Satisfiable", listOf(
                        "Content-Range: bytes */${target.sizeBytes}",
                        "Accept-Ranges: bytes",
                        "Content-Length: 0"
                    ) + LocalStreamCorsPolicy.responseHeaders(headers["origin"]))
                    return
                }
                val response = RangeResponseBuilder.build(range, target.sizeBytes, contentType(target.name))
                val start = response.start
                val end = response.endInclusive
                val out = client.getOutputStream()
                if (method == "HEAD") {
                    writeHeaders(
                        out,
                        response.statusCode,
                        response.reason,
                        response.headers + LocalStreamCorsPolicy.responseHeaders(headers["origin"])
                    )
                    return
                }

                // Prime the first bytes before committing a 200/206 response.
                // If the swarm cannot provide the requested piece, the player
                // receives a retryable HTTP error instead of a truncated success.
                val firstCount = minOf(STREAM_CHUNK_BYTES, end - start + 1L).toInt()
                val firstBytes = try {
                    readChunk(id, fileIndex, start, firstCount)
                } catch (_: Exception) {
                    return sendError(
                        out,
                        503,
                        "Service Unavailable",
                        "Retry-After: 2\r\n"
                    )
                }

                writeHeaders(
                    out,
                    response.statusCode,
                    response.reason,
                    response.headers + LocalStreamCorsPolicy.responseHeaders(headers["origin"])
                )
                out.write(firstBytes)
                out.flush()

                var offset = start + firstBytes.size
                while (offset <= end) {
                    val count = minOf(STREAM_CHUNK_BYTES, end - offset + 1L).toInt()
                    if (count <= 0) throw java.io.IOException("Пустой диапазон видеопотока")
                    val bytes = readChunk(id, fileIndex, offset, count)
                    out.write(bytes)
                    out.flush()
                    offset += bytes.size
                }
            } catch (_: Exception) {
                // The player will reconnect on a failed range; do not expose internal file paths.
            }
        }
    }

    /** Loads a bounded byte segment, treating cache failures as cache misses. */
    private fun readChunk(id: String, fileIndex: Int, offset: Long, count: Int): ByteArray {
        require(count > 0) { "Пустой диапазон видеопотока" }
        val key = StreamCacheKey(id, fileIndex, offset)
        val cached = runCatching { streamCache.get(key, count) }.getOrNull()
        return cached?.bytes ?: run {
            val file = awaitRange(id, fileIndex, offset, count.toLong())
            ByteArray(count).also { buffer ->
                java.io.RandomAccessFile(file, "r").use { input ->
                    input.seek(offset)
                    input.readFully(buffer)
                }
                // The verified torrent file remains authoritative. A full or
                // failed cache must never prevent playback.
                runCatching { streamCache.put(key, buffer) }
            }
        }
    }

    /**
     * Reads one HTTP line without allowing BufferedReader.readLine() to allocate an
     * attacker-controlled amount of memory before the header-size check runs.
     */
    private fun readBoundedLine(input: InputStream, maxBytes: Int): String? {
        val bytes = ByteArrayOutputStream(minOf(maxBytes, 256))
        var received = false
        while (true) {
            val next = input.read()
            if (next < 0) {
                if (!received) return null
                break
            }
            received = true
            if (next == '\n'.code) break
            if (bytes.size() >= maxBytes) throw RequestTooLargeException()
            bytes.write(next)
        }
        val raw = bytes.toByteArray()
        val length = if (raw.lastOrNull() == '\r'.code.toByte()) raw.size - 1 else raw.size
        return String(raw, 0, length, StandardCharsets.US_ASCII)
    }

    private class RequestTooLargeException : IOException()

    private fun validHost(value: String?): Boolean = LocalStreamHostPolicy.accepts(value, port)

    private fun validAccessToken(candidate: String): Boolean =
        MessageDigest.isEqual(
            accessToken.toByteArray(StandardCharsets.US_ASCII),
            candidate.toByteArray(StandardCharsets.US_ASCII)
        )

    private fun contentType(filename: String): String = when (filename.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "avi" -> "video/x-msvideo"
        "mpeg", "mpg" -> "video/mpeg"
        "ts" -> "video/mp2t"
        "m3u8" -> "application/vnd.apple.mpegurl"
        "mp3" -> "audio/mpeg"
        "aac" -> "audio/aac"
        "flac" -> "audio/flac"
        "srt" -> "application/x-subrip"
        "vtt" -> "text/vtt"
        else -> "application/octet-stream"
    }

    private fun writeHeaders(out: OutputStream, code: Int, reason: String, headers: List<String>) {
        val text = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            headers.forEach { append(it).append("\r\n") }
            append("\r\n")
        }
        out.write(text.toByteArray(StandardCharsets.US_ASCII))
        out.flush()
    }

    private fun sendError(socket: OutputStream, code: Int, reason: String, extra: String = "") {
        val body = reason.toByteArray(StandardCharsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Connection: close\r\n")
            append("Content-Type: text/plain; charset=utf-8\r\n")
            append("Content-Length: ${body.size}\r\n")
            append(extra)
            append("\r\n")
        }
        socket.write(headers.toByteArray(StandardCharsets.US_ASCII))
        socket.write(body)
        socket.flush()
    }

    @Synchronized
    override fun close() {
        try { serverSocket?.close() } catch (_: Exception) { }
        serverSocket = null
        clients.forEach { client -> runCatching { client.close() } }
        clients.clear()
        try { workers?.shutdownNow() } catch (_: Exception) { }
        workers = null
        runCatching { streamCache.close() }
        port = 0
        acceptThread = null
    }

    companion object {
        private fun newAccessToken(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        private const val FIRST_PORT = 8090
        private const val LAST_PORT = 8099
        private const val MAX_CLIENTS = 4
        private const val MAX_QUEUED_CLIENTS = 8
        private const val MAX_REQUEST_LINE_BYTES = 4 * 1024
        private const val MAX_HEADER_LINE_BYTES = 8 * 1024
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val CLIENT_IDLE_TIMEOUT_MS = 35_000
        private const val STREAM_CHUNK_BYTES = StreamDiskCache.DEFAULT_SEGMENT_BYTES.toLong()
    }
}
