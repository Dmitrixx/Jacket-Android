package app.jacket.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.IOException

class JackettFeedParserTest {
    private val registry = IndexerRegistry()
    private val config = IndexerConfig(
        id = "jackett-lan",
        name = "Jackett",
        searchUrlTemplate = "http://192.168.1.50:9117/api/v2.0/indexers/all/results/torznab/api?t=search&q={query}",
        protocol = IndexerProtocol.JACKETT
    )

    @Test
    fun parsesJackettTrackerTorrentLinkAndPeers() {
        val xml = """
            <rss xmlns:torznab="http://torznab.com/schemas/2015/feed">
              <channel>
                <item>
                  <title>Movie.2160p.WEB-DL</title>
                  <guid>https://tracker.example/details/1</guid>
                  <comments>https://tracker.example/details/1</comments>
                  <jackettindexer id="sample">SampleTracker</jackettindexer>
                  <enclosure url="http://192.168.1.50:9117/dl/sample/?jackett_apikey=secret" type="application/x-bittorrent" />
                  <size>987654321</size>
                  <torznab:attr name="seeders" value="42" />
                  <torznab:attr name="peers" value="47" />
                </item>
                <item>
                  <title>Magnet result</title>
                  <link>magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567</link>
                  <jackettindexer id="other">OtherTracker</jackettindexer>
                  <torznab:attr name="seeders" value="7" />
                  <torznab:attr name="leechers" value="2" />
                </item>
              </channel>
            </rss>
        """.trimIndent()
        val results = registry.parseFeedXml(xml, config, KXmlParser())
        assertEquals(2, results.size)
        val torrent = results.first { it.downloadUrl != null }
        assertEquals("SampleTracker", torrent.tracker)
        assertEquals("http://192.168.1.50:9117/dl/sample/?jackett_apikey=secret", torrent.downloadUrl)
        assertEquals(987_654_321L, torrent.sizeBytes)
        assertEquals(42, torrent.seeders)
        assertEquals(5, torrent.leechers)
        assertEquals("https://tracker.example/details/1", torrent.pageUrl)

        val magnet = results.first { it.magnetUri != null }
        assertEquals("OtherTracker", magnet.tracker)
        assertTrue(magnet.magnetUri!!.startsWith("magnet:?"))
        assertEquals(2, magnet.leechers)
    }

    @Test
    fun invalidApiKeyXmlIsReportedAsAnIndexerError() {
        try {
            registry.parseFeedXml("""<error code="100" description="Invalid API Key" />""", config, KXmlParser())
            fail("Expected an indexer error")
        } catch (error: IOException) {
            assertEquals("Jackett: Invalid API Key (код 100)", error.message)
        }
    }

    @Test
    fun capabilitiesResponseIsAValidEmptyResult() {
        val xml = """<caps><server title="Jackett" /><searching><search available="yes" supportedParams="q" /></searching></caps>"""
        assertTrue(registry.parseFeedXml(xml, config, KXmlParser()).isEmpty())
    }

    @Test
    fun rejectsPublicHttpDownloadLinksButAllowsLocalJackettDownloads() {
        val xml = """<rss><channel>
            <item><title>Public HTTP</title>
                <enclosure url="http://tracker.example.org/item.torrent" type="application/x-bittorrent" />
            </item>
            <item><title>Local Jackett</title>
                <enclosure url="http://192.168.1.50:9117/dl/1" type="application/x-bittorrent" />
            </item>
        </channel></rss>"""
        val results = registry.parseFeedXml(xml, config, KXmlParser())
        assertEquals(1, results.size)
        assertEquals("Local Jackett", results.single().title)
    }
}
