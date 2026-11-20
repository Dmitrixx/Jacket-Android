package app.jacket.mobile.data

import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder

data class HdRezkaSearchEntry(val title: String, val pageUrl: String, val posterUrl: String?, val description: String)
data class HdRezkaTranslator(val id: String, val name: String)
data class HdRezkaEpisodeIds(val seasonId: String, val episodeId: String)
data class HdRezkaQualityVariant(val qualityLabel: String?, val url: String)

/**
 * Direct HTTPS adapter for one mirror explicitly entered by the user.
 * No mirror discovery/rotation, proxy, TLS spoofing, CAPTCHA solver or cleartext fallback.
 * Session cookies are sent only to the exact configured mirror origin, never to a CDN.
 */
object HdRezkaCatalogClient {
    private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
    private const val MAX_SEARCH_RESULTS = 8
    private const val MAX_TRANSLATORS = 8
    private const val MAX_REDIRECTS = 4
    private const val USER_AGENT = "Jacket-Android/2.7 (Android media client)"
    private val qualityPattern = Regex("\\[([^\\]]+)](https://[^,\\s]+)", RegexOption.IGNORE_CASE)
    private val directUrlPattern = Regex("https://[^\\s,\\]<>\\\"']+", RegexOption.IGNORE_CASE)
    private val episodeCodePattern = Regex("(?i)S(\\d{1,2})E(\\d{1,3})")
    private val redirectCodes = setOf(301, 302, 303, 307, 308)

    fun searchSources(settings: HdRezkaSettings, query: String, kind: MediaKind, episodeCode: String? = null): List<SourceResult> {
        require(query.isNotBlank()) { "Введите название для поиска HDRezka" }
        val mirror = HdRezkaLoginClient.validateMirror(settings.mirror)
        val searchUrl = "$mirror/search/?do=search&subaction=search&q=${encode(query.trim().take(160))}"
        val html = requestText(mirror, searchUrl, "GET", null, "$mirror/", settings)
        val entries = parseSearchEntries(html, mirror).distinctBy { it.pageUrl }.take(MAX_SEARCH_RESULTS)

        return buildList {
            entries.forEach { entry ->
                val page = runCatching { requestText(mirror, entry.pageUrl, "GET", null, "$mirror/", settings) }
                    .getOrNull() ?: return@forEach
                val translators = parseTranslators(page).take(MAX_TRANSLATORS)
                val postId = parsePostId(page, entry.pageUrl) ?: return@forEach
                val isSeries = isSeriesPage(page)
                if (kind == MediaKind.MOVIE && isSeries) return@forEach
                if (kind == MediaKind.SERIES && !isSeries) return@forEach

                if (!isSeries) {
                    val options = translators.ifEmpty { listOf(HdRezkaTranslator("0", "Оригинал")) }
                    options.forEach { translator ->
                        val response = runCatching {
                            ajax(mirror, entry.pageUrl, settings, linkedMapOf(
                                "action" to "get_movie", "id" to postId, "translator_id" to translator.id
                            ))
                        }.getOrNull() ?: return@forEach
                        addAll(parseStreamSources(response, entry, translator, entry.pageUrl, null))
                    }
                } else {
                    val match = episodeCodePattern.find(episodeCode.orEmpty())
                    if (match == null) {
                        add(SourceResult(
                            id = "hdrezka:$postId",
                            title = entry.title,
                            providerName = "HDRezka",
                            pageUrl = entry.pageUrl,
                            sourceType = MediaSourceType.HDREZKA
                        ))
                    } else {
                        val wantedSeason = match.groupValues[1].toIntOrNull() ?: return@forEach
                        val wantedEpisode = match.groupValues[2].toIntOrNull() ?: return@forEach
                        val options = translators.ifEmpty { listOf(HdRezkaTranslator("0", "Оригинал")) }
                        options.forEach { translator ->
                            val episodeIds = runCatching {
                                resolveEpisodeIds(
                                    ajax(mirror, entry.pageUrl, settings, linkedMapOf(
                                        "action" to "get_episodes", "id" to postId, "translator_id" to translator.id
                                    )),
                                    wantedSeason,
                                    wantedEpisode
                                )
                            }.getOrNull() ?: return@forEach
                            val response = runCatching {
                                ajax(mirror, entry.pageUrl, settings, linkedMapOf(
                                    "action" to "get_stream",
                                    "id" to postId,
                                    "translator_id" to translator.id,
                                    "season" to episodeIds.seasonId,
                                    "episode" to episodeIds.episodeId
                                ))
                            }.getOrNull() ?: return@forEach
                            addAll(parseStreamSources(response, entry, translator, entry.pageUrl, episodeCode))
                        }
                    }
                }
            }
        }.distinctBy { it.id }.take(80)
    }

