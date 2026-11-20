package app.jacket.mobile.data

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** A single tracker entry returned by Jackett's Torznab t=indexers endpoint. */
data class JackettIndexerEntry(
    val id: String,
    val name: String,
    val description: String = "",
    val siteLink: String? = null,
    val language: String? = null,
    val type: String? = null,
    val configured: Boolean = false,
    val categoryIds: List<String> = emptyList(),
    val categoryNames: List<String> = emptyList()
) {
    val isPrivate: Boolean
        get() = type.equals("private", ignoreCase = true)
}

/**
 * Reads the indexer catalogue from the user's own Jackett instance.
 *
 * This client intentionally uses only the documented Torznab API-key endpoint:
 * it never attempts to log into an arbitrary dashboard, bypass authentication,
 * solve challenges, or configure remote accounts without an explicit session.
 */
object JackettIndexerClient {
    private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 12_000
    private const val USER_AGENT = "JacketAndroid/2.6 (Jackett indexer catalogue)"

    fun fetch(config: IndexerConfig): List<JackettIndexerEntry> {
        require(config.protocol == IndexerProtocol.JACKETT) {
            "Для каталога индексаторов нужен подключённый Jackett"
        }
        require(config.apiKey.isNotBlank()) { "Укажите API-ключ Jackett" }
        val parsed = JackettUrl.parseTemplate(config.searchUrlTemplate)
            ?: throw IOException("Не удалось определить адрес сервера Jackett")
        val requestUrl = buildCatalogUrl(parsed.baseUrl, config.apiKey)
        if (!LocalNetwork.isAllowedUrl(requestUrl)) {
            throw IOException("Jackett должен быть доступен по HTTPS или по локальному HTTP")
        }

        val connection = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/xml, text/xml")
            setRequestProperty("User-Agent", USER_AGENT)
            if (config.username.isNotBlank() || config.password.isNotBlank()) {
                val credentials = "${config.username}:${config.password}"
                val encoded = java.util.Base64.getEncoder()
                    .encodeToString(credentials.toByteArray(Charsets.UTF_8))
                setRequestProperty("Authorization", "Basic $encoded")
            }
            if (config.cookie.isNotBlank()) setRequestProperty("Cookie", config.cookie.take(4096))
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                val label = when (status) {
                    401, 403 -> "Jackett отклонил API-ключ или доступ к серверу"
                    404 -> "Сервер Jackett не поддерживает каталог t=indexers по этому адресу"
                    429 -> "Jackett временно ограничил количество запросов"
                    else -> "Jackett вернул HTTP $status"
                }
                throw IOException(label)
            }
            val xml = connection.inputStream.use { readLimited(it, MAX_RESPONSE_BYTES) }
                .toString(Charsets.UTF_8)
            val entries = parseIndexers(xml)
            if (entries.isEmpty()) throw IOException("Jackett вернул пустой каталог индексаторов")
            return entries
        } finally {
            connection.disconnect()
        }
    }

    /** Creates the standard aggregate endpoint for Jackett's native indexer catalogue. */
    internal fun buildCatalogUrl(baseUrl: String, apiKey: String): String {
        require(apiKey.isNotBlank()) { "API-ключ Jackett не указан" }
        val base = JackettUrl.normalizeBase(baseUrl)
            ?: throw IllegalArgumentException("Некорректный адрес сервера Jackett")
        val encodedKey = URLEncoder.encode(apiKey.trim(), Charsets.UTF_8.name())
        return "$base/api/v2.0/indexers/all/results/torznab/api?apikey=$encodedKey&t=indexers"
    }

    /** Converts one catalogue row into an existing in-app Torznab search configuration. */
    fun searchConfig(base: IndexerConfig, indexerId: String): IndexerConfig {
        require(indexerId.matches(Regex("[a-zA-Z0-9._:-]{1,100}"))) {
            "Jackett вернул некорректный идентификатор индексатора"
        }
        val parsed = JackettUrl.parseTemplate(base.searchUrlTemplate)
            ?: throw IllegalArgumentException("Некорректный URL Jackett")
        return base.copy(
            id = base.id,
            name = "Jackett · $indexerId",
            searchUrlTemplate = JackettUrl.searchTemplate(parsed.baseUrl, indexerId),
            protocol = IndexerProtocol.JACKETT,
            enabled = base.enabled
        )
    }

    internal fun parseIndexers(xml: String): List<JackettIndexerEntry> =
        parseIndexers(xml, XmlPullParserFactory.newInstance().newPullParser())

    /** Parser injection keeps the catalogue parser deterministic in JVM tests. */
    internal fun parseIndexers(xml: String, parser: XmlPullParser): List<JackettIndexerEntry> {
        require(xml.length <= MAX_RESPONSE_BYTES) { "Ответ каталога Jackett слишком большой" }
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        runCatching { parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false) }
        parser.setInput(xml.reader())

        data class MutableEntry(
            val id: String,
            val configured: Boolean,
            var name: String = "",
            var description: String = "",
            var link: String? = null,
            var language: String? = null,
            var type: String? = null,
            val categoryIds: MutableList<String> = mutableListOf(),
            val categoryNames: MutableList<String> = mutableListOf()
        )

        val entries = LinkedHashMap<String, JackettIndexerEntry>()
        var current: MutableEntry? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name.substringAfter(':').lowercase(Locale.ROOT)
                    if (name == "indexer") {
                        val id = parser.getAttributeValue(null, "id").orEmpty().trim()
                        if (id.matches(Regex("[a-zA-Z0-9._:-]{1,100}"))) {
                            current = MutableEntry(
                                id = id,
                                configured = parser.getAttributeValue(null, "configured")
                                    ?.equals("true", ignoreCase = true) == true
                            )
                        }
                    } else {
                        val entry = current
                        if (entry != null) {
                            when (name) {
                                "title" -> entry.name = parser.nextText().trim()
                                "description" -> entry.description = parser.nextText().trim().take(1200)
                                "link" -> {
                                    val value = parser.nextText().trim()
                                    entry.link = value.takeIf(LocalNetwork::isAllowedUrl)
                                }
                                "language" -> entry.language = parser.nextText().trim().takeIf(String::isNotBlank)
                                "type" -> entry.type = parser.nextText().trim().takeIf(String::isNotBlank)
                                "category" -> {
                                    val id = parser.getAttributeValue(null, "id")?.trim().orEmpty()
                                    val label = parser.getAttributeValue(null, "name")?.trim().orEmpty()
                                    if (id.isNotBlank()) entry.categoryIds += id
                                    if (label.isNotBlank()) entry.categoryNames += label
                                }
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name.substringAfter(':').equals("indexer", true)) {
                    val entry = current
                    if (entry != null) {
                        val name = entry.name.ifBlank { entry.id }
                        entries[entry.id] = JackettIndexerEntry(
                            id = entry.id,
                            name = name,
                            description = entry.description,
                            siteLink = entry.link,
                            language = entry.language,
                            type = entry.type,
                            configured = entry.configured,
                            categoryIds = entry.categoryIds.distinct().take(100),
                            categoryNames = entry.categoryNames.distinct().take(100)
                        )
                    }
                    current = null
                }
                XmlPullParser.DOCDECL -> throw IOException("XML document type declarations are not supported")
            }
            event = parser.next()
        }
        return entries.values.sortedWith(
            compareBy<JackettIndexerEntry> { !it.configured }
                .thenBy { it.name.lowercase(Locale.ROOT) }
        )
    }

    private fun readLimited(input: java.io.InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) throw IOException("Ответ каталога Jackett слишком большой")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
