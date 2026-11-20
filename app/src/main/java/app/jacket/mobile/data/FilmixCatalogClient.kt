package app.jacket.mobile.data

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder

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

/**
 * Direct Filmix v2 client authenticated with the user's device token.
 *
 * It deliberately uses only the API base explicitly selected in settings:
 * no mirror discovery, proxy, cleartext fallback or TLS relaxation.
 */
object FilmixCatalogClient {
    private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
    private const val MAX_SEARCH_ITEMS = 8
    private const val MAX_TRANSLATIONS = 12
    private const val MAX_EPISODES_PER_SEASON = 100
    private const val USER_AGENT = "JacketAndroid/2.7 (Filmix v2 client)"
    private const val LIVEHASH_POST_ID = 171042
    private const val LIVEHASH_TTL_MS = 2 * 60 * 60 * 1000L
    private val livehashPattern = Regex("/s/([^/]+)/")
    @Volatile private var cachedLivehash: String? = null
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
        episodeCode: String? = null
    ): List<SourceResult> {
        require(query.isNotBlank()) { "Введите название для поиска Filmix" }
        require(token.isNotBlank()) { "Сначала привяжите устройство Filmix" }
        val safeBase = FilmixDeviceAuth.normalizeApiBase(apiBase)
        val safeDeviceId = deviceId.takeIf { it.matches(Regex("[a-fA-F0-9]{16}")) }
            ?: throw IllegalArgumentException("Некорректный ID устройства Filmix")

        val searchJson = getJson(
            buildApiUrl(safeBase, "search", safeDeviceId, token, mapOf("story" to query.trim().take(160)))
        )
        val entries = parseSearchEntries(searchJson)
            .asSequence()
            .filter { when (kind) {
                MediaKind.MOVIE -> !it.isSeries
                MediaKind.SERIES -> it.isSeries
                MediaKind.VIDEO -> true
            } }
            .distinctBy { it.id }
            .take(MAX_SEARCH_ITEMS)
            .toList()
        if (entries.isEmpty()) return emptyList()

        val maxQuality = loadMaxQuality(safeBase, safeDeviceId, token)
        val parsed = buildList {
            entries.forEach { entry ->
                val post = runCatching {
                    getJson(buildApiUrl(safeBase, "post/${entry.id}", safeDeviceId, token))
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

        // Some Filmix CDN URLs contain a rotating /s/{livehash}/ prefix. Refresh
        // it from the public reference post only when a selected result needs it.
        if (parsed.none { livehashPattern.containsMatchIn(it.streamUrl.orEmpty()) }) return parsed
        val livehash = getLivehash(safeBase, safeDeviceId, token) ?: return parsed
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

    private fun getLivehash(apiBase: String, deviceId: String, token: String): String? {
        val now = System.currentTimeMillis()
        cachedLivehash?.takeIf { now < livehashExpiresAt }?.let { return it }
        synchronized(livehashLock) {
            val lockedNow = System.currentTimeMillis()
            cachedLivehash?.takeIf { lockedNow < livehashExpiresAt }?.let { return it }
            val post = runCatching {
                getJson(buildApiUrl(apiBase, "post/$LIVEHASH_POST_ID", deviceId, token))
            }.getOrNull() ?: return null
            val fresh = extractLivehash(post) ?: return null
            cachedLivehash = fresh
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
            val section = item.optInt("section", -1)
            val isSeries = section == 7
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
                    val name = translation.optString("translation").ifBlank { "Оригинал" }
                    val link = translation.optString("link")
                    addAll(
                        variantsToSources(
                            entry = entry,
                            title = title,
                            translation = name,
                            link = link,
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
        val season = playlist.optJSONObject(wantedSeason.toString()) ?: return emptyList()
        return buildList {
            val translations = season.keys()
            var count = 0
            while (translations.hasNext() && count++ < MAX_TRANSLATIONS) {
                val translationName = translations.next()
                val value = season.opt(translationName)
                val episode = findEpisode(value, wantedEpisode) ?: continue
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
                    val quality = explicitQualities.optInt(index).takeIf { it in 144..4320 }
                    if (quality != null) add(quality)
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
        fun flag(name: String): Boolean {
            val value = profile.opt(name)
            return when (value) {
                is Boolean -> value
                is Number -> value.toInt() != 0
                is String -> value == "1" || value.equals("true", true)
                else -> false
            }
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
        val variants = parseQualityVariants(link, explicitQualities)
        if (variants.isEmpty()) return emptyList()
        val eligible = variants.filter { it.quality == null || it.quality <= maxQuality }
        return eligible.map { variant ->
            val qualityText = variant.quality?.let { "${it}p" }
            SourceResult(
                id = "filmix:${entry.id}:${episodeCode.orEmpty()}:$translation:${variant.quality ?: "auto"}",
                title = title,
                providerName = "Filmix",
                streamUrl = variant.url,
                pageUrl = entry.pageUrl,
                sourceType = MediaSourceType.FILMIX,
                qualityLabel = qualityText,
                translationName = translation,
                requestHeaders = mapOf(
                    "Referer" to (entry.pageUrl ?: FilmixDeviceAuth.SITE_BASE),
                    "User-Agent" to USER_AGENT
                )
            )
        }
    }

    private fun findEpisode(value: Any?, episodeNumber: Int): JSONObject? = when (value) {
        is JSONArray -> value.optJSONObject(episodeNumber - 1)
        is JSONObject -> value.optJSONObject(episodeNumber.toString())
            ?: value.optJSONObject(episodeNumber.toString().padStart(2, '0'))
        else -> null
    }

    private fun loadMaxQuality(apiBase: String, deviceId: String, token: String): Int = runCatching {
        maxQualityFromProfile(getJson(buildApiUrl(apiBase, "user_profile", deviceId, token)))
    }.getOrDefault(720)

    private fun getJson(requestUrl: String): JSONObject {
        val uri = URI(requestUrl)
        require(uri.scheme.equals("https", true) && uri.host in setOf("filmixapp.cyou", "fxapp.biz")) {
            "API Filmix должен использовать выбранный HTTPS-сервер"
        }
        val connection = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 15_000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IOException("Filmix API: HTTP $status (${uri.host}, ${uri.path})")
            }
            val declaredSize = connection.getHeaderFieldLong("Content-Length", -1L)
            require(declaredSize <= MAX_RESPONSE_BYTES) { "Ответ Filmix API слишком большой" }
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_RESPONSE_BYTES) throw IOException("Ответ Filmix API слишком большой")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
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