    internal fun parseSearchEntries(html: String, mirror: String): List<HdRezkaSearchEntry> {
        val document = Jsoup.parse(html, mirror)
        return document.select(".b-content__inline_item").mapNotNull { card ->
            val anchor = card.selectFirst("a.b-content__inline_item-link")
                ?: card.selectFirst("a[href]") ?: return@mapNotNull null
            val page = anchor.absUrl("href").takeIf { it.isNotBlank() && isSameOrigin(it, mirror) }
                ?: return@mapNotNull null
            val title = anchor.attr("title").ifBlank { anchor.text() }.trim().takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val image = card.selectFirst(".b-content__inline_item-cover img, img")
            val poster = image?.let { it.absUrl("src").ifBlank { it.absUrl("data-src") } }?.takeIf(::isHttpsUrl)
            val details = card.selectFirst(".b-content__inline_item-details, .b-content__inline_item-info")
                ?.text()?.trim().orEmpty().take(500)
            HdRezkaSearchEntry(title, page, poster, details)
        }.take(100)
    }

    internal fun parseTranslators(html: String): List<HdRezkaTranslator> =
        Jsoup.parse(html).select("#translators-list [data-translator_id], [data-translator_id]")
            .mapNotNull { el ->
                val id = el.attr("data-translator_id").trim().takeIf { it.matches(Regex("[0-9]{1,12}")) }
                    ?: return@mapNotNull null
                val name = el.attr("title").ifBlank { el.text() }.trim().take(100).ifBlank { "Озвучка $id" }
                HdRezkaTranslator(id, name)
            }.distinctBy { it.id }.take(MAX_TRANSLATORS)

    internal fun parsePostId(html: String, pageUrl: String): String? {
        val doc = Jsoup.parse(html, pageUrl)
        val input = doc.selectFirst("input#post_id, input[name=post_id]")?.attr("value")
            ?.takeIf { it.matches(Regex("[0-9]{1,12}")) }
        if (input != null) return input
        val pagePath = runCatching { URI(pageUrl).path.orEmpty() }.getOrDefault("")
        return Regex("/(?:film|serial|cartoon|anime)/([0-9]{1,12})-").find(pagePath)?.groupValues?.getOrNull(1)
    }

    internal fun isSeriesPage(html: String): Boolean {
        val doc = Jsoup.parse(html)
        return doc.selectFirst("meta[property=og:type]")?.attr("content")
            .equals("video.tv_series", true) || doc.select(".b-simple_episode__item").isNotEmpty()
    }

