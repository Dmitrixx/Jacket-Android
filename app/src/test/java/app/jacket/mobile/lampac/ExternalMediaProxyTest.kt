package app.jacket.mobile.lampac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

class ExternalMediaProxyTest {
    @Test
    fun rewritesOnlyEmbeddedLampacMediaUrls() {
        val local = "http://127.0.0.1:9118/ts/stream/heart.mkv?play"
        assertEquals(
            "http://127.0.0.1:50123/ts/stream/heart.mkv?play",
            routeExternalMediaUrl(local, 50123)
        )
        assertNull(routeExternalMediaUrl(local, null))

        val remote = "https://cdn.example.test/video.mkv"
        assertEquals(remote, routeExternalMediaUrl(remote, null))
        assertEquals(
            "http://192.168.1.20:9118/ts/stream/video.mkv",
            routeExternalMediaUrl("http://192.168.1.20:9118/ts/stream/video.mkv", 50123)
        )

        val onlineProxy = "http://127.0.0.1:9118/proxy/q0j7token.m3u8?audio=0"
        assertEquals(
            "http://127.0.0.1:50123/proxy/q0j7token.m3u8?audio=0",
            routeExternalMediaUrl(onlineProxy, 50123)
        )
        val dashProxy = "http://127.0.0.1:9118/proxy-dash/q0j7token/segment-1.m4s"
        assertEquals(
            "http://127.0.0.1:50123/proxy-dash/q0j7token/segment-1.m4s",
            routeExternalMediaUrl(dashProxy, 50123)
        )

        val localSettings = "http://127.0.0.1:9118/ts/settings"
        assertEquals(localSettings, routeExternalMediaUrl(localSettings, 50123))
        val localImageProxy = "http://127.0.0.1:9118/proxyimg/poster.jpg"
        assertEquals(localImageProxy, routeExternalMediaUrl(localImageProxy, 50123))
    }

    @Test
    fun headProbeIsConvertedToGetAndReturnsHeadersWithoutMediaBody() {
        val receivedRequest = AtomicReference<List<String>>(emptyList())
        val upstream = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val upstreamThread = Thread {
            upstream.accept().use { socket ->
                val lines = readRequestLines(socket)
                receivedRequest.set(lines)
                socket.getOutputStream().apply {
                    write(
                        (
                            "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: video/x-matroska\r\n" +
                                "Content-Length: 777777\r\n" +
                                "Accept-Ranges: bytes\r\n" +
                                "Connection: close\r\n\r\n"
                            ).toByteArray(StandardCharsets.ISO_8859_1)
                    )
                    flush()
                }
            }
        }.apply { isDaemon = true; start() }

        val proxy = ExternalMediaProxyServer(upstreamPort = upstream.localPort)
        try {
            assertTrue(proxy.start())
            val response = sendRequest(
                proxy.port!!,
                "HEAD /ts/stream/heart.mkv HTTP/1.1\r\n" +
                    "Host: 127.0.0.1\r\n" +
                    "User-Agent: VLC-test\r\n\r\n"
            )
            upstreamThread.join(3_000)

            assertTrue(receivedRequest.get().first().startsWith("GET /ts/stream/heart.mkv "))
            assertTrue(receivedRequest.get().any { it.startsWith("Host: 127.0.0.1:${proxy.port}", true) })
            assertTrue(response.startsWith("HTTP/1.1 200 OK"))
            assertTrue(response.contains("Content-Length: 777777"))
            assertTrue(response.contains("Content-Type: video/x-matroska"))
            assertFalse(response.substringAfter("\r\n\r\n").contains("video"))
        } finally {
            proxy.stop()
            upstream.close()
            upstreamThread.join(1_000)
        }
    }

