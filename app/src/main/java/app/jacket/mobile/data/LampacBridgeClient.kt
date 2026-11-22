package app.jacket.mobile.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Client for a Lampac instance operated by the user.
 *
 * Uses Lampac's documented /lite/events -> /lifeevents -> /lite/{provider}?rjson=true flow.
 * API and playback URLs are pinned to the configured server origin. This client does not
 * automate provider sign-in, solve browser challenges, or alter subscription checks.
 */
data class LampacSearchOutcome(
    val results: List<SourceResult>,
    val failures: List<IndexerFailure>,
    val discoveredProviders: Int,
    val checkedProviders: Int
)

/** Dynamic Lampac federation; provider routes are discovered from the configured server. */
object LampacBridgeClient {
    private const val MAX_REDIRECTS = 3
    private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    private const val MAX_PROVIDER_ROUTES = 48
    private const val MAX_PARALLEL_PROVIDERS = 4
    private const val MAX_LIFE_POLLS = 20
    private const val MAX_TOTAL_RESULTS = 80
    private const val MIN_POLL_DELAY_MS = 350L
    private const val USER_AGENT = "Jacket-Android/2.8 (Lampac bridge)"

    private val redirectCodes = setOf(301, 302, 303, 307, 308)
    private val episodePattern = Regex("""(?i)S(\d{1,2})E(\d{1,3})""")

