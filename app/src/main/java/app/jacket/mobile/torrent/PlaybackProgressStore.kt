package app.jacket.mobile.torrent

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Local playback positions; keys are opaque and do not persist source URLs or authentication data. */
class PlaybackProgressStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun position(key: String): Long = preferences.getLong(key, 0L).coerceAtLeast(0L)

    fun save(key: String, positionMs: Long, durationMs: Long) {
        if (key.isBlank()) return
        val position = positionMs.coerceAtLeast(0L)
        if (durationMs > 0L && position >= durationMs * WATCHED_PERCENT / 100L) {
            preferences.edit { remove(key) }
        } else if (position > 0L) {
            preferences.edit { putLong(key, position) }
        }
        updateTorrentSession(key, position, durationMs)
    }

    fun clear(key: String) {
        preferences.edit { remove(key) }
        torrentIdentity(key)?.let { (hash, index) -> PlaybackSessionStore(appContext).remove(hash, index) }
    }

    private fun updateTorrentSession(key: String, positionMs: Long, durationMs: Long) {
        torrentIdentity(key)?.let { (hash, index) ->
            PlaybackSessionStore(appContext).saveProgress(hash, index, positionMs, durationMs)
        }
    }

    private fun torrentIdentity(key: String): Pair<String, Int>? {
        if (!key.startsWith("torrent:")) return null
        val parts = key.removePrefix("torrent:").split(':')
        if (parts.size != 2 || !parts[0].matches(INFO_HASH)) return null
        val index = parts[1].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        return parts[0].lowercase() to index
    }

    companion object {
        private const val PREFERENCES = "jacket_playback_positions"
        private const val WATCHED_PERCENT = 90L
        private val INFO_HASH = Regex("(?i)([a-f0-9]{40}|[a-f0-9]{64})")

        fun torrentKey(infoHash: String, fileIndex: Int): String = "torrent:${infoHash.lowercase()}:$fileIndex"

        fun urlKey(url: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
            val hex = digest.joinToString("") { "%02x".format(it) }
            return "url:$hex"
        }
    }
}


/** Resumable local-torrent viewing history. Kept separate from the card-opening history. */
data class PlaybackSession(
    val infoHash: String,
    val fileIndex: Int,
    val title: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long
) {
    val key: String get() = "$infoHash:$fileIndex"
    val progress: Float
        get() = if (durationMs <= 0L) 0f else
            (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
}

class PlaybackSessionStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)

    val sessions: StateFlow<List<PlaybackSession>> get() = state.asStateFlow()

    init { synchronized(lock) { publish(readLocked()) } }

    fun begin(infoHash: String, fileIndex: Int, title: String, initialPositionMs: Long) {
        require(infoHash.matches(INFO_HASH) && fileIndex >= 0)
        synchronized(lock) {
            val current = readLocked()
            val previous = current.firstOrNull { it.infoHash == infoHash.lowercase() && it.fileIndex == fileIndex }
            val session = PlaybackSession(
                infoHash.lowercase(), fileIndex, title.substringAfterLast('/').take(MAX_TITLE_LENGTH).ifBlank { "Видео" },
                previous?.positionMs ?: initialPositionMs.coerceAtLeast(0L),
                previous?.durationMs ?: 0L,
                System.currentTimeMillis()
            )
            writeLocked((current.filterNot { it.key == session.key } + session)
                .sortedByDescending { it.updatedAt }.take(MAX_SESSIONS))
        }
    }

    fun saveProgress(infoHash: String, fileIndex: Int, positionMs: Long, durationMs: Long) {
        if (!infoHash.matches(INFO_HASH) || fileIndex < 0) return
        synchronized(lock) {
            val current = readLocked()
            val old = current.firstOrNull { it.infoHash == infoHash.lowercase() && it.fileIndex == fileIndex } ?: return
            val position = positionMs.coerceAtLeast(0L)
            val duration = durationMs.takeIf { it > 0L } ?: old.durationMs
            val next = if (duration > 0L && position >= duration * WATCHED_PERCENT / 100L) {
                current.filterNot { it.key == old.key }
            } else {
                current.filterNot { it.key == old.key } + old.copy(
                    positionMs = position, durationMs = duration, updatedAt = System.currentTimeMillis()
                )
            }
            writeLocked(next.sortedByDescending { it.updatedAt }.take(MAX_SESSIONS))
        }
    }

    fun remove(infoHash: String, fileIndex: Int) = synchronized(lock) {
        writeLocked(readLocked().filterNot { it.infoHash == infoHash.lowercase() && it.fileIndex == fileIndex })
    }

    private fun readLocked(): List<PlaybackSession> = runCatching {
        val array = JSONArray(preferences.getString(SESSION_KEY, "[]").orEmpty())
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val hash = item.optString("infoHash").lowercase()
                val fileIndex = item.optInt("fileIndex", -1)
                if (!hash.matches(INFO_HASH) || fileIndex < 0) continue
                add(
                    PlaybackSession(
                        hash, fileIndex, item.optString("title").take(MAX_TITLE_LENGTH).ifBlank { "Видео" },
                        item.optLong("positionMs", 0L).coerceAtLeast(0L),
                        item.optLong("durationMs", 0L).coerceAtLeast(0L),
                        item.optLong("updatedAt", 0L).coerceAtLeast(0L)
                    )
                )
            }
        }.distinctBy { it.key }.sortedByDescending { it.updatedAt }.take(MAX_SESSIONS)
    }.getOrDefault(emptyList())

    private fun writeLocked(items: List<PlaybackSession>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject().put("infoHash", item.infoHash).put("fileIndex", item.fileIndex)
                    .put("title", item.title).put("positionMs", item.positionMs)
                    .put("durationMs", item.durationMs).put("updatedAt", item.updatedAt)
            )
        }
        preferences.edit { putString(SESSION_KEY, array.toString()) }
        publish(items)
    }

    private fun publish(items: List<PlaybackSession>) { state.value = items }

    companion object {
        private const val SESSION_PREFS = "jacket_playback_sessions"
        private const val SESSION_KEY = "sessions_json"
        private const val MAX_SESSIONS = 30
        private const val MAX_TITLE_LENGTH = 240
        private const val WATCHED_PERCENT = 90L
        private val INFO_HASH = Regex("(?i)([a-f0-9]{40}|[a-f0-9]{64})")
        private val lock = Any()
        private val state = MutableStateFlow<List<PlaybackSession>>(emptyList())
    }
}
