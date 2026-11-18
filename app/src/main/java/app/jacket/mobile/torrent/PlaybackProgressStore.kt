package app.jacket.mobile.torrent

import android.content.Context
import androidx.core.content.edit
import java.security.MessageDigest

/** Local playback positions; keys are opaque and do not persist source URLs or authentication data. */
class PlaybackProgressStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun position(key: String): Long = preferences.getLong(key, 0L).coerceAtLeast(0L)

    fun save(key: String, positionMs: Long, durationMs: Long) {
        if (key.isBlank()) return
        val position = positionMs.coerceAtLeast(0L)
        if (durationMs > 0L && position >= durationMs * WATCHED_PERCENT / 100L) {
            preferences.edit { remove(key) }
        } else if (position > 0L) {
            preferences.edit { putLong(key, position) }
        }
    }

    fun clear(key: String) {
        preferences.edit { remove(key) }
    }

    companion object {
        private const val PREFERENCES = "jacket_playback_positions"
        private const val WATCHED_PERCENT = 90L

        fun torrentKey(infoHash: String, fileIndex: Int): String = "torrent:${infoHash.lowercase()}:$fileIndex"

        fun urlKey(url: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
            val hex = digest.joinToString("") { "%02x".format(it) }
            return "url:$hex"
        }
    }
}
