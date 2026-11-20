package app.jacket.mobile.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** A single indexer entry returned by Jackett's native REST catalogue. */
data class JackettIndexerEntry(
    val id: String,
    val name: String,
    val description: String = "",
    val siteLink: String? = null,
    val alternateSiteLinks: List<String> = emptyList(),
    val language: String? = null,
    val type: String? = null,
    val configured: Boolean = false,
    val categoryIds: List<String> = emptyList(),
    val categoryNames: List<String> = emptyList(),
    val lastError: String? = null
) {
    val isPrivate: Boolean
        get() = type.equals("private", ignoreCase = true)
}

/**
 * One dynamic setup field from Jackett's authenticated indexer configuration endpoint.
 * Password replacement markers are preserved if the user does not enter a new value.
 */
data class JackettConfigField(
    val id: String,
    val type: String,
    val name: String,
    val value: String = "",
    val values: List<String> = emptyList(),
    val options: Map<String, String> = emptyMap(),
    val separator: String? = null,
    val delimiters: String? = null,
    val pattern: String? = null,
    val whitelist: List<String> = emptyList(),
    val blacklist: List<String> = emptyList(),
    /** Original server value, retained so an unchanged masked password stays untouched. */
    val originalValue: String = value
) {
    val isPreviousPassword: Boolean
        get() = originalValue == PASSWORD_REPLACEMENT && value.isBlank()

    companion object {
        const val PASSWORD_REPLACEMENT = "|||%%PREVJACKPASSWD%%|||"
    }
}

/**
 * Jackett's native REST administration API.
 *
 * The public catalogue and per-indexer setup endpoints are different from Torznab search:
 * - GET /api/v2.0/indexers returns the public/private/configured/capabilities catalogue.
 * - GET /api/v2.0/indexers/{id}/config returns dynamic setup controls.
 * - POST /api/v2.0/indexers/{id}/config saves those controls.
 *
 * Management endpoints use the user's authenticated Jackett dashboard session cookie,
 * stored in IndexerConfig.cookie (encrypted locally). The API key remains for Torznab search.
 */
object JackettIndexerClient {
    private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    private const val MAX_CONFIG_FIELDS = 120
    private const val MAX_CONFIG_BODY_BYTES = 128 * 1024
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 12_000
    private const val USER_AGENT = "JacketAndroid/2.6 (Jackett native indexer manager)"
    private val INDEXER_ID_PATTERN = Regex("[a-zA-Z0-9._:-]{1,100}")

    fun fetch(config: IndexerConfig): List<JackettIndexerEntry> {
        validateJackettConfig(config)
        val url = buildIndexersUrl(baseUrl(config))
        val body = execute(config, url, "GET").toString(Charsets.UTF_8)
        val entries = parseIndexersJson(body)
        if (entries.isEmpty()) throw IOException("Jackett вернул пустой каталог индексаторов")
        return entries
    }

    /** Reads the editable controls for one indexer; dashboard Cookie is required when auth is enabled. */
    fun fetchIndexerConfig(config: IndexerConfig, indexerId: String): List<JackettConfigField> {
        validateJackettConfig(config)
        val url = buildIndexerConfigUrl(baseUrl(config), indexerId)
        val body = execute(config, url, "GET").toString(Charsets.UTF_8)
        return parseIndexerConfig(body)
    }

    /** Runs Jackett's own tracker test endpoint and lets it update last_error. */
    fun testIndexer(config: IndexerConfig, indexerId: String) {
        validateJackettConfig(config)
        execute(config, buildIndexerTestUrl(baseUrl(config), indexerId), "POST")
    }

    /** Saves the dynamic controls and lets Jackett test the indexer when its config requires it. */
    fun updateIndexerConfig(
        config: IndexerConfig,
        indexerId: String,
        fields: List<JackettConfigField>
    ) {
        validateJackettConfig(config)
        require(fields.size <= MAX_CONFIG_FIELDS) { "Слишком много настроек индексатора" }
        val url = buildIndexerConfigUrl(baseUrl(config), indexerId)
        val body = buildConfigPayload(fields).toString()
        require(body.toByteArray(Charsets.UTF_8).size <= MAX_CONFIG_BODY_BYTES) {
            "Данные настройки индексатора слишком большие"
        }
        execute(config, url, "POST", body.toByteArray(Charsets.UTF_8))
    }

    /** Creates the REST catalogue endpoint used by Jackett's own dashboard. */
    internal fun buildIndexersUrl(baseUrl: String): String {
        val base = JackettUrl.normalizeBase(baseUrl)
            ?: throw IllegalArgumentException("Некорректный адрес сервера Jackett")
        return "$base/api/v2.0/indexers"
    }

