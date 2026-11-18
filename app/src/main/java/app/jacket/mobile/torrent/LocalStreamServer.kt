package app.jacket.mobile.torrent

import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Loopback-only HTTP byte-range server for in-progress torrents. It never binds a LAN interface.
 * Every requested chunk is gated by libtorrent piece availability before reading the file.
 */
class LocalStreamServer(
    private val metadata: (String, Int) -> TorrentStreamTarget,
    private val awaitRange: (String, Int, Long, Long) -> File
) : Closeable {
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var acceptThread: Thread? = null
    @Volatile private var workers: ExecutorService? = null
    @Volatile var port: Int = 0
        private set
    private val accessToken = newAccessToken()

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
        workers = Executors.newFixedThreadPool(MAX_CLIENTS) { runnable ->
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
                workers?.execute { handle(socket) } ?: socket.close()
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
                val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII))
                val requestLine = reader.readLine() ?: return
                val request = requestLine.split(' ')
                if (request.size < 2) return sendError(client.getOutputStream(), 400, "Bad Request")
                val method = request[0].uppercase()
                if (method != "GET" && method != "HEAD") {
                    return sendError(client.getOutputStream(), 405, "Method Not Allowed", "Allow: GET, HEAD\r\n")
                }

                val headers = LinkedHashMap<String, String>()
                var totalHeaderBytes = requestLine.length
                while (true) {
                    val line = reader.readLine() ?: break
                    totalHeaderBytes += line.length
                    if (totalHeaderBytes > MAX_HEADER_BYTES) return sendError(client.getOutputStream(), 431, "Request Header Fields Too Large")
                    if (line.isEmpty()) break
                    val separator = line.indexOf(':')
                    if (separator > 0) headers[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
                }

                if (!validHost(headers["host"])) return sendError(client.getOutputStream(), 403, "Forbidden")
                val route = LocalStreamRouteParser.parse(request[1])
                    ?: return sendError(client.getOutputStream(), 404, "Not Found")
                if (!validAccessToken(route.token)) return sendError(client.getOutputStream(), 403, "Forbidden")
                val fileIndex = route.fileIndex
                val id = route.infoHash
                val target = try {
                    metadata(id, fileIndex)
                } catch (error: Exception) {
                    val status = if (error.message?.contains("не найден", true) == true) 404 else 503
                    return sendError(client.getOutputStream(), status, error.message ?: "Stream unavailable")
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
                    ))
                    return
                }
                val response = RangeResponseBuilder.build(range, target.sizeBytes, contentType(target.name))
                val start = response.start
                val end = response.endInclusive
                val out = client.getOutputStream()
                writeHeaders(out, response.statusCode, response.reason, response.headers)
                if (method == "HEAD") return

                var offset = start
                val buffer = ByteArray(STREAM_CHUNK_BYTES)
                while (offset <= end) {
                    val count = minOf(buffer.size.toLong(), end - offset + 1L).toInt()
                    val file = awaitRange(id, fileIndex, offset, count.toLong())
                    java.io.RandomAccessFile(file, "r").use { input ->
                        input.seek(offset)
                        var remaining = count
                        while (remaining > 0) {
                            val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                            if (read < 0) throw java.io.IOException("Файл оборвался во время чтения")
                            out.write(buffer, 0, read)
                            remaining -= read
                        }
                    }
                    out.flush()
                    offset += count
                }
            } catch (_: Exception) {
                // The player will reconnect on a failed range; do not expose internal file paths.
            }
        }
    }

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
        try { workers?.shutdownNow() } catch (_: Exception) { }
        workers = null
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
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val CLIENT_IDLE_TIMEOUT_MS = 35_000
        private const val STREAM_CHUNK_BYTES = 256 * 1024
    }
}