    internal fun resolveEpisodeIds(response: JSONObject, wantedSeason: Int, wantedEpisode: Int): HdRezkaEpisodeIds? {
        val html = response.optString("episodes")
            .ifBlank { response.optJSONObject("data")?.optString("episodes").orEmpty() }
            .ifBlank { response.optString("message") }
        if (html.isBlank()) return null
        val items = Jsoup.parse(html).select(".b-simple_episode__item[data-season_id][data-episode_id]")
        if (items.isEmpty()) return null
        val seasons = items.map { it.attr("data-season_id") }
            .filter { it.matches(Regex("[0-9]{1,12}")) }.distinct()
        val seasonId = if (seasons.contains(wantedSeason.toString())) wantedSeason.toString()
        else seasons.mapNotNull(String::toIntOrNull).sorted().getOrNull(wantedSeason - 1)?.toString()
            ?: return null
        val seasonItems = items.filter { it.attr("data-season_id") == seasonId }
        val episode = seasonItems.firstOrNull {
            it.attr("data-episode_id") == wantedEpisode.toString() ||
                it.attr("data-episode-number") == wantedEpisode.toString()
        } ?: seasonItems.getOrNull(wantedEpisode - 1) ?: return null
        val episodeId = episode.attr("data-episode_id").takeIf { it.matches(Regex("[0-9]{1,12}")) } ?: return null
        return HdRezkaEpisodeIds(seasonId, episodeId)
    }

    internal fun parseQualityVariants(raw: String): List<HdRezkaQualityVariant> {
        val value = raw.trim()
        if (value.isBlank() || value.startsWith("#2")) return emptyList()
        val variants = qualityPattern.findAll(value).mapNotNull { m ->
            val label = m.groupValues[1].trim().take(32)
            val url = m.groupValues[2].trim().trimEnd(')', ';')
            url.takeIf(::isHttpsUrl)?.let { HdRezkaQualityVariant(label, it) }
        }.distinctBy { it.url }.toList()
        if (variants.isNotEmpty()) return variants
        val direct = directUrlPattern.find(value)?.value?.trim()?.trimEnd(')', ';')
        return listOfNotNull(direct?.takeIf(::isHttpsUrl)?.let { HdRezkaQualityVariant(null, it) })
    }

    internal fun parseStreamSources(
        response: JSONObject,
        entry: HdRezkaSearchEntry,
        translator: HdRezkaTranslator,
        pageUrl: String,
        episodeLabel: String?
    ): List<SourceResult> {
        val data = response.optJSONObject("data") ?: response.optJSONObject("response") ?: response
        val variants = parseQualityVariants(data.optString("url").ifBlank { data.optString("stream") })
        val subtitles = parseSubtitles(data)
        return variants.map { variant ->
            val title = entry.title + if (episodeLabel.isNullOrBlank()) "" else " • $episodeLabel"
            SourceResult(
                id = "hdrezka:${entry.pageUrl.hashCode()}:${translator.id}:${episodeLabel.orEmpty()}:${variant.qualityLabel ?: variant.url.hashCode()}",
                title = title,
                providerName = "HDRezka",
                streamUrl = variant.url,
                pageUrl = pageUrl,
                sourceType = MediaSourceType.HDREZKA,
                qualityLabel = variant.qualityLabel,
                translationName = translator.name,
                subtitles = subtitles,
                requestHeaders = mapOf("Referer" to pageUrl)
            )
        }
    }

    internal fun parseSubtitles(data: JSONObject): List<MediaSubtitle> {
        val raw = data.optString("subtitle").ifBlank { data.optString("subtitles") }
        if (raw.isBlank()) return emptyList()
        val languages = data.optJSONObject("subtitle_lns") ?: JSONObject()
        val defaultLanguage = data.optString("subtitle_def").takeIf { it.isNotBlank() }
        val matcher = Regex("\\[([^\\]]+)](https://[^,\\s]+)", RegexOption.IGNORE_CASE)
        val parsed = matcher.findAll(raw).mapNotNull { m ->
            val language = m.groupValues[1].trim().take(12)
            val url = m.groupValues[2].trim()
            if (!isHttpsUrl(url)) return@mapNotNull null
            MediaSubtitle(
                url = url,
                mimeType = if (url.substringBefore('?').endsWith(".srt", true)) "application/x-subrip" else "text/vtt",
                language = language,
                label = languages.optString(language).takeIf { it.isNotBlank() } ?: language
            )
        }.toMutableList()
        if (parsed.isEmpty()) {
            directUrlPattern.find(raw)?.value?.let { url ->
                if (isHttpsUrl(url)) parsed += MediaSubtitle(
                    url = url,
                    mimeType = if (url.substringBefore('?').endsWith(".srt", true)) "application/x-subrip" else "text/vtt",
                    language = defaultLanguage,
                    label = defaultLanguage?.let { languages.optString(it).ifBlank { it } } ?: "Субтитры"
                )
            }
        }
        return parsed.distinctBy { it.url }.take(12)
    }

