package app.jacket.mobile.torrent

data class LocalStreamRoute(val infoHash: String, val fileIndex: Int)

/** Accepts only the exact /stream/{hex-infohash}/{non-negative-index} route shape. */
object LocalStreamRouteParser {
    private val hashPattern = Regex("(?i)([a-f0-9]{40}|[a-f0-9]{64})")

    fun parse(requestTarget: String): LocalStreamRoute? {
        if (requestTarget.isBlank() || requestTarget.contains('\\')) return null
        val path = requestTarget.substringBefore('?')
        if (path.contains("%2f", ignoreCase = true) || path.contains("%5c", ignoreCase = true)) return null
        val parts = path.split('/')
        if (parts.size != 4 || parts[0].isNotEmpty() || parts[1] != "stream") return null
        val hash = parts[2].takeIf { hashPattern.matches(it) } ?: return null
        val indexText = parts[3]
        if (!indexText.matches(Regex("[0-9]{1,9}"))) return null
        val index = indexText.toIntOrNull() ?: return null
        return LocalStreamRoute(hash.lowercase(), index)
    }
}
