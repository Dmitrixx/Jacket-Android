package app.jacket.mobile.torrent

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest

/** Durable task metadata; native JNI handles deliberately never enter this model. */
data class QueuedTorrent(
    val id: String,
    val title: String,
    val magnetUri: String? = null,
    val torrentPath: String? = null,
    val selectedFileIndex: Int? = null
) {
    init {
        require(magnetUri?.startsWith("magnet:?") == true || !torrentPath.isNullOrBlank()) {
            "Задача должна содержать magnet или сохранённый .torrent"
        }
    }
}

class TorrentQueueStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    fun all(): List<QueuedTorrent> {
        val raw = preferences.getString(KEY_QUEUE, "[]").orEmpty()
        val isVersion2 = raw.startsWith("JACKET_QUEUE_V2")
        val decoded = if (isVersion2) {
            runCatching { TorrentQueueCodec.decode(raw) }.getOrDefault(emptyList())
        } else {
            // Legacy IDs are untrusted: canonicalize magnets and validate file IDs
            // before writing V2, so one malformed record cannot poison the queue.
            runCatching { TorrentQueueCodec.decodeLegacy(raw) }.getOrDefault(emptyList())
        }
        val valid = decoded.filter { it.magnetUri?.startsWith("magnet:?") == true ||
            (!it.torrentPath.isNullOrBlank() && File(it.torrentPath).isFile) }.take(MAX_QUEUE_SIZE)
        if (raw != TorrentQueueCodec.encode(valid)) save(valid)
        return valid
    }

    @Synchronized
    fun get(id: String): QueuedTorrent? = all().firstOrNull { it.id.equals(id, true) }

    @Synchronized
    fun add(magnet: String, title: String): QueuedTorrent {
        require(magnet.startsWith("magnet:?") && magnet.length <= MAX_MAGNET_LENGTH) {
            "Некорректная или слишком длинная magnet-ссылка"
        }
        val item = QueuedTorrent(
            id = idFor(magnet),
            title = title.trim().take(MAX_TITLE_LENGTH).ifBlank { "Торрент" },
            magnetUri = magnet
        )
        upsert(item)
        return item
    }

    @Synchronized
    fun addTorrentFile(torrentFile: File, id: String, title: String): QueuedTorrent {
        require(torrentFile.isFile && torrentFile.length() in 1..MAX_TORRENT_BYTES) {
            "Файл .torrent отсутствует или превышает допустимый размер"
        }
        require(id.matches(INFO_HASH)) { "Некорректный infohash" }
        val item = QueuedTorrent(
            id = id.lowercase(),
            title = title.trim().take(MAX_TITLE_LENGTH).ifBlank { torrentFile.nameWithoutExtension },
            torrentPath = torrentFile.absolutePath
        )
        upsert(item)
        return item
    }

    @Synchronized
    fun selectFile(id: String, index: Int?) {
        val item = get(id) ?: throw IllegalArgumentException("Загрузка не найдена")
        require(index == null || index >= 0) { "Индекс файла не может быть отрицательным" }
        upsert(item.copy(selectedFileIndex = index))
    }

    @Synchronized
    fun remove(id: String) {
        save(all().filterNot { it.id.equals(id, true) })
        val paused = preferences.getStringSet(KEY_PAUSED, emptySet()).orEmpty().toMutableSet()
        paused.remove(id)
        check(preferences.edit().putStringSet(KEY_PAUSED, paused).commit()) {
            "Не удалось надёжно сохранить состояние паузы загрузки"
        }
    }

    @Synchronized
    fun setPaused(id: String, paused: Boolean) {
        val values = preferences.getStringSet(KEY_PAUSED, emptySet()).orEmpty().toMutableSet()
        if (paused) values.add(id) else values.remove(id)
        check(preferences.edit().putStringSet(KEY_PAUSED, values).commit()) {
            "Не удалось надёжно сохранить состояние паузы загрузки"
        }
    }

    fun isPaused(id: String): Boolean = id in preferences.getStringSet(KEY_PAUSED, emptySet()).orEmpty()
    fun pausedIds(): Set<String> = preferences.getStringSet(KEY_PAUSED, emptySet()).orEmpty().toSet()

    @Synchronized
    private fun upsert(item: QueuedTorrent) {
        save((all().filterNot { it.id.equals(item.id, true) } + item).takeLast(MAX_QUEUE_SIZE))
    }

    private fun save(items: List<QueuedTorrent>) {
        // Queue writes happen on the torrent service IO dispatcher. Commit before
        // libtorrent is mutated so a process kill cannot lose the newest magnet,
        // .torrent path or selected-file decision from an asynchronously applied edit.
        check(preferences.edit().putString(KEY_QUEUE, TorrentQueueCodec.encode(items)).commit()) {
            "Не удалось надёжно сохранить очередь торрентов"
        }
    }

    companion object {
        private const val PREFERENCES = "jacket_torrent_queue"
        private const val KEY_QUEUE = "queue_json"
        private const val KEY_PAUSED = "paused_ids"
        private const val MAX_QUEUE_SIZE = 300
        private const val MAX_MAGNET_LENGTH = 16_384
        private const val MAX_TITLE_LENGTH = 512
        const val MAX_TORRENT_BYTES = 8L * 1024L * 1024L
        private val INFO_HASH = Regex("(?i)([a-f0-9]{40}|[a-f0-9]{64})")

        /** Returns a canonical hex info-hash so queue operations match libtorrent's hash IDs. */
        fun idFor(magnet: String): String {
            val xtValues = runCatching { Uri.parse(magnet).getQueryParameters("xt") }.getOrNull().orEmpty()
            val btih = xtValues.firstOrNull { it.startsWith("urn:btih:", ignoreCase = true) }
                ?.substringAfterLast(':')
                ?.let(::normalizeBtih)
            if (btih != null) return btih

            // BEP 52 magnet links use a multihash prefix (0x12 = SHA-256, 0x20 = 32 bytes).
            val btmh = xtValues.firstOrNull { it.startsWith("urn:btmh:", ignoreCase = true) }
                ?.substringAfterLast(':')
                ?.lowercase()
                ?.removePrefix("1220")
                ?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }
            if (btmh != null) return btmh

            return MessageDigest.getInstance("SHA-256")
                .digest(magnet.toByteArray(Charsets.UTF_8))
                .take(12)
                .joinToString("") { "%02x".format(it) }
        }

        fun idForFile(file: File): String {
            require(file.isFile && file.length() in 1..MAX_TORRENT_BYTES) {
                "Файл .torrent отсутствует или превышает допустимый размер"
            }
            return file.nameWithoutExtension.lowercase().takeIf { it.matches(INFO_HASH) }
                ?: throw IllegalArgumentException("Не удалось определить infohash из имени .torrent")
        }

        private fun normalizeBtih(value: String): String? {
            val clean = value.trim()
            if (clean.matches(Regex("[a-fA-F0-9]{40}"))) return clean.lowercase()
            if (!clean.matches(Regex("[A-Z2-7a-z]{32}"))) return null

            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
            var buffer = 0
            var bits = 0
            val bytes = ArrayList<Byte>(20)
            for (character in clean.uppercase()) {
                val digit = alphabet.indexOf(character)
                if (digit < 0) return null
                buffer = (buffer shl 5) or digit
                bits += 5
                if (bits >= 8) {
                    bits -= 8
                    bytes += ((buffer shr bits) and 0xff).toByte()
                }
            }
            if (bytes.size != 20) return null
            return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}