    private fun ajax(mirror: String, pageUrl: String, settings: HdRezkaSettings, fields: Map<String, String>): JSONObject {
        val body = fields.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        val text = requestText(
            mirror, "$mirror/ajax/get_cdn_series/", "POST", body, pageUrl, settings,
            "application/x-www-form-urlencoded; charset=UTF-8"
        )
        return runCatching { JSONObject(text) }
            .getOrElse { throw IOException("HDRezka вернула некорректный ответ медиаплеера") }
    }

    private fun requestText(
        mirror: String,
        url: String,
        method: String,
        body: String?,
        referer: String,
        settings: HdRezkaSettings = HdRezkaSettings(),
        contentType: String? = null
    ): String {
        require(isSameOrigin(url, mirror)) { "HDRezka перенаправила запрос на другой домен; Cookie не отправлена." }
        var currentUrl = url
        var currentMethod = method
        var currentBody = body
        repeat(MAX_REDIRECTS + 1) {
            val uri = URI(currentUrl)
            val connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = currentMethod
                connectTimeout = 10_000
                readTimeout = 18_000
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/json, text/plain, */*")
                setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("X-Requested-With", "XMLHttpRequest")
                setRequestProperty("Origin", mirror)
                setRequestProperty("Referer", referer)
                if (HdRezkaLoginClient.hasAuthenticatedCookie(settings.cookie) &&
                    uri.host.equals(URI(mirror).host, true)
                ) setRequestProperty("Cookie", settings.cookie)
                if (currentBody != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", contentType ?: "application/x-www-form-urlencoded")
                }
            }
            try {
                if (currentBody != null) connection.outputStream.use { it.write(currentBody.toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                if (status in redirectCodes) {
                    val location = connection.getHeaderField("Location")
                        ?: throw IOException("HDRezka вернула перенаправление без адреса")
                    val next = URL(URL(currentUrl), location).toString()
                    if (!isSameOrigin(next, mirror)) {
                        throw IOException("HDRezka перенаправила запрос на другой домен. Автоматический переход запрещён.")
                    }
                    if (status in setOf(301, 302, 303)) {
                        currentMethod = "GET"
                        currentBody = null
                    }
                    currentUrl = next
                    return@repeat
                }
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val text = (stream?.use { readLimited(it, MAX_RESPONSE_BYTES) } ?: ByteArray(0)).toString(Charsets.UTF_8)
                if (status == 403 || HdRezkaLoginClient.containsBrowserChallenge(text)) {
                    throw IOException(
                        "HDRezka требует браузерную проверку или заблокировала прямой доступ. " +
                            "Jacket не обходит CAPTCHA/Anubis; откройте сайт обычным браузером."
                    )
                }
                if (status !in 200..299) throw IOException("HDRezka ответила HTTP $status на ${uri.path}")
                return text
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Слишком много перенаправлений HDRezka")
    }

    private fun readLimited(input: java.io.InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > maxBytes) throw IOException("Ответ HDRezka слишком большой")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun isSameOrigin(url: String, mirror: String): Boolean = runCatching {
        val target = URI(url)
        val base = URI(mirror)
        target.scheme.equals("https", true) && target.host.equals(base.host, true) &&
            (if (target.port >= 0) target.port else 443) == (if (base.port >= 0) base.port else 443)
    }.getOrDefault(false)

    private fun isHttpsUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
    }.getOrDefault(false)

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}
