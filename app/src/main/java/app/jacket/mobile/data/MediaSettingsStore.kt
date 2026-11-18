package app.jacket.mobile.data

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Local settings. TMDB and all indexer credentials are encrypted with Android Keystore. */
class MediaSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun tmdbToken(): String = decrypt(preferences.getString(KEY_TMDB_TOKEN, null))

    fun saveTmdbToken(token: String) {
        preferences.edit { putString(KEY_TMDB_TOKEN, encrypt(token.trim())) }
    }

    fun filmixToken(): String = decrypt(preferences.getString(KEY_FILMIX_TOKEN, null))

    @Synchronized
    fun saveFilmixToken(token: String) {
        preferences.edit { putString(KEY_FILMIX_TOKEN, encrypt(token.trim())) }
    }

    @Synchronized
    fun clearFilmixToken() {
        preferences.edit { remove(KEY_FILMIX_TOKEN) }
    }

    /** Stable per-install identifier required by Filmix's device-code endpoint. */
    @Synchronized
    fun filmixDeviceId(): String {
        preferences.getString(KEY_FILMIX_DEVICE_ID, null)
            ?.takeIf { it.matches(Regex("[a-fA-F0-9]{16}")) }
            ?.let { return it }
        val bytes = ByteArray(8).also(java.security.SecureRandom()::nextBytes)
        val id = bytes.joinToString("") { "%02x".format(it) }
        preferences.edit { putString(KEY_FILMIX_DEVICE_ID, id) }
        return id
    }

    fun hdRezkaSettings(): HdRezkaSettings = HdRezkaSettings(
        mirror = preferences.getString(KEY_HDREZKA_MIRROR, "") ?: "",
        login = decrypt(preferences.getString(KEY_HDREZKA_LOGIN, null)),
        password = decrypt(preferences.getString(KEY_HDREZKA_PASSWORD, null)),
        cookie = decrypt(preferences.getString(KEY_HDREZKA_COOKIE, null)),
        connected = preferences.getBoolean(KEY_HDREZKA_CONNECTED, false)
    )

    @Synchronized
    fun saveHdRezkaSettings(settings: HdRezkaSettings) {
        val mirror = settings.mirror.trim().take(300).let {
            if (it.isBlank()) "" else HdRezkaLoginClient.validateMirror(it)
        }
        val login = settings.login.replace(Regex("[\\r\\n]"), "").trim().take(256)
        val password = settings.password.take(512)
        val cookie = settings.cookie.replace(Regex("[\\r\\n]"), "").trim().take(8_192)
        preferences.edit {
            putString(KEY_HDREZKA_MIRROR, mirror)
            putString(KEY_HDREZKA_LOGIN, encrypt(login))
            putString(KEY_HDREZKA_PASSWORD, encrypt(password))
            putString(KEY_HDREZKA_COOKIE, encrypt(cookie))
            putBoolean(KEY_HDREZKA_CONNECTED, settings.connected && cookie.isNotBlank())
        }
    }

    @Synchronized
    fun disconnectHdRezka() {
        val current = hdRezkaSettings()
        saveHdRezkaSettings(current.copy(cookie = "", connected = false))
    }

    fun indexers(): List<IndexerConfig> = runCatching {
        val array = JSONArray(preferences.getString(KEY_INDEXERS, "[]"))
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val config = IndexerConfig(
                    id = item.optString("id"),
                    name = item.optString("name"),
                    searchUrlTemplate = item.optString("url"),
                    protocol = runCatching {
                        IndexerProtocol.valueOf(item.optString("protocol", "RSS"))
                    }.getOrDefault(IndexerProtocol.RSS),
                    apiKey = decrypt(item.optString("apiKeyEnc")),
                    username = decrypt(item.optString("usernameEnc")),
                    password = decrypt(item.optString("passwordEnc")),
                    cookie = decrypt(item.optString("cookieEnc")),
                    categories = item.optString("categories"),
                    tags = item.optString("tags"),
                    freeleechOnly = item.optBoolean("freeleechOnly", false),
                    sortBy = item.optString("sortBy", "created"),
                    sortOrder = item.optString("sortOrder", "desc"),
                    enabled = item.optBoolean("enabled", true)
                )
                if (config.id.isNotBlank() && config.name.isNotBlank() && isValidTemplate(config.searchUrlTemplate)) {
                    add(config)
                }
            }
        }.distinctBy { it.id }.take(MAX_INDEXERS)
    }.getOrDefault(emptyList())

    /** Upsert also supports editing an existing indexer without losing its stable ID. */
    @Synchronized
    fun saveIndexer(config: IndexerConfig): IndexerConfig {
        require(config.name.isNotBlank()) { "Укажите название индексатора" }
        require(isValidTemplate(config.searchUrlTemplate)) {
            "Нужен HTTPS URL поиска с маркером {query}. Для Torznab обычно используют /api?t=search&q={query}"
        }
        val normalized = config.copy(
            id = config.id.ifBlank { UUID.randomUUID().toString() },
            name = config.name.trim().take(80),
            searchUrlTemplate = config.searchUrlTemplate.trim(),
            apiKey = config.apiKey.replace(Regex("[\\r\\n]"), "").trim().take(512),
            username = config.username.replace(Regex("[\\r\\n]"), "").trim().take(256),
            password = config.password.take(512),
            // Prevent custom auth values from injecting HTTP headers.
            cookie = config.cookie.replace(Regex("[\\r\\n]"), "").trim().take(4096),
            categories = config.categories.trim().take(500),
            tags = config.tags.trim().take(300),
            sortBy = config.sortBy.trim().take(40).ifBlank { "created" },
            sortOrder = config.sortOrder.trim().lowercase().takeIf { it in setOf("asc", "desc") } ?: "desc"
        )
        val current = indexers().filterNot { it.id == normalized.id }
        require(current.size < MAX_INDEXERS) { "Достигнут лимит в $MAX_INDEXERS индексаторов" }
        saveIndexers(current + normalized)
        return normalized
    }

    @Synchronized
    fun addIndexer(name: String, urlTemplate: String) {
        saveIndexer(IndexerConfig(id = "", name = name, searchUrlTemplate = urlTemplate))
    }

    /** Export only non-secret fields; authentication must be re-entered after import. */
    fun exportIndexersJson(): String {
        val root = JSONArray()
        indexers().forEach { item ->
            root.put(
                JSONObject()
                    .put("name", item.name)
                    .put("url", item.searchUrlTemplate)
                    .put("protocol", item.protocol.name)
                    .put("categories", item.categories)
                    .put("tags", item.tags)
                    .put("freeleechOnly", item.freeleechOnly)
                    .put("sortBy", item.sortBy)
                    .put("sortOrder", item.sortOrder)
                    .put("enabled", item.enabled)
            )
        }
        return root.toString(2)
    }

    /** Import replaces nothing: each validated source is added with blank credentials. */
    @Synchronized
    fun importIndexersJson(json: String): Int {
        require(json.length <= MAX_IMPORT_BYTES) { "Файл настроек слишком большой" }
        val root = JSONArray(json)
        var imported = 0
        for (i in 0 until minOf(root.length(), MAX_INDEXERS)) {
            val item = root.optJSONObject(i) ?: continue
            val config = runCatching {
                IndexerConfig(
                    id = "",
                    name = item.optString("name"),
                    searchUrlTemplate = item.optString("url"),
                    protocol = runCatching { IndexerProtocol.valueOf(item.optString("protocol", "RSS")) }
                        .getOrDefault(IndexerProtocol.RSS),
                    categories = item.optString("categories"),
                    tags = item.optString("tags"),
                    freeleechOnly = item.optBoolean("freeleechOnly", false),
                    sortBy = item.optString("sortBy", "created"),
                    sortOrder = item.optString("sortOrder", "desc"),
                    enabled = item.optBoolean("enabled", true)
                )
            }.getOrNull() ?: continue
            if (runCatching { saveIndexer(config) }.isSuccess) imported++
        }
        return imported
    }

    @Synchronized
    fun setIndexerEnabled(id: String, enabled: Boolean) {
        val updated = indexers().map { if (it.id == id) it.copy(enabled = enabled) else it }
        saveIndexers(updated)
    }

    @Synchronized
    fun removeIndexer(id: String) {
        saveIndexers(indexers().filterNot { it.id == id })
    }

    @Synchronized
    fun webSearchProviders(): List<WebSearchProvider> {
        if (!preferences.getBoolean(KEY_TRACKER_PRESETS_SEEDED, false)) {
            val merged = (readWebSearchProviders() + DEFAULT_TRACKER_SHORTCUTS)
                .distinctBy { it.id }
                .take(MAX_WEB_SEARCH_PROVIDERS)
            saveWebSearchProviders(merged)
            preferences.edit { putBoolean(KEY_TRACKER_PRESETS_SEEDED, true) }
        }
        return readWebSearchProviders()
    }

    @Synchronized
    fun restoreDefaultTrackerShortcuts(): List<WebSearchProvider> {
        val merged = (webSearchProviders() + DEFAULT_TRACKER_SHORTCUTS)
            .distinctBy { it.id }
            .take(MAX_WEB_SEARCH_PROVIDERS)
        saveWebSearchProviders(merged)
        return readWebSearchProviders()
    }

    private fun readWebSearchProviders(): List<WebSearchProvider> = runCatching {
        val array = JSONArray(preferences.getString(KEY_WEB_SEARCH_PROVIDERS, "[]"))
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val provider = WebSearchProvider(
                    id = item.optString("id"),
                    name = item.optString("name"),
                    searchUrlTemplate = item.optString("url"),
                    enabled = item.optBoolean("enabled", true)
                )
                if (provider.id.isNotBlank() && provider.name.isNotBlank() &&
                    runCatching { WebSearchUrlBuilder.validateTemplate(provider.searchUrlTemplate) }.isSuccess
                ) add(provider)
            }
        }.distinctBy { it.id }.take(MAX_WEB_SEARCH_PROVIDERS)
    }.getOrDefault(emptyList())

    @Synchronized
    fun saveWebSearchProvider(provider: WebSearchProvider): WebSearchProvider {
        require(provider.name.isNotBlank()) { "Укажите название сайта" }
        val normalized = provider.copy(
            id = provider.id.ifBlank { UUID.randomUUID().toString() },
            name = provider.name.trim().take(80),
            searchUrlTemplate = WebSearchUrlBuilder.validateTemplate(provider.searchUrlTemplate)
        )
        val current = webSearchProviders().filterNot { it.id == normalized.id }
        require(current.size < MAX_WEB_SEARCH_PROVIDERS) {
            "Достигнут лимит в $MAX_WEB_SEARCH_PROVIDERS сайтов поиска"
        }
        saveWebSearchProviders(current + normalized)
        return normalized
    }

    @Synchronized
    fun setWebSearchProviderEnabled(id: String, enabled: Boolean) {
        saveWebSearchProviders(webSearchProviders().map {
            if (it.id == id) it.copy(enabled = enabled) else it
        })
    }

    @Synchronized
    fun removeWebSearchProvider(id: String) {
        saveWebSearchProviders(webSearchProviders().filterNot { it.id == id })
    }

    fun favorites(): List<CatalogItem> = loadItems(KEY_FAVORITES)
    fun history(): List<CatalogItem> = loadItems(KEY_HISTORY)

    @Synchronized
    fun saveFavorites(items: List<CatalogItem>) = saveItems(KEY_FAVORITES, items.take(MAX_SAVED_ITEMS))

    @Synchronized
    fun addToHistory(item: CatalogItem) {
        saveItems(KEY_HISTORY, (listOf(item) + history().filterNot { it.id == item.id }).take(MAX_HISTORY))
    }

    private fun loadItems(key: String): List<CatalogItem> = runCatching {
        val array = JSONArray(preferences.getString(key, "[]"))
        buildList {
            for (i in 0 until array.length()) {
                catalogItemFromJson(array.optJSONObject(i) ?: continue)?.let(::add)
            }
        }
    }.getOrDefault(emptyList())

    private fun saveItems(key: String, items: List<CatalogItem>) {
        val json = JSONArray()
        items.forEach { json.put(it.toJson()) }
        preferences.edit { putString(key, json.toString()) }
    }

    private fun saveWebSearchProviders(items: List<WebSearchProvider>) {
        val json = JSONArray()
        items.forEach { provider ->
            json.put(
                JSONObject()
                    .put("id", provider.id)
                    .put("name", provider.name)
                    .put("url", provider.searchUrlTemplate)
                    .put("enabled", provider.enabled)
            )
        }
        preferences.edit { putString(KEY_WEB_SEARCH_PROVIDERS, json.toString()) }
    }

    private fun saveIndexers(items: List<IndexerConfig>) {
        val json = JSONArray()
        items.forEach { item ->
            json.put(
                JSONObject()
                    .put("id", item.id)
                    .put("name", item.name)
                    .put("url", item.searchUrlTemplate)
                    .put("protocol", item.protocol.name)
                    .put("apiKeyEnc", encrypt(item.apiKey))
                    .put("usernameEnc", encrypt(item.username))
                    .put("passwordEnc", encrypt(item.password))
                    .put("cookieEnc", encrypt(item.cookie))
                    .put("categories", item.categories)
                    .put("tags", item.tags)
                    .put("freeleechOnly", item.freeleechOnly)
                    .put("sortBy", item.sortBy)
                    .put("sortOrder", item.sortOrder)
                    .put("enabled", item.enabled)
            )
        }
        preferences.edit { putString(KEY_INDEXERS, json.toString()) }
    }

    private fun encrypt(value: String): String {
        if (value.isBlank()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val payload = ByteBuffer.allocate(cipher.iv.size + encrypted.size).put(cipher.iv).put(encrypted).array()
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decrypt(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return runCatching {
            val buffer = ByteBuffer.wrap(Base64.decode(value, Base64.NO_WRAP))
            require(buffer.remaining() > GCM_IV_LENGTH)
            val iv = ByteArray(GCM_IV_LENGTH).also(buffer::get)
            val encrypted = ByteArray(buffer.remaining()).also(buffer::get)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun isValidTemplate(value: String): Boolean = runCatching {
        if (!value.contains("{query}")) return false
        val uri = Uri.parse(value.replace("{query}", "probe").replace("{apikey}", "test"))
        uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null
    }.getOrDefault(false)

    private companion object {
        val DEFAULT_TRACKER_SHORTCUTS = listOf(
            WebSearchProvider(
                id = "tracker-rutracker",
                name = "RuTracker",
                searchUrlTemplate = "https://rutracker.org/forum/tracker.php?nm={query}"
            ),
            WebSearchProvider(
                id = "tracker-nnmclub",
                name = "NNM-Club",
                searchUrlTemplate = "https://nnmclub.to/forum/tracker.php?nm={query}"
            ),
            WebSearchProvider(
                id = "tracker-rutor",
                name = "Rutor",
                searchUrlTemplate = "https://rutor.info/search/0/0/100/2/{query}"
            ),
            WebSearchProvider(
                id = "tracker-kinozal",
                name = "Kinozal",
                searchUrlTemplate = "https://kinozal.tv/browse.php?s={query}"
            ),
            WebSearchProvider(
                id = "tracker-1337x",
                name = "1337x",
                searchUrlTemplate = "https://1337x.to/search/{query}/1/"
            ),
            WebSearchProvider(
                id = "tracker-tpb",
                name = "The Pirate Bay",
                searchUrlTemplate = "https://thepiratebay.org/search.php?q={query}"
            ),
            WebSearchProvider(
                id = "tracker-archive",
                name = "Internet Archive",
                searchUrlTemplate = "https://archive.org/search?query={query}%20AND%20mediatype%3Amovies"
            )
        )

        const val PREFERENCES = "jacket_media_settings"
        const val KEY_TMDB_TOKEN = "tmdb_token_enc"
        const val KEY_FILMIX_TOKEN = "filmix_device_token_enc"
        const val KEY_FILMIX_DEVICE_ID = "filmix_device_id"
        const val KEY_HDREZKA_MIRROR = "hdrezka_mirror"
        const val KEY_HDREZKA_LOGIN = "hdrezka_login_enc"
        const val KEY_HDREZKA_PASSWORD = "hdrezka_password_enc"
        const val KEY_HDREZKA_COOKIE = "hdrezka_cookie_enc"
        const val KEY_HDREZKA_CONNECTED = "hdrezka_connected"
        const val KEY_INDEXERS = "rss_indexers"
        const val KEY_WEB_SEARCH_PROVIDERS = "web_search_providers"
        const val KEY_TRACKER_PRESETS_SEEDED = "tracker_presets_seeded"
        const val KEY_FAVORITES = "catalog_favorites"
        const val KEY_HISTORY = "catalog_history"
        const val MAX_INDEXERS = 100
        const val MAX_WEB_SEARCH_PROVIDERS = 50
        const val MAX_IMPORT_BYTES = 256 * 1024
        const val MAX_SAVED_ITEMS = 200
        const val MAX_HISTORY = 30
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        // Keep the existing alias so saved TMDB tokens remain readable.
        const val KEY_ALIAS = "jacket_tmdb_settings_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_LENGTH_BITS = 128
    }
}
