package app.jacket.mobile.torrent

data class LocalStreamRoute(val token: String, val infoHash: String, val fileIndex: Int)

object LocalStreamRouteParser {
    private val tokenPattern = Regex("(?i)[a-f0-9]{32}")
    private val hashPattern = Regex("(?i)([a-f0-9]{40}|[a-f0-9]{64})")

    fun parse(requestTarget: String): LocalStreamRoute? {
        if (requestTarget.isBlank() || requestTarget.contains('\\')) return null
        val path = requestTarget.substringBefore('?')
        if (path.contains("%2f", ignoreCase = true) || path.contains("%5c", ignoreCase = true)) return null
        val parts = path.split('/')
        if (parts.size != 5 || parts[0].isNotEmpty() || parts[1] != "stream") return null
        val token = parts[2].takeIf { tokenPattern.matches(it) } ?: return null
        val hash = parts[3].takeIf { hashPattern.matches(it) } ?: return null
        val indexText = parts[4]
        if (!indexText.matches(Regex("[0-9]{1,9}"))) return null
        val index = indexText.toIntOrNull() ?: return null
        return LocalStreamRoute(token.lowercase(), hash.lowercase(), index)
    }
}
