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
            apiKey = config.apiKey.trim(),
            username = config.username.trim(),
            password = config.password,
            cookie = config.cookie.trim(),
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

    @Synchronized
    fun setIndexerEnabled(id: String, enabled: Boolean) {
        val updated = indexers().map { if (it.id == id) it.copy(enabled = enabled) else it }
        saveIndexers(updated)
    }

    @Synchronized
    fun removeIndexer(id: String) {
        saveIndexers(indexers().filterNot { it.id == id })
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
        const val PREFERENCES = "jacket_media_settings"
        const val KEY_TMDB_TOKEN = "tmdb_token_enc"
        const val KEY_INDEXERS = "rss_indexers"
        const val KEY_FAVORITES = "catalog_favorites"
        const val KEY_HISTORY = "catalog_history"
        const val MAX_INDEXERS = 100
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
