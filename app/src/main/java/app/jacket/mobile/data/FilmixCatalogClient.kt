package app.jacket.mobile.data

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

data class FilmixSearchEntry(
    val id: Int,
    val title: String,
    val originalTitle: String,
    val year: Int?,
    val isSeries: Boolean,
    val pageUrl: String?
)

data class FilmixQualityVariant(
    val quality: Int?,
    val url: String
)

/** Filmix v2 client using the Lampa-compatible fingerprint and optional HTTPS CORS transport. */
object FilmixCatalogClient {
    private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
    private const val MAX_SEARCH_ITEMS = 8
    private const val MAX_TRANSLATIONS = 12
    private const val LIVEHASH_POST_ID = 171042
    private const val LIVEHASH_TTL_MS = 2 * 60 * 60 * 1000L
    private const val USER_AGENT = FilmixDeviceAuth.API_USER_AGENT
    private val livehashPattern = Regex("/s/([^/]+)/")
    @Volatile private var cachedLivehash: String? = null
    @Volatile private var cachedLivehashKey: String? = null
    @Volatile private var livehashExpiresAt = 0L
    private val livehashLock = Any()
    private val qualityListPattern = Regex("_\\[([0-9,]+)]\\.mp4", RegexOption.IGNORE_CASE)
    private val qualityPlaceholderPattern = Regex("_%s\\.mp4", RegexOption.IGNORE_CASE)
    private val episodeCodePattern = Regex("(?i)S(\\d{1,2})E(\\d{1,3})")

    fun searchSources(
        apiBase: String,
        deviceId: String,
        token: String,
        query: String,
        kind: MediaKind,
        episodeCode: String? = null,
        corsProxyBase: String = FilmixDeviceAuth.DEFAULT_CORS_PROXY_BASE
    ): List<SourceResult> {
        require(query.isNotBlank()) { "Введите название для поиска Filmix" }
        require(token.isNotBlank()) { "Сначала привяжите устройство Filmix" }
        val safeBase = FilmixDeviceAuth.normalizeApiBase(apiBase, allowCustomHost = true)
        val safeProxy = FilmixDeviceAuth.normalizeCorsProxyBase(corsProxyBase)
        val safeDeviceId = deviceId.takeIf { it.matches(Regex("[a-fA-F0-9]{16}")) }
            ?: throw IllegalArgumentException("Некорректный ID устройства Filmix")

        val searchJson = getJson(
            buildApiUrl(safeBase, "search", safeDeviceId, token, mapOf("story" to query.trim().take(160))),
            safeBase,
            safeProxy
        )
        val entries = parseSearchEntries(searchJson)
            .asSequence()
            .filter {
                when (kind) {
                    MediaKind.MOVIE -> !it.isSeries
                    MediaKind.SERIES -> it.isSeries
                    MediaKind.VIDEO -> true
                }
            }
            .distinctBy { it.id }
            .take(MAX_SEARCH_ITEMS)
            .toList()
        if (entries.isEmpty()) return emptyList()

        val maxQuality = loadMaxQuality(safeBase, safeDeviceId, token, safeProxy)
        val parsed = buildList {
            entries.forEach { entry ->
                val post = runCatching {
                    getJson(
                        buildApiUrl(safeBase, "post/${entry.id}", safeDeviceId, token),
                        safeBase,
                        safeProxy
                    )
                }.getOrNull() ?: return@forEach
                addAll(
                    parsePostSources(
                        post = post,
                        entry = entry,
                        episodeCode = episodeCode,
                        maxQuality = maxQuality
                    )
                )
            }
        }.distinctBy { it.id }.take(80)

        if (parsed.none { livehashPattern.containsMatchIn(it.streamUrl.orEmpty()) }) return parsed
        val livehash = getLivehash(safeBase, safeDeviceId, token, safeProxy) ?: return parsed
        return parsed.map { source ->
            val url = source.streamUrl ?: return@map source
            source.copy(streamUrl = replaceLivehash(url, livehash))
        }
    }

