package app.jacket.mobile.data

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.Locale
import java.util.concurrent.Callable

/**
 * In-app HTML tracker adapter.
 *
 * It parses result markup and direct links published by the tracker. It does not
 * solve CAPTCHA, bypass anti-bot challenges, or automate account verification.
 */
internal data class TrackerSearchBatchOutcome(
    val results: List<SourceResult>,
    val failures: List<String>,
    val completedProviders: Int = 0,
    val totalProviders: Int = 0,
    val timedOut: Boolean = false
)

object TrackerSearchClient {
    private const val MAX_HTML_BYTES = 4 * 1024 * 1024
    private const val MAX_RESULTS = 40
    private const val MAX_DETAIL_PAGES = 10
    private const val MAX_DETAIL_CONCURRENCY = 4
    private const val SEARCH_ALL_TIMEOUT_MS = 25_000L
    private const val DETAIL_RESOLUTION_TIMEOUT_MS = 8_000L
    private const val DETAIL_CONNECT_TIMEOUT_MS = 3_000
    private const val DETAIL_READ_TIMEOUT_MS = 5_000
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 12_000
    private const val USER_AGENT = "JacketAndroid/2.6 (in-app tracker search)"
    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

    private data class Profile(
        val charset: Charset,
        val detailSelectors: List<String>
    )

    internal data class Candidate(
        val title: String,
        val pageUrl: String?,
        val magnet: String?,
        val torrentUrl: String?,
        val rowText: String,
        val sizeBytes: Long?,
        val seeders: Int?,
        val leechers: Int?
    )

    private data class HtmlPage(val finalUrl: String, val html: String)

    private val profiles = mapOf(
        "tracker-rutracker" to Profile(
            Charset.forName("windows-1251"),
            listOf("a.tLink", "a[href*='viewtopic.php']")
        ),
        "tracker-nnmclub" to Profile(
            Charset.forName("windows-1251"),
            listOf("a[href*='viewtopic.php']", "a.tLink")
        ),
        "tracker-rutor" to Profile(
            Charsets.UTF_8,
            listOf("a.detLink", "a[href*='/torrent/']")
        ),
        "tracker-kinozal" to Profile(
            Charset.forName("windows-1251"),
            listOf("a[href*='details.php?id=']")
        ),
        "tracker-1337x" to Profile(
            Charsets.UTF_8,
            listOf("a[href*='/torrent/']")
        ),
        "tracker-tpb" to Profile(
            Charsets.UTF_8,
            listOf("a[href*='torrent/']")
        )
    )

    fun supports(providerId: String): Boolean = providerId in profiles

    /** Encodes Cyrillic search text using the selected tracker's expected charset. */
    internal fun buildSearchUrl(provider: WebSearchProvider, query: String): String {
        val profile = profiles[provider.id]
            ?: throw IllegalArgumentException("Для этого сайта нет встроенного парсера")
        require(query.isNotBlank()) { "Введите название для поиска" }
        val template = WebSearchUrlBuilder.validateTemplate(provider.searchUrlTemplate)
        val encoded = URLEncoder.encode(query.trim(), profile.charset.name()).replace("+", "%20")
        val result = template.replace("{query}", encoded)
        require(result.length <= 4_096) { "Ссылка поиска слишком длинная" }
        return result
    }

    /**
     * Searches up to six enabled built-in trackers concurrently, with a strict
     * per-provider concurrency cap. Partial results are retained if one site fails.
     */
    private data class ProviderSearchOutcome(
        val provider: WebSearchProvider,
        val results: List<SourceResult> = emptyList(),
        val error: String? = null
    )

