package app.jacket.mobile.torrent

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files

/** Exercises the actual loopback listener, request parser, token check and byte-range response. */
class LocalStreamServerIntegrationTest {
    @Test
    fun servesHeadAndPartialContentAndRejectsWrongToken() {
        val payload = "0123456789".toByteArray(Charsets.US_ASCII)
        val file = Files.createTempFile("jacket-stream-test", ".mp4").toFile()
        file.writeBytes(payload)

        val hash = "abcdef0123456789abcdef0123456789abcdef01"
        val cacheDirectory = Files.createTempDirectory("jacket-stream-ring").toFile()
        var awaitedRanges = 0
        val server = LocalStreamServer(
            metadata = { id, index ->
                assertEquals(hash, id)
                assertEquals(0, index)
                TorrentStreamTarget(id, index, "sample.mp4", payload.size.toLong(), file.absolutePath, 0.5f)
            },
            awaitRange = { id, index, start, length ->
                assertEquals(hash, id)
                assertEquals(0, index)
                assertEquals(2L, start)
                assertEquals(4L, length)
                awaitedRanges++
                file
            },
            cacheDirectory = cacheDirectory
        )

        try {
            server.start()
            val streamUrl = URL(server.url(hash, 0))

            val range = (streamUrl.openConnection() as HttpURLConnection).apply {
                connectTimeout = 3_000
                readTimeout = 3_000
                setRequestProperty("Range", "bytes=2-5")
            }
            try {
                assertEquals(206, range.responseCode)
                assertEquals("bytes 2-5/10", range.getHeaderField("Content-Range"))
                assertEquals("4", range.getHeaderField("Content-Length"))
                assertArrayEquals(payload.copyOfRange(2, 6), range.inputStream.use { it.readBytes() })
            } finally {
                range.disconnect()
            }

            val cachedRange = (streamUrl.openConnection() as HttpURLConnection).apply {
                connectTimeout = 3_000
                readTimeout = 3_000
                setRequestProperty("Range", "bytes=2-5")
            }
            try {
                assertEquals(206, cachedRange.responseCode)
                assertArrayEquals(payload.copyOfRange(2, 6), cachedRange.inputStream.use { it.readBytes() })
                assertEquals("A repeated seek should hit the disk cache", 1, awaitedRanges)
            } finally {
                cachedRange.disconnect()
            }

            val head = (streamUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = 3_000
                readTimeout = 3_000
            }
            try {
                assertEquals(200, head.responseCode)
                assertEquals("10", head.getHeaderField("Content-Length"))
            } finally {
                head.disconnect()
            }

            val parts = streamUrl.path.split('/')
            val replacementToken = if (parts[2] == "0".repeat(32)) "1".repeat(32) else "0".repeat(32)
            val forgedUrl = URL(
                streamUrl.protocol,
                streamUrl.host,
                streamUrl.port,
                "/stream/$replacementToken/${parts[3]}/${parts[4]}"
            )
            val forged = (forgedUrl.openConnection() as HttpURLConnection).apply {
                connectTimeout = 3_000
                readTimeout = 3_000
            }
            try {
                assertEquals(403, forged.responseCode)
            } finally {
                forged.disconnect()
            }
        } finally {
            server.close()
            file.delete()
            cacheDirectory.deleteRecursively()
        }
    }
}
