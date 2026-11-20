package app.jacket.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerSearchClientTest {
    @Test
    fun encodesCyrillicQueryUsingTrackerCharset() {
        val rutracker = WebSearchProvider(
            id = "tracker-rutracker",
            name = "RuTracker",
            searchUrlTemplate = "https://rutracker.org/forum/tracker.php?nm={query}"
        )
        val rutor = rutracker.copy(
            id = "tracker-rutor",
            name = "Rutor",
            searchUrlTemplate = "https://rutor.info/search/0/0/100/2/{query}"
        )

        val cp1251Url = TrackerSearchClient.buildSearchUrl(rutracker, "Обитель зла")
        val utf8Url = TrackerSearchClient.buildSearchUrl(rutor, "Обитель зла")

        assertTrue(cp1251Url.contains("%CE%E1%E8%F2%E5%EB%FC"))
        assertNotEquals(cp1251Url, utf8Url)
        assertTrue(utf8Url.contains("%D0%9E"))
    }

    @Test
    fun parsesMagnetAndBareSeedPeerColumns() {
        val hash = "0123456789abcdef0123456789abcdef01234567"
        val html = """
            <table>
              <tr>
                <td><a class="tLink" href="/forum/viewtopic.php?t=7">Обитель зла 1080p</a></td>
                <td>1.2 GB</td><td>42</td><td>17</td>
                <td><a href="magnet:?xt=urn:btih:$hash&amp;dn=Resident">magnet</a></td>
              </tr>
            </table>
        """.trimIndent()

        val rows = TrackerSearchClient.parseListing(
            html,
            "https://rutracker.org/forum/tracker.php?nm=film",
            "tracker-rutracker"
        )

        assertEquals(1, rows.size)
        assertEquals("Обитель зла 1080p", rows.single().title)
        assertEquals(42, rows.single().seeders)
        assertEquals(17, rows.single().leechers)
        assertEquals(1_288_490_188L, rows.single().sizeBytes)
        assertTrue(rows.single().magnet!!.contains(hash))
    }

    @Test
    fun parsesResultLinksFromEveryBuiltInTrackerAdapter() {
        val samples = listOf(
            Triple("tracker-rutracker", "https://rutracker.org/forum/tracker.php", """<a class="tLink" href="/forum/viewtopic.php?t=1">Rutracker release</a>"""),
            Triple("tracker-nnmclub", "https://nnmclub.to/forum/tracker.php", """<a href="/forum/viewtopic.php?t=2">NNM release</a>"""),
            Triple("tracker-rutor", "https://rutor.info/search/film", """<a class="detLink" href="/torrent/3">Rutor release</a>"""),
            Triple("tracker-kinozal", "https://kinozal.tv/browse.php", """<a href="/details.php?id=4">Kinozal release</a>"""),
            Triple("tracker-1337x", "https://1337x.to/search/film/1/", """<a href="/torrent/example/5">1337x release</a>"""),
            Triple("tracker-tpb", "https://thepiratebay.org/search.php", """<a href="/torrent/6">TPB release</a>""")
        )

        samples.forEach { (providerId, baseUrl, anchorHtml) ->
            val parsed = TrackerSearchClient.parseListing(
                "<html><body><table><tr><td>$anchorHtml</td></tr></table></body></html>",
                baseUrl,
                providerId
            )
            assertTrue("No row parsed for $providerId", parsed.isNotEmpty())
            assertTrue("Wrong result title for $providerId", parsed.single().title.isNotBlank())
        }
    }

    @Test
    fun mergesTrackerResultsByInfoHashAndPrefersMoreSeeders() {
        val hash = "0123456789abcdef0123456789abcdef01234567"
        val lower = SourceResult(
            id = "rutor:1",
            title = "Release from Rutor",
            providerName = "Built-in",
            magnetUri = "magnet:?xt=urn:btih:$hash&dn=Movie",
            seeders = 12,
            indexerId = "tracker-rutor"
        )
        val higher = lower.copy(
            id = "rutracker:2",
            title = "Same release from RuTracker",
            seeders = 54,
            indexerId = "tracker-rutracker"
        )
        val different = SourceResult(
            id = "kinozal:3",
            title = "Different release",
            providerName = "Built-in",
            magnetUri = "magnet:?xt=urn:btih:abcdef0123456789abcdef0123456789abcdef01&dn=Other",
            seeders = 30,
            indexerId = "tracker-kinozal"
        )

        val merged = TrackerSearchClient.mergeResults(listOf(listOf(lower, different), listOf(higher)))

        assertEquals(2, merged.size)
        assertEquals("Same release from RuTracker", merged.first().title)
        assertEquals(54, merged.first().seeders)
    }

    @Test
    fun genericCaptchaWordDoesNotTriggerChallengeDetection() {
        val html = """
            <html><head><title>Search results</title></head>
            <body><article>Film title contains the word captcha in a description.</article></body></html>
        """.trimIndent()
        TrackerSearchClient.ensureNotChallenge(html, "Test tracker")
        assertFalse(TrackerSearchClient.supports("custom-indexer"))
    }

    @Test
    fun challengeMarkersAreReportedWithoutAttemptingToSolveThem() {
        val html = """
            <html><head><title>Just a moment...</title></head>
            <body><div id="challenge-form">Checking your browser</div></body></html>
        """.trimIndent()

        val message = runCatching {
            TrackerSearchClient.ensureNotChallenge(html, "Rutor")
        }.exceptionOrNull()?.message.orEmpty()

        assertTrue(message.contains("браузерную проверку"))
        assertTrue(message.contains("обход защиты не выполняется"))
    }
}
