package app.jacket.mobile.torrent

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Small durable queue. Native torrent resume data is added in a later storage migration. */
data class QueuedTorrent(
    val id: String,
    val title: String,
    val magnetUri: String
)

class TorrentQueueStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    fun all(): List<QueuedTorrent> = runCatching {
        val array = JSONArray(preferences.getString(KEY_QUEUE, "[]"))
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val magnet = item.optString("magnet").takeIf { it.startsWith("magnet:?") } ?: continue
                val id = item.optString("id").ifBlank { idFor(magnet) }
                add(QueuedTorrent(id, item.optString("title").ifBlank { "Торрент $id" }, magnet))
            }
        }.distinctBy { it.id }
    }.getOrDefault(emptyList())

    @Synchronized
    fun add(magnet: String, title: String): QueuedTorrent {
        require(magnet.startsWith("magnet:?") && magnet.length <= MAX_MAGNET_LENGTH) {
            "Некорректная или слишком длинная magnet-ссылка"
        }
        val item = QueuedTorrent(idFor(magnet), title.trim().take(MAX_TITLE_LENGTH).ifBlank { "Торрент" }, magnet)
        val updated = (all().filterNot { it.id == item.id } + item).takeLast(MAX_QUEUE_SIZE)
        save(updated)
        return item
    }

    @Synchronized
    fun remove(id: String) {
        save(all().filterNot { it.id == id })
        val paused = preferences.getStringSet(KEY_PAUSED, emptySet()).orEmpty().toMutableSet()
        paused.remove(id)
        preferences.edit().putStringSet(KEY_PAUSED, paused).apply()
    }

    @Synchronized
    fun setPaused(id: String, paused: Boolean) {
        val values = preferences.getStringSet(KEY_PAUSED, emptySet()).orEmpty().toMutableSet()
        if (paused) values.add(id) else values.remove(id)
        preferences.edit().putStringSet(KEY_PAUSED, values).apply()
    }

    fun isPaused(id: String): Boolean = id in preferences.getStringSet(KEY_PAUSED, emptySet()).orEmpty()
    fun pausedIds(): Set<String> = preferences.getStringSet(KEY_PAUSED, emptySet()).orEmpty().toSet()

    private fun save(items: List<QueuedTorrent>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(JSONObject().put("id", item.id).put("title", item.title).put("magnet", item.magnetUri))
        }
        preferences.edit().putString(KEY_QUEUE, array.toString()).apply()
    }

    companion object {
        private const val PREFERENCES = "jacket_torrent_queue"
        private const val KEY_QUEUE = "queue_json"
        private const val KEY_PAUSED = "paused_ids"
        private const val MAX_QUEUE_SIZE = 300
        private const val MAX_MAGNET_LENGTH = 16_384
        private const val MAX_TITLE_LENGTH = 512

        fun idFor(magnet: String): String {
            val xt = runCatching { Uri.parse(magnet).getQueryParameter("xt") }.getOrNull()
            val hash = xt?.substringAfterLast(':')?.takeIf { it.matches(Regex("[a-fA-F0-9]{32,64}")) }
            if (hash != null) return hash.lowercase()
            return MessageDigest.getInstance("SHA-256")
                .digest(magnet.toByteArray(Charsets.UTF_8))
                .take(12)
                .joinToString("") { "%02x".format(it) }
        }
    }
}
