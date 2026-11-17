package app.jacket.mobile.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores the optional TMDB token encrypted and keeps custom direct RSS/Atom sources locally. */
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
                    searchUrlTemplate = item.optString("url")
                )
                if (config.id.isNotBlank() && config.name.isNotBlank() && isValidFeedTemplate(config.searchUrlTemplate)) {
                    add(config)
                }
            }
        }.distinctBy { it.id }
    }.getOrDefault(emptyList())

    @Synchronized
    fun addIndexer(name: String, urlTemplate: String) {
        require(name.isNotBlank()) { "Укажите название источника" }
        require(isValidFeedTemplate(urlTemplate)) {
            "Нужен HTTPS RSS/Atom URL с параметром {query}, например https://example.org/rss?q={query}"
        }
        val item = IndexerConfig(
            id = urlTemplate.trim().hashCode().toUInt().toString(16),
            name = name.trim().take(60),
            searchUrlTemplate = urlTemplate.trim()
        )
        val updated = (indexers().filterNot { it.id == item.id } + item).takeLast(MAX_INDEXERS)
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
        items.forEach {
            json.put(JSONObject().put("id", it.id).put("name", it.name).put("url", it.searchUrlTemplate))
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

    private fun isValidFeedTemplate(value: String): Boolean = runCatching {
        if (!value.contains("{query}")) return false
        val uri = android.net.Uri.parse(value.replace("{query}", "test"))
        uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null
    }.getOrDefault(false)

    private companion object {
        const val PREFERENCES = "jacket_media_settings"
        const val KEY_TMDB_TOKEN = "tmdb_token_enc"
        const val KEY_INDEXERS = "rss_indexers"
        const val KEY_FAVORITES = "catalog_favorites"
        const val KEY_HISTORY = "catalog_history"
        const val MAX_INDEXERS = 30
        const val MAX_SAVED_ITEMS = 200
        const val MAX_HISTORY = 30
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "jacket_tmdb_settings_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_LENGTH_BITS = 128
    }
}
