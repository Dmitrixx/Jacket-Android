package app.jacket.mobile.data

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale

/**
 * In-app HTML search adapter for the built-in tracker shortcuts.
 *
 * It parses only public result markup and links the tracker itself exposes. It
 * does not solve CAPTCHA, impersonate browser TLS, or bypass login/anti-bot
 * controls. Trackers that require an account can use a user-supplied Cookie.
 */
object TrackerSearchClient {
    private const val MAX_HTML_BYTES = 4 * 1024 * 1024
    private const val MAX_RESULTS = 40
    private const val MAX_DETAIL_PAGES = 10
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 12_000
    private const val USER_AGENT = "JacketAndroid/2.6 (in-app tracker search)"

    private val supportedIds = setOf(
        "tracker-rutracker",
        "tracker-nnmclub",
        "tracker-rutor",
        "tracker-kinozal",
        "tracker-1337x",
        "tracker-tpb"
    )

    internal data class Candidate(
        val title: String,
        val pageUrl: String?,
        val magnet: String?,
        val torrentUrl: String?,
        val rowText: String
    )

    private data class HtmlPage(val finalUrl: String, val html: String)

    fun supports(providerId: String): Boolean = providerId in supportedIds

    fun search(provider: WebSearchProvider, query: String): List<SourceResult> {
        require(supports(provider.id)) { "Для этого сайта нет встроенного парсера" }
        require(provider.enabled) { "Трекер отключён" }
        require(query.isNotBlank()) { "Введите название для поиска" }

        val searchUrl = WebSearchUrlBuilder.build(provider.searchUrlTemplate, query)
        val searchPage = fetchHtml(searchUrl, provider.cookie, URI(searchUrl).host)
        ensureNotChallenge(searchPage.html, provider.name)
        val candidates = parseListing(searchPage.html, searchPage.finalUrl, provider.id)
        if (candidates.isEmpty()) {
            throw IOException(
                "${provider.name}: не удалось распознать результаты. Возможно, сайт изменил разметку " +
                    "или требует вход через браузер."
            )
        }

        val resolved = ArrayList<SourceResult>()
        var detailsRead = 0
        for (candidate in candidates) {
            var magnet = candidate.magnet
            var torrentUrl = candidate.torrentUrl
            if (magnet == null && torrentUrl == null && candidate.pageUrl != null &&
                detailsRead < MAX_DETAIL_PAGES
            ) {
                detailsRead++
                runCatching {
                    val detail = fetchHtml(candidate.pageUrl, provider.cookie, URI(searchUrl).host)
                    ensureNotChallenge(detail.html, provider.name)
                    val links = parseDownloadLinks(detail.html, detail.finalUrl)
                    magnet = links.first
                    torrentUrl = links.second
                }
            }
            if (magnet == null && torrentUrl == null) continue
            val stats = parseStats(candidate.rowText)
            val stableId = magnet ?: torrentUrl ?: candidate.pageUrl ?: candidate.title
            resolved += SourceResult(
                id = "${provider.id}:${stableId.hashCode()}",
                title = candidate.title,
                providerName = "Встроенный парсер",
                magnetUri = magnet,
                downloadUrl = torrentUrl,
                sizeBytes = stats.first,
                seeders = stats.second,
                leechers = stats.third,
                pageUrl = candidate.pageUrl,
                indexerId = provider.id,
                tracker = provider.name
            )
            if (resolved.size >= MAX_RESULTS) break
        }

        return resolved.distinctBy { it.magnetUri ?: it.downloadUrl ?: it.id }
            .sortedWith(compareByDescending<SourceResult> { it.seeders ?: -1 }.thenBy { it.title.lowercase(Locale.ROOT) })
    }

