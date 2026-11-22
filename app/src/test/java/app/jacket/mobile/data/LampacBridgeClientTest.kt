package app.jacket.mobile.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LampacBridgeClientTest {
    private val movie = CatalogItem(
        id = "tmdb:movie:550",
        providerId = "tmdb",
        providerItemId = "550",
        kind = MediaKind.MOVIE,
        title = "Бойцовский клуб",
        releaseDate = "1999-10-15"
    )

    private val series = CatalogItem(
        id = "tmdb:series:1399",
        providerId = "tmdb",
        providerItemId = "1399",
        kind = MediaKind.SERIES,
        title = "Игра престолов",
        releaseDate = "2011-04-17"
    )

    @Test
    fun acceptsHttpsAndConfiguredLoopbackHttpOnly() {
        assertEquals(
            "https://lampac.example",
            LampacBridgeClient.normalizeBaseUrl("https://lampac.example/")
        )
        assertEquals(
            "http://127.0.0.1:9118",
            LampacBridgeClient.normalizeBaseUrl("http://127.0.0.1:9118")
        )
    }

    @Test
    fun rejectsPublicCleartextHttpAndUrlsWithCredentialsOrPaths() {
        listOf(
            "http://lampac.example:9118",
            "http://192.168.1.20:9118",
            "http://lampac.lan:9118",
            "https://user:password@lampac.example",
            "https://lampac.example/base",
            "https://lampac.example/?token=secret",
            "ftp://192.168.1.20:9118"
        ).forEach { value ->
            assertFalse(value, runCatching { LampacBridgeClient.normalizeBaseUrl(value) }.isSuccess)
        }
    }

    @Test
    fun buildsMovieEventsUrlAndEncodesConfiguredAccountValues() {
        val settings = LampacSettings(
            baseUrl = "https://lampac.example/",
            accountEmail = "user+test@example.com",
            uid = "device-1",
            token = "secret token",
            enabled = true
        )
        val url = LampacBridgeClient.buildEventsUrl(settings, movie)
        assertTrue(url.startsWith("https://lampac.example/lite/events?"))
        assertTrue(url.contains("id=550"))
        assertTrue(url.contains("tmdb_id=550"))
        assertTrue(url.contains("source=tmdb"))
        assertTrue(url.contains("serial=0"))
        assertTrue(url.contains("life=true"))
        assertTrue(url.contains("external_ids=true"))
        assertTrue(url.contains("account_email=user%2Btest%40example.com"))
        assertTrue(url.contains("uid=device-1"))
        assertTrue(url.contains("token=secret%20token"))
    }

    @Test
    fun buildsSeriesEventsAndLongPollUrlsWithRequestedEpisodeContext() {
        val settings = LampacSettings(
            baseUrl = "https://lampac.example",
            accountEmail = "account@example.com",
            uid = "u1",
            token = "t1",
            enabled = true
        )
        val events = LampacBridgeClient.buildEventsUrl(settings, series)
        assertTrue(events.contains("serial=1"))
        val poll = LampacBridgeClient.buildLifeEventsUrl(settings, series, "opaque-key", 4)
        assertTrue(poll.startsWith("https://lampac.example/lifeevents?"))
        assertTrue(poll.contains("memkey=opaque-key"))
        assertTrue(poll.contains("seen=4"))
        assertTrue(poll.contains("uid=u1"))
    }

    @Test
    fun pinsProviderUrlsAndPlaybackUrlsToConfiguredOrigin() {
        assertTrue(LampacBridgeClient.isSameOrigin("/lite/rezka/movie?x=1", "https://lampac.example"))
        assertTrue(LampacBridgeClient.isSameOrigin("https://lampac.example/lite/movie", "https://lampac.example"))
        assertFalse(LampacBridgeClient.isSameOrigin("https://other.example/lite/movie", "https://lampac.example"))
        assertFalse(LampacBridgeClient.isSameOrigin("http://lampac.example/lite/movie", "https://lampac.example"))
        assertFalse(LampacBridgeClient.isSameOrigin("https://lampac.example:9443/lite/movie", "https://lampac.example"))
    }

    @Test
    fun stripsAccountCredentialsFromProviderPageMetadata() {
        val safe = LampacBridgeClient.stripSensitiveQueryParams(
            "/lite/rezka/movie?title=Demo&account_email=user%40example.com&uid=device&token=secret&rjson=true",
            "https://lampac.example"
        )
        assertTrue(safe.startsWith("https://lampac.example/lite/rezka/movie?"))
        assertTrue(safe.contains("title=Demo"))
        assertTrue(safe.contains("rjson=true"))
        assertFalse(safe.contains("account_email"))
        assertFalse(safe.contains("uid="))
        assertFalse(safe.contains("token="))
        assertFalse(safe.contains("secret"))
    }

    @Test
    fun parsesMovieStreamsAndCopiesOnlySafePlaybackHeaders() {
        val data = JSONArray().put(
            JSONObject()
                .put("stream", "/lite/rezka/movie.m3u8?play=true")
                .put("maxquality", "1080p")
                .put("voice_name", "Русская озвучка")
                .put(
                    "headers",
                    JSONObject()
                        .put("Referer", "https://lampac.example/")
                        .put("User-Agent", "Jacket test")
                        .put("Cookie", "private=secret")
                        .put("Authorization", "Bearer secret")
                )
        )
        val result = LampacBridgeClient.parsePlayableEntries(
            data = data,
            providerUrl = "https://lampac.example/lite/rezka?rjson=true",
            baseUrl = "https://lampac.example",
            item = movie,
            providerName = "HDRezka",
            targetSeason = null,
            targetEpisode = null
        )
        assertEquals(1, result.size)
        assertEquals(MediaSourceType.LAMPAC, result.single().sourceType)
        assertEquals("HDRezka", result.single().providerName)
        assertEquals("lampac:rezka", result.single().indexerId)
        assertEquals("https://lampac.example/lite/rezka?rjson=true", result.single().pageUrl)
        assertEquals("https://lampac.example/lite/rezka/movie.m3u8?play=true", result.single().streamUrl)
        assertEquals("1080p", result.single().qualityLabel)
        assertEquals("Русская озвучка", result.single().translationName)
        assertEquals(
            mapOf("Referer" to "https://lampac.example/", "User-Agent" to "Jacket test"),
            result.single().requestHeaders
        )
        assertFalse(result.single().requestHeaders.containsKey("Cookie"))
        assertFalse(result.single().requestHeaders.containsKey("Authorization"))
    }

    @Test
    fun keepsProviderIdentitySeparateFromLampacServerName() {
        val data = JSONArray().put(JSONObject().put("stream", "/watch/video.m3u8"))
        val result = LampacBridgeClient.parsePlayableEntries(
            data = data,
            providerUrl = "https://lampac.example/lite/filmix?rjson=true",
            baseUrl = "https://lampac.example",
            item = movie,
            providerName = "Filmix",
            targetSeason = null,
            targetEpisode = null,
            providerId = "filmix"
        )
        assertEquals(1, result.size)
        assertEquals("Filmix", result.single().providerName)
        assertEquals("lampac:filmix", result.single().indexerId)
        assertFalse(result.single().pageUrl.orEmpty().contains("token="))
    }

    @Test
    fun ignoresExternalOrMissingPlaybackStreams() {
        val data = JSONArray()
            .put(JSONObject().put("stream", "https://cdn.example/video.m3u8"))
            .put(JSONObject().put("stream", "https://lampac.example/"))
            .put(JSONObject().put("url", "https://lampac.example/no-stream.m3u8"))
        val result = LampacBridgeClient.parsePlayableEntries(
            data,
            "https://lampac.example/lite/provider?rjson=true",
            "https://lampac.example",
            movie,
            "Test provider",
            null,
            null
        )
        assertEquals(1, result.size)
        assertEquals("https://lampac.example/", result.single().streamUrl)
        assertEquals("Test provider", result.single().providerName)
    }

    @Test
    fun selectsRequestedSeasonFromLampacSeasonTemplate() {
        val seasons = JSONArray()
            .put(JSONObject().put("id", 1).put("name", "1 сезон").put("url", "/series?s=1"))
            .put(JSONObject().put("id", 2).put("name", "2 сезон").put("url", "/series?s=2"))
        assertEquals(
            "/series?s=2",
            LampacBridgeClient.selectSeasonUrl(seasons, 2)
        )
        assertNull(LampacBridgeClient.selectSeasonUrl(seasons, 3))
    }

    @Test
    fun parsesOnlyRequestedEpisodeFromEpisodeTemplate() {
        val data = JSONArray()
            .put(JSONObject().put("s", 1).put("e", 1).put("stream", "/watch/1-1.m3u8"))
            .put(JSONObject().put("s", 1).put("e", 2).put("stream", "/watch/1-2.m3u8"))
            .put(JSONObject().put("s", 2).put("e", 2).put("stream", "/watch/2-2.m3u8"))
        val result = LampacBridgeClient.parsePlayableEntries(
            data,
            "https://lampac.example/lite/rezka/serial?rjson=true",
            "https://lampac.example",
            series,
            "HDRezka",
            1,
            2
        )
        assertEquals(1, result.size)
        assertTrue(result.single().title.contains("S01E02"))
        assertEquals("https://lampac.example/watch/1-2.m3u8", result.single().streamUrl)
    }
}
