package app.jacket.mobile.torrent

data class RangeHttpResponse(
    val statusCode: Int,
    val reason: String,
    val start: Long,
    val endInclusive: Long,
    val contentLength: Long,
    val headers: List<String>
)

/** Pure HTTP range response builder shared by the loopback server and unit tests. */
object RangeResponseBuilder {
    fun build(range: ByteRange?, size: Long, contentType: String): RangeHttpResponse {
        require(size > 0L) { "Range response requires a non-empty file" }
        val start = range?.start ?: 0L
        val end = range?.endInclusive ?: (size - 1L)
        require(start >= 0L && end >= start && end < size)
        val partial = range != null
        val headers = buildList {
            add("Content-Type: $contentType")
            add("Accept-Ranges: bytes")
            add("Content-Length: ${end - start + 1L}")
            add("Connection: close")
            add("Cache-Control: no-store")
            if (partial) add("Content-Range: bytes $start-$end/$size")
        }
        return RangeHttpResponse(
            statusCode = if (partial) 206 else 200,
            reason = if (partial) "Partial Content" else "OK",
            start = start,
            endInclusive = end,
            contentLength = end - start + 1L,
            headers = headers
        )
    }
}