    internal fun buildIndexerConfigUrl(baseUrl: String, indexerId: String): String {
        require(INDEXER_ID_PATTERN.matches(indexerId)) {
            "Jackett вернул некорректный идентификатор индексатора"
        }
        return buildIndexersUrl(baseUrl) + "/" +
            URLEncoder.encode(indexerId, Charsets.UTF_8.name()) + "/config"
    }

    internal fun buildIndexerTestUrl(baseUrl: String, indexerId: String): String {
        require(INDEXER_ID_PATTERN.matches(indexerId)) {
            "Jackett вернул некорректный идентификатор индексатора"
        }
        return buildIndexersUrl(baseUrl) + "/" +
            URLEncoder.encode(indexerId, Charsets.UTF_8.name()) + "/test"
    }

    /** Converts one catalogue row into the existing in-app Torznab search configuration. */
    fun searchConfig(base: IndexerConfig, indexerId: String): IndexerConfig {
        require(INDEXER_ID_PATTERN.matches(indexerId)) {
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

    internal fun parseIndexersJson(json: String): List<JackettIndexerEntry> {
        require(json.length <= MAX_RESPONSE_BYTES) { "Ответ каталога Jackett слишком большой" }
        val array = runCatching { JSONArray(json) }
            .getOrElse { throw IOException("Jackett вернул некорректный JSON каталога", it) }
        val entries = LinkedHashMap<String, JackettIndexerEntry>()
        for (index in 0 until minOf(array.length(), 1_000)) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("id").trim()
            if (!INDEXER_ID_PATTERN.matches(id)) continue
            val capabilities = item.optJSONArray("caps")
            val categoryIds = ArrayList<String>()
            val categoryNames = ArrayList<String>()
            if (capabilities != null) {
                for (capIndex in 0 until minOf(capabilities.length(), 500)) {
                    val capability = capabilities.optJSONObject(capIndex) ?: continue
                    val categoryId = capability.optString("ID").takeIf(String::isNotBlank)
                    val categoryName = capability.optString("Name").trim().takeIf(String::isNotBlank)
                    if (categoryId != null) categoryIds += categoryId
                    if (categoryName != null) categoryNames += categoryName
                }
            }
            val alternateLinks = item.optJSONArray("alternativesitelinks")?.toStringList().orEmpty()
                .filter(LocalNetwork::isAllowedUrl).take(20)
            val siteLink = item.optString("site_link").takeIf(String::isNotBlank)
                ?.takeIf(LocalNetwork::isAllowedUrl)
            entries[id] = JackettIndexerEntry(
                id = id,
                name = item.optString("name").ifBlank { id }.take(160),
                description = item.optString("description").take(1200),
                siteLink = siteLink,
                alternateSiteLinks = alternateLinks,
                language = item.optString("language").takeIf(String::isNotBlank),
                type = item.optString("type").takeIf(String::isNotBlank),
                configured = item.optBoolean("configured", false),
                categoryIds = categoryIds.distinct().take(100),
                categoryNames = categoryNames.distinct().take(100),
                lastError = item.optString("last_error").takeIf(String::isNotBlank)?.take(240)
            )
        }
        return entries.values.sortedWith(
            compareBy<JackettIndexerEntry> { !it.configured }
                .thenBy { it.name.lowercase(Locale.ROOT) }
        )
    }

    internal fun parseIndexerConfig(json: String): List<JackettConfigField> {
        require(json.length <= MAX_RESPONSE_BYTES) { "Ответ настроек Jackett слишком большой" }
        val array = runCatching { JSONArray(json) }
            .getOrElse { throw IOException("Jackett вернул некорректные настройки индексатора", it) }
        return buildList {
            for (index in 0 until minOf(array.length(), MAX_CONFIG_FIELDS)) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id").trim()
                val type = item.optString("type").lowercase(Locale.ROOT)
                if (id.isBlank() || id.length > 120 || type.isBlank()) continue
                val values = item.optJSONArray("values")?.toStringList().orEmpty()
                val optionsObject = item.optJSONObject("options")
                val options = LinkedHashMap<String, String>()
                if (optionsObject != null) {
                    val keys = optionsObject.keys()
                    while (keys.hasNext() && options.size < 300) {
                        val key = keys.next()
                        options[key] = optionsObject.optString(key).take(300)
                    }
                }
                val fieldValue = item.opt("value")?.let { value ->
                    if (value == JSONObject.NULL) "" else value.toString()
                }.orEmpty().take(8_192)
                add(
                    JackettConfigField(
                        id = id,
                        type = type,
                        name = item.optString("name").ifBlank { id }.take(160),
                        value = fieldValue,
                        originalValue = fieldValue,
                        values = values.take(300),
                        options = options,
                        separator = item.optString("separator").takeIf(String::isNotBlank)?.take(4),
                        delimiters = item.optString("delimiters").takeIf(String::isNotBlank)?.take(200),
                        pattern = item.optString("pattern").takeIf(String::isNotBlank)?.take(300),
                        whitelist = item.optString("whitelist").split(',').map(String::trim)
                            .filter(String::isNotBlank).take(200),
                        blacklist = item.optString("blacklist").split(',').map(String::trim)
                            .filter(String::isNotBlank).take(200)
                    )
                )
            }
        }
    }

