package app.jacket.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerSearchClientTest {
    @Test
    fun recognizesBuiltInTrackerAdaptersOnly() {
        assertTrue(TrackerSearchClient.supports("tracker-rutor"))
        assertTrue(TrackerSearchClient.supports("tracker-rutracker"))
        assertFalse(TrackerSearchClient.supports("custom-my-site"))
        assertFalse(TrackerSearchClient.supports("tracker-archive"))
    }

    @Test
    fun extractsMagnetFromListingRow() {
        val hash = "0123456789abcdef0123456789abcdef01234567"
        val html = """
            <table>
              <tr>
                <td><a class="detLink" href="/details.php?id=1">Example Film 1080p</a></td>
                <td>2.1 GB</td><td>Seeds: 42</td>
                <td><a href="magnet:?xt=urn:btih:$hash&amp;dn=Example">magnet</a></td>
              </tr>
            </table>
        """.trimIndent()

        val candidates = TrackerSearchClient.parseListing(
            html,
            "https://rutor.info/search/0/0/100/2/test",
            "tracker-rutor"
        )

        assertEquals(1, candidates.size)
        assertEquals("Example Film 1080p", candidates.single().title)
        assertTrue(candidates.single().magnet?.startsWith("magnet:?xt=urn:btih:$hash") == true)
    }

    @Test
    fun extractsDetailPageAndTorrentDownloadLinks() {
        val hash = "abcdef0123456789abcdef0123456789abcdef01"
        val html = """
            <html><body>
              <a href="magnet:?xt=urn:btih:$hash&amp;dn=Example">Magnet</a>
              <a href="/download.php?id=77">Download .torrent</a>
            </body></html>
        """.trimIndent()

        val links = TrackerSearchClient.parseDownloadLinks(html, "https://kinozal.tv/details.php?id=77")

        assertEquals("magnet:?xt=urn:btih:$hash&dn=Example", links.first)
        assertEquals("https://kinozal.tv/download.php?id=77", links.second)
    }

    @Test
    fun resolvesRelativeDetailUrlsWithoutOpeningBrowser() {
        val html = """
            <table><tr><td>
              <a class="tLink" href="/forum/viewtopic.php?t=123">Release Name 720p</a>
            </td></tr></table>
        """.trimIndent()

        val candidates = TrackerSearchClient.parseListing(
            html,
            "https://rutracker.org/forum/tracker.php?nm=release",
            "tracker-rutracker"
        )

        assertEquals(1, candidates.size)
        assertEquals("https://rutracker.org/forum/viewtopic.php?t=123", candidates.single().pageUrl)
    }
}
