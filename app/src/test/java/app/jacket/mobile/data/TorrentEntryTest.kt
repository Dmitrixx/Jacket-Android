package app.jacket.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TorrentEntryTest {
    @Test
    fun prefersMagnetUriWhenBothLinksArePresent() {
        val entry = TorrentEntry(
            id = "test",
            title = "Ubuntu",
            magnetUri = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567",
            torrentUrl = "https://example.invalid/file.torrent",
            sizeBytes = 1L,
            seeders = 1,
            leechers = 0,
            publishedAt = null,
            indexer = null
        )
        assertEquals(entry.magnetUri, entry.openUri)
    }

    @Test
    fun usesTorrentUrlWhenMagnetIsMissing() {
        val url = "https://example.invalid/file.torrent"
        val entry = TorrentEntry("test", "Linux", null, url, null, null, null, null, null)
        assertEquals(url, entry.openUri)
    }

    @Test
    fun returnsNullWhenNoActionableUrlExists() {
        val entry = TorrentEntry("test", "Linux", null, null, null, null, null, null, null)
        assertNull(entry.openUri)
    }

    @Test
    fun formatsByteUnitsAndUnknownValues() {
        assertEquals("Размер неизвестен", formatBytes(0))
        assertEquals("1.0 КБ", formatBytes(1024))
        assertEquals("1.0 МБ", formatBytes(1024L * 1024L))
        assertEquals("Размер неизвестен", formatBytes(null))
    }
}