    /** Builds the exact id/value(s) array consumed by Jackett's UpdateConfig controller. */
    internal fun buildConfigPayload(fields: List<JackettConfigField>): JSONArray {
        val payload = JSONArray()
        fields.forEach { field ->
            require(field.id.isNotBlank() && field.id.length <= 120) { "Некорректное поле настройки" }
            val item = JSONObject().put("id", field.id)
            when (field.type) {
                "inputcheckbox" -> {
                    val allowed = field.options.keys
                    val safeValues = field.values.distinct().filter { it in allowed }.take(300)
                    item.put("values", JSONArray(safeValues))
                }
                "inputbool" -> item.put("value", field.value.equals("true", ignoreCase = true))
                "password" -> {
                    // Jackett's password marker means "leave the stored secret unchanged".
                    // The UI keeps that intent even after a user types and then clears the field.
                    val passwordValue = if (
                        field.value.isBlank() && field.originalValue == JackettConfigField.PASSWORD_REPLACEMENT
                    ) {
                        JackettConfigField.PASSWORD_REPLACEMENT
                    } else {
                        field.value
                    }
                    item.put("value", passwordValue.take(8_192))
                }
                "inputstring", "inputselect", "inputtags", "hiddendata" ->
                    item.put("value", field.value.take(8_192))
                // Jackett's own dashboard includes an id-only entry for display-only controls.
                "displayinfo", "displayimage" -> Unit
                else -> Unit
            }
            payload.put(item)
        }
        return payload
    }

    private fun execute(
        config: IndexerConfig,
        url: String,
        method: String,
        body: ByteArray? = null
    ): ByteArray {
        if (!LocalNetwork.isAllowedUrl(url)) {
            throw IOException("Jackett должен быть доступен по HTTPS или по локальному HTTP")
        }
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", USER_AGENT)
            if (config.cookie.isNotBlank()) setRequestProperty("Cookie", config.cookie.take(8_192))
            if (config.username.isNotBlank() || config.password.isNotBlank()) {
                val credentials = "${config.username}:${config.password}"
                val encoded = java.util.Base64.getEncoder()
                    .encodeToString(credentials.toByteArray(Charsets.UTF_8))
                setRequestProperty("Authorization", "Basic $encoded")
            }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setFixedLengthStreamingMode(body.size)
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            if (status !in 200..299) {
                val message = when (status) {
                    401, 403 -> "Jackett отклонил доступ. Проверьте Cookie авторизованной панели в настройках."
                    404 -> "Jackett не нашёл REST endpoint /api/v2.0/indexers."
                    429 -> "Jackett временно ограничил количество запросов."
                    else -> "Jackett вернул HTTP $status."
                }
                throw IOException(message)
            }
            if (status == HttpURLConnection.HTTP_NO_CONTENT) return ByteArray(0)
            val stream = connection.inputStream
            return stream.use { readLimited(it, MAX_RESPONSE_BYTES) }
        } finally {
            connection.disconnect()
        }
    }

    private fun validateJackettConfig(config: IndexerConfig) {
        require(config.protocol == IndexerProtocol.JACKETT) {
            "Для каталога индексаторов нужен подключённый Jackett"
        }
        require(config.apiKey.isNotBlank()) { "Укажите API-ключ Jackett для поиска Torznab" }
    }

    private fun baseUrl(config: IndexerConfig): String =
        JackettUrl.parseTemplate(config.searchUrlTemplate)?.baseUrl
            ?: throw IOException("Не удалось определить адрес сервера Jackett")

    private fun JSONArray.toStringList(): List<String> = buildList {
        for (i in 0 until minOf(length(), 500)) {
            val value = optString(i).takeIf(String::isNotBlank) ?: continue
            add(value.take(300))
        }
    }

    private fun readLimited(input: java.io.InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) throw IOException("Ответ Jackett слишком большой")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