    fun normalizeBaseUrl(value: String): String {
        val supplied = value.trim()
        require(supplied.isNotBlank()) { "Укажите адрес своего Lampac-сервера" }
        val uri = runCatching { URI(supplied) }
            .getOrElse { throw IllegalArgumentException("Некорректный URL Lampac", it) }
        require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null) {
            "Укажите адрес сервера без логина и пароля в URL"
        }
        val isHttps = uri.scheme.equals("https", true)
        val isLoopbackHttp = uri.scheme.equals("http", true) &&
            uri.host.equals("127.0.0.1", true)
        require(isHttps || isLoopbackHttp) {
            "Для удалённого Lampac нужен HTTPS. HTTP разрешён только для 127.0.0.1."
        }
        require(uri.rawQuery.isNullOrBlank() && uri.rawFragment.isNullOrBlank() &&
            (uri.rawPath.isNullOrBlank() || uri.rawPath == "/")
        ) { "Укажите только адрес сервера, без пути, запроса и фрагмента" }
        val normalized = "${uri.scheme.lowercase()}://${uri.rawAuthority}".trimEnd('/')
        require(LocalNetwork.isAllowedUrl(normalized)) {
            "HTTP разрешён только для локального сервера. Для удалённого сервера используйте HTTPS."
        }
        return normalized
    }

    fun buildEventsUrl(settings: LampacSettings, item: CatalogItem): String {
        val base = normalizeBaseUrl(settings.baseUrl)
        require(item.providerId == "tmdb") { "Lampac bridge пока поддерживает карточки TMDB" }
        val tmdbId = item.providerItemId.toLongOrNull()?.takeIf { it > 0L }
            ?: throw IllegalArgumentException("У карточки отсутствует корректный TMDB ID")
        val params = linkedMapOf(
            "id" to tmdbId.toString(),
            "imdb_id" to "",
            "kinopoisk_id" to "0",
            "tmdb_id" to tmdbId.toString(),
            "title" to item.title,
            "original_title" to item.title,
            "source" to "tmdb",
            "year" to item.year.takeIf { it.matches(Regex("""\d{4}""")) }.orEmpty().ifBlank { "0" },
            "serial" to if (item.kind == MediaKind.SERIES) "1" else "0",
            "life" to "true",
            "islite" to "true",
            "external_ids" to "true"
        )
        addAccountParams(params, settings)
        return appendQuery("$base/lite/events", params)
    }

    fun buildLifeEventsUrl(
        settings: LampacSettings,
        item: CatalogItem,
        memkey: String,
        seen: Int
    ): String {
        val base = normalizeBaseUrl(settings.baseUrl)
        val tmdbId = item.providerItemId.toLongOrNull()?.takeIf { it > 0L }
            ?: throw IllegalArgumentException("У карточки отсутствует корректный TMDB ID")
        val params = linkedMapOf(
            "memkey" to memkey,
            "id" to tmdbId.toString(),
            "imdb_id" to "",
            "kinopoisk_id" to "0",
            "serial" to if (item.kind == MediaKind.SERIES) "1" else "0",
            "seen" to seen.coerceAtLeast(0).toString()
        )
        addAccountParams(params, settings)
        return appendQuery("$base/lifeevents", params)
    }

    /** Quick connection check against Lampac's public online.js endpoint. */
    fun testConnection(settings: LampacSettings): String {
        val base = normalizeBaseUrl(settings.baseUrl)
        val body = requestText("$base/online.js", base)
        if (body.isBlank()) throw IOException("Lampac ответил пустым ответом на /online.js")
        return "Lampac доступен по $base"
    }

    fun searchSources(
        settings: LampacSettings,
        item: CatalogItem,
        episodeCode: String? = null
    ): List<SourceResult> = searchDetailed(settings, item, episodeCode).results

    /**
     * Queries the live route list returned by Lampac. A bounded worker pool
     * isolates provider failures so one broken plugin cannot discard other streams.
     */
    fun searchDetailed(
        settings: LampacSettings,
        item: CatalogItem,
        episodeCode: String? = null
    ): LampacSearchOutcome {
        if (!settings.enabled || settings.baseUrl.isBlank() ||
            item.kind == MediaKind.VIDEO || item.providerId != "tmdb"
        ) return LampacSearchOutcome(emptyList(), emptyList(), 0, 0)

        val base = normalizeBaseUrl(settings.baseUrl)
        val kickoffText = requestText(buildEventsUrl(settings, item), base)
        val routes = LinkedHashMap<String, ProviderRoute>()
        val kickoffObject = runCatching { JSONObject(kickoffText) }.getOrNull()

        if (kickoffObject == null) {
            val kickoffArray = runCatching { JSONArray(kickoffText) }.getOrElse {
                throw IOException("Lampac вернул неподдерживаемый ответ /lite/events")
            }
            collectRoutes(kickoffArray, routes)
        } else {
            val memkey = kickoffObject.optString("memkey").takeIf(String::isNotBlank)
            if (memkey == null) {
                val message = kickoffObject.optString("msg").takeIf(String::isNotBlank)
                    ?: kickoffObject.optString("error").takeIf(String::isNotBlank)
                if (message != null) throw IOException(message.take(240))
                kickoffObject.optJSONArray("online")?.let { collectRoutes(it, routes) }
            } else {
                var seen = 0
                for (pollIndex in 0 until MAX_LIFE_POLLS) {
                    if (Thread.currentThread().isInterrupted) throw IOException("Поиск Lampac отменён")
                    val startedAt = System.nanoTime()
                    val update = JSONObject(
                        requestText(buildLifeEventsUrl(settings, item, memkey, seen), base)
                    )
                    val online = update.optJSONArray("online") ?: JSONArray()
                    collectRoutes(online, routes)
                    val serverMessage = update.optString("msg").takeIf(String::isNotBlank)
                    if (routes.isEmpty() && serverMessage != null) {
                        throw IOException(serverMessage.take(240))
                    }
                    seen = online.length()
                    if (update.optBoolean("ready", false) || update.optInt("tasks", -1) == 0) break
                    val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
                    val remaining = MIN_POLL_DELAY_MS - elapsedMs
                    if (remaining > 0) Thread.sleep(remaining)
                }
            }
        }

        if (routes.isEmpty()) return LampacSearchOutcome(emptyList(), emptyList(), 0, 0)
        val episode = episodePattern.find(episodeCode.orEmpty())
        val targetSeason = episode?.groupValues?.getOrNull(1)?.toIntOrNull()
        val targetEpisode = episode?.groupValues?.getOrNull(2)?.toIntOrNull()
        val selectedRoutes = routes.values.take(MAX_PROVIDER_ROUTES)
        val executor = Executors.newFixedThreadPool(MAX_PARALLEL_PROVIDERS)
        val results = ArrayList<SourceResult>()
        val failures = ArrayList<IndexerFailure>()
        var checked = 0

        try {
            val futures = selectedRoutes.map { route ->
                executor.submit(Callable {
                    runCatching {
                        resolveProviderRoute(
                            route = route,
                            settings = settings,
                            base = base,
                            item = item,
                            targetSeason = targetSeason,
                            targetEpisode = targetEpisode
                        )
                    }
                })
            }

            futures.forEachIndexed { index, future ->
                if (Thread.currentThread().isInterrupted) {
                    throw IOException("Поиск Lampac отменён")
                }
                val route = selectedRoutes[index]
                val outcome = try {
                    future.get()
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("Поиск Lampac отменён", interrupted)
                } catch (error: ExecutionException) {
                    Result.failure(error.cause ?: error)
                }
                checked++
                outcome.onSuccess { results.addAll(it) }.onFailure { error ->
                    val safeMessage = error.message.orEmpty()
                        .replace(Regex("[\\r\\n\\t]+"), " ")
                        .take(180)
                        .ifBlank { "Провайдер вернул неподдерживаемый ответ" }
                    failures += IndexerFailure(
                        indexerId = "lampac:" + providerRouteId(route),
                        indexerName = route.name,
                        message = safeMessage
                    )
                }
            }
        } finally {
            executor.shutdownNow()
            runCatching { executor.awaitTermination(250, TimeUnit.MILLISECONDS) }
        }

        return LampacSearchOutcome(
            results = results.distinctBy { it.magnetUri ?: it.streamUrl ?: it.id }.take(MAX_TOTAL_RESULTS),
            failures = failures,
            discoveredProviders = routes.size,
            checkedProviders = checked
        )
    }

    private fun resolveProviderRoute(
        route: ProviderRoute,
        settings: LampacSettings,
        base: String,
        item: CatalogItem,
        targetSeason: Int?,
        targetEpisode: Int?
    ): List<SourceResult> {
        if (Thread.currentThread().isInterrupted) throw IOException("Поиск Lampac отменён")
        val providerId = providerRouteId(route)
        val safePageUrl = stripSensitiveQueryParams(route.url, base)
        val providerUrl = appendJsonModeAndAuth(route.url, settings, base)
        val payload = JSONObject(requestText(providerUrl, base))
        return when (payload.optString("type").lowercase()) {
            "movie" -> if (item.kind == MediaKind.MOVIE) {
                parsePlayableEntries(
                    payload.optJSONArray("data"), safePageUrl, base, item,
                    route.name, null, null, providerId
                )
            } else emptyList()
            "season" -> {
                if (item.kind != MediaKind.SERIES || targetSeason == null || targetEpisode == null) {
                    emptyList()
                } else {
                    val seasonUrl = selectSeasonUrl(payload.optJSONArray("data"), targetSeason)
                        ?: return emptyList()
                    val episodeRequestUrl = appendJsonModeAndAuth(seasonUrl, settings, base)
                    val episodePayload = JSONObject(requestText(episodeRequestUrl, base))
                    if (episodePayload.optString("type").equals("episode", true)) {
                        parsePlayableEntries(
                            episodePayload.optJSONArray("data"),
                            stripSensitiveQueryParams(seasonUrl, base),
                            base,
                            item,
                            route.name,
                            targetSeason,
                            targetEpisode,
                            providerId
                        )
                    } else emptyList()
                }
            }
            "episode" -> if (item.kind == MediaKind.SERIES && targetSeason != null && targetEpisode != null) {
                parsePlayableEntries(
                    payload.optJSONArray("data"),
                    safePageUrl,
                    base,
                    item,
                    route.name,
                    targetSeason,
                    targetEpisode,
                    providerId
                )
            } else emptyList()
            else -> emptyList()
        }
    }

    private fun providerRouteId(route: ProviderRoute): String {
        val path = runCatching { URI(route.url).path.orEmpty() }.getOrDefault("")
        val value = path.substringAfterLast('/').ifBlank { route.name.lowercase() }
        return value.lowercase()
            .filter { it.isLetterOrDigit() || it in "-_." }
            .take(80)
            .ifBlank { "provider" }
    }

    internal fun isSameOrigin(candidate: String, baseUrl: String): Boolean = runCatching {
        val base = URI(normalizeBaseUrl(baseUrl))
        val uri = base.resolve(candidate)
        uri.scheme.equals(base.scheme, true) &&
            uri.host.equals(base.host, true) &&
            effectivePort(uri) == effectivePort(base) &&
            uri.rawUserInfo == null &&
            LocalNetwork.isAllowedUrl(uri.toString())
    }.getOrDefault(false)

    internal fun validateSameOriginUrl(candidate: String, baseUrl: String): String {
        val base = normalizeBaseUrl(baseUrl)
        val uri = runCatching { URI(base).resolve(candidate) }
            .getOrElse { throw IOException("Lampac вернул некорректную ссылку", it) }
        require(isSameOrigin(uri.toString(), base)) {
            "Lampac вернул ссылку на другой домен; внешний переход заблокирован."
        }
        return uri.toString()
    }

    internal fun parsePlayableEntries(
        data: JSONArray?,
        providerUrl: String,
        baseUrl: String,
        item: CatalogItem,
        providerName: String,
        targetSeason: Int?,
        targetEpisode: Int?,
        providerId: String? = null
    ): List<SourceResult> {
        if (data == null) return emptyList()
        val result = ArrayList<SourceResult>()
        for (index in 0 until minOf(data.length(), 80)) {
            val entry = data.optJSONObject(index) ?: continue
            if (targetSeason != null && entry.optInt("s", -1) != targetSeason) continue
            if (targetEpisode != null && entry.optInt("e", -1) != targetEpisode) continue
            val stream = entry.optString("stream").takeIf(String::isNotBlank) ?: continue
            val safeStream = runCatching { validateSameOriginUrl(stream, baseUrl) }.getOrNull() ?: continue
            val translation = entry.optString("voice_name")
                .ifBlank { entry.optString("translate") }
                .takeIf(String::isNotBlank)
            val quality = entry.optString("maxquality").takeIf(String::isNotBlank)
                ?: entry.optJSONObject("quality")?.keys()?.asSequence()?.firstOrNull()
            val title = buildString {
                append(item.title)
                if (targetSeason != null && targetEpisode != null) {
                    append(" • S")
                    append(targetSeason.toString().padStart(2, '0'))
                    append("E")
                    append(targetEpisode.toString().padStart(2, '0'))
                }
                if (!translation.isNullOrBlank()) append(" • ").append(translation)
                if (!quality.isNullOrBlank()) append(" • ").append(quality)
            }
            result += SourceResult(
                id = "lampac:" + providerName.lowercase() + ":" + stableStreamKey(safeStream),
                title = title,
                providerName = providerName,
                streamUrl = safeStream,
                pageUrl = providerUrl,
                indexerId = "lampac:" + (providerId ?: providerSlugFromUrl(providerUrl)),
                tracker = providerName,
                sourceType = MediaSourceType.LAMPAC,
                qualityLabel = quality,
                translationName = translation,
                requestHeaders = safePlaybackHeaders(entry.optJSONObject("headers"))
            )
        }
        return result
    }

    /** Stable provider identity derived from the route path, never from a query parameter. */
    internal fun providerSlugFromUrl(providerUrl: String): String {
        val path = runCatching { URI(providerUrl).path.orEmpty() }.getOrDefault("")
        val route = path.split('/').lastOrNull { it.isNotBlank() }.orEmpty()
        return route.lowercase()
            .filter { it.isLetterOrDigit() || it in "-_." }
            .take(80)
            .ifBlank { "provider" }
    }

    internal fun selectSeasonUrl(data: JSONArray?, targetSeason: Int): String? {
        if (data == null) return null
        for (index in 0 until minOf(data.length(), 80)) {
            val entry = data.optJSONObject(index) ?: continue
            if (entry.optInt("id", -1) == targetSeason ||
                entry.optString("name").contains("$targetSeason сезон", ignoreCase = true)
            ) {
                return entry.optString("url").takeIf(String::isNotBlank)
            }
        }
        return null
    }

    private data class ProviderRoute(val name: String, val url: String)

    private fun collectRoutes(array: JSONArray, target: LinkedHashMap<String, ProviderRoute>) {
        for (index in 0 until minOf(array.length(), 100)) {
            val entry = array.optJSONObject(index) ?: continue
            val url = entry.optString("url").takeIf(String::isNotBlank) ?: continue
            if (!entry.optBoolean("show", true)) continue
            val name = entry.optString("name").ifBlank { entry.optString("balanser") }
                .ifBlank { entry.optString("plugin") }
                .ifBlank { "Источник " + (index + 1) }
            target.putIfAbsent(url, ProviderRoute(name.take(100), url))
        }
    }

    /**
     * Provider requests need account parameters, but page metadata must never retain
     * account identifiers or credentials that can be exposed in links or diagnostics.
     */
    internal fun stripSensitiveQueryParams(url: String, baseUrl: String): String {
        val safe = validateSameOriginUrl(url, baseUrl)
        val uri = URI(safe)
        val sensitive = setOf("account_email", "uid", "token", "password", "cookie", "api_key")
        val query = uri.rawQuery.orEmpty().split('&').filter { part ->
            part.isNotBlank() && decode(part.substringBefore('=')).lowercase() !in sensitive
        }.joinToString("&")
        val path = uri.rawPath.orEmpty()
        return buildString {
            append(uri.scheme).append("://").append(uri.rawAuthority).append(path)
            if (query.isNotBlank()) append('?').append(query)
        }
    }

    private fun appendJsonModeAndAuth(url: String, settings: LampacSettings, baseUrl: String): String {
        val safe = validateSameOriginUrl(url, baseUrl)
        val params = linkedMapOf<String, String>()
        if (!hasQueryParam(safe, "rjson")) params["rjson"] = "true"
        addAccountParams(params, settings)
        return appendQuery(safe, params)
    }

    private fun addAccountParams(target: LinkedHashMap<String, String>, settings: LampacSettings) {
        settings.accountEmail.trim().take(254).takeIf(String::isNotBlank)?.let { target["account_email"] = it }
        settings.uid.trim().take(128).takeIf(String::isNotBlank)?.let { target["uid"] = it }
        settings.token.take(1024).takeIf(String::isNotBlank)?.let { target["token"] = it }
    }

    private fun appendQuery(url: String, params: Map<String, String>): String {
        val additions = params.filter { (key, value) ->
            value.isNotEmpty() && !hasQueryParam(url, key)
        }
        if (additions.isEmpty()) return url
        val separator = if (url.contains('?')) '&' else '?'
        return url + separator + additions.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
    }

    private fun hasQueryParam(url: String, name: String): Boolean {
        val query = runCatching { URI(url).rawQuery.orEmpty() }.getOrDefault("")
        return query.split('&').any { part -> decode(part.substringBefore('=')) == name }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun decode(value: String): String =
        runCatching { java.net.URLDecoder.decode(value, Charsets.UTF_8.name()) }.getOrDefault(value)

    private fun stableStreamKey(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(it) }

    private fun safePlaybackHeaders(source: JSONObject?): Map<String, String> {
        if (source == null) return emptyMap()
        val result = LinkedHashMap<String, String>()
        source.keys().forEach { key ->
            if (key.equals("Referer", true) || key.equals("User-Agent", true)) {
                val value = source.optString(key).trim()
                if (value.isNotBlank() && value.length <= 1_000 &&
                    value.none { it == '\r' || it == '\n' }
                ) {
                    result[if (key.equals("Referer", true)) "Referer" else "User-Agent"] = value
                }
            }
        }
        return result
    }

    private fun requestText(startUrl: String, baseUrl: String): String {
        var currentUrl = validateSameOriginUrl(startUrl, baseUrl)
        for (redirect in 0..MAX_REDIRECTS) {
            if (Thread.currentThread().isInterrupted) throw IOException("Запрос Lampac отменён")
            val connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 5_000
                instanceFollowRedirects = false
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json, text/plain, */*")
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Cache-Control", "no-cache")
            }
            try {
                val status = connection.responseCode
                if (status in redirectCodes) {
                    if (redirect == MAX_REDIRECTS) throw IOException("Слишком много перенаправлений Lampac")
                    val location = connection.getHeaderField("Location")
                        ?: throw IOException("Lampac перенаправил запрос без адреса (HTTP $status)")
                    currentUrl = validateSameOriginUrl(URI(currentUrl).resolve(location).toString(), baseUrl)
                    continue
                }
                if (status !in 200..299) throw IOException("Lampac вернул HTTP $status")
                val bytes = connection.inputStream.use { readLimited(it, MAX_RESPONSE_BYTES) }
                return bytes.toString(Charsets.UTF_8)
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Не удалось получить ответ от Lampac")
    }

    private fun readLimited(input: java.io.InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > maxBytes) throw IOException("Ответ Lampac слишком большой")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun effectivePort(uri: URI): Int = when {
        uri.port >= 0 -> uri.port
        uri.scheme.equals("https", true) -> 443
        else -> 80
    }
}
