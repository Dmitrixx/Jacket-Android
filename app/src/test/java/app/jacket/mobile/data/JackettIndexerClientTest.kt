package app.jacket.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser

class JackettIndexerClientTest {
    @Test
    fun buildsCatalogEndpointWithoutLosingReverseProxyPath() {
        val url = JackettIndexerClient.buildCatalogUrl(
            "https://seedbox.example/wp-content/Jackett",
            "secret-key"
        )
        assertEquals(
            "https://seedbox.example/wp-content/Jackett/api/v2.0/indexers/all/results/torznab/api?apikey=secret-key&t=indexers",
            url
        )
    }

    @Test
    fun parsesPublicPrivateAndConfiguredIndexerRows() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <indexers>
              <indexer id="rutracker" configured="true">
                <title>RuTracker</title>
                <description>Private tracker with Russian releases</description>
                <link>https://rutracker.org/</link>
                <language>ru-RU</language>
                <type>private</type>
                <caps><categories>
                  <category id="2000" name="Movies" />
                  <category id="5000" name="TV" />
                </categories></caps>
              </indexer>
              <indexer id="public.example" configured="false">
                <title>Public Example</title>
                <description>Public tracker</description>
                <link>https://tracker.example/</link>
                <language>en-US</language>
                <type>public</type>
              </indexer>
            </indexers>
        """.trimIndent()

        val entries = JackettIndexerClient.parseIndexers(xml, KXmlParser())

        assertEquals(2, entries.size)
        assertEquals("RuTracker", entries[0].name)
        assertTrue(entries[0].configured)
        assertTrue(entries[0].isPrivate)
        assertEquals(listOf("2000", "5000"), entries[0].categoryIds)
        assertEquals(listOf("Movies", "TV"), entries[0].categoryNames)
        assertEquals("Public Example", entries[1].name)
        assertFalse(entries[1].configured)
        assertFalse(entries[1].isPrivate)
    }

    @Test
    fun dropsUnsafeSiteLinksAndIgnoresMalformedRows() {
        val xml = """
            <indexers>
              <indexer id="local" configured="true">
                <title>Local test</title>
                <link>http://127.0.0.1/admin</link>
                <language>en-US</language>
                <type>public</type>
              </indexer>
              <indexer id="../bad" configured="true">
                <title>Malformed identifier</title>
              </indexer>
            </indexers>
        """.trimIndent()

        val entries = JackettIndexerClient.parseIndexers(xml)

        assertEquals(1, entries.size)
        assertEquals(null, entries.single().siteLink)
    }

    @Test
    fun buildsSpecificIndexerSearchConfigFromSavedJackettConnection() {
        val base = IndexerConfig(
            id = "jackett-1",
            name = "My Jackett",
            searchUrlTemplate = "https://seedbox.example/wp/Jackett/api/v2.0/indexers/all/results/torznab/api?t=search&q={query}",
            protocol = IndexerProtocol.JACKETT,
            apiKey = "private-key"
        )
        val queryConfig = JackettIndexerClient.searchConfig(base, "rutracker")

        assertEquals("private-key", queryConfig.apiKey)
        assertEquals(
            "https://seedbox.example/wp/Jackett/api/v2.0/indexers/rutracker/results/torznab/api?t=search&q={query}",
            queryConfig.searchUrlTemplate
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTraversalIndexerId() {
        val base = IndexerConfig(
            id = "jackett-1",
            name = "My Jackett",
            searchUrlTemplate = "https://seedbox.example/api/v2.0/indexers/all/results/torznab/api?t=search&q={query}",
            protocol = IndexerProtocol.JACKETT,
            apiKey = "key"
        )
        JackettIndexerClient.searchConfig(base, "../admin")
    }
}