    internal fun parseListing(html: String, baseUrl: String, providerId: String): List<Candidate> {
        require(supports(providerId))
        val document = Jsoup.parse(html, baseUrl)
        val result = LinkedHashMap<String, Candidate>()

        document.select("a[href^=magnet:]").forEach { anchor ->
            val row = resultContainer(anchor)
            val title = titleFrom(row, anchor)
            if (title.isNotBlank()) {
                val magnet = anchor.attr("href").takeIf { it.startsWith("magnet:", true) }
                val key = magnet ?: title
                result.putIfAbsent(
                    key,
                    Candidate(title, firstDetailUrl(row, baseUrl), magnet, null, row.text())
                )
            }
        }

        document.select("a[href]").forEach { anchor ->
            val href = safeResolve(baseUrl, anchor.attr("href")) ?: return@forEach
            val path = runCatching { URI(href).rawPath.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
            if (!path.endsWith(".torrent")) return@forEach
            val row = resultContainer(anchor)
            val title = titleFrom(row, anchor)
            if (title.isNotBlank()) result.putIfAbsent(
                href, Candidate(title, null, null, href, row.text())
            )
        }

        val detailSelector = listOf(
            "a.detLink",
            "a.tLink",
            "a.torrent-name",
            "a[href*='viewtopic.php']",
            "a[href*='details.php?id=']",
            "a[href*='/torrent/']",
            "a[href*='/topic/']"
        ).joinToString(",")
        document.select(detailSelector).forEach { anchor ->
            val title = anchor.text().trim().ifBlank { return@forEach }
            val href = safeResolve(baseUrl, anchor.attr("href")) ?: return@forEach
            if (href.startsWith("magnet:", true)) return@forEach
            val row = resultContainer(anchor)
            val directTorrent = findTorrentLink(row, baseUrl)
            val key = directTorrent ?: href
            result.putIfAbsent(key, Candidate(title, href, null, directTorrent, row.text()))
        }

        if (result.isEmpty()) {
            document.select("tr, article, li").take(200).forEach { row ->
                val anchor = row.selectFirst("a[href]") ?: return@forEach
                val href = safeResolve(baseUrl, anchor.attr("href")) ?: return@forEach
                val looksLikeResult = isDetailLink(href)
                val title = anchor.text().trim()
                if (!looksLikeResult || title.length < 3) return@forEach
                result.putIfAbsent(href, Candidate(title, href, null, findTorrentLink(row, baseUrl), row.text()))
            }
        }
        return result.values.take(MAX_RESULTS)
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

    private fun fetchHtml(urlValue: String, cookie: String, originalHost: String): HtmlPage {
        var current = urlValue
        for (redirect in 0..MAX_REDIRECTS) {
            if (!LocalNetwork.isAllowedUrl(current)) throw IOException("Трекер вернул небезопасный URL")
            val uri = URI(current)
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.7")
                if (cookie.isNotBlank() && uri.host.equals(originalHost, true)) {
                    setRequestProperty("Cookie", cookie.take(8_192))
                }
            }
            try {
                val status = connection.responseCode
                if (status in REDIRECT_CODES) {
                    if (redirect >= MAX_REDIRECTS) throw IOException("Слишком много перенаправлений трекера")
                    val location = connection.getHeaderField("Location")
                        ?: throw IOException("Трекер вернул перенаправление без Location")
                    current = URL(URL(current), location).toString()
                    continue
                }
                if (status == 401 || status == 403 || status == 429) {
                    throw IOException("HTTP $status от трекера. Проверьте Cookie или доступность сайта.")
                }
                if (status !in 200..299) throw IOException("HTTP $status от трекера")
                val body = connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8_192)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_HTML_BYTES) throw IOException("Ответ трекера превышает 4 МиБ")
                        output.write(buffer, 0, count)
                    }
                    output.toString(Charsets.UTF_8.name())
                }
                return HtmlPage(connection.url.toString(), body)
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Не удалось получить страницу трекера")
    }

    private fun ensureNotChallenge(html: String, trackerName: String) {
        if (html.contains("cf-chl", true) ||
            html.contains("Just a moment", true) ||
            html.contains("captcha", true) ||
            html.contains("challenge-platform", true)
        ) {
            throw IOException(
                "$trackerName требует браузерную проверку или CAPTCHA. Откройте сайт обычным способом " +
                    "и добавьте действующую Cookie-сессию; обход защиты не выполняется."
            )
        }
    }

    private fun resultContainer(anchor: Element): Element =
        anchor.closest("tr, article, li, .torrent, .result, .row, .gai") ?: anchor.parent() ?: anchor

    private fun titleFrom(container: Element, fallback: Element): String {
        val named = container.selectFirst(".detLink, .tLink, .torrent-name, .title, h2, h3")
            ?.text()?.trim().orEmpty()
        return (named.ifBlank { fallback.text().trim() })
            .replace(Regex("\\s+"), " ")
            .take(240)
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
            href.takeIf { path.endsWith(".torrent") || path.endsWith("/dl.php") || path.contains("download.php") }
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

    private fun parseStats(text: String): Triple<Long?, Int?, Int?> {
        val clean = text.replace(',', '.')
        val sizeMatch = Regex("(?i)([0-9]+(?:\\.[0-9]+)?)\\s*(байт|bytes|kb|kib|mb|mib|gb|gib|tb|tib|кб|мб|гб|тб)").find(clean)
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
        fun count(vararg patterns: String): Int? {
            for (pattern in patterns) {
                Regex(pattern, RegexOption.IGNORE_CASE).find(clean)?.groupValues?.getOrNull(1)
                    ?.replace(" ", "")?.toIntOrNull()?.let { if (it >= 0) return it }
            }
            return null
        }
        val seeds = count("(?:seeders?|seeds?|сид(?:ы|ов)?|раздач(?:а|и))\\s*[:=]?\\s*([0-9]+)")
        val peers = count("(?:leechers?|peers?|пиров|лич(?:ей|и)?)\\s*[:=]?\\s*([0-9]+)")
        return Triple(size, seeds, peers)
    }

    private const val MAX_REDIRECTS = 4
    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
}
