package app.jacket.mobile.data

import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/** Small Torznab client for a Jackett server. All network methods must run off the main thread. */
class JackettClient {
    fun testConnection(serverUrl: String, apiKey: String) {
        val body = request(serverUrl, apiKey, mapOf("t" to "caps"))
        val text = body.toString(Charsets.UTF_8)
        if (text.contains("<error", ignoreCase = true)) {
            throw IOException(extractXmlError(text) ?: "Jackett вернул ошибку API")
        }
        if (!text.contains("<caps", ignoreCase = true)) {
            throw IOException("Ответ получен, но это не похоже на Torznab API Jackett")
        }
    }

    fun search(serverUrl: String, apiKey: String, query: String): List<TorrentEntry> {
        val body = request(serverUrl, apiKey, mapOf("t" to "search", "q" to query))
        val text = body.toString(Charsets.UTF_8)
        if (text.contains("<error", ignoreCase = true)) {
            throw IOException(extractXmlError(text) ?: "Jackett вернул ошибку поиска")
        }
        return parseResults(body)
    }

    private fun request(serverUrl: String, apiKey: String, parameters: Map<String, String>): ByteArray {
        val normalized = serverUrl.trim().trimEnd('/')
        val base = Uri.parse(normalized)
        val host = base.host?.trim('[', ']')
        if (base.scheme !in setOf("http", "https") || host.isNullOrBlank() || base.userInfo != null) {
            throw IOException("Укажите корректный URL Jackett, например http://192.168.1.10:9117")
        }
        if (base.scheme == "http" && !isPrivateOrLoopbackHost(host)) {
            throw IOException("Незащищённый HTTP разрешён только для локального сервера. Для внешнего адреса используйте HTTPS или VPN.")
        }
        if (apiKey.isBlank()) throw IOException("Не указан API-ключ Jackett")

        val builder = base.buildUpon()
            .clearQuery()
            .fragment(null)
            .appendPath("api")
            .appendPath("v2.0")
            .appendPath("indexers")
            .appendPath("all")
            .appendPath("results")
            .appendPath("torznab")
            .appendPath("api")
            .appendQueryParameter("apikey", apiKey)
        parameters.forEach { (key, value) -> builder.appendQueryParameter(key, value) }

        val connection = (URL(builder.build().toString()).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/xml, application/rss+xml;q=0.9, */*;q=0.8")
            setRequestProperty("User-Agent", "JacketAndroid/1.2")
            setRequestProperty("Cache-Control", "no-store")
            instanceFollowRedirects = false
        }

        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_RESPONSE_BYTES) throw IOException("Ответ Jackett слишком большой")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: ByteArray(0)

            if (status in 300..399) {
                throw IOException("Jackett перенаправил запрос (HTTP $status). Укажите конечный URL сервера.")
            }
            if (status !in 200..299) {
                val message = extractXmlError(response.toString(Charsets.UTF_8))
                throw IOException(message ?: "Ошибка Jackett: HTTP $status")
            }
            return response
        } finally {
            connection.disconnect()
        }
    }

    private fun parseResults(xml: ByteArray): List<TorrentEntry> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        parser.setInput(xml.inputStream(), "UTF-8")

        val results = mutableListOf<TorrentEntry>()
        var inItem = false
        var title = ""
        var link: String? = null
        var magnet: String? = null
        var size: Long? = null
        var seeders: Int? = null
        var leechers: Int? = null
        var published: String? = null
        var indexer: String? = null
        var guid: String? = null

        fun resetItem() {
            title = ""
            link = null
            magnet = null
            size = null
            seeders = null
            leechers = null
            published = null
            indexer = null
            guid = null
        }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT && results.size < MAX_RESULTS) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name.substringAfter(':')) {
                        "item" -> {
                            inItem = true
                            resetItem()
                        }
                        "title" -> if (inItem) title = parser.nextText().trim()
                        "link" -> if (inItem) {
                            val value = parser.nextText().trim()
                            if (value.startsWith("magnet:", ignoreCase = true)) magnet = value else link = value
                        }
                        "pubDate" -> if (inItem) published = parser.nextText().trim()
                        "guid" -> if (inItem) guid = parser.nextText().trim()
                        "enclosure" -> if (inItem) {
                            val enclosureUrl = parser.getAttributeValue(null, "url")
                            if (!enclosureUrl.isNullOrBlank()) {
                                if (enclosureUrl.startsWith("magnet:", ignoreCase = true)) magnet = enclosureUrl
                                else if (link.isNullOrBlank()) link = enclosureUrl
                            }
                        }
                        "attr" -> if (inItem) {
                            val name = parser.getAttributeValue(null, "name")?.lowercase().orEmpty()
                            val value = parser.getAttributeValue(null, "value")
                            when (name) {
                                "magneturl" -> if (!value.isNullOrBlank()) magnet = value
                                "size" -> size = value?.toLongOrNull()?.takeIf { it >= 0 }
                               "seeders" -> seeders = value?.toIntOrNull()?.takeIf { it >= 0 }
                                "peers" -> leechers = value?.toIntOrNull()?.takeIf { it >= 0 }
                                "leechers" -> leechers = value?.toIntOrNull()?.takeIf { it >= 0 }
                               "infohash" -> if (magnet.isNullOrBlank() && !value.isNullOrBlank()) {
                                    magnet = "magnet:?xt=urn:btih:$value&dn=${Uri.encode(title)}"
                                }
                                "indexer" -> indexer = value
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name.substringAfter(':') == "item" && inItem) {
                    val cleanMagnet = magnet?.takeIf { it.startsWith("magnet:", ignoreCase = true) }
                    val cleanLink = link?.takeIf { it.isNotBlank() }
                    if (title.isNotBlank() && (cleanMagnet != null || cleanLink != null)) {
                        results += TorrentEntry(
                            id = guid?.takeIf { it.isNotBlank() } ?: cleanMagnet ?: "$cleanLink|$title",
                            title = title,
                            magnetUri = cleanMagnet,
                            torrentUrl = cleanLink,
                            sizeBytes = size,
                            seeders = seeders,
                            leechers = leechers,
                            publishedAt = published,
                            indexer = indexer
                        )
                    }
                    inItem = false
                }
            }
            event = parser.next()
        }
        return results.distinctBy { it.id }
            .sortedWith(compareByDescending<TorrentEntry> { it.seeders ?: -1 }.thenBy { it.title.lowercase() })
    }

    private fun isPrivateOrLoopbackHost(host: String): Boolean {
        if (host.equals("localhost", ignoreCase = true) || host.endsWith(".local", ignoreCase = true)) return true
        // Do not permit arbitrary DNS names over HTTP: only literal private/loopback addresses.
        if (!(host.matches(Regex("[0-9.]+")) || host.contains(':'))) return false
        return runCatching {
            val address = InetAddress.getByName(host)
            address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
                address.isSiteLocalAddress || (address.address.size == 16 &&
                (address.address[0].toInt() and 0xfe) == 0xfc)
        }.getOrDefault(false)
    }

    private fun extractXmlError(body: String): String? {
        val match = Regex("<error[^>]*\\bdescription=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
            .find(body)
        return match?.groupValues?.getOrNull(1)
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 12_000
        const val READ_TIMEOUT_MS = 25_000
        const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024
        const val MAX_RESULTS = 250
    }
}
