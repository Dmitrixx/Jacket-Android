package app.jacket.mobile.torrent

import java.nio.charset.StandardCharsets
import java.util.Base64

/** Pure queue persistence codec, intentionally independent from Android and libtorrent JNI. */
object TorrentQueueCodec {
    private const val HEADER = "JACKET_QUEUE_V2"
    private const val SEPARATOR = "\t"

    fun encode(items: List<QueuedTorrent>): String = buildString {
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

    fun decode(value: String): List<QueuedTorrent> {
        require(value.length <= MAX_ENCODED_BYTES) { "Файл очереди слишком большой" }
        val lines = value.lineSequence().toList()
        require(lines.firstOrNull() == HEADER) { "Неизвестный формат очереди" }
        return lines.drop(1).filter(String::isNotBlank).map { line ->
            val fields = line.split(SEPARATOR)
            require(fields.size == 5) { "Повреждена запись очереди" }
            val id = fields[0]
            require(id.matches(Regex("(?i)[a-f0-9]{24,64}"))) { "Некорректный ID очереди" }
            val title = decodeField(fields[1]).orEmpty()
            val magnet = decodeField(fields[2])
            val path = decodeField(fields[3])
            val index = fields[4].toIntOrNull()?.takeIf { it >= 0 }
            QueuedTorrent(
                id = id,
                title = title,
                magnetUri = magnet,
                torrentPath = path,
                selectedFileIndex = index
            )
        }.distinctBy { it.id }
    }

    private fun encodeField(value: String?): String =
        value?.let {
            Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(StandardCharsets.UTF_8))
        } ?: "-"

    private fun decodeField(value: String): String? {
        if (value == "-") return null
        return String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
    }

    private const val MAX_ENCODED_BYTES = 512 * 1024
}
