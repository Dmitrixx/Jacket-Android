package app.jacket.mobile.data

import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.Base64

data class HdRezkaSearchEntry(val title: String, val pageUrl: String, val posterUrl: String?, val description: String)
data class HdRezkaTranslator(
    val id: String,
    val name: String,
    val isCamrip: String = "0",
    val isAds: String = "0",
    val isDirector: String = "0",
    val isPremium: Boolean = false
)
data class HdRezkaPageContext(
    val postId: String,
    val translators: List<HdRezkaTranslator>,
    val favs: String,
    val isSeries: Boolean,
    val defaultSeasonId: String? = null,
    val defaultEpisodeId: String? = null
)
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
    private const val MAX_OBFUSCATED_URL_CHARS = 512 * 1024
    private const val USER_AGENT = "Jacket-Android/2.7 (Android media client)"
    private val qualityPattern = Regex("""^\[([^\]]+)](.+)$""", RegexOption.IGNORE_CASE)
    private val encodedTrashTokens = listOf(
        "JCQhIUAkJEBeIUAjJCRA",
        "QEBAQEAhIyMhXl5e",
        "IyMjI14hISMjIUBA",
        "Xl5eIUAjIyEhIyM=",
        "JCQjISFAIyFAIyM=",
    )
    private val directUrlPattern = Regex("https://[^\\s,\\]<>\\\"']+", RegexOption.IGNORE_CASE)
    private val episodeCodePattern = Regex("(?i)S(\\d{1,2})E(\\d{1,3})")
    private val seriesInitPattern = Regex("""initCDNSeriesEvents\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)(?:\s*,|\s*\))""")
    private val movieInitPattern = Regex("""initCDNMoviesEvents\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)(?:\s*,|\s*\))""")
    private val redirectCodes = setOf(301, 302, 303, 307, 308)

    fun searchSources(
        settings: HdRezkaSettings,
        query: String,
        kind: MediaKind,
        episodeCode: String? = null
    ): List<SourceResult> {
        require(query.isNotBlank()) { "Введите название для поиска HDRezka" }
        val mirror = HdRezkaLoginClient.validateMirror(settings.mirror)
        val searchUrl = buildSearchUrl(mirror, query)
        val html = requestText(mirror, searchUrl, "GET", null, "$mirror/", settings)
        val entries = parseSearchEntries(html, mirror).distinctBy { it.pageUrl }.take(MAX_SEARCH_RESULTS)
        return buildList {
            entries.forEach entryLoop@{ entry ->
                val pageHtml = requestText(mirror, entry.pageUrl, "GET", null, "$mirror/", settings)
                val page = parsePageContext(pageHtml, entry.pageUrl) ?: return@entryLoop
                if (kind == MediaKind.MOVIE && page.isSeries) return@entryLoop
                if (kind == MediaKind.SERIES && !page.isSeries) return@entryLoop
                val translators = page.translators.ifEmpty {
                    listOf(HdRezkaTranslator("0", "Оригинал"))
                }.take(MAX_TRANSLATORS)

                if (!page.isSeries) {
                    translators.forEach translatorLoop@{ translator ->
                        if (translator.isPremium) {
                            add(paywallResult(entry, translator, null))
                            return@translatorLoop
                        }
                        val response = ajax(
                            mirror, entry.pageUrl, settings, page.favs, linkedMapOf(
                                "action" to "get_movie",
                                "id" to page.postId,
                                "translator_id" to translator.id,
                                "is_camrip" to translator.isCamrip,
                                "is_ads" to translator.isAds,
                                "is_director" to translator.isDirector
                            )
                        )
                        addAll(parseStreamSources(response, entry, translator, entry.pageUrl, null))
                    }
                } else {
                    val match = episodeCodePattern.find(episodeCode.orEmpty())
                    if (match == null) {
                        translators.forEach { translator ->
                            add(SourceResult(
                                id = "hdrezka:${page.postId}:${translator.id}:episodes",
                                title = entry.title,
                                providerName = "HDRezka",
                                pageUrl = entry.pageUrl,
                                sourceType = MediaSourceType.HDREZKA,
                                translationName = translator.name,
                                requiresSubscription = translator.isPremium
                            ))
                        }
                        return@entryLoop
                    }
                    val wantedSeason = match.groupValues[1].toIntOrNull() ?: return@entryLoop
                    val wantedEpisode = match.groupValues[2].toIntOrNull() ?: return@entryLoop
                    translators.forEach translatorLoop@{ translator ->
                        if (translator.isPremium) {
                            add(paywallResult(entry, translator, episodeCode))
                            return@translatorLoop
                        }
                        val episodeResponse = ajax(
                            mirror, entry.pageUrl, settings, page.favs, linkedMapOf(
                                "action" to "get_episodes",
                                "id" to page.postId,
                                "translator_id" to translator.id
                            )
                        )
                        val episodeIds = resolveEpisodeIds(
                            episodeResponse, wantedSeason, wantedEpisode,
                            page.defaultSeasonId, page.defaultEpisodeId
                        ) ?: return@translatorLoop
                        val streamResponse = ajax(
                            mirror, entry.pageUrl, settings, page.favs, linkedMapOf(
                                "action" to "get_stream",
                                "id" to page.postId,
                                "translator_id" to translator.id,
                                "season" to episodeIds.seasonId,
                                "episode" to episodeIds.episodeId
                            )
                        )
                        addAll(parseStreamSources(streamResponse, entry, translator, entry.pageUrl, episodeCode))
                    }
                }
            }
        }.distinctBy { it.id }.take(80)
    }

    internal fun buildSearchUrl(mirror: String, query: String): String =
        "$mirror/engine/ajax/search.php?q=${encode(query.trim().take(160))}"

    internal fun parseSearchEntries(html: String, mirror: String): List<HdRezkaSearchEntry> {
        val document = Jsoup.parse(html, mirror)
        fun toEntry(anchor: org.jsoup.nodes.Element, container: org.jsoup.nodes.Element?): HdRezkaSearchEntry? {
            val pageUrl = anchor.absUrl("href")
                .takeIf { it.isNotBlank() && isSameOrigin(it, mirror) }
                ?: return null
            val path = runCatching { URI(pageUrl).path.orEmpty().lowercase() }.getOrDefault("")
            if (!isLikelyMediaPage(path)) return null
            val title = anchor.selectFirst(".enty")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: anchor.attr("title").ifBlank { anchor.text() }.trim()
                    .takeIf { it.isNotBlank() }
                ?: return null
            val image = container?.selectFirst(".b-content__inline_item-cover img, img")
            val poster = image?.let { element ->
                sequenceOf("src", "data-src", "data-original")
                    .map { element.absUrl(it) }
                    .firstOrNull(::isHttpsUrl)
            }
            val description = container?.text()?.trim().orEmpty().take(500)
            return HdRezkaSearchEntry(title, pageUrl, poster, description)
        }

        val cards = document.select(".b-content__inline_item")
        val cardEntries = cards.mapNotNull { card ->
            val anchor = card.selectFirst(".b-content__inline_item-link a[href]")
                ?: card.selectFirst("a.b-content__inline_item-link[href]")
                ?: card.selectFirst("a[href]")
                ?: return@mapNotNull null
            toEntry(anchor, card)
        }
        if (cardEntries.isNotEmpty()) return cardEntries.distinctBy { it.pageUrl }.take(100)

        // The current DLE AJAX search returns simple anchors with .enty titles,
        // rather than the older full-card .b-content__inline_item markup.
        return document.select("a[href]").mapNotNull { anchor ->
            toEntry(anchor, anchor.parent())
        }.distinctBy { it.pageUrl }.take(100)
    }

    private fun isLikelyMediaPage(path: String): Boolean =
        Regex("""/(?:films?|serials?|series|cartoons?|anime|shorts?)/""")
            .containsMatchIn(path) ||
            Regex("""/[a-z][a-z0-9_-]{1,24}/[0-9]{1,12}[-.]""")
                .containsMatchIn(path)

    internal fun parseTranslators(html: String): List<HdRezkaTranslator> =
        Jsoup.parse(html).select("#translators-list [data-translator_id], [data-translator_id]")
            .mapNotNull { element ->
                val id = element.attr("data-translator_id").trim()
                    .takeIf { it.matches(Regex("[0-9]{1,12}")) } ?: return@mapNotNull null
                val name = element.attr("title").ifBlank { element.text() }.trim()
                    .take(100).ifBlank { "Озвучка $id" }
                HdRezkaTranslator(
                    id = id,
                    name = name,
                    isCamrip = element.attr("data-camrip").ifBlank { "0" },
                    isAds = element.attr("data-ads").ifBlank { "0" },
                    isDirector = element.attr("data-director").ifBlank { "0" },
                    isPremium = element.hasClass("b-prem_translator")
                )
            }.distinctBy { it.id }.take(MAX_TRANSLATORS)

    internal fun parsePageContext(html: String, pageUrl: String): HdRezkaPageContext? {
        val document = Jsoup.parse(html, pageUrl)
        val seriesInit = seriesInitPattern.find(html)
        val movieInit = movieInitPattern.find(html)
        val postId = document.selectFirst("input#post_id, input[name=post_id]")?.attr("value")
            ?.takeIf { it.matches(Regex("[0-9]{1,12}")) }
            ?: seriesInit?.groupValues?.get(1)
            ?: movieInit?.groupValues?.get(1)
            ?: runCatching { URI(pageUrl).path.orEmpty() }.getOrDefault("")
                .let { Regex("/(?:films?|serials?|cartoons?|anime)/([0-9]{1,12})-").find(it)?.groupValues?.get(1) }
            ?: return null
        val translators = parseTranslators(html).toMutableList()
        val defaultTranslator = seriesInit?.groupValues?.get(2) ?: movieInit?.groupValues?.get(2)
        if (translators.isEmpty() && defaultTranslator != null) {
            translators += HdRezkaTranslator(
                id = defaultTranslator,
                name = "Оригинал",
                isCamrip = movieInit?.groupValues?.getOrNull(3) ?: "0",
                isAds = movieInit?.groupValues?.getOrNull(4) ?: "0",
                isDirector = movieInit?.groupValues?.getOrNull(5) ?: "0"
            )
        }
        val favs = (
            document.selectFirst("input#ctrl_favs")?.attr("value")
                ?: document.selectFirst("[data-favs]")?.attr("data-favs")
                ?: Regex("""var\s+sof\s*=.*?\.send\([^,]+,\s*['"]([^'"]+)['"]""", RegexOption.DOT_MATCHES_ALL)
                    .find(html)?.groupValues?.getOrNull(1)
                ?: ""
        ).take(256)
        val isSeries = seriesInit != null ||
            document.selectFirst("#simple-episodes-tabs, #simple-seasons-tabs") != null ||
            document.select(".b-simple_episode__item").isNotEmpty()
        return HdRezkaPageContext(
            postId, translators.distinctBy { it.id }.take(MAX_TRANSLATORS), favs, isSeries,
            seriesInit?.groupValues?.getOrNull(3),
            seriesInit?.groupValues?.getOrNull(4)
        )
    }

    internal fun parsePostId(html: String, pageUrl: String): String? =
        parsePageContext(html, pageUrl)?.postId

    internal fun isSeriesPage(html: String): Boolean {
        val document = Jsoup.parse(html)
        return document.selectFirst("meta[property=og:type]")?.attr("content")
            .equals("video.tv_series", true) || seriesInitPattern.containsMatchIn(html) ||
            document.selectFirst("#simple-episodes-tabs, #simple-seasons-tabs") != null ||
            document.select(".b-simple_episode__item").isNotEmpty()
    }

    internal fun resolveEpisodeIds(
        response: JSONObject,
        wantedSeason: Int,
        wantedEpisode: Int,
        fallbackSeasonId: String? = null,
        fallbackEpisodeId: String? = null
    ): HdRezkaEpisodeIds? {
        val seasonsHtml = response.optString("seasons")
            .ifBlank { response.optJSONObject("data")?.optString("seasons").orEmpty() }
        val episodesHtml = response.optString("episodes")
            .ifBlank { response.optJSONObject("data")?.optString("episodes").orEmpty() }
        if (episodesHtml.isBlank()) {
            return if (wantedSeason == 1 && wantedEpisode == 1 &&
                fallbackSeasonId?.matches(Regex("[0-9]{1,12}")) == true &&
                fallbackEpisodeId?.matches(Regex("[0-9]{1,12}")) == true
            ) HdRezkaEpisodeIds(fallbackSeasonId, fallbackEpisodeId) else null
        }
        val seasonTabs = Jsoup.parse("<ul>$seasonsHtml</ul>").select(".b-simple_season__item")
        val seasonTab = seasonTabs.firstOrNull { tab ->
            Regex("\\d+").find(tab.text())?.value?.toIntOrNull() == wantedSeason
        } ?: seasonTabs.getOrNull(wantedSeason - 1)
        val preferredSeasonId = seasonTab?.attr("data-tab_id")
            ?.takeIf { it.matches(Regex("[0-9]{1,12}")) }
        val items = Jsoup.parse("<div>$episodesHtml</div>")
            .select(".b-simple_episode__item[data-season_id][data-episode_id]")
        if (items.isEmpty()) return null
        val availableSeasonIds = items.map { it.attr("data-season_id") }.distinct()
        val seasonId = preferredSeasonId?.takeIf(availableSeasonIds::contains)
            ?: if (availableSeasonIds.contains(wantedSeason.toString())) wantedSeason.toString()
            else availableSeasonIds.getOrNull(wantedSeason - 1)
                ?: fallbackSeasonId?.takeIf(availableSeasonIds::contains)
                ?: return null
        val seasonEpisodes = items.filter { it.attr("data-season_id") == seasonId }
        val selected = seasonEpisodes.firstOrNull { element ->
            Regex("\\d+").find(element.text())?.value?.toIntOrNull() == wantedEpisode ||
                element.attr("data-episode-number") == wantedEpisode.toString()
        } ?: seasonEpisodes.getOrNull(wantedEpisode - 1)
        if (selected == null) {
            return if (wantedSeason == 1 && wantedEpisode == 1 && fallbackSeasonId == seasonId &&
                fallbackEpisodeId?.matches(Regex("[0-9]{1,12}")) == true
            ) HdRezkaEpisodeIds(seasonId, fallbackEpisodeId) else null
        }
        val episodeId = selected.attr("data-episode_id")
            .takeIf { it.matches(Regex("[0-9]{1,12}")) } ?: return null
        return HdRezkaEpisodeIds(seasonId, episodeId)
    }

    internal fun decodeStreamPayload(raw: String): String? {
        val value = raw.trim()
        if (value.isBlank()) return null
        if (!value.startsWith("#")) return value
        if (value.length > MAX_OBFUSCATED_URL_CHARS || value.length < 4) return null

        var payload = value.substring(2)
        encodedTrashTokens.forEach { token ->
            payload = payload.replace("//_//$token", "")
        }
        val bytes = runCatching { Base64.getDecoder().decode(payload) }.getOrNull()
            ?: return null
        if (bytes.isEmpty() || bytes.size > MAX_OBFUSCATED_URL_CHARS) return null
        val decoded = bytes.toString(Charsets.UTF_8)
        if (decoded.any { it.isISOControl() && it !in '\t' && it !in '\r' && it !in '\n' }) {
            return null
        }
        return decoded.takeIf { it.contains("https://", ignoreCase = true) }
    }

    internal fun parseQualityVariants(raw: String): List<HdRezkaQualityVariant> {
        val value = decodeStreamPayload(raw) ?: return emptyList()
        val parsed = value.split(',').mapNotNull { segment ->
            val match = qualityPattern.matchEntire(segment.trim()) ?: return@mapNotNull null
            val label = match.groupValues[1].trim().take(64)
            val urls = match.groupValues[2].split(Regex("""\s+or\s+"""))
            // HDRezka serves several alternative files for one quality; follow
            // the player convention and prefer the last (usually direct MP4) URL.
            val url = urls.asReversed().firstOrNull { candidate ->
                isHttpsUrl(candidate.trim())
            }?.trim() ?: return@mapNotNull null
            val numericQuality = Regex("""(?i)([0-9]{3,4})""")
                .find(label)?.groupValues?.getOrNull(1)
            val normalizedLabel = numericQuality?.let { "${it}p" } ?: label
            HdRezkaQualityVariant(normalizedLabel, url)
        }.distinctBy { it.url }
        if (parsed.isNotEmpty()) return parsed
        val direct = directUrlPattern.find(value)?.value?.trim()?.trimEnd(')', ';')
        return listOfNotNull(direct?.takeIf(::isHttpsUrl)?.let {
            HdRezkaQualityVariant(null, it)
        })
    }

    internal fun parseStreamSources(
        response: JSONObject,
        entry: HdRezkaSearchEntry,
        translator: HdRezkaTranslator,
        pageUrl: String,
        episodeLabel: String?
    ): List<SourceResult> {
        val data = response.optJSONObject("data") ?: response.optJSONObject("response") ?: response
        val premium = isPremiumResponse(response) || translator.isPremium
        if (response.has("success") && !response.optBoolean("success", false)) {
            if (premium) return listOf(paywallResult(entry, translator, episodeLabel))
            val message = response.optString("message").replace(Regex("[\\r\\n\\t]+"), " ").take(160)
            throw IOException(message.ifBlank { "HDRezka не вернула медиапоток для выбранной озвучки" })
        }
        val variants = parseQualityVariants(data.optString("url").ifBlank { data.optString("stream") })
        if (variants.isEmpty()) {
            if (premium) return listOf(paywallResult(entry, translator, episodeLabel))
            return emptyList()
        }
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
                requestHeaders = mapOf(
                    "Referer" to pageUrl,
                    "User-Agent" to USER_AGENT
                )
            )
        }
    }

    private fun paywallResult(entry: HdRezkaSearchEntry, translator: HdRezkaTranslator, episodeLabel: String?): SourceResult =
        SourceResult(
            id = "hdrezka:${entry.pageUrl.hashCode()}:${translator.id}:${episodeLabel.orEmpty()}:premium",
            title = entry.title + if (episodeLabel.isNullOrBlank()) "" else " • $episodeLabel",
            providerName = "HDRezka",
            pageUrl = entry.pageUrl,
            sourceType = MediaSourceType.HDREZKA,
            translationName = translator.name,
            requiresSubscription = true
        )

    private fun isPremiumResponse(response: JSONObject): Boolean {
        val flag = response.opt("premium_content")
        return when (flag) {
            is Boolean -> flag
            is Number -> flag.toInt() == 1
            is String -> flag == "1" || flag.equals("true", true)
            else -> false
        } || response.optString("message").contains("premium", true) ||
            response.optString("message").contains("преми", true)
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

    private fun ajax(
        mirror: String,
        pageUrl: String,
        settings: HdRezkaSettings,
        favs: String,
        fields: Map<String, String>
    ): JSONObject {
        val values = LinkedHashMap(fields)
        values["favs"] = favs
        val body = values.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        val text = requestText(
            mirror, "$mirror/ajax/get_cdn_series/?t=${System.currentTimeMillis()}", "POST", body, pageUrl, settings,
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
            val requestBody = currentBody
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
                if (requestBody != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", contentType ?: "application/x-www-form-urlencoded")
                }
            }
            try {
                if (requestBody != null) connection.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }
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
        target.scheme.equals("https", true) && target.rawUserInfo == null &&
            target.host.equals(base.host, true) &&
            (if (target.port >= 0) target.port else 443) == (if (base.port >= 0) base.port else 443)
    }.getOrDefault(false)

    private fun isHttpsUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
    }.getOrDefault(false)

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}
