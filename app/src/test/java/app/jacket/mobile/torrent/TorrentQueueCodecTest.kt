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
}
