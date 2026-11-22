package app.jacket.mobile.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilmixCatalogClientTest {
    @Test
    fun buildsAuthenticatedSearchUrlWithLampaDeviceFingerprint() {
        val url = FilmixCatalogClient.buildApiUrl(
            FilmixDeviceAuth.DEFAULT_API_BASE,
            "search",
            "0123456789abcdef",
            "private-token",
            mapOf("story" to "Тест фильм")
        )
        assertTrue(url.startsWith("https://filmixapp.cyou/api/v2/search?"))
        assertTrue(url.contains("user_dev_id=0123456789abcdef"))
        assertTrue(url.contains("user_dev_token=private-token"))
        assertTrue(url.contains("user_dev_name=Xiaomi"))
        assertTrue(url.contains("user_dev_vendor=Xiaomi"))
        assertTrue(url.contains("user_dev_os=14"))
        assertTrue(url.contains("user_dev_apk=2.2.0"))
        assertTrue(url.contains("app_lang=ru-rRU"))
        assertTrue(url.contains("story="))
        assertFalse(url.contains("Тест фильм"))
    }

    @Test
    fun buildsUrlForAnExplicitCustomHttpsApiHost() {
        val url = FilmixCatalogClient.buildApiUrl(
            "https://api.example.net/api/v2",
            "search",
            "0123456789abcdef",
            "private-token"
        )
        assertTrue(url.startsWith("https://api.example.net/api/v2/search?"))
    }

    @Test
    fun parsesSearchResultsAndBuildsCurrentFilmixPageUrls() {
        val parsed = FilmixCatalogClient.parseSearchEntries(JSONObject().put("data", JSONArray().put(
            JSONObject()
                .put("id", 42)
                .put("title", "Тест")
                .put("original_title", "Test")
                .put("year", 2024)
                .put("section", 7)
                .put("alt_name", "test-serial")
        )))
        assertEquals(1, parsed.size)
        assertTrue(parsed.single().isSeries)
        assertEquals("https://filmix.gg/seria/42-test-serial.html", parsed.single().pageUrl)
    }

    @Test
    fun parsesMovieQualitiesWithoutGuessingMissingVariants() {
        val parsed = FilmixCatalogClient.parseQualityVariants(
            "https://cdn.example/video_[1080,720,480].mp4"
        )
        assertEquals(listOf(1080, 720, 480), parsed.mapNotNull { it.quality })
        assertTrue(parsed.first().url.endsWith("video_1080.mp4"))
        assertTrue(FilmixCatalogClient.parseQualityVariants("https://cdn.example/video_%s.mp4").isEmpty())
    }

    @Test
    fun honorsExplicitQualityArrayForCurrentPlaceholderFormat() {
        val parsed = FilmixCatalogClient.parseQualityVariants(
            "https://cdn.example/video_%s.mp4",
            JSONArray().put(1080).put(720)
        )
        assertEquals(listOf(1080, 720), parsed.mapNotNull { it.quality })
    }

    @Test
    fun updatesOnlyLivehashPathSegmentWithoutChangingOtherUrlParts() {
        val url = "https://cdn.example/s/old-hash/path/video.mp4?token=other"
        assertEquals(
            "https://cdn.example/s/new_hash-42/path/video.mp4?token=other",
            FilmixCatalogClient.replaceLivehash(url, "new_hash-42")
        )
    }

    @Test
    fun extractsLivehashFromFilmixPlayerLinks() {
        val post = JSONObject().put(
            "player_links",
            JSONObject().put("movie", JSONArray().put(
                JSONObject().put("link", "https://cdn.example/s/active123/video_[1080,720].mp4")
            ))
        )
        assertEquals("active123", FilmixCatalogClient.extractLivehash(post))
    }

    @Test
    fun appliesAccountQualityLimitsFromWrappedAndFlatProfiles() {
        assertEquals(720, FilmixCatalogClient.maxQualityFromProfile(JSONObject()))
        assertEquals(1080, FilmixCatalogClient.maxQualityFromProfile(JSONObject().put("is_pro", true)))
        assertEquals(2160, FilmixCatalogClient.maxQualityFromProfile(JSONObject().put("is_pro_plus", "1")))
        assertEquals(
            720,
            FilmixCatalogClient.maxQualityFromProfile(
                JSONObject().put("user_data", JSONObject().put("login", "free"))
            )
        )
        assertEquals(
            1080,
            FilmixCatalogClient.maxQualityFromProfile(
                JSONObject().put("user_data", JSONObject().put("is_pro", 1))
            )
        )
        assertEquals(
            2160,
            FilmixCatalogClient.maxQualityFromProfile(
                JSONObject().put("user_data", JSONObject().put("is_pro_plus", true))
            )
        )
    }

    @Test
    fun buildsSeriesSourcesOnlyForTheRequestedEpisode() {
        val entry = FilmixSearchEntry(7, "Сериал", "", 2024, true, null)
        val post = JSONObject().put("player_links", JSONObject().put(
            "playlist",
            JSONObject().put(
                "1",
                JSONObject().put(
                    "Озвучка",
                    JSONArray()
                        .put(JSONObject().put("link", "https://cdn.example/s01e01_[720,480].mp4"))
                        .put(JSONObject().put("link", "https://cdn.example/s01e02_[720,480].mp4"))
                )
            )
        ))
        val results = FilmixCatalogClient.parsePostSources(post, entry, "S01E02", 720)
        assertEquals(2, results.size)
        assertTrue(results.all { it.title.contains("S01E02") })
        assertTrue(results.all { it.streamUrl.orEmpty().contains("s01e02_") })
    }

    @Test
    fun findsRequestedEpisodeInsideNonSequentialObjectKeys() {
        val entry = FilmixSearchEntry(7, "Сериал", "", 2024, true, null)
        val post = JSONObject().put("player_links", JSONObject().put(
            "playlist",
            JSONObject().put(
                "1",
                JSONObject().put(
                    "Озвучка",
                    JSONObject()
                        .put("episode-a", JSONObject().put("link", "https://cdn.example/s01e01.mp4"))
                        .put("episode-z", JSONObject().put("link", "https://cdn.example/s01e02_[720,480].mp4"))
                )
            )
        ))
        val results = FilmixCatalogClient.parsePostSources(post, entry, "S01E02", 720)
        assertEquals(2, results.size)
        assertTrue(results.all { it.streamUrl.orEmpty().contains("s01e02") })
    }

    @Test
    fun excludesQualitiesAboveTheAccountPlanAndUsesFilmixUserAgent() {
        val entry = FilmixSearchEntry(1, "Фильм", "", 2024, false, null)
        val post = JSONObject().put("player_links", JSONObject().put(
            "movie",
            JSONArray().put(
                JSONObject()
                    .put("translation", "Озвучка")
                    .put("link", "https://cdn.example/movie_[2160,1080,720].mp4")
            )
        ))
        val results = FilmixCatalogClient.parsePostSources(post, entry, null, 1080)
        assertEquals(listOf("1080p", "720p"), results.map { it.qualityLabel })
        assertEquals(
            FilmixDeviceAuth.SITE_BASE,
            results.first().requestHeaders["Referer"]
        )
        assertEquals(
            "okhttp/3.10.0",
            results.first().requestHeaders["User-Agent"]
        )
    }
}
