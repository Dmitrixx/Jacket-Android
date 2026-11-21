package app.jacket.mobile.torrent

import org.junit.Assert.assertEquals
import org.junit.Test

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
    fun roundTripsLocalTorrentPathAndUnicodeTitle() {
        val item = QueuedTorrent(
            id = "abcdefabcdefabcdefabcdefabcdefabcdefabcd",
            title = "Фильм — тест",
            torrentPath = "/data/user/0/app/files/torrent-metainfo/example.torrent"
        )
        assertEquals(listOf(item), TorrentQueueCodec.decode(TorrentQueueCodec.encode(listOf(item))))
    }

    @Test
    fun skipsCorruptEntryAndRetainsValidEntriesAroundIt() {
        val first = QueuedTorrent(
            id = "0123456789abcdef0123456789abcdef01234567",
            title = "First",
            magnetUri = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"
        )
        val last = QueuedTorrent(
            id = "abcdefabcdefabcdefabcdefabcdefabcdefabcd",
            title = "Last",
            torrentPath = "/data/user/0/app/files/torrent-metainfo/last.torrent"
        )
        val encoded = TorrentQueueCodec.encode(listOf(first, last))
        val lines = encoded.lines().toMutableList()
        lines.add(2, "fedcbafedcbafedcbafedcbafedcbafedcbafe\t%%%\t-\t-\t-1")

        assertEquals(listOf(first, last), TorrentQueueCodec.decode(lines.joinToString("\n")))
    }

    @Test
    fun skipsMalformedFieldCountAndInvalidQueueId() {
        val valid = QueuedTorrent(
            id = "0123456789abcdef0123456789abcdef01234567",
            title = "Valid",
            magnetUri = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"
        )
        val encoded = TorrentQueueCodec.encode(listOf(valid))
        val lines = encoded.lines() + listOf("broken", "not-a-hash\t-\t-\t-\t0")

        assertEquals(listOf(valid), TorrentQueueCodec.decode(lines.joinToString("\n")))
    }
}
