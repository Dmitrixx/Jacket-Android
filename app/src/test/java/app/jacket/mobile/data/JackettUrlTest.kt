package app.jacket.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JackettUrlTest {
    @Test
    fun bareLocalAndRemoteHostsGetSafeDefaultSchemes() {
        assertEquals("http://192.168.1.50:9117", JackettUrl.normalizeBase("192.168.1.50:9117"))
        assertEquals("http://nas.local:9117", JackettUrl.normalizeBase("nas.local:9117"))
        assertEquals("https://jackett.example.org", JackettUrl.normalizeBase("jackett.example.org"))
    }

    @Test
    fun dashboardAndCopiedApiPathsAreStripped() {
        assertEquals(
            "http://192.168.1.5:9117",
            JackettUrl.normalizeBase("http://192.168.1.5:9117/UI/Dashboard")
        )
        assertEquals(
            "http://192.168.1.5:9117",
            JackettUrl.normalizeBase("http://192.168.1.5:9117/api/v2.0/indexers/all/results/torznab/api?apikey=abc&t=search")
        )
    }

    @Test
    fun reverseProxyPrefixIsPreservedAndRoundTrips() {
        val normalized = JackettUrl.normalizeBase("https://home.example.org/jackett/UI/Dashboard")
        assertEquals("https://home.example.org/jackett", normalized)
        val template = JackettUrl.searchTemplate(normalized!!, "tag:movies")
        val parsed = JackettUrl.parseTemplate(template)
        assertEquals(normalized, parsed?.baseUrl)
        assertEquals("tag:movies", parsed?.indexer)
        assertEquals(normalized + "/api/v2.0/indexers/tag:movies/results/torznab/api?t=search&q={query}", template)
    }

    @Test
    fun capabilitiesEndpointCanBeProbed() {
        assertEquals(
            "http://192.168.1.5:9117/api/v2.0/indexers/all/results/torznab/api?t=caps&q={query}",
            JackettUrl.capabilitiesTemplate("http://192.168.1.5:9117")
        )
    }

    @Test
    fun publicHttpAndCredentialUrlsAreRejected() {
        assertNull(JackettUrl.normalizeBase("http://203.0.113.9:9117"))
        assertNull(JackettUrl.normalizeBase("https://user:pass@jackett.example.org"))
        assertNull(JackettUrl.normalizeBase("   "))
    }

    @Test
    fun indexerFilterIsSanitized() {
        val suffix = "/results/torznab/api?t=search&q={query}"
        assertEquals(
            "http://h.local:1/api/v2.0/indexers/tag:movies$suffix",
            JackettUrl.searchTemplate("http://h.local:1", "tag:movies")
        )
        assertEquals(
            "http://h.local:1/api/v2.0/indexers/all$suffix",
            JackettUrl.searchTemplate("http://h.local:1", "../")
        )
    }
}
