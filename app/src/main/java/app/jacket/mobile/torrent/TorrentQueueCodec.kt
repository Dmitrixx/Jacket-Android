package app.jacket.mobile.torrent

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.Base64

/** Pure queue persistence codec, intentionally independent from Android and libtorrent JNI. */
object TorrentQueueCodec {
    private const val HEADER = "JACKET_QUEUE_V2"
    private const val SEPARATOR = "\t"
    private val QUEUE_ID = Regex("(?i)[a-f0-9]{24,64}")
    private val LEGACY_FILE_ID = Regex("(?i)([a-f0-9]{40}|[a-f0-9]{64})")

    fun encode(items: List<QueuedTorrent>): String {
        require(items.size <= MAX_QUEUE_ITEMS) { "В очереди слишком много загрузок" }
        items.forEach { item ->
            require(item.id.matches(QUEUE_ID)) { "Некорректный ID очереди" }
            require(item.title.length <= MAX_TITLE_LENGTH) { "Название загрузки слишком длинное" }
            require(item.magnetUri == null || (
                item.magnetUri.startsWith("magnet:?") && item.magnetUri.length <= MAX_MAGNET_LENGTH
            )) { "Некорректная magnet-ссылка" }
            require(item.torrentPath == null || item.torrentPath.length <= MAX_PATH_LENGTH) {
                "Путь к .torrent слишком длинный"
            }
            require(item.selectedFileIndex == null || item.selectedFileIndex >= 0) {
                "Индекс выбранного файла не может быть отрицательным"
            }
        }
        val estimatedBytes = HEADER.toByteArray(StandardCharsets.UTF_8).size.toLong() +
            items.sumOf(::encodedRecordBytes)
        require(estimatedBytes <= MAX_ENCODED_BYTES) { "Файл очереди слишком большой" }

        return buildString {
            append(HEADER)
            items.forEach { item ->
                append('\n')
                append(item.id).append(SEPARATOR)
                append(encodeField(item.title)).append(SEPARATOR)
                append(encodeField(item.magnetUri)).append(SEPARATOR)
                append(encodeField(item.torrentPath)).append(SEPARATOR)
                append(item.selectedFileIndex ?: -1)
            }
        }
    }

    fun decode(value: String): List<QueuedTorrent> {
        require(value.length <= MAX_ENCODED_BYTES) { "Файл очереди слишком большой" }
        val lines = value.lineSequence().toList()
        require(lines.firstOrNull() == HEADER) { "Неизвестный формат очереди" }
        return lines.drop(1).filter(String::isNotBlank).take(MAX_QUEUE_ITEMS).mapNotNull { line ->
            runCatching {
                val fields = line.split(SEPARATOR)
                require(fields.size == 5) { "Повреждена запись очереди" }
                val id = fields[0]
                require(id.matches(QUEUE_ID)) { "Некорректный ID очереди" }
                val title = decodeField(fields[1]).orEmpty()
                require(title.length <= MAX_TITLE_LENGTH) { "Название загрузки слишком длинное" }
                val magnet = decodeField(fields[2])
                require(magnet == null || (magnet.startsWith("magnet:?") && magnet.length <= MAX_MAGNET_LENGTH)) {
                    "Некорректная magnet-ссылка"
                }
                val path = decodeField(fields[3])
                require(path == null || path.length <= MAX_PATH_LENGTH) { "Путь к .torrent слишком длинный" }
                val index = fields[4].toIntOrNull()?.takeIf { it >= 0 }
                QueuedTorrent(
                    id = id,
                    title = title,
                    magnetUri = magnet,
                    torrentPath = path,
                    selectedFileIndex = index
                )
            }.getOrNull()
        }.distinctBy { it.id.lowercase() }
    }

