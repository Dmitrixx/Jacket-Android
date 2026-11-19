package app.jacket.mobile.torrent

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class StreamDiskCacheTest {
    @Test
    fun readsSegmentsAndEvictsLeastRecentlyUsedSlot() {
        val directory = Files.createTempDirectory("jacket-stream-cache-test").toFile()
        val cache = StreamDiskCache(directory, maxBytes = 8L, segmentBytes = 4)
        val first = StreamCacheKey("a".repeat(40), 0, 0)
        val second = StreamCacheKey("a".repeat(40), 0, 1)
        val third = StreamCacheKey("a".repeat(40), 0, 2)

        try {
            cache.put(first, byteArrayOf(1, 2, 3, 4))
            cache.put(second, byteArrayOf(5, 6, 7, 8))
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), cache.get(first, 4)?.bytes)
            cache.put(third, byteArrayOf(9, 10, 11, 12))

            assertArrayEquals(byteArrayOf(1, 2, 3, 4), cache.get(first, 4)?.bytes)
            assertNull(cache.get(second, 4)?.bytes)
            assertArrayEquals(byteArrayOf(9, 10, 11, 12), cache.get(third, 4)?.bytes)
            assertEquals(1L, cache.evictions)
        } finally {
            cache.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun returnsCachedSuffixForOverlappingRange() {
        val directory = Files.createTempDirectory("jacket-stream-cache-test").toFile()
        val cache = StreamDiskCache(directory, maxBytes = 8L, segmentBytes = 4)
        val stored = StreamCacheKey("b".repeat(40), 1, 100)
        val requested = StreamCacheKey("b".repeat(40), 1, 102)
        try {
            cache.put(stored, byteArrayOf(1, 2, 3, 4))
            val hit = cache.get(requested, 4)
            assertEquals(102L, hit?.startOffset)
            assertArrayEquals(byteArrayOf(3, 4), hit?.bytes)
        } finally {
            cache.close()
            directory.deleteRecursively()
        }
    }
}
