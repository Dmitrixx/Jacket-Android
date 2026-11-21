package app.jacket.mobile.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HdRezkaCatalogClientTest {
    @Test
    fun parsesSearchCardsAndKeepsResultsOnConfiguredOrigin() {
        val html = """
            <div class="b-content__inline_item">
              <a class="b-content__inline_item-link" href="https://hdrezka.example/film/123-demo.html" title="Демо">Демо</a>
              <div class="b-content__inline_item-cover"><img src="data:image/gif;base64,AA==" data-src="https://hdrezka.example/poster.jpg"></div>
              <div class="b-content__inline_item-details">2024</div>
            </div>
        """.trimIndent()
        val items = HdRezkaCatalogClient.parseSearchEntries(html, "https://hdrezka.example")
        assertEquals(1, items.size)
        assertEquals("Демо", items.single().title)
        assertEquals("https://hdrezka.example/film/123-demo.html", items.single().pageUrl)
        assertEquals("https://hdrezka.example/poster.jpg", items.single().posterUrl)
    }

    @Test
    fun ignoresExternalSearchResults() {
        val html = """
            <div class="b-content__inline_item">
              <a class="b-content__inline_item-link" href="https://other.example/film.html">X</a>
            </div>
            <div class="b-content__inline_item">
              <a class="b-content__inline_item-link" href="https://user@hdrezka.example/film.html">Y</a>
            </div>
        """.trimIndent()
        assertTrue(HdRezkaCatalogClient.parseSearchEntries(html, "https://hdrezka.example").isEmpty())
    }

    @Test
    fun readsTranslatorsAndPostIdsFromPage() {
        val html = """
            <input id="post_id" value="7712">
            <div id="translators-list">
              <span data-translator_id="12" title="Профессиональный">Проф</span>
              <span data-translator_id="14">Любительский</span>
            </div>
        """.trimIndent()
        assertEquals("7712", HdRezkaCatalogClient.parsePostId(html, "https://hdrezka.example/film/demo.html"))
        assertEquals(listOf("12", "14"), HdRezkaCatalogClient.parseTranslators(html).map { it.id })
    }

    @Test
    fun parsesMovieBootstrapFlagsAndFavoritesToken() {
        val html = """
            <input id="ctrl_favs" value="fav-token-42">
            <div id="translators-list">
              <span data-translator_id="17" data-camrip="1" data-ads="0" data-director="1">Озвучка</span>
            </div>
            <script>initCDNMoviesEvents(7712, 17, 1, 0, 1);</script>
        """.trimIndent()
        val page = HdRezkaCatalogClient.parsePageContext(
            html,
            "https://hdrezka.example/film/demo.html"
        )
        assertNotNull(page)
        assertEquals("7712", page?.postId)
        assertEquals("fav-token-42", page?.favs)
        assertFalse(page?.isSeries ?: true)
        assertEquals("17", page?.translators?.single()?.id)
        assertEquals("1", page?.translators?.single()?.isCamrip)
        assertEquals("0", page?.translators?.single()?.isAds)
        assertEquals("1", page?.translators?.single()?.isDirector)
    }

    @Test
    fun parsesSeriesBootstrapSeasonAndEpisodeDefaults() {
        val html = """
            <script>initCDNSeriesEvents(991, 7, 3, 105);</script>
            <div id="simple-episodes-tabs"></div>
        """.trimIndent()
        val page = HdRezkaCatalogClient.parsePageContext(
            html,
            "https://hdrezka.example/serial/demo.html"
        )
        assertNotNull(page)
        assertEquals("991", page?.postId)
        assertTrue(page?.isSeries ?: false)
        assertEquals("3", page?.defaultSeasonId)
        assertEquals("105", page?.defaultEpisodeId)
        assertEquals("7", page?.translators?.single()?.id)
    }

    @Test
    fun exposesPremiumTranslationAsSubscriptionPageInsteadOfBrokenStream() {
        val entry = HdRezkaSearchEntry("Демо", "https://hdrezka.example/film/demo.html", null, "")
        val result = HdRezkaCatalogClient.parseStreamSources(
            JSONObject().put("success", false).put("premium_content", true),
            entry,
            HdRezkaTranslator("99", "Premium", isPremium = true),
            entry.pageUrl,
            null
        )
        assertEquals(1, result.size)
        assertTrue(result.single().requiresSubscription)
        assertEquals(entry.pageUrl, result.single().pageUrl)
        assertNull(result.single().streamUrl)
    }

    @Test
    fun recognizesMovieBootstrapWithTrailingArguments() {
        val html = """<script>player.initCDNMoviesEvents(7712, 17, 1, 0, 1, true);</script>"""
        val page = HdRezkaCatalogClient.parsePageContext(
            html,
            "https://hdrezka.example/film/demo.html"
        )
        assertNotNull(page)
        assertEquals("7712", page?.postId)
        assertFalse(page?.isSeries ?: true)
        assertEquals("17", page?.translators?.single()?.id)
        assertEquals("1", page?.translators?.single()?.isCamrip)
        assertEquals("0", page?.translators?.single()?.isAds)
        assertEquals("1", page?.translators?.single()?.isDirector)
    }

    @Test
    fun recognizesSeriesBootstrapWithTrailingArguments() {
        val html = """<script>player.initCDNSeriesEvents(991, 7, 3, 105, true);</script>"""
        val page = HdRezkaCatalogClient.parsePageContext(
            html,
            "https://hdrezka.example/serial/demo.html"
        )
        assertNotNull(page)
        assertTrue(page?.isSeries ?: false)
        assertEquals("991", page?.postId)
        assertEquals("7", page?.translators?.single()?.id)
        assertEquals("3", page?.defaultSeasonId)
        assertEquals("105", page?.defaultEpisodeId)
    }

    @Test
    fun mapsSeasonAndEpisodeIdsFromEpisodesResponse() {
        val response = JSONObject().put(
            "episodes",
            """
                <div class="b-simple_episode__item" data-season_id="3" data-episode_id="101"></div>
                <div class="b-simple_episode__item" data-season_id="3" data-episode_id="102"></div>
                <div class="b-simple_episode__item" data-season_id="8" data-episode_id="201"></div>
            """.trimIndent()
        )
        val ids = HdRezkaCatalogClient.resolveEpisodeIds(response, 1, 2)
        assertNotNull(ids)
        assertEquals(HdRezkaEpisodeIds("3", "102"), ids)
    }

    @Test
    fun buildsCurrentEngineAjaxSearchPostRequest() {
        assertEquals(
            "https://hdrezka.example/engine/ajax/search.php",
            HdRezkaCatalogClient.buildSearchUrl("https://hdrezka.example")
        )
        assertEquals(
            "q=Interstellar+2024",
            HdRezkaCatalogClient.buildSearchBody("Interstellar 2024")
        )
    }

    @Test
    fun parsesCurrentAjaxSearchAnchorsWithoutLegacyResultCards() {
        val html = """
            <ul>
              <li><a href="/film/123-demo.html"><span class="enty">Демо</span> (Original, 2024)</a></li>
              <li><a href="/search/?q=other">Search</a></li>
            </ul>
        """.trimIndent()
        val entries = HdRezkaCatalogClient.parseSearchEntries(html, "https://hdrezka.example")
        assertEquals(1, entries.size)
        assertEquals("Демо", entries.single().title)
        assertEquals("https://hdrezka.example/film/123-demo.html", entries.single().pageUrl)
    }

    @Test
    fun decodesHdRezkaTrashPayloadAndSelectsDirectQualityFallback() {
        val encoded = "#hWzEwODBwXWh0dHBzOi8vY2RuLmV4YW1wbGUv" +
            "//_//JCQhIUAkJEBeIUAjJCRA" +
            "bW92aWVfMTA4MC5tcDQsWzcyMHBdaHR0cHM6Ly9jZG4uZXhhbXBsZS9tb3ZpZV83MjAubXA0" +
            "//_//QEBAQEAhIyMhXl5e" +
            "//_//IyMjI14hISMjIUBA" +
            "//_//Xl5eIUAjIyEhIyM=" +
            "//_//JCQjISFAIyFAIyM="
        val decoded = HdRezkaCatalogClient.decodeStreamPayload(encoded)
        assertEquals(
            "[1080p]https://cdn.example/movie_1080.mp4,[720p]https://cdn.example/movie_720.mp4",
            decoded
        )
        val variants = HdRezkaCatalogClient.parseQualityVariants(encoded)
        assertEquals(listOf("1080p", "720p"), variants.map { it.qualityLabel })
        assertEquals("https://cdn.example/movie_1080.mp4", variants.first().url)
    }

    @Test
    fun prefersMp4OverHlsRegardlessOfAlternativeOrder() {
        val variants = HdRezkaCatalogClient.parseQualityVariants(
            "[1080p Ultra]https://cdn.example/file.mp4 or https://cdn.example/master.m3u8"
        )
        assertEquals(1, variants.size)
        assertEquals("1080p", variants.single().qualityLabel)
        assertEquals("https://cdn.example/file.mp4", variants.single().url)
    }

    @Test
    fun parsesQualityVariantsAndSubtitleLanguages() {
        val variants = HdRezkaCatalogClient.parseQualityVariants(
            "[1080p]https://cdn.example/movie_1080.m3u8,[720p]https://cdn.example/movie_720.m3u8"
        )
        assertEquals(listOf("1080p", "720p"), variants.map { it.qualityLabel })
        val subtitles = HdRezkaCatalogClient.parseSubtitles(
            JSONObject()
                .put("subtitle", "[ru]https://cdn.example/ru.vtt,[en]https://cdn.example/en.srt")
                .put("subtitle_lns", JSONObject().put("ru", "Русский").put("en", "English"))
                .put("subtitle_def", "ru")
        )
        assertEquals(2, subtitles.size)
        assertEquals("Русский", subtitles.first().label)
        assertEquals("application/x-subrip", subtitles.last().mimeType)
    }

    @Test
    fun rejectsObfuscatedAndCleartextUrlsRatherThanGuessing() {
        assertTrue(HdRezkaCatalogClient.parseQualityVariants("#2opaque").isEmpty())
        assertTrue(HdRezkaCatalogClient.parseQualityVariants("http://cdn.example/video.mp4").isEmpty())
        assertFalse(HdRezkaCatalogClient.parseQualityVariants("https://cdn.example/video.mp4").isEmpty())
    }

    @Test
    fun streamSourcesCarryOnlyRefererAndNeverAccountCookie() {
        val entry = HdRezkaSearchEntry("Демо", "https://hdrezka.example/film/demo.html", null, "")
        val results = HdRezkaCatalogClient.parseStreamSources(
            JSONObject().put("url", "[720p]https://cdn.example/video.mp4"),
            entry,
            HdRezkaTranslator("7", "Русская озвучка"),
            "https://hdrezka.example/film/demo.html",
            null
        )
        assertEquals(1, results.size)
        assertEquals("Русская озвучка", results.single().translationName)
        assertEquals(
            mapOf(
                "Referer" to "https://hdrezka.example/film/demo.html",
                "User-Agent" to "Jacket-Android/2.7 (Android media client)"
            ),
            results.single().requestHeaders
        )
        assertNull(results.single().magnetUri)
    }
}