    internal fun buildApiUrl(
        apiBase: String,
        endpoint: String,
        deviceId: String,
        token: String,
        query: Map<String, String> = emptyMap()
    ): String = FilmixDeviceAuth.buildAuthenticatedApiUrl(apiBase, endpoint, deviceId, token, query)

    internal fun replaceLivehash(url: String, livehash: String): String {
        require(livehash.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Некорректный Filmix livehash" }
        return livehashPattern.replace(url, "/s/$livehash/")
    }

    internal fun extractLivehash(post: JSONObject): String? =
        livehashPattern.find(post.toString())?.groupValues?.getOrNull(1)
            ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) }

    private fun getLivehash(
        apiBase: String,
        deviceId: String,
        token: String,
        corsProxyBase: String
    ): String? {
        val cacheKey = "$apiBase|$deviceId"
        val now = System.currentTimeMillis()
        if (cachedLivehashKey == cacheKey && now < livehashExpiresAt) {
            cachedLivehash?.let { return it }
        }
        synchronized(livehashLock) {
            val lockedNow = System.currentTimeMillis()
            if (cachedLivehashKey == cacheKey && lockedNow < livehashExpiresAt) {
                cachedLivehash?.let { return it }
            }
            val post = runCatching {
                getJson(buildApiUrl(apiBase, "post/$LIVEHASH_POST_ID", deviceId, token), apiBase, corsProxyBase)
            }.getOrNull() ?: return null
            val fresh = extractLivehash(post) ?: return null
            cachedLivehash = fresh
            cachedLivehashKey = cacheKey
            livehashExpiresAt = System.currentTimeMillis() + LIVEHASH_TTL_MS
            return fresh
        }
    }

    internal fun parseSearchEntries(json: JSONObject): List<FilmixSearchEntry> {
        val array = when {
            json.has("data") -> json.optJSONArray("data")
            json.has("results") -> json.optJSONArray("results")
            json.has("items") -> json.optJSONArray("items")
            else -> null
        } ?: JSONArray()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optInt("id").takeIf { it > 0 } ?: return@mapNotNull null
            val title = item.optString("title").trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val isSeries = item.optInt("section", -1) == 7
            val slug = item.optString("alt_name").trim()
            val page = slug.takeIf { it.matches(Regex("[\\p{L}\\p{N}._-]{1,180}")) }?.let {
                "${FilmixDeviceAuth.SITE_BASE}/${if (isSeries) "seria" else "film"}/$id-$it.html"
            }
            FilmixSearchEntry(
                id = id,
                title = title,
                originalTitle = item.optString("original_title").trim(),
                year = item.optInt("year").takeIf { it in 1888..2200 },
                isSeries = isSeries,
                pageUrl = page
            )
        }.take(100)
    }

    internal fun parsePostSources(
        post: JSONObject,
        entry: FilmixSearchEntry,
        episodeCode: String?,
        maxQuality: Int
    ): List<SourceResult> {
        val data = post.optJSONObject("data") ?: post
        val playerLinks = data.optJSONObject("player_links") ?: JSONObject()
        val title = data.optString("title").takeIf { it.isNotBlank() } ?: entry.title
        if (!entry.isSeries) {
            val movies = playerLinks.optJSONArray("movie") ?: JSONArray()
            return buildList {
                for (i in 0 until minOf(movies.length(), MAX_TRANSLATIONS)) {
                    val translation = movies.optJSONObject(i) ?: continue
                    addAll(
                        variantsToSources(
                            entry = entry,
                            title = title,
                            translation = translation.optString("translation").ifBlank { "Оригинал" },
                            link = translation.optString("link"),
                            explicitQualities = translation.optJSONArray("qualities"),
                            maxQuality = maxQuality,
                            episodeCode = null
                        )
                    )
                }
            }
        }

        val episodeMatch = episodeCodePattern.find(episodeCode.orEmpty())
        if (episodeMatch == null) {
            val translations = linkedSetOf<String>()
            val playlist = playerLinks.optJSONObject("playlist") ?: JSONObject()
            val seasonKeys = playlist.keys()
            while (seasonKeys.hasNext() && translations.size < MAX_TRANSLATIONS) {
                val season = playlist.optJSONObject(seasonKeys.next()) ?: continue
                val names = season.keys()
                while (names.hasNext() && translations.size < MAX_TRANSLATIONS) translations += names.next()
            }
            return translations.map { translation ->
                SourceResult(
                    id = "filmix:${entry.id}:$translation",
                    title = title,
                    providerName = "Filmix",
                    pageUrl = entry.pageUrl,
                    sourceType = MediaSourceType.FILMIX,
                    translationName = translation
                )
            }
        }

        val wantedSeason = episodeMatch.groupValues[1].toIntOrNull() ?: return emptyList()
        val wantedEpisode = episodeMatch.groupValues[2].toIntOrNull() ?: return emptyList()
        val playlist = playerLinks.optJSONObject("playlist") ?: return emptyList()
        val season = sequenceOf(
            wantedSeason.toString(),
            wantedSeason.toString().padStart(2, '0'),
            "s$wantedSeason",
            "season$wantedSeason"
        ).firstNotNullOfOrNull { playlist.optJSONObject(it) } ?: return emptyList()

        return buildList {
            val translations = season.keys()
            var count = 0
            while (translations.hasNext() && count++ < MAX_TRANSLATIONS) {
                val translationName = translations.next()
                val episode = findEpisode(season.opt(translationName), wantedEpisode, wantedSeason) ?: continue
                addAll(
                    variantsToSources(
                        entry = entry,
                        title = "$title • $episodeCode",
                        translation = translationName,
                        link = episode.optString("link"),
                        explicitQualities = episode.optJSONArray("qualities"),
                        maxQuality = maxQuality,
                        episodeCode = episodeCode
                    )
                )
            }
        }
    }

    internal fun parseQualityVariants(link: String, explicitQualities: JSONArray? = null): List<FilmixQualityVariant> {
        val safeLink = link.trim()
        if (safeLink.isBlank()) return emptyList()
        val parsedQualities = buildSet {
            if (explicitQualities != null) {
                for (index in 0 until explicitQualities.length()) {
                    explicitQualities.optInt(index).takeIf { it in 144..4320 }?.let(::add)
                }
            }
            qualityListPattern.find(safeLink)?.groupValues?.getOrNull(1)
                ?.split(',')
                ?.mapNotNull { it.toIntOrNull()?.takeIf { q -> q in 144..4320 } }
                ?.forEach(::add)
        }.sortedDescending()

        if (parsedQualities.isNotEmpty()) {
            return parsedQualities.mapNotNull { quality ->
                val resolved = when {
                    qualityListPattern.containsMatchIn(safeLink) ->
                        qualityListPattern.replace(safeLink, "_$quality.mp4")
                    qualityPlaceholderPattern.containsMatchIn(safeLink) ->
                        qualityPlaceholderPattern.replace(safeLink, "_$quality.mp4")
                    else -> safeLink
                }
                resolved.takeIf(::isHttpsUrl)?.let { FilmixQualityVariant(quality, it) }
            }
        }
        if (qualityListPattern.containsMatchIn(safeLink) || qualityPlaceholderPattern.containsMatchIn(safeLink)) {
            return emptyList()
        }
        return listOfNotNull(safeLink.takeIf(::isHttpsUrl)?.let { FilmixQualityVariant(null, it) })
    }

    internal fun maxQualityFromProfile(profile: JSONObject): Int {
        val data = profile.optJSONObject("user_data") ?: profile
        fun flag(name: String): Boolean = when (val value = data.opt(name)) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> value == "1" || value.equals("true", true)
            else -> false
        }
        return when {
            flag("is_pro_plus") -> 2160
            flag("is_pro") -> 1080
            else -> 720
        }
    }

    private fun variantsToSources(
        entry: FilmixSearchEntry,
        title: String,
        translation: String,
        link: String,
        explicitQualities: JSONArray?,
        maxQuality: Int,
        episodeCode: String?
    ): List<SourceResult> {
        val eligible = parseQualityVariants(link, explicitQualities)
            .filter { it.quality == null || it.quality <= maxQuality }
        return eligible.map { variant ->
            SourceResult(
                id = "filmix:${entry.id}:${episodeCode.orEmpty()}:$translation:${variant.quality ?: "auto"}",
                title = title,
                providerName = "Filmix",
                streamUrl = variant.url,
                pageUrl = entry.pageUrl,
                sourceType = MediaSourceType.FILMIX,
                qualityLabel = variant.quality?.let { "${it}p" },
                translationName = translation,
                requestHeaders = mapOf(
                    "Referer" to (entry.pageUrl ?: FilmixDeviceAuth.SITE_BASE),
                    "User-Agent" to USER_AGENT
                )
            )
        }
    }

    private fun findEpisode(value: Any?, episodeNumber: Int, seasonNumber: Int): JSONObject? {
        fun matches(item: JSONObject): Boolean {
            val explicit = listOf("episode", "episode_num", "number", "num")
                .firstNotNullOfOrNull { key -> item.optString(key).toIntOrNull() }
            if (explicit == episodeNumber) return true
            val link = item.optString("link")
            val code = episodeCodePattern.find(link)
            return code != null &&
                code.groupValues[1].toIntOrNull() == seasonNumber &&
                code.groupValues[2].toIntOrNull() == episodeNumber
        }

        return when (value) {
            is JSONArray -> {
                val entries = (0 until value.length()).mapNotNull { value.optJSONObject(it) }
                entries.firstOrNull(::matches) ?: entries.getOrNull(episodeNumber - 1)
            }
            is JSONObject -> {
                value.optJSONObject(episodeNumber.toString())
                    ?: value.optJSONObject(episodeNumber.toString().padStart(2, '0'))
                    ?: value.keys().asSequence()
                        .mapNotNull { key -> value.optJSONObject(key) }
                        .firstOrNull(::matches)
            }
            else -> null
        }
    }

    private fun loadMaxQuality(
        apiBase: String,
        deviceId: String,
        token: String,
        corsProxyBase: String
    ): Int = runCatching {
        maxQualityFromProfile(
            getJson(buildApiUrl(apiBase, "user_profile", deviceId, token), apiBase, corsProxyBase)
        )
    }.getOrDefault(720)

    private fun getJson(requestUrl: String, apiBase: String, corsProxyBase: String): JSONObject {
        val baseUri = URI(FilmixDeviceAuth.normalizeApiBase(apiBase, allowCustomHost = true))
        val targetUri = URI(requestUrl)
        val basePath = baseUri.rawPath.trimEnd('/')
        require(
            targetUri.scheme.equals("https", true) &&
                targetUri.host.equals(baseUri.host, true) &&
                (targetUri.port == -1 || targetUri.port == 443) &&
                targetUri.rawUserInfo == null &&
                targetUri.rawPath.startsWith("$basePath/")
        ) { "Запрос Filmix вышел за пределы выбранного API-сервера" }

        val proxy = FilmixDeviceAuth.normalizeCorsProxyBase(corsProxyBase)
        val outboundUrl = if (proxy.isBlank()) requestUrl
            else FilmixDeviceAuth.buildCorsProxyUrl(requestUrl, proxy)
        val connection = (URL(outboundUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 15_000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_RESPONSE_BYTES) {
                        throw IOException("Ответ Filmix API слишком большой")
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: ByteArray(0)
            if (status !in 200..299) throw IOException("Filmix API: HTTP $status")
            val parsed = JSONTokener(bytes.toString(Charsets.UTF_8)).nextValue()
            if (parsed is JSONObject) return parsed
            if (parsed is JSONArray) return JSONObject().put("data", parsed)
            throw IOException("Filmix API вернул неожиданный JSON")
        } finally {
            connection.disconnect()
        }
    }

    private fun isHttpsUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
    }.getOrDefault(false)
}
