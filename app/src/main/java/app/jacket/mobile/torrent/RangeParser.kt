package app.jacket.mobile.torrent

/** Inclusive byte range used by the local HTTP stream endpoint. */
data class ByteRange(val start: Long, val endInclusive: Long) {
    val length: Long get() = endInclusive - start + 1L
}

/** Strict single-range parser. Multipart ranges are intentionally not supported. */
object RangeParser {
    fun parse(header: String?, size: Long): ByteRange? {
        require(size >= 0L)
        if (header.isNullOrBlank()) return null
        if (!header.startsWith("bytes=", ignoreCase = true)) throw IllegalArgumentException("Unsupported Range unit")
        val value = header.substringAfter('=').trim()
        if (value.isBlank() || value.contains(',')) throw IllegalArgumentException("Only one byte range is supported")
        val pieces = value.split('-', limit = 2)
        if (pieces.size != 2) throw IllegalArgumentException("Malformed Range")
        val startPart = pieces[0].trim()
        val endPart = pieces[1].trim()
        if (size == 0L) throw IllegalArgumentException("Empty file")

        val range = if (startPart.isBlank()) {
            val suffix = endPart.toLongOrNull()?.takeIf { it > 0L }
                ?: throw IllegalArgumentException("Invalid suffix range")
            val actual = minOf(suffix, size)
            ByteRange(size - actual, size - 1L)
        } else {
            val start = startPart.toLongOrNull()?.takeIf { it >= 0L }
                ?: throw IllegalArgumentException("Invalid range start")
            if (start >= size) throw IllegalArgumentException("Range starts beyond end of file")
            val end = if (endPart.isBlank()) size - 1L else
                endPart.toLongOrNull()?.takeIf { it >= start }
                    ?: throw IllegalArgumentException("Invalid range end")
            ByteRange(start, minOf(end, size - 1L))
        }
        return range
    }
}
