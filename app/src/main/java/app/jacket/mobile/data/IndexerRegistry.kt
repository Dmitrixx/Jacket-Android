package app.jacket.mobile.data

import android.net.Uri
import android.util.Base64
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

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
    ): List<SourceResult> {
        val results = ArrayList<SourceResult>()
        if (includeArchive) {
            runCatching { catalog.archiveSources(query) }.getOrDefault(emptyList()).forEach(results::add)
        }
        customIndexers.asSequence()
            .filter { it.enabled }
            .take(MAX_INDEXERS)
            .forEach { config ->
                runCatching { searchFeed(config, query) }
                    .getOrDefault(emptyList())
                    .forEach(results::add)
            }

        return results
            .distinctBy { it.magnetUri ?: it.downloadUrl ?: it.streamUrl ?: it.id }
            .sortedWith(compareByDescending<SourceResult> { it.seeders ?: -1 }.thenBy { it.title.lowercase() })
            .take(MAX_TOTAL_RESULTS)
    }

    /** A valid empty response still means that the authenticated indexer is reachable. */
    fun test(config: IndexerConfig): Int = searchFeed(config, "jacket-connectivity-test").size

    private fun searchFeed(config: IndexerConfig, query: String): List<SourceResult> {
        val requestUrl = buildRequestUrl(config, query)
        val uri = Uri.parse(requestUrl)
        if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) {
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
                val encoded = Base64.encodeToString(credentials.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
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
            .replace("{query}", Uri.encode(query))
            .replace("{apikey}", Uri.encode(config.apiKey))
        val builder = Uri.parse(substituted).buildUpon()
        val existing = Uri.parse(substituted).queryParameterNames
        fun appendIfMissing(key: String, value: String?) {
            if (!value.isNullOrBlank() && key !in existing) builder.appendQueryParameter(key, value)
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
        return builder.build().toString()
    }

    private fun parseFeed(xml: ByteArray, config: IndexerConfig): List<SourceResult> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        parser.setInput(xml.inputStream(), "UTF-8")

        val result = mutableListOf<SourceResult>()
        var inEntry = false
        var title = ""
        var pageUrl: String? = null
        var magnet: String? = null
        var torrentUrl: String? = null
        var size: Long? = null
        var seeders: Int? = null
        var leechers: Int? = null

        fun reset() {
            title = ""
            pageUrl = null
            magnet = null
            torrentUrl = null
            size = null
            seeders = null
            leechers = null
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
                            when {
                                value.startsWith("magnet:", true) -> magnet = value
                                rel.equals("enclosure", true) && value.endsWith(".torrent", true) -> torrentUrl = value
                                value.isNotBlank() && pageUrl == null -> pageUrl = value
                            }
                        }
                        "enclosure" -> if (inEntry) {
                            val value = parser.getAttributeValue(null, "url").orEmpty()
                            val type = parser.getAttributeValue(null, "type").orEmpty()
                            when {
                                value.startsWith("magnet:", true) -> magnet = value
                                value.endsWith(".torrent", true) || type.contains("bittorrent", true) -> torrentUrl = value
                                value.startsWith("https://") && pageUrl == null -> pageUrl = value
                            }
                        }
                        "guid", "id" -> if (inEntry && pageUrl == null) {
                            pageUrl = parser.nextText().trim()
                        }
                        "attr" -> if (inEntry) {
                            val attr = parser.getAttributeValue(null, "name")?.lowercase().orEmpty()
                            val value = parser.getAttributeValue(null, "value")
                            when (attr) {
                                "magneturl" -> if (!value.isNullOrBlank()) magnet = value
                                "seeders" -> seeders = value?.toIntOrNull()?.takeIf { it >= 0 }
                                "peers", "leechers" -> leechers = value?.toIntOrNull()?.takeIf { it >= 0 }
                                "size" -> size = value?.toLongOrNull()?.takeIf { it >= 0L }
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val name = parser.name.substringAfter(':').lowercase()
                    if (inEntry && name in setOf("item", "entry")) {
                        val safeMagnet = magnet?.takeIf { it.startsWith("magnet:", true) }
                        val safeTorrent = torrentUrl?.takeIf(::isHttps)
                        val safePage = pageUrl?.takeIf(::isHttps)
                        if (title.isNotBlank() && (safeMagnet != null || safeTorrent != null || safePage != null)) {
                            result += SourceResult(
                                id = "${config.id}:${result.size}:${title.hashCode()}",
                                title = title,
                                providerName = config.name,
                                magnetUri = safeMagnet,
                                downloadUrl = safeTorrent,
                                sizeBytes = size,
                                seeders = seeders,
                                leechers = leechers,
                                pageUrl = safePage
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

    private fun isHttps(value: String): Boolean = runCatching {
        val uri = Uri.parse(value)
        uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null
    }.getOrDefault(false)

    private companion object {
        const val MAX_INDEXERS = 100
        const val MAX_TOTAL_RESULTS = 200
        const val MAX_FEED_RESULTS = 100
        const val MAX_FEED_BYTES = 4 * 1024 * 1024
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 25_000
        const val USER_AGENT = "JacketAndroid/2.1 (direct indexer federation)"
    }
}