    /**
     * Migrates the former JSON SharedPreferences queue into the current model.
     * Magnet hashes are derived from their URI instead of trusting a stale ID.
     * File-only entries retain a valid infohash or use a hash-named metainfo file.
     */
    internal fun decodeLegacy(value: String): List<QueuedTorrent> {
        require(value.length <= MAX_ENCODED_BYTES) { "Файл очереди слишком большой" }
        val array = runCatching { JSONArray(value) }
            .getOrElse { throw IllegalArgumentException("Повреждена старая очередь торрентов", it) }
        val migrated = ArrayList<QueuedTorrent>()
        val seen = HashSet<String>()
        var encodedBytes = HEADER.toByteArray(StandardCharsets.UTF_8).size.toLong()
        for (index in 0 until minOf(array.length(), MAX_QUEUE_ITEMS)) {
            val item = array.optJSONObject(index) ?: continue
            val magnet = item.optString("magnet").takeIf {
                it.startsWith("magnet:?") && it.length <= MAX_MAGNET_LENGTH
            }
            val path = item.optString("torrentPath").takeIf { rawPath ->
                rawPath.length <= MAX_PATH_LENGTH && rawPath.isNotBlank() &&
                    File(rawPath).let { file -> file.isFile && file.length() in 1..MAX_TORRENT_BYTES }
            }
            if (magnet == null && path == null) continue
            val id = magnet?.let { TorrentQueueStore.idFor(it) }
                ?: item.optString("id").lowercase().takeIf { it.matches(LEGACY_FILE_ID) }
                ?: path?.let { runCatching { TorrentQueueStore.idForFile(File(it)) }.getOrNull() }
                ?: continue
            if (!seen.add(id.lowercase())) continue
            val legacyTitle = if (item.has("title") && !item.isNull("title")) {
                item.opt("title")?.toString().orEmpty()
                    .takeUnless { it.equals("null", ignoreCase = true) }
                    .orEmpty()
            } else {
                ""
            }
            val migratedItem = QueuedTorrent(
                id = id,
                // Android and JVM org.json implementations differ for missing and JSON-null optString.
                title = legacyTitle.trim().take(MAX_TITLE_LENGTH)
                    .ifBlank { "Торрент ${id.take(10)}" },
                magnetUri = magnet,
                torrentPath = path,
                selectedFileIndex = item.optInt("selectedFileIndex", -1).takeIf { it >= 0 }
            )
            val recordBytes = encodedRecordBytes(migratedItem)
            if (encodedBytes + recordBytes > MAX_ENCODED_BYTES) {
                seen.remove(id.lowercase())
                continue
            }
            migrated += migratedItem
            encodedBytes += recordBytes
        }
        return migrated
    }

    private fun encodedRecordBytes(item: QueuedTorrent): Long =
        item.id.toByteArray(StandardCharsets.UTF_8).size.toLong() + 4L +
            encodedFieldBytes(item.title) + encodedFieldBytes(item.magnetUri) +
            encodedFieldBytes(item.torrentPath) +
            (item.selectedFileIndex ?: -1).toString().length + 1L

    private fun encodedFieldBytes(value: String?): Long {
        if (value == null) return 1L // null is represented by "-"
        val bytes = value.toByteArray(StandardCharsets.UTF_8).size.toLong()
        return (bytes * 4L + 2L) / 3L // URL-safe Base64 without padding
    }

    private fun encodeField(value: String?): String =
        value?.let {
            Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(StandardCharsets.UTF_8))
        } ?: "-"

    private fun decodeField(value: String): String? {
        if (value == "-") return null
        return String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
    }

    private const val MAX_ENCODED_BYTES = 8 * 1024 * 1024
    private const val MAX_QUEUE_ITEMS = 300
    private const val MAX_MAGNET_LENGTH = 16_384
    private const val MAX_PATH_LENGTH = 4_096
    private const val MAX_TITLE_LENGTH = 512
    private const val MAX_TORRENT_BYTES = TorrentQueueStore.MAX_TORRENT_BYTES
}
