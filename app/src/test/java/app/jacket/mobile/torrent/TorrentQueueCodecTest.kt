package app.jacket.mobile.torrent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TorrentQueueCodecTest {
    @Test
    fun roundTripsMagnetAndSelectedFile() {
        val item = QueuedTorrent(
            id = "0123456789abcdef0123456789abcdef01234567",
            title = "Some release 1080p",
            magnetUri = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Some%20release",
            selectedFileIndex = 3
        )
        assertEquals(listOf(item), TorrentQueueCodec.decode(TorrentQueueCodec.encode(listOf(item))))
    }

    @Test
    fun skipsCorruptV2RecordAndKeepsValidQueueEntries() {
        val valid = QueuedTorrent(
            id = "0123456789abcdef0123456789abcdef01234567",
            title = "Keep me",
            magnetUri = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"
        )
        val encoded = TorrentQueueCodec.encode(listOf(valid))
        val corrupted = encoded + "\ninvalid\t-\t-\t-\t-1"

        assertEquals(listOf(valid), TorrentQueueCodec.decode(corrupted))
    }

    @Test
    fun rejectsOversizedQueueBeforeEncoding() {
        val oversizedMagnet = "magnet:?" + "🦄".repeat(8_000)
        val items = (0 until 300).map { index ->
            QueuedTorrent(
                id = index.toString(16).padStart(40, '0'),
                title = "Large queue item",
                magnetUri = oversizedMagnet
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            TorrentQueueCodec.encode(items)
        }
    }

    @Test
    fun migratesLegacyMagnetWithInvalidIdAndDeduplicatesByCanonicalHash() {
        val hash = "0123456789abcdef0123456789abcdef01234567"
        val magnet = "magnet:?xt=urn:btih:$hash&dn=Old%20release"
        val legacy = JSONArray()
            .put(JSONObject().put("id", "not-a-hash").put("magnet", magnet))
            .put(JSONObject().put("id", hash.uppercase()).put("magnet", magnet))
            .toString()

        val migrated = TorrentQueueCodec.decodeLegacy(legacy)

        assertEquals(listOf(TorrentQueueStore.idFor(magnet)), migrated.map { it.id })
        assertEquals("Торрент ${hash.take(10)}", migrated.single().title)
    }

    @Test
    fun migratesLegacyFileUsingHashFilenameAndSkipsUnverifiableIds() {
        val hash = "abcdef0123456789abcdef0123456789abcdef01"
        val directory = Files.createTempDirectory("jacket-legacy-queue").toFile()
        val hashNamedFile = File(directory, "$hash.torrent")
        val unverifiedFile = File(directory, "old-name.torrent")
        try {
            hashNamedFile.writeText("metainfo")
            unverifiedFile.writeText("metainfo")
            val legacy = JSONArray()
                .put(JSONObject().put("id", "invalid").put("torrentPath", hashNamedFile.absolutePath))
                .put(JSONObject().put("id", "invalid").put("torrentPath", unverifiedFile.absolutePath))
                .toString()

            val migrated = TorrentQueueCodec.decodeLegacy(legacy)

            assertEquals(listOf(hash), migrated.map { it.id })
            assertEquals(hashNamedFile.absolutePath, migrated.single().torrentPath)
        } finally {
            hashNamedFile.delete()
            unverifiedFile.delete()
            directory.delete()
        }
    }

    @Test
    fun roundTripsLocalTorrentPathAndUnicodeTitle() {
        val item = QueuedTorrent(
            id = "abcdefabcdefabcdefabcdefabcdefabcdefabcd",
            title = "Фильм — тест",
            torrentPath = "/data/user/0/app/files/torrent-metainfo/example.torrent"
        )
        assertEquals(listOf(item), TorrentQueueCodec.decode(TorrentQueueCodec.encode(listOf(item))))
    }
}
