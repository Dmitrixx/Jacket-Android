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
              <div class="b-content__inline_item-cover"><img src="https://hdrezka.example/poster.jpg"></div>
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
        val html = """<div class="b-content__inline_item"><a class="b-content__inline_item-link" href="https://other.example/film.html">X</a></div>"""
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
        assertEquals(mapOf("Referer" to "https://hdrezka.example/film/demo.html"), results.single().requestHeaders)
        assertNull(results.single().magnetUri)
    }
}