    /**
     * Searches enabled trackers concurrently and publishes a merged snapshot each
     * time one finishes. Results are collected in completion order, not provider
     * settings order, and the whole batch has a hard time budget.
     */
    internal fun searchAll(
        providers: List<WebSearchProvider>,
        query: String,
        onUpdate: (TrackerSearchBatchOutcome) -> Unit = {}
    ): TrackerSearchBatchOutcome {
        require(query.isNotBlank()) { "Введите название для поиска" }
        val active = providers
            .filter { it.enabled && supports(it.id) }
            .distinctBy { it.id }
            .take(6)
        require(active.isNotEmpty()) { "Нет включённых встроенных трекеров" }

        val tasks = active.map { provider ->
            Callable {
                try {
                    ProviderSearchOutcome(provider, search(provider, query.trim()))
                } catch (error: Exception) {
                    val cause = error.cause ?: error
                    ProviderSearchOutcome(
                        provider = provider,
                        error = cause.message?.take(160) ?: cause.javaClass.simpleName
                    )
                }
            }
        }
        val batches = ArrayList<List<SourceResult>>(active.size)
        val failures = ArrayList<String>()
        val completedIds = HashSet<String>()
        var completedProviders = 0

        fun snapshot(timedOut: Boolean = false) = TrackerSearchBatchOutcome(
            results = mergeResults(batches),
            failures = failures.toList(),
            completedProviders = completedProviders,
            totalProviders = active.size,
            timedOut = timedOut
        )

        val completed = CompletionOrderCollector.collect(
            tasks = tasks,
            timeoutMs = SEARCH_ALL_TIMEOUT_MS,
            maxConcurrency = 3
        ) { result ->
            completedProviders++
            result.fold(
                onSuccess = { outcome ->
                    completedIds += outcome.provider.id
                    if (outcome.error != null) {
                        failures += "${outcome.provider.name}: ${outcome.error}"
                    } else {
                        batches += outcome.results
                    }
                },
                onFailure = { error ->
                    failures += "Ошибка обработчика трекера: ${error.message?.take(160) ?: error.javaClass.simpleName}"
                }
            )
            runCatching { onUpdate(snapshot()) }
        }

        val timedOut = completed < active.size
        if (timedOut) {
            active.filterNot { it.id in completedIds }.forEach { provider ->
                failures += "${provider.name}: превышено время поиска (25 секунд)"
            }
        }
        return snapshot(timedOut).also { outcome ->
            runCatching { onUpdate(outcome) }
        }
    }

    /** Merges provider batches by BitTorrent info-hash and keeps the healthiest duplicate. */
    internal fun mergeResults(batches: List<List<SourceResult>>): List<SourceResult> {
        val merged = LinkedHashMap<String, SourceResult>()
        batches.flatten().forEach { source ->
            val key = magnetInfoHash(source.magnetUri)
                ?: source.downloadUrl
                ?: source.id
            val current = merged[key]
            if (current == null || (source.seeders ?: -1) > (current.seeders ?: -1)) {
                merged[key] = source
            }
        }
        return merged.values
            .sortedWith(
                compareByDescending<SourceResult> { it.seeders ?: -1 }
                    .thenByDescending { it.sizeBytes ?: -1L }
                    .thenBy { it.title.lowercase(Locale.ROOT) }
            )
            .take(MAX_RESULTS * 6)
    }

    fun search(provider: WebSearchProvider, query: String): List<SourceResult> {
        val profile = profiles[provider.id]
            ?: throw IllegalArgumentException("Для этого сайта нет встроенного парсера")
        require(provider.enabled) { "Трекер отключён" }
        require(query.isNotBlank()) { "Введите название для поиска" }

        val searchUrl = buildSearchUrl(provider, query)
        val originalHost = URI(searchUrl).host
        val searchPage = fetchHtml(searchUrl, provider.cookie, originalHost, profile.charset)
        ensureNotChallenge(searchPage.html, provider.name)
        val candidates = parseListing(searchPage.html, searchPage.finalUrl, provider.id)
        if (candidates.isEmpty()) {
            throw IOException(
                "${provider.name}: не удалось распознать результаты. Возможно, сайт изменил разметку " +
                    "или требует вход через браузер."
            )
        }

        val detailRequests = candidates.withIndex()
            .filter { (_, item) ->
                item.magnet == null && item.torrentUrl == null && item.pageUrl != null
            }
            .take(MAX_DETAIL_PAGES)
            .map { it.index to it.value.pageUrl!! }

        val details = resolveDetailsConcurrently(
            detailRequests, provider, originalHost, profile.charset
        )

        val resolved = candidates.mapIndexedNotNull { index, candidate ->
            val detailLinks = details[index]
            val magnet = candidate.magnet ?: detailLinks?.first
            val torrent = candidate.torrentUrl ?: detailLinks?.second
            if (magnet == null && torrent == null) return@mapIndexedNotNull null

            val identity = magnetInfoHash(magnet) ?: torrent ?: candidate.pageUrl ?: candidate.title
            SourceResult(
                id = "${provider.id}:${identity.lowercase(Locale.ROOT).hashCode()}",
                title = candidate.title,
                providerName = "Встроенный парсер",
                magnetUri = magnet,
                downloadUrl = torrent,
                sizeBytes = candidate.sizeBytes,
                seeders = candidate.seeders,
                leechers = candidate.leechers,
                pageUrl = candidate.pageUrl,
                indexerId = provider.id,
                tracker = provider.name
            )
        }

        return resolved
            .distinctBy { magnetInfoHash(it.magnetUri) ?: it.downloadUrl ?: it.id }
            .sortedWith(
                compareByDescending<SourceResult> { it.seeders ?: -1 }
                    .thenBy { it.title.lowercase(Locale.ROOT) }
            )
            .take(MAX_RESULTS)
    }

