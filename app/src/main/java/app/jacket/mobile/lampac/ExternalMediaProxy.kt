package app.jacket.mobile.lampac

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Local HTTP compatibility proxy for external Android media players.
 *
 * Lampac 1.90.2's BaseMod middleware rejects HEAD before media routes are
 * reached, while VLC and other players commonly probe media with HEAD first.
 * This loopback-only proxy converts that probe to GET, returns its headers
 * without a body, and relays GET/Range response bytes and HTTP framing unchanged.
 * Raw socket forwarding preserves chunked media streams that high-level HTTP
 * clients may transparently decode.
 *
 * It exposes only Lampac's media namespaces (/ts/stream, /ts/gst, /proxy and
 * /proxy-dash) and never forwards caller-supplied Authorization or Cookie headers.
 */
internal class ExternalMediaProxyServer(
    private val upstreamHost: String = "127.0.0.1",
    private val upstreamPort: Int = 9118,
    private val listenPort: Int = 0
) : Closeable {
    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var executor: ThreadPoolExecutor? = null

    @Volatile
    private var acceptThread: Thread? = null

    @Volatile
    var port: Int? = null
        private set

    private val running = AtomicBoolean(false)
    private val activeClients = ConcurrentHashMap.newKeySet<Socket>()

    @Synchronized
    fun start(): Boolean {
        if (running.get()) return port != null

        val socket = ServerSocket()
        try {
            socket.reuseAddress = true
            socket.bind(
                InetSocketAddress(InetAddress.getByName("127.0.0.1"), listenPort),
                16
            )

            val pool = ThreadPoolExecutor(
                2,
                8,
                30L,
                TimeUnit.SECONDS,
                LinkedBlockingQueue<Runnable>(32),
                daemonThreadFactory("ExternalMediaProxy-Worker"),
                ThreadPoolExecutor.CallerRunsPolicy()
            )

            serverSocket = socket
            executor = pool
            port = socket.localPort
            running.set(true)

            acceptThread = Thread(
                { acceptLoop(socket, pool) },
                "ExternalMediaProxy-Accept"
            ).apply {
                isDaemon = true
                start()
            }
            return true
        } catch (_: Exception) {
            running.set(false)
            port = null
            runCatching { socket.close() }
            executor?.shutdownNow()
            executor = null
            serverSocket = null
            return false
        }
    }

    @Synchronized
    fun stop() {
        running.set(false)
        port = null

        runCatching { serverSocket?.close() }
        serverSocket = null

        activeClients.toList().forEach { client ->
            runCatching { client.close() }
        }
        activeClients.clear()

        executor?.shutdownNow()
        executor = null
        acceptThread = null
    }

    override fun close() = stop()

    private fun acceptLoop(socket: ServerSocket, pool: ThreadPoolExecutor) {
        while (running.get()) {
            val client = try {
                socket.accept()
            } catch (_: SocketException) {
                break
            } catch (_: IOException) {
                if (!running.get()) break
                continue
            }

            if (!running.get()) {
                runCatching { client.close() }
                break
            }

            activeClients.add(client)
            try {
                client.tcpNoDelay = true
                pool.execute {
                    try {
                        handleClient(client)
                    } finally {
                        activeClients.remove(client)
                    }
                }
            } catch (_: Exception) {
                activeClients.remove(client)
                runCatching { client.close() }
            }
        }
    }

    private fun handleClient(client: Socket) {
        client.use { connected ->
            try {
                connected.soTimeout = REQUEST_READ_TIMEOUT_MS
                val input = BufferedInputStream(connected.getInputStream(), IO_BUFFER_SIZE)
                val output = BufferedOutputStream(connected.getOutputStream(), IO_BUFFER_SIZE)

                val requestLine = readLine(input, MAX_REQUEST_LINE_BYTES)
                    ?: return
                val parts = requestLine.split(' ', limit = 3)
                if (parts.size != 3 ||
                    (parts[2] != "HTTP/1.0" && parts[2] != "HTTP/1.1")
                ) {
                    writeSimpleResponse(output, 400, "Bad Request")
                    return
                }

                val method = parts[0].uppercase(Locale.ROOT)
                if (method == "OPTIONS") {
                    output.write(
                        ("HTTP/1.1 204 No Content\r\n" +
                            "Allow: GET, HEAD, OPTIONS\r\n" +
                            "Content-Length: 0\r\n" +
                            "Connection: close\r\n\r\n").toByteArray(StandardCharsets.ISO_8859_1)
                    )
                    output.flush()
                    return
                }
                if (method != "GET" && method != "HEAD") {
                    writeSimpleResponse(output, 405, "Method Not Allowed")
                    return
                }

                val target = runCatching { URI(parts[1]) }.getOrNull()
                val path = target?.rawPath
                val decodedPath = target?.path
                if (target == null || target.isAbsolute || target.rawAuthority != null ||
                    path.isNullOrBlank() || !isExternalMediaPath(path) ||
                    decodedPath.orEmpty().split('/').any { it == "." || it == ".." } ||
                    '\\' in path || (target.rawQuery?.length ?: 0) > MAX_QUERY_LENGTH
                ) {
                    writeSimpleResponse(output, 404, "Not Found")
                    return
                }

                val requestHeaders = readHeaders(input) ?: run {
                    writeSimpleResponse(output, 400, "Bad Request")
                    return
                }

                proxyRequest(
                    output = output,
                    method = method,
                    rawPath = path,
                    rawQuery = target.rawQuery,
                    requestHeaders = requestHeaders
                )
            } catch (_: Exception) {
                // Client cancellations and malformed third-party probes are expected.
            }
        }
    }

    private fun proxyRequest(
        output: BufferedOutputStream,
        method: String,
        rawPath: String,
        rawQuery: String?,
        requestHeaders: Map<String, String>
    ) {
        val upstream = Socket()
        var responseStarted = false
        try {
            upstream.tcpNoDelay = true
            upstream.connect(
                InetSocketAddress(upstreamHost, upstreamPort),
                UPSTREAM_CONNECT_TIMEOUT_MS
            )
            upstream.soTimeout = 0

            val upstreamInput = BufferedInputStream(upstream.getInputStream(), IO_BUFFER_SIZE)
            val upstreamOutput = BufferedOutputStream(upstream.getOutputStream(), IO_BUFFER_SIZE)
            val querySuffix = rawQuery?.let { "?$it" }.orEmpty()

            // The upstream server's middleware blocks HEAD before media handlers.
            // Use GET to obtain media headers, but omit the body for HEAD.
            upstreamOutput.write(
                "GET $rawPath$querySuffix HTTP/1.1\r\n".toByteArray(StandardCharsets.ISO_8859_1)
            )
            // Lampac can emit absolute playlist/segment URLs using Request.Host.
            // Keep those links on the compatibility port so nested media requests
            // also receive HEAD-to-GET and byte-range handling for external players.
            val proxyPort = port ?: throw IOException("Внешний прокси уже остановлен")
            upstreamOutput.write(
                "Host: 127.0.0.1:$proxyPort\r\n".toByteArray(StandardCharsets.ISO_8859_1)
            )
            upstreamOutput.write("Connection: close\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            upstreamOutput.write("Accept-Encoding: identity\r\n".toByteArray(StandardCharsets.ISO_8859_1))

            listOf(
                "Range",
                "If-Range",
                "If-Modified-Since",
                "If-None-Match",
                "Accept",
                "User-Agent",
                "Referer",
                "Origin"
            ).forEach { name ->
                requestHeaders.entries.firstOrNull { it.key.equals(name, true) }
                    ?.value
                    ?.takeIf { '\r' !in it && '\n' !in it }
                    ?.let { value ->
                        upstreamOutput.write(
                            "$name: $value\r\n".toByteArray(StandardCharsets.ISO_8859_1)
                        )
                    }
            }
            upstreamOutput.write("\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            upstreamOutput.flush()

            val statusLine = readLine(upstreamInput, MAX_HEADER_LINE_BYTES)
                ?: throw IOException("Lampac закрыл соединение без HTTP-ответа")
            val statusParts = statusLine.split(' ', limit = 3)
            if (statusParts.size < 2 ||
                (statusParts[0] != "HTTP/1.0" && statusParts[0] != "HTTP/1.1")
            ) {
                throw IOException("Некорректная строка HTTP-статуса от Lampac")
            }
            val status = statusParts[1].toIntOrNull()
                ?: throw IOException("Некорректный HTTP-статус от Lampac")

            val responseHeaders = ArrayList<Pair<String, String>>()
            var totalHeaderBytes = statusLine.length
            var headerCount = 0
            while (true) {
                if (++headerCount > MAX_HEADER_COUNT) {
                    throw IOException("Слишком много HTTP-заголовков от Lampac")
                }
                val line = readLine(upstreamInput, MAX_HEADER_LINE_BYTES)
                    ?: throw IOException("Lampac оборвал HTTP-заголовки")
                totalHeaderBytes += line.length
                if (totalHeaderBytes > MAX_HEADER_BYTES) {
                    throw IOException("HTTP-заголовки Lampac слишком большие")
                }
                if (line.isEmpty()) break

                val separator = line.indexOf(':')
                if (separator <= 0) throw IOException("Некорректный HTTP-заголовок от Lampac")
                val name = line.substring(0, separator).trim()
                val value = line.substring(separator + 1).trim()
                if (name.isBlank() || value.any { it == '\r' || it == '\n' }) {
                    throw IOException("Некорректное значение HTTP-заголовка от Lampac")
                }
                responseHeaders += name to value
            }

            val contentType = responseHeaders.firstOrNull { it.first.equals("Content-Type", true) }
                ?.second
                ?.substringBefore(';')
                ?.take(80)
            val shouldLogResponse = method == "HEAD" ||
                status !in 200..299 ||
                (method == "GET" && (
                    rawPath.endsWith(".m3u8", ignoreCase = true) ||
                        rawPath.endsWith(".mpd", ignoreCase = true) ||
                        contentType?.contains("mpegurl", ignoreCase = true) == true ||
                        contentType?.contains("dash+xml", ignoreCase = true) == true
                    ))
            if (shouldLogResponse) {
                val length = responseHeaders.firstOrNull { it.first.equals("Content-Length", true) }?.second
                val detail = buildString {
                    append("Внешний плеер: ")
                    append(if (method == "HEAD") "HEAD→GET" else method)
                    append(' ')
                    append(mediaRouteLabel(rawPath))
                    append(" → HTTP ")
                    append(status)
                    append(", ")
                    append(contentType ?: "тип не указан")
                    if (!length.isNullOrBlank()) append(", $length байт")
                }
                LampacRuntimeState.appendLog(detail)
            }

            val isManifestResponse = method == "GET" &&
                status in 200..299 &&
                (rawPath.endsWith(".m3u8", ignoreCase = true) ||
                    rawPath.endsWith(".m3u", ignoreCase = true) ||
                    rawPath.endsWith(".mpd", ignoreCase = true) ||
                    contentType?.contains("mpegurl", ignoreCase = true) == true ||
                    contentType?.contains("dash+xml", ignoreCase = true) == true)

            if (isManifestResponse) {
                val originalBody = readManifestBody(upstreamInput, responseHeaders)
                val manifestBase = buildString {
                    append("http://")
                    append(upstreamHost)
                    append(':')
                    append(upstreamPort)
                    append(rawPath)
                    if (!rawQuery.isNullOrBlank()) append("?").append(rawQuery)
                }
                val source = String(originalBody, StandardCharsets.UTF_8)
                val filtered = runCatching {
                    StreamAdManifestFilter.filter(source, manifestBase)
                }.getOrElse {
                    LampacRuntimeState.appendLog(
                        "Внешний плеер: не удалось разобрать манифест рекламы; оставляем исходный поток."
                    )
                    StreamAdFilterResult(source)
                }
                val currentPort = port ?: throw IOException("Внешний прокси уже остановлен")
                val oldOrigin = "http://$upstreamHost:$upstreamPort"
                val localOrigin = "http://127.0.0.1:$currentPort"
                val rewrittenManifest = filtered.content.replace(
                    oldOrigin,
                    localOrigin,
                    ignoreCase = true
                )
                val body = rewrittenManifest.toByteArray(StandardCharsets.UTF_8)

                if (filtered.removedSegments > 0 || filtered.removedPeriods > 0 ||
                    filtered.removedInterstitialMarkers > 0
                ) {
                    LampacRuntimeState.appendLog(
                        "Внешний плеер: удалены рекламные вставки — " +
                            "HLS-сегменты: ${filtered.removedSegments}, " +
                            "DASH-периоды: ${filtered.removedPeriods}, " +
                            "interstitial: ${filtered.removedInterstitialMarkers}."
                    )
                }

                responseStarted = true
                output.write("$statusLine\r\n".toByteArray(StandardCharsets.ISO_8859_1))
                responseHeaders.forEach { (name, rawValue) ->
                    if (name.equals("Connection", true) ||
                        name.equals("Keep-Alive", true) ||
                        name.equals("Proxy-Connection", true) ||
                        name.equals("Content-Length", true) ||
                        name.equals("Transfer-Encoding", true) ||
                        name.equals("Content-Encoding", true) ||
                        name.equals("Content-Type", true) ||
                        name.equals("Content-MD5", true) ||
                        name.equals("ETag", true)
                    ) return@forEach

                    val value = if (name.equals("Location", true)) rewriteLocation(rawValue) else rawValue
                    output.write(
                        "$name: $value\r\n".toByteArray(StandardCharsets.ISO_8859_1)
                    )
                }
                output.write(
                    "Content-Length: ${body.size}\r\n".toByteArray(StandardCharsets.ISO_8859_1)
                )
                val manifestMimeType = if (rawPath.endsWith(".mpd", ignoreCase = true) ||
                    contentType?.contains("dash+xml", ignoreCase = true) == true
                ) "application/dash+xml" else "application/vnd.apple.mpegurl"
                output.write("Content-Type: $manifestMimeType; charset=utf-8\r\n"
                    .toByteArray(StandardCharsets.ISO_8859_1))
                output.write("Connection: close\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
                output.write(body)
                output.flush()
                return
            }

            // Keep the origin's exact body framing for media segments. Raw socket
            // forwarding preserves chunked video bytes and Range responses.
            responseStarted = true
            output.write("$statusLine\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            responseHeaders.forEach { (name, rawValue) ->
                if (name.equals("Connection", true) ||
                    name.equals("Keep-Alive", true) ||
                    name.equals("Proxy-Connection", true) ||
                    (method == "HEAD" && name.equals("Transfer-Encoding", true))
                ) return@forEach

                val value = if (name.equals("Location", true)) rewriteLocation(rawValue) else rawValue
                output.write(
                    "$name: $value\r\n".toByteArray(StandardCharsets.ISO_8859_1)
                )
            }
            output.write("Connection: close\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            output.flush()

            if (method == "GET" && status !in BODYLESS_STATUS_CODES) {
                val buffer = ByteArray(IO_BUFFER_SIZE)
                while (true) {
                    val count = upstreamInput.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    output.flush()
                }
            }
        } catch (error: Exception) {
            if (!responseStarted) {
                runCatching { writeSimpleResponse(output, 502, "Bad Gateway") }
            }
            val failure = when (error) {
                is SocketTimeoutException -> "тайм-аут"
                is ConnectException -> "соединение отклонено"
                is UnknownHostException -> "ошибка DNS"
                else -> error.javaClass.simpleName
            }
            LampacRuntimeState.appendLog(
                "Внешний плеер: ${mediaRouteLabel(rawPath)} — ошибка relay ($failure)."
            )
        } finally {
            runCatching { upstream.close() }
        }
    }

    /**
     * HLS/DASH manifests are small, finite documents, unlike media segments.
     * Decode their HTTP framing so ad markers can be removed and a new
     * Content-Length can be returned to VLC/MX Player.
     */
    private fun readManifestBody(
        input: BufferedInputStream,
        headers: List<Pair<String, String>>
    ): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val transferEncoding = headers.firstOrNull {
            it.first.equals("Transfer-Encoding", true)
        }?.second.orEmpty()
        val contentLength = headers.firstOrNull {
            it.first.equals("Content-Length", true)
        }?.second?.toLongOrNull()
        val limit = MAX_MANIFEST_BYTES

        if (transferEncoding.contains("chunked", ignoreCase = true)) {
            while (true) {
                val line = readLine(input, MAX_HEADER_LINE_BYTES)
                    ?: throw IOException("Manifest chunk-size line is missing")
                val chunkSize = line.substringBefore(';').trim().toLongOrNull(16)
                    ?: throw IOException("Invalid HTTP chunk size in manifest")
                if (chunkSize == 0L) {
                    while (true) {
                        val trailer = readLine(input, MAX_HEADER_LINE_BYTES)
                            ?: throw IOException("Manifest chunk trailers are incomplete")
                        if (trailer.isEmpty()) break
                    }
                    break
                }
                if (chunkSize < 0 || chunkSize > limit || output.size().toLong() + chunkSize > limit) {
                    throw IOException("Manifest exceeds the $limit byte safety limit")
                }
                var remaining = chunkSize.toInt()
                val buffer = ByteArray(IO_BUFFER_SIZE)
                while (remaining > 0) {
                    val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                    if (count < 0) throw IOException("Manifest chunk ended unexpectedly")
                    output.write(buffer, 0, count)
                    remaining -= count
                }
                val chunkTerminator = readLine(input, 2)
                if (chunkTerminator != "") throw IOException("Invalid HTTP chunk terminator")
            }
        } else if (contentLength != null) {
            if (contentLength < 0 || contentLength > limit) {
                throw IOException("Manifest exceeds the $limit byte safety limit")
            }
            var remaining = contentLength.toInt()
            val buffer = ByteArray(IO_BUFFER_SIZE)
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (count < 0) throw IOException("Manifest ended before Content-Length")
                output.write(buffer, 0, count)
                remaining -= count
            }
        } else {
            val buffer = ByteArray(IO_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size().toLong() + count > limit) {
                    throw IOException("Manifest exceeds the $limit byte safety limit")
                }
                output.write(buffer, 0, count)
            }
        }

        return output.toByteArray()
    }

    private fun mediaRouteLabel(rawPath: String): String = when {
        rawPath.startsWith("/proxy-dash/", ignoreCase = true) -> "/proxy-dash"
        rawPath.startsWith("/proxy/", ignoreCase = true) -> "/proxy"
        rawPath.startsWith("/ts/stream", ignoreCase = true) -> "/ts/stream"
        rawPath.startsWith("/ts/gst", ignoreCase = true) -> "/ts/gst"
        else -> "media"
    }

    private fun rewriteLocation(location: String): String {
        val oldOrigin = "http://$upstreamHost:$upstreamPort"
        val currentPort = port ?: return location
        return if (location.startsWith("$oldOrigin/", ignoreCase = true)) {
            location.replaceFirst(oldOrigin, "http://127.0.0.1:$currentPort", ignoreCase = true)
        } else {
            location
        }
    }

    private fun readHeaders(input: BufferedInputStream): Map<String, String>? {
        val headers = LinkedHashMap<String, String>()
        var totalBytes = 0

        repeat(MAX_HEADER_COUNT) {
            val line = readLine(input, MAX_HEADER_LINE_BYTES) ?: return null
            totalBytes += line.length
            if (totalBytes > MAX_HEADER_BYTES) return null
            if (line.isEmpty()) return headers

            val separator = line.indexOf(':')
            if (separator <= 0) return null
            val name = line.substring(0, separator).trim()
            if (name.isEmpty() || name.any { !it.isLetterOrDigit() && it !in HEADER_NAME_PUNCTUATION }) {
                return null
            }
            val value = line.substring(separator + 1).trim()
            if ('\r' in value || '\n' in value) return null
            headers[name] = value
        }

        return null
    }

    private fun readLine(input: BufferedInputStream, maxBytes: Int): String? {
        val bytes = java.io.ByteArrayOutputStream()
        var previous = -1

        while (bytes.size() <= maxBytes) {
            val next = input.read()
            if (next < 0) return if (bytes.size() == 0) null else String(
                bytes.toByteArray(),
                StandardCharsets.ISO_8859_1
            )

            if (previous == '\r'.code && next == '\n'.code) {
                val value = bytes.toByteArray()
                if (value.isNotEmpty()) {
                    return String(value, 0, value.size - 1, StandardCharsets.ISO_8859_1)
                }
                return ""
            }

            bytes.write(next)
            previous = next
        }

        throw IOException("HTTP header line is too long")
    }

    private fun writeSimpleResponse(
        output: BufferedOutputStream,
        status: Int,
        reason: String
    ) {
        val body = "$status $reason\n".toByteArray(StandardCharsets.UTF_8)
        output.write("HTTP/1.1 $status $reason\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        output.write("Content-Type: text/plain; charset=utf-8\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        output.write("Content-Length: ${body.size}\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        output.write("Connection: close\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        output.write(body)
        output.flush()
    }

    private fun reasonPhrase(status: Int): String = when (status) {
        200 -> "OK"
        206 -> "Partial Content"
        301 -> "Moved Permanently"
        302 -> "Found"
        304 -> "Not Modified"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        416 -> "Range Not Satisfiable"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        else -> "Upstream Response"
    }

    private fun daemonThreadFactory(prefix: String) = ThreadFactory { runnable ->
        Thread(runnable, "$prefix-${threadCounter++}").apply { isDaemon = true }
    }

    private var threadCounter = 0

    private companion object {
        const val IO_BUFFER_SIZE = 64 * 1024
        const val REQUEST_READ_TIMEOUT_MS = 15_000
        const val UPSTREAM_CONNECT_TIMEOUT_MS = 10_000
        const val MAX_REQUEST_LINE_BYTES = 16 * 1024
        const val MAX_HEADER_LINE_BYTES = 8 * 1024
        const val MAX_HEADER_BYTES = 32 * 1024
        const val MAX_HEADER_COUNT = 64
        const val MAX_QUERY_LENGTH = 16 * 1024
        const val MAX_MANIFEST_BYTES = 8 * 1024 * 1024
        const val HEADER_NAME_PUNCTUATION = "!#$%&'*+-.^_`|~"
        val BODYLESS_STATUS_CODES = setOf(204, 304)
    }
}

/** Maps this app's local Lampac media URLs to the loopback proxy used by external players. */
internal fun routeExternalMediaUrl(rawUrl: String, proxyPort: Int?): String? {
    val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return null
    val isEmbeddedLampacMediaUrl =
        uri.scheme.equals("http", true) &&
            uri.host.equals("127.0.0.1", true) &&
            uri.port == 9118 &&
            uri.rawPath?.let(::isExternalMediaPath) == true

    if (!isEmbeddedLampacMediaUrl) return rawUrl
    if (proxyPort == null || proxyPort !in 1..65535) return null

    return rawUrl.replaceFirst(
        "http://127.0.0.1:9118",
        "http://127.0.0.1:$proxyPort",
        ignoreCase = true
    )
}

private fun isExternalMediaPath(path: String): Boolean =
    path == "/ts/stream" ||
        path.startsWith("/ts/stream/") ||
        path == "/ts/gst" ||
        path.startsWith("/ts/gst/") ||
        path.startsWith("/proxy/") ||
        path.startsWith("/proxy-dash/")

/** Process-wide proxy owner; the Lampac foreground service controls its lifetime. */
internal object ExternalMediaProxy {
    private val server = ExternalMediaProxyServer()

    val port: Int?
        get() = server.port

    fun start(): Boolean = server.start()

    fun stop() = server.stop()
}