    @Test
    fun getRangeProbePreservesRangeAndStreamsThePartialContent() {
        val receivedRequest = AtomicReference<List<String>>(emptyList())
        val upstream = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val upstreamThread = Thread {
            upstream.accept().use { socket ->
                receivedRequest.set(readRequestLines(socket))
                socket.getOutputStream().apply {
                    write(
                        (
                            "HTTP/1.1 206 Partial Content\r\n" +
                                "Content-Type: video/x-matroska\r\n" +
                                "Content-Length: 4\r\n" +
                                "Content-Range: bytes 0-3/100\r\n" +
                                "Accept-Ranges: bytes\r\n" +
                                "Connection: close\r\n\r\n" +
                                "data"
                            ).toByteArray(StandardCharsets.ISO_8859_1)
                    )
                    flush()
                }
            }
        }.apply { isDaemon = true; start() }

        val proxy = ExternalMediaProxyServer(upstreamPort = upstream.localPort)
        try {
            assertTrue(proxy.start())
            val response = sendRequest(
                proxy.port!!,
                "GET /ts/stream/heart.mkv?link=hash123&index=2&play&uid=test-user HTTP/1.1\r\n" +
                    "Host: 127.0.0.1\r\n" +
                    "Range: bytes=0-3\r\n" +
                    "Cookie: session=must-not-forward\r\n" +
                    "Authorization: Bearer must-not-forward\r\n\r\n"
            )
            upstreamThread.join(3_000)

            assertTrue(receivedRequest.get().first().contains("/ts/stream/heart.mkv?link=hash123&index=2&play&uid=test-user "))
            assertTrue(receivedRequest.get().any { it.equals("Range: bytes=0-3", true) })
            assertFalse(receivedRequest.get().any { it.startsWith("Cookie:", true) })
            assertFalse(receivedRequest.get().any { it.startsWith("Authorization:", true) })
            assertTrue(response.startsWith("HTTP/1.1 206 Partial Content"))
            assertTrue(response.contains("Content-Range: bytes 0-3/100"))
            assertTrue(response.endsWith("data"))
        } finally {
            proxy.stop()
            upstream.close()
            upstreamThread.join(1_000)
        }
    }


    @Test
    fun rewritesChunkedHlsManifestAndRemovesMarkedAdSegmentsForExternalPlayers() {
        val receivedRequest = AtomicReference<List<String>>(emptyList())
        val upstream = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val playlist = """
            #EXTM3U
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXTINF:6.0,
            movie-before.ts
            #EXT-X-CUE-OUT:6
            #EXTINF:6.0,
            inserted-ad.ts
            #EXT-X-CUE-IN
            #EXTINF:6.0,
            movie-after.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val playlistBytes = playlist.toByteArray(StandardCharsets.UTF_8)
        val upstreamThread = Thread {
            upstream.accept().use { socket ->
                receivedRequest.set(readRequestLines(socket))
                socket.getOutputStream().apply {
                    write(
                        (
                            "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: application/vnd.apple.mpegurl\r\n" +
                                "Transfer-Encoding: chunked\r\n" +
                                "Connection: close\r\n\r\n" +
                                "${playlistBytes.size.toString(16)}\r\n"
                            ).toByteArray(StandardCharsets.ISO_8859_1)
                    )
                    write(playlistBytes)
                    write("\r\n0\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
                    flush()
                }
            }
        }.apply { isDaemon = true; start() }

        val proxy = ExternalMediaProxyServer(upstreamPort = upstream.localPort)
        try {
            assertTrue(proxy.start())
            val response = sendRequest(
                proxy.port!!,
                "GET /proxy/q0j7token.m3u8?audio=0 HTTP/1.1\r\n" +
                    "Host: 127.0.0.1\r\n\r\n"
            )
            upstreamThread.join(3_000)

            assertTrue(receivedRequest.get().first().startsWith("GET /proxy/q0j7token.m3u8?audio=0 "))
            assertTrue(receivedRequest.get().any { it.startsWith("Host: 127.0.0.1:${proxy.port}", true) })
            assertTrue(response.startsWith("HTTP/1.1 200 OK"))
            assertFalse(response.contains("Transfer-Encoding: chunked"))
            assertTrue(response.contains("Content-Length:"))
            assertFalse(response.contains("inserted-ad.ts"))
            assertTrue(response.contains("movie-before.ts"))
            assertTrue(response.contains("movie-after.ts"))
            assertTrue(response.contains("http://127.0.0.1:${proxy.port}/proxy/"))
        } finally {
            proxy.stop()
            upstream.close()
            upstreamThread.join(1_000)
        }
    }

    private fun readRequestLines(socket: Socket): List<String> {
        val reader = socket.getInputStream().bufferedReader(StandardCharsets.ISO_8859_1)
        val lines = ArrayList<String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            lines += line
        }
        return lines
    }

    private fun sendRequest(port: Int, request: String): String {
        return Socket(InetAddress.getByName("127.0.0.1"), port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().apply {
                write(request.toByteArray(StandardCharsets.ISO_8859_1))
                flush()
            }
            val response = ByteArrayOutputStream()
            val buffer = ByteArray(4 * 1024)
            while (true) {
                val count = socket.getInputStream().read(buffer)
                if (count < 0) break
                response.write(buffer, 0, count)
            }
            response.toString(StandardCharsets.ISO_8859_1.name())
        }
    }
}
