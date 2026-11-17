package app.jacket.mobile.data

import java.util.Locale

data class TorrentEntry(
    val id: String,
    val title: String,
    val magnetUri: String?,
    val torrentUrl: String?,
    val sizeBytes: Long?,
    val seeders: Int?,
    val leechers: Int?,
    val publishedAt: String?,
    val indexer: String?
) {
    val openUri: String?
        get() = magnetUri ?: torrentUrl
}

fun formatBytes(bytes: Long?): String {
    if (bytes == null || bytes <= 0L) return "Размер неизвестен"
    val units = arrayOf("Б", "КБ", "МБ", "ГБ", "ТБ")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return if (unit == 0) "${value.toLong()} ${units[unit]}"
    else String.format(Locale.ROOT, "%.1f %s", value, units[unit])
}
