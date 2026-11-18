package app.jacket.mobile.data

import org.xmlpull.v1.XmlPullParserFactory
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Pluggable direct indexer federation. Supports user-configured RSS/Atom and Torznab/Newznab
 * endpoints without running a separate Jackett service. Credentials are supplied only over HTTPS.
 */
class IndexerRegistry(
    private val catalog: MediaCatalogClient = MediaCatalogClient()
) {
    fun search(
        query: String,
        customIndexers: List<IndexerConfig>,
        includeArchive: Boolean = true
    ): List<SourceResult> = searchDetailed(query, customIndexers, includeArchive).results

    /** Runs no more than four source requests concurrently; one failed provider never cancels peers. */
    fun searchDetailed(
        query: String,
        customIndexers: List<IndexerConfig>,
        includeArchive: Boolean = true
    ): IndexerSearchOutcome {
        val active = customIndexers.asSequence()
            .filter { it.enabled }
            .take(MAX_INDEXERS)
            .toList()
        val executor = Executors.newFixedThreadPool(MAX_PARALLEL_REQUESTS)
        try {
            val tasks = buildList {
                if (includeArchive) {
                    add(
                        "archive" to "Internet Archive" to executor.submit(Callable {
                            runCatching { catalog.archiveSources(query) }
                        })
                    )
                }
                active.forEach { config ->
                    add(
                        config.id to config.name to executor.submit(Callable {
                            runCatching { searchFeed(config, query) }
                        })
                    )
                }
            }

            val results = ArrayList<SourceResult>()
            val failures = ArrayList<IndexerFailure>()
            tasks.forEach { task ->
                val (keyAndName, future) = task
                val (id, name) = keyAndName
                try {
                    val result = future.get()
                    result.onSuccess(results::addAll).onFailure { error ->
                        failures += IndexerFailure(
                            indexerId = id,
                            indexerName = name,
                            message = safeError(error)
                        )
                    }
                } catch (error: Exception) {
                    failures += IndexerFailure(id, name, safeError(error))
                }
            }

            return IndexerSearchOutcome(orderResults(results), failures)
        } finally {
            executor.shutdownNow()
            runCatching { executor.awaitTermination(250, TimeUnit.MILLISECONDS) }
        }
    }

    fun searchOne(config: IndexerConfig, query: String): List<SourceResult> {
        require(config.enabled) { "Индексатор отключён" }
        require(query.isNotBlank()) { "Введите поисковый запрос" }
        return searchFeed(config, query.trim())
    }

    /** A valid empty response still means that the authenticated indexer is reachable. */
    fun test(config: IndexerConfig): Int = searchFeed(config, "jacket-connectivity-test").size

    fun testAll(configs: List<IndexerConfig>): List<IndexerFailure> {
        val executor = Executors.newFixedThreadPool(MAX_PARALLEL_REQUESTS)
        try {
            val futures = configs.filter { it.enabled }.take(MAX_INDEXERS).map { config ->
                config to executor.submit(Callable { runCatching { searchFeed(config, "jacket-connectivity-test") } })
            }
            return futures.mapNotNull { (config, future) ->
                val result = runCatching { future.get() }.getOrElse {
                    Result.failure(it)
                }
                result.exceptionOrNull()?.let { IndexerFailure(config.id, config.name, safeError(it)) }
            }
        } finally {
            executor.shutdownNow()
            runCatching { executor.awaitTermination(250, TimeUnit.MILLISECONDS) }
        }
    }

    internal fun parseFeedXml(xml: String, config: IndexerConfig): List<SourceResult> =
        parseFeed(xml.toByteArray(Charsets.UTF_8), config, newXmlParser())

    /** Parser injection keeps RSS/Torznab fixtures testable without Android framework classes. */
    internal fun parseFeedXml(xml: String, config: IndexerConfig, parser: XmlPullParser): List<SourceResult> =
        parseFeed(xml.toByteArray(Charsets.UTF_8), config, parser)

    internal fun orderResults(results: List<SourceResult>): List<SourceResult> =
        results
            .distinctBy { it.magnetUri ?: it.downloadUrl ?: it.streamUrl ?: it.id }
            .sortedWith(compareByDescending<SourceResult> { it.seeders ?: -1 }.thenBy { it.title.lowercase() })
            .take(MAX_TOTAL_RESULTS)

    private fun searchFeed(config: IndexerConfig, query: String): List<SourceResult> {
        val requestUrl = buildRequestUrl(config, query)
        val uri = runCatching { java.net.URI(requestUrl) }.getOrElse { throw IOException("Некорректный URL индексатора", it) }
        if (!uri.scheme.equals("https", true) || uri.host.isNullOrBlank() || uri.rawUserInfo != null) {
            throw IOException("Источник «${config.name}» должен использовать HTTPS без логина в URL")
        }

        val connection = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml")
            setRequestProperty("User-Agent", USER_AGENT)
            if (config.username.isNotBlank() || config.password.isNotBlank()) {
                val credentials = "${config.username}:${config.password}"
                val encoded = java.util.Base64.getEncoder().encodeToString(credentials.toByteArray(Charsets.UTF_8))
                setRequestProperty("Authorization", "Basic $encoded")
            }
            if (config.cookie.isNotBlank()) setRequestProperty("Cookie", config.cookie.take(4096))
            if (config.apiKey.isNotBlank()) setRequestProperty("X-Api-Key", config.apiKey.take(512))
        }
        val body = try {
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("HTTP $status от ${config.name}")
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_FEED_BYTES) throw IOException("Ответ ${config.name} слишком большой")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
        return parseFeed(body, config)
    }

    private fun buildRequestUrl(config: IndexerConfig, query: String): String {
        val template = config.searchUrlTemplate
        val substituted = template
            .replace("{query}", urlEncode(query))
            .replace("{apikey}", urlEncode(config.apiKey))
        val uri = runCatching { java.net.URI(substituted) }
            .getOrElse { throw IOException("Некорректный URL индексатора", it) }
        if (!uri.scheme.equals("https", true) || uri.host.isNullOrBlank() || uri.rawUserInfo != null) {
            throw IOException("Индексатор должен использовать HTTPS без логина в URL")
        }

        val queryParts = uri.rawQuery.orEmpty().split('&').filter(String::isNotBlank).toMutableList()
        val existingNames = queryParts.mapTo(HashSet()) { part ->
            runCatching { java.net.URLDecoder.decode(part.substringBefore('='), Charsets.UTF_8.name()) }
                .getOrDefault(part.substringBefore('='))
                .lowercase()
        }
        fun appendIfMissing(key: String, value: String?) {
            if (!value.isNullOrBlank() && key.lowercase() !in existingNames) {
                queryParts += "${urlEncode(key)}=${urlEncode(value)}"
                existingNames += key.lowercase()
            }
        }

        appendIfMissing("apikey", config.apiKey.takeIf { it.isNotBlank() && "{apikey}" !in template })
        if (config.protocol == IndexerProtocol.TORZNAB) {
            appendIfMissing("t", "search")
            appendIfMissing("cat", config.categories)
            appendIfMissing("tag", config.tags)
            appendIfMissing("sort", config.sortBy)
            appendIfMissing("order", config.sortOrder)
            if (config.freeleechOnly) appendIfMissing("freeleech", "1")
        }
        return buildString {
            append(uri.scheme).append("://").append(uri.rawAuthority)
            if (!uri.rawPath.isNullOrEmpty()) append(uri.rawPath)
            if (queryParts.isNotEmpty()) append('?').append(queryParts.joinToString("&"))
            if (!uri.rawFragment.isNullOrEmpty()) append('#').append(uri.rawFragment)
        }
    }

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun newXmlParser(): XmlPullParser =
        XmlPullParserFactory.newInstance().newPullParser()

    private fun parseFeed(xml: ByteArray, config: IndexerConfig): List<SourceResult> =
        parseFeed(xml, config, newXmlParser())

    private fun parseFeed(xml: ByteArray, config: IndexerConfig, parser: XmlPullParser): List<SourceResult> {
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(xml.inputStream(), "UTF-8")

        val result = mutableListOf<SourceResult>()
        var inEntry = false
        var title = ""
        var pageUrl: String? = null
        var magnet: String? = null
        var torrentUrl: String? = null
        var streamUrl: String? = null
        var size: Long? = null
        var seeders: Int? = null
        var leechers: Int? = null
        var category: String? = null

        fun reset() {
            title = ""
            pageUrl = null
            magnet = null
            torrentUrl = null
            streamUrl = null
            size = null
            seeders = null
            leechers = null
            category = null
        }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT && result.size < MAX_FEED_RESULTS) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name.substringAfter(':').lowercase()
                    when (name) {
                        "item", "entry" -> {
                            inEntry = true
                            reset()
                        }
                        "title" -> if (inEntry) title = parser.nextText().trim()
                        "link" -> if (inEntry) {
                            val href = parser.getAttributeValue(null, "href")
                            val rel = parser.getAttributeValue(null, "rel")
                            val value = href?.takeIf(String::isNotBlank) ?: parser.nextText().trim()
                            val contentType = parser.getAttributeValue(null, "type").orEmpty()
                            when {
                                value.startsWith("magnet:", true) -> magnet = value
                                rel.equals("enclosure", true) &&
                                    (value.endsWith(".torrent", true) || contentType.contains("bittorrent", true)) ->
                                    torrentUrl = value
                                isPlayableVideoUrl(value, contentType) -> streamUrl = value
                                value.isNotBlank() && pageUrl == null -> pageUrl = value
                            }
                        }
                        "enclosure" -> if (inEntry) {
                            val value = parser.getAttributeValue(null, "url").orEmpty()
                            val type = parser.getAttributeValue(null, "type").orEmpty()
                            when {
                                value.startsWith("magnet:", true) -> magnet = value
                                value.endsWith(".torrent", true) || type.contains("bittorrent", true) -> torrentUrl = value
                                isPlayableVideoUrl(value, type) -> streamUrl = value
                                value.startsWith("https://") && pageUrl == null -> pageUrl = value
                            }
                        }
                        "content" -> if (inEntry) {
                            val value = parser.getAttributeValue(null, "url").orEmpty()
                            val type = parser.getAttributeValue(null, "type").orEmpty()
                            val medium = parser.getAttributeValue(null, "medium").orEmpty()
                            if (isPlayableVideoUrl(value, type) || medium.equals("video", ignoreCase = true)) {
                                if (isHttps(value)) streamUrl = value
                            }
                            val fileSize = parser.getAttributeValue(null, "fileSize")?.toLongOrNull()?.takeIf { it >= 0L }
                            if (size == null) size = fileSize
                        }
                        "guid", "id" -> if (inEntry && pageUrl == null) pageUrl = parser.nextText().trim()
                        "category" -> if (inEntry) {
                            val value = parser.getAttributeValue(null, "id")
                                ?: parser.getAttributeValue(null, "term")
                                ?: parser.nextText()
                            category = value.trim().takeIf(String::isNotBlank)
                        }
                        "attr" -> if (inEntry) {
                            val attr = parser.getAttributeValue(null, "name")?.lowercase().orEmpty()
                            val value = parser.getAttributeValue(null, "value")
                            when (attr) {
                                "magneturl" -> if (!value.isNullOrBlank()) magnet = value
                                "seeders" -> seeders = value?.toIntOrNull()?.takeIf { it >= 0 }
                                "peers", "leechers" -> leechers = value?.toIntOrNull()?.takeIf { it >= 0 }
                                "size", "length" -> {
                                    val parsed = value?.toLongOrNull()?.takeIf { it >= 0L }
                                    if (parsed != null) size = parsed
                                }
                                "category", "categoryid" -> if (!value.isNullOrBlank()) category = value
                            }
                        }
                    }
                }
                XmlPullParser.DOCDECL -> throw IOException("XML document type declarations are not supported")
                XmlPullParser.END_TAG -> {
                    val name = parser.name.substringAfter(':').lowercase()
                    if (inEntry && name in setOf("item", "entry")) {
                        val safeMagnet = magnet?.takeIf { it.startsWith("magnet:", true) }
                        val safeTorrent = torrentUrl?.takeIf(::isHttps)
                        val safeStream = streamUrl?.takeIf(::isHttps)
                        val safePage = pageUrl?.takeIf(::isHttps)
                        if (title.isNotBlank() &&
                            (safeMagnet != null || safeTorrent != null || safeStream != null || safePage != null)
                        ) {
                            result += SourceResult(
                                id = "${config.id}:${result.size}:${title.hashCode()}",
                                title = title,
                                providerName = config.name,
                                magnetUri = safeMagnet,
                                downloadUrl = safeTorrent,
                                streamUrl = safeStream,
                                sizeBytes = size,
                                seeders = seeders,
                                leechers = leechers,
                                pageUrl = safePage,
                                category = category,
                                indexerId = config.id
                            )
                        }
                        inEntry = false
                    }
                }
            }
            event = parser.next()
        }
        return result
    }

    private fun isPlayableVideoUrl(value: String, contentType: String): Boolean {
        if (!isHttps(value)) return false
        val mime = contentType.substringBefore(';').trim().lowercase()
        if (mime.startsWith("video/") || mime in PLAYLIST_MIME_TYPES) return true
        val path = runCatching { java.net.URI(value).rawPath.orEmpty().lowercase() }.getOrDefault("")
        return VIDEO_EXTENSIONS.any(path::endsWith)
    }

    private fun isHttps(value: String): Boolean = runCatching {
        val uri = java.net.URI(value)
        uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
    }.getOrDefault(false)

    private fun safeError(error: Throwable): String =
        (error.cause?.message ?: error.message ?: error.javaClass.simpleName)
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .take(MAX_ERROR_LENGTH)

    private companion object {
        const val MAX_INDEXERS = 100
        const val MAX_PARALLEL_REQUESTS = 4
        const val MAX_TOTAL_RESULTS = 200
        const val MAX_FEED_RESULTS = 100
        const val MAX_FEED_BYTES = 4 * 1024 * 1024
        const val MAX_ERROR_LENGTH = 180
        val PLAYLIST_MIME_TYPES = setOf(
            "application/vnd.apple.mpegurl",
            "application/x-mpegurl",
            "application/dash+xml"
        )
        val VIDEO_EXTENSIONS = setOf(
            ".m3u8", ".mpd", ".mp4", ".m4v", ".mov", ".webm", ".mkv", ".ogv"
        )
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 25_000
        const val USER_AGENT = "JacketAndroid/2.1 (direct indexer federation)"
    }
}
