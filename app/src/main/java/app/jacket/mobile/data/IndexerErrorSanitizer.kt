package app.jacket.mobile.data

import java.net.URI

/** Removes configured credentials and URL query secrets before provider failures reach the UI. */
internal object IndexerErrorSanitizer {
    private val sensitiveParameter = Regex(
        "(?i)(apikey|api[_-]?key|access[_-]?token|token|password|passwd|pwd|secret|cookie|authorization|auth)=([^&\\s#]+)"
    )
    private val sensitiveHeader = Regex("(?i)\\b(authorization|cookie|x-api-key)\\s*:\\s*[^\\r\\n]*")
    private val basicCredentials = Regex("(?i)\\bBasic\\s+[A-Za-z0-9+/=]+")
    private val url = Regex("""(?i)https?://[^\s<>"']+""")
    private val whitespace = Regex("[\\r\\n\\t]+")

    fun sanitize(error: Throwable, configuredSecrets: Collection<String> = emptyList()): String {
        val messages = generateSequence(error) { it.cause }
            .take(5)
            .mapNotNull { it.message }
            .toList()
        val message = messages.joinToString(": ").ifBlank { error.javaClass.simpleName }
        return sanitize(message, configuredSecrets)
    }

    fun sanitize(rawMessage: String, configuredSecrets: Collection<String> = emptyList()): String {
        var safe = rawMessage
        configuredSecrets.asSequence()
            .filter { it.isNotBlank() && it.length >= 3 }
            .distinct()
            .sortedByDescending(String::length)
            .forEach { secret -> safe = safe.replace(secret, "[redacted]") }

        safe = sensitiveParameter.replace(safe) { match ->
            "${match.groupValues[1]}=[redacted]"
        }
        safe = sensitiveHeader.replace(safe) { match ->
            "${match.groupValues[1]}: [redacted]"
        }
        safe = basicCredentials.replace(safe, "Basic [redacted]")
        safe = url.replace(safe) { match ->
            val candidate = match.value.trimEnd('.', ',', ')', ']', ';')
            val suffix = match.value.substring(candidate.length)
            val hostOnly = runCatching {
                val uri = URI(candidate)
                val host = uri.host ?: return@runCatching "[URL]"
                val port = if (uri.port >= 0) ":${uri.port}" else ""
                "${uri.scheme}://$host$port" + if (uri.rawQuery != null) "/?[redacted]" else ""
            }.getOrDefault("[URL]")
            hostOnly + suffix
        }
        return safe.replace(whitespace, " ").trim().take(180)
    }
}
