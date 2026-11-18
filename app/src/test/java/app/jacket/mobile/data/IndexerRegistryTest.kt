package app.jacket.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser

class IndexerRegistryTest {
    private val registry = IndexerRegistry()
    private val config = IndexerConfig(
        id = "test-indexer",
        name = "Test indexer",
        searchUrlTemplate = "https://indexer.example/api?t=search&q={query}",
        protocol = IndexerProtocol.TORZNAB
    )

    @Test
    fun parsesTorznabMagnetTorrentStatsAndCategory() {
        val xml = """
            <rss xmlns:torznab="http://torznab.com/schemas/2015/feed">
              <channel>
                <item>
                  <title>Film.1080p.WEB-DL</title>
                  <guid>https://indexer.example/details/1</guid>
                  <enclosure url="https://indexer.example/download/1.torrent" type="application/x-bittorrent" />
                  <torznab:attr name="seeders" value="37" />
                  <torznab:attr name="peers" value="5" />
                  <torznab:attr name="size" value="9876543" />
                  <torznab:attr name="category" value="2000" />
                </item>
                <item>
                  <title>Film.Magnet.Release</title>
                  <link>magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&amp;dn=Film</link>
                  <torznab:attr name="seeders" value="9" />
                  <torznab:attr name="length" value="12345" />
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val results = registry.parseFeedXml(xml, config, KXmlParser())

        assertEquals(2, results.size)
        val torrent = results.first { it.downloadUrl != null }
        assertEquals(37, torrent.seeders)
        assertEquals(5, torrent.leechers)
        assertEquals(9_876_543L, torrent.sizeBytes)
        assertEquals("2000", torrent.category)
        assertEquals("test-indexer", torrent.indexerId)
        assertEquals("https://indexer.example/details/1", torrent.pageUrl)

        val magnet = results.first { it.magnetUri != null }
        assertTrue(magnet.magnetUri!!.startsWith("magnet:?"))
        assertEquals(9, magnet.seeders)
        assertEquals(12_345L, magnet.sizeBytes)
    }

    @Test
    fun parsesAtomMagnetEnclosure() {
        val xml = """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry>
                <title>Episode.S01E02.720p</title>
                <link rel="alternate" href="https://indexer.example/details/episode" />
                <link rel="enclosure" type="application/x-bittorrent" href="magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567" />
              </entry>
            </feed>
        """.trimIndent()

        val results = registry.parseFeedXml(xml, config, KXmlParser())

        assertEquals(1, results.size)
        assertTrue(results.single().magnetUri!!.startsWith("magnet:?"))
        assertEquals("test-indexer", results.single().indexerId)
        assertEquals("https://indexer.example/details/episode", results.single().pageUrl)
    }

    @Test
    fun parsesDirectVideoEnclosuresAndCommonMediaRss() {
        val xml = """
            <rss xmlns:media="http://search.yahoo.com/mrss/">
              <channel>
                <item>
                  <title>Licensed HLS stream</title>
                  <enclosure url="https://media.example/master.m3u8" type="application/vnd.apple.mpegurl" />
                </item>
                <item>
                  <title>Licensed DASH stream</title>
                  <link rel="enclosure" type="application/dash+xml" href="https://media.example/manifest.mpd" />
                </item>
                <item>
                  <title>Licensed MP4 file</title>
                  <media:content url="https://media.example/movie.mp4" type="video/mp4" medium="video" fileSize="123456" />
                </item>
                <item>
                  <title>Insecure video</title>
                  <enclosure url="http://media.example/movie.mp4" type="video/mp4" />
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val results = registry.parseFeedXml(xml, config, KXmlParser())

        assertEquals(3, results.size)
        assertEquals(
            setOf(
                "https://media.example/master.m3u8",
                "https://media.example/manifest.mpd",
                "https://media.example/movie.mp4"
            ),
            results.mapNotNull { it.streamUrl }.toSet()
        )
        assertEquals(123_456L, results.single { it.title == "Licensed MP4 file" }.sizeBytes)
        assertTrue(results.all { it.downloadUrl == null && it.magnetUri == null })
    }

    @Test
    fun rejectsInsecureOnlyLinks() {
        val xml = """
            <rss><channel>
              <item><title>Insecure</title><link>http://indexer.example/release/1</link></item>
            </channel></rss>
        """.trimIndent()

        assertTrue(registry.parseFeedXml(xml, config, KXmlParser()).isEmpty())
    }

    @Test
    fun orderDeduplicatesByMagnetAndSortsBySeeders() {
        val duplicate = SourceResult(
            id = "one",
            title = "Z release",
            providerName = "A",
            magnetUri = "magnet:?xt=urn:btih:abc"
        )
        val sameMagnet = duplicate.copy(id = "two", title = "Duplicate", providerName = "B")
        val high = SourceResult(
            id = "three",
            title = "A release",
            providerName = "C",
            downloadUrl = "https://indexer.example/3.torrent",
            seeders = 50
        )
        val low = SourceResult(
            id = "four",
            title = "B release",
            providerName = "D",
            downloadUrl = "https://indexer.example/4.torrent",
            seeders = 2
        )

        val ordered = registry.orderResults(listOf(duplicate, sameMagnet, low, high))

        assertEquals(listOf("three", "four", "one"), ordered.map { it.id })
    }
}