    private fun resolveDetailsConcurrently(
        pages: List<Pair<Int, String>>,
        provider: WebSearchProvider,
        originalHost: String,
        fallbackCharset: Charset
    ): Map<Int, Pair<String?, String?>> {
        if (pages.isEmpty()) return emptyMap()
        val tasks = pages.map { (index, url) ->
            Callable {
                val page = fetchHtml(
                    url,
                    provider.cookie,
                    originalHost,
                    fallbackCharset,
                    connectTimeoutMs = DETAIL_CONNECT_TIMEOUT_MS,
                    readTimeoutMs = DETAIL_READ_TIMEOUT_MS
                )
                ensureNotChallenge(page.html, provider.name)
                index to parseDownloadLinks(page.html, page.finalUrl)
            }
        }
        val details = LinkedHashMap<Int, Pair<String?, String?>>()
        CompletionOrderCollector.collect(
            tasks = tasks,
            timeoutMs = DETAIL_RESOLUTION_TIMEOUT_MS,
            maxConcurrency = MAX_DETAIL_CONCURRENCY
        ) { result ->
            result.getOrNull()?.let { (index, links) -> details[index] = links }
        }
        return details
    }

    internal fun parseListing(html: String, baseUrl: String, providerId: String): List<Candidate> {
        val profile = profiles[providerId]
            ?: throw IllegalArgumentException("Для этого сайта нет встроенного парсера")
        val document = Jsoup.parse(html, baseUrl)
        val candidates = LinkedHashMap<String, Candidate>()

        fun addCandidate(
            row: Element,
            title: String,
            pageUrl: String?,
            magnet: String?,
            torrentUrl: String?
        ) {
            val cleanTitle = title.replace(Regex("\\s+"), " ").trim().take(240)
            if (cleanTitle.isBlank()) return
            val identity = magnetInfoHash(magnet) ?: torrentUrl ?: pageUrl ?: cleanTitle
            val stats = parseStats(row)
            candidates.putIfAbsent(
                identity,
                Candidate(
                    cleanTitle,
                    pageUrl,
                    magnet,
                    torrentUrl,
                    row.text(),
                    stats.first,
                    stats.second,
                    stats.third
                )
            )
        }

        document.select("a[href^=magnet:]").forEach { anchor ->
            val row = resultContainer(anchor)
            val magnet = anchor.attr("href").takeIf { it.startsWith("magnet:", true) }
            addCandidate(row, titleFrom(row, anchor), firstDetailUrl(row, baseUrl), magnet, null)
        }

        document.select("a[href]").forEach { anchor ->
            val href = safeResolve(baseUrl, anchor.attr("href")) ?: return@forEach
            val path = runCatching { URI(href).rawPath.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
            if (!path.endsWith(".torrent")) return@forEach
            val row = resultContainer(anchor)
            addCandidate(row, titleFrom(row, anchor), null, null, href)
        }

        val detailSelector = profile.detailSelectors.joinToString(",")
        document.select(detailSelector).forEach { anchor ->
            val title = anchor.text().trim().ifBlank { return@forEach }
            val href = safeResolve(baseUrl, anchor.attr("href")) ?: return@forEach
            if (href.startsWith("magnet:", true)) return@forEach
            val row = resultContainer(anchor)
            val rowMagnet = row.selectFirst("a[href^=magnet:]")?.attr("href")
                ?.takeIf { it.startsWith("magnet:", true) }
            val rowHash = magnetInfoHash(rowMagnet)
            if (rowHash != null && candidates.containsKey(rowHash)) return@forEach
            addCandidate(row, title, href, null, findTorrentLink(row, baseUrl))
        }

        if (candidates.isEmpty()) {
            document.select("tr, article, li").take(200).forEach { row ->
                val anchor = row.selectFirst("a[href]") ?: return@forEach
                val href = safeResolve(baseUrl, anchor.attr("href")) ?: return@forEach
                if (!isDetailLink(href)) return@forEach
                val title = anchor.text().trim()
                if (title.length < 3) return@forEach
                addCandidate(row, title, href, null, findTorrentLink(row, baseUrl))
            }
        }
        return candidates.values.take(MAX_RESULTS)
    }

    internal fun parseDownloadLinks(html: String, baseUrl: String): Pair<String?, String?> {
        val document = Jsoup.parse(html, baseUrl)
        val magnet = document.selectFirst("a[href^=magnet:]")?.attr("href")
            ?.takeIf { it.startsWith("magnet:", true) }
        val torrent = document.select("a[href]").firstNotNullOfOrNull { anchor ->
            val href = safeResolve(baseUrl, anchor.attr("href")) ?: return@firstNotNullOfOrNull null
            val path = runCatching { URI(href).rawPath.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
            val label = anchor.text().lowercase(Locale.ROOT)
            href.takeIf {
                path.endsWith(".torrent") ||
                    path.contains("download.php") ||
                    path.endsWith("/dl.php") ||
                    (label.contains("torrent") && (label.contains("скач") || label.contains("download")))
            }
        }
        return magnet to torrent
    }

    private fun fetchHtml(
        urlValue: String,
        cookie: String,
        originalHost: String,
        fallbackCharset: Charset,
        connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = READ_TIMEOUT_MS
    ): HtmlPage {
        var current = urlValue
        for (redirect in 0..4) {
            if (!LocalNetwork.isAllowedUrl(current)) throw IOException("Трекер вернул небезопасный URL")
            val uri = URI(current)
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.7")
                setRequestProperty("Cache-Control", "no-cache")
                if (cookie.isNotBlank() && uri.host.equals(originalHost, true)) {
                    setRequestProperty("Cookie", cookie.take(8_192))
                }
            }
            try {
                val status = connection.responseCode
                if (status in REDIRECT_CODES) {
                    if (redirect == 4) throw IOException("Слишком много перенаправлений трекера")
                    val location = connection.getHeaderField("Location")
                        ?: throw IOException("Трекер вернул перенаправление без Location")
                    current = URL(URL(current), location).toString()
                    continue
                }
                if (status == 401 || status == 403 || status == 429) {
                    throw IOException("HTTP $status от трекера. Проверьте Cookie или доступность сайта.")
                }
                if (status !in 200..299) throw IOException("HTTP $status от трекера")
                val bytes = connection.inputStream.use { readLimited(it, MAX_HTML_BYTES) }
                val html = decodeHtml(bytes, connection.getHeaderField("Content-Type"), fallbackCharset)
                return HtmlPage(connection.url.toString(), html)
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Не удалось получить страницу трекера")
    }

    private fun decodeHtml(bytes: ByteArray, contentType: String?, fallbackCharset: Charset): String {
        val headerCharset = Regex("(?i)charset\\s*=\\s*[\\\"']?([^;\\s\\\"']+)")
            .find(contentType.orEmpty())?.groupValues?.getOrNull(1)
        val prefix = bytes.copyOfRange(0, minOf(bytes.size, 8_192)).toString(Charsets.ISO_8859_1)
        val metaCharset = Regex("(?is)<meta[^>]+charset=['\\\"]?([a-z0-9._-]+)")
            .find(prefix)?.groupValues?.getOrNull(1)
        val charset = sequenceOf(headerCharset, metaCharset)
            .filterNotNull()
            .mapNotNull { name -> runCatching { Charset.forName(name) }.getOrNull() }
            .firstOrNull() ?: fallbackCharset
        return String(bytes, charset)
    }

    private fun readLimited(input: java.io.InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) throw IOException("Ответ трекера превышает допустимый размер")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    internal fun ensureNotChallenge(html: String, trackerName: String) {
        val document = Jsoup.parse(html)
        val title = document.title().lowercase(Locale.ROOT)
        val explicitChallenge =
            html.contains("cf-chl-", true) ||
                html.contains("challenge-platform", true) ||
                document.selectFirst(
                    "form#challenge-form, form[action*=captcha], iframe[src*=captcha], " +
                        ".g-recaptcha, .h-captcha, #captcha, .captcha"
                ) != null
        if (title.contains("just a moment") || explicitChallenge) {
            throw IOException(
                "$trackerName требует браузерную проверку или CAPTCHA. Пройдите её в обычном браузере " +
                    "и добавьте Cookie-сессию; обход защиты не выполняется."
            )
        }
    }

    private fun resultContainer(anchor: Element): Element =
        anchor.closest("tr, article, li, .torrent, .result, .row, .gai") ?: anchor.parent() ?: anchor

    private fun titleFrom(container: Element, fallback: Element): String {
        val named = container.selectFirst(".detLink, .tLink, .torrent-name, .title, h2, h3")
            ?.text()?.trim().orEmpty()
        return named.ifBlank { fallback.text().trim() }
    }

    private fun firstDetailUrl(container: Element, baseUrl: String): String? =
        container.select("a[href]").firstNotNullOfOrNull { anchor ->
            val href = safeResolve(baseUrl, anchor.attr("href")) ?: return@firstNotNullOfOrNull null
            href.takeIf { isDetailLink(it) }
        }

    private fun findTorrentLink(container: Element, baseUrl: String): String? =
        container.select("a[href]").firstNotNullOfOrNull { anchor ->
            val href = safeResolve(baseUrl, anchor.attr("href")) ?: return@firstNotNullOfOrNull null
            val path = runCatching { URI(href).rawPath.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
            href.takeIf {
                path.endsWith(".torrent") || path.endsWith("/dl.php") || path.contains("download.php")
            }
        }

    private fun isDetailLink(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        val path = uri.rawPath.orEmpty().lowercase(Locale.ROOT)
        val query = uri.rawQuery.orEmpty().lowercase(Locale.ROOT)
        return path.contains("viewtopic.php") || path.contains("details.php") ||
            path.contains("/torrent/") || path.contains("/topic/") ||
            query.contains("id=") && (path.contains("browse") || path.contains("details"))
    }

    private fun safeResolve(baseUrl: String, value: String): String? {
        val clean = value.trim()
        if (clean.isBlank() || clean.startsWith("#") || clean.startsWith("javascript:", true)) return null
        if (clean.startsWith("magnet:", true)) return clean
        return runCatching {
            val resolved = URL(URL(baseUrl), clean).toString()
            resolved.takeIf { LocalNetwork.isAllowedUrl(it) }
        }.getOrNull()
    }

    private fun parseStats(row: Element): Triple<Long?, Int?, Int?> {
        val rowText = row.text().replace(',', '.')
        val sizeMatch = Regex(
            "(?i)([0-9]+(?:\\.[0-9]+)?)\\s*(байт|bytes|kb|kib|mb|mib|gb|gib|tb|tib|кб|мб|гб|тб)"
        ).find(rowText)
        val size = sizeMatch?.let { match ->
            val amount = match.groupValues[1].toDoubleOrNull() ?: return@let null
            val unit = match.groupValues[2].lowercase(Locale.ROOT)
            val multiplier = when (unit) {
                "байт", "bytes" -> 1.0
                "kb", "kib", "кб" -> 1024.0
                "mb", "mib", "мб" -> 1024.0 * 1024.0
                "gb", "gib", "гб" -> 1024.0 * 1024.0 * 1024.0
                else -> 1024.0 * 1024.0 * 1024.0 * 1024.0
            }
            (amount * multiplier).takeIf { it <= Long.MAX_VALUE.toDouble() }?.toLong()
        }

        fun labelled(vararg patterns: String): Int? {
            for (pattern in patterns) {
                Regex(pattern, RegexOption.IGNORE_CASE).find(rowText)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { if (it >= 0) return it }
            }
            return null
        }

        val seedsByLabel = labelled(
            "(?:seeders?|seeds?|сид(?:ы|ов)?|раздач(?:а|и))\\s*[:=]?\\s*([0-9]+)"
        )
        val peersByLabel = labelled(
            "(?:leechers?|peers?|пиров|лич(?:ей|и)?)\\s*[:=]?\\s*([0-9]+)"
        )
        val seedsByClass = row.selectFirst(
            ".seedmed, .seed, .seeders, .seed-count, .seeds, [class*=seed]"
        )?.text()?.filter(Char::isDigit)?.toIntOrNull()
        val peersByClass = row.selectFirst(
            ".leechmed, .leech, .leechers, .peer-count, .peers, [class*=leech]"
        )?.text()?.filter(Char::isDigit)?.toIntOrNull()
        val numericCells = row.select("td").mapNotNull { cell ->
            cell.text().replace(Regex("[\\s\\u00a0]"), "")
                .takeIf { it.matches(Regex("[0-9]{1,8}")) }?.toIntOrNull()
        }
        val seedsByPosition = numericCells.getOrNull(numericCells.size - 2)
        val peersByPosition = numericCells.lastOrNull()
        return Triple(
            size,
            seedsByLabel ?: seedsByClass ?: seedsByPosition,
            peersByLabel ?: peersByClass ?: peersByPosition
        )
    }

    private fun magnetInfoHash(magnet: String?): String? {
        if (magnet.isNullOrBlank()) return null
        val encoded = Regex("(?i)(?:[?&])xt=urn:btih:([^&]+)").find(magnet)
            ?.groupValues?.getOrNull(1) ?: return null
        val hash = runCatching {
            java.net.URLDecoder.decode(encoded, Charsets.UTF_8.name())
        }.getOrDefault(encoded).trim()
        return hash.takeIf { it.matches(Regex("(?i)([a-f0-9]{40}|[a-z2-7]{32})")) }
            ?.uppercase(Locale.ROOT)
    }
}
