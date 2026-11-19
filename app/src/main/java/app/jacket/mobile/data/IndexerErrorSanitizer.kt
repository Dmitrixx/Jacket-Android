package app.jacket.mobile.data

import java.net.URI

/**
 * Sanitizes diagnostics before they reach toasts, status labels or logs.
 * Credentials configured for a provider are redacted even if they occur in
 * path segments or an unusual query parameter name.
 */
internal object IndexerErrorSanitizer {
    private val sensitiveParameter = Regex(
        "(?i)(apikey|api[_-]?key|jackett[_-]?apikey|access[_-]?token|token|password|passwd|pwd|secret|cookie|authorization|auth)=([^&\\s#]+)"
    )
    private val sensitiveHeader = Regex("(?i)\\b(authorization|cookie|x-api-key)\\s*:\\s*[^\\r\\n]*")
    private val basicCredentials = Regex("(?i)\\bBasic\\s+[A-Za-z0-9+/=]+")
    private val bearerCredentials = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/-]+=*")
    private val url = Regex("""(?i)https?://[^\s<>"']+""")
    private val whitespace = Regex("[\\r\\n\\t]+")

    fun sanitize(error: Throwable, configuredSecrets: Collection<String> = emptyList()): String {
        val messages = generateSequence(error) { it.cause }
            .take(8)
            .mapNotNull { it.message }
            .toList()
        return sanitize(messages.joinToString(": ").ifBlank { error.javaClass.simpleName }, configuredSecrets)
    }

    fun sanitize(rawMessage: String, configuredSecrets: Collection<String> = emptyList()): String {
        var safe = rawMessage
        configuredSecrets.asSequence()
            .filter { it.isNotBlank() && it.length >= 3 }
            .distinct()
            .sortedByDescending(String::length)
            .forEach { secret -> safe = safe.replace(secret, "[redacted]") }

        safe = sensitiveParameter.replace(safe) { "${it.groupValues[1]}=[redacted]" }
        safe = sensitiveHeader.replace(safe) { "${it.groupValues[1]}: [redacted]" }
        safe = basicCredentials.replace(safe, "Basic [redacted]")
        safe = bearerCredentials.replace(safe, "Bearer [redacted]")
        safe = url.replace(safe) { match ->
            val candidate = match.value.trimEnd('.', ',', ')', ']', ';')
            val suffix = match.value.substring(candidate.length)
            val authority = runCatching {
                val uri = URI(candidate)
                val host = uri.host ?: return@runCatching "[URL]"
                val port = if (uri.port >= 0) ":${uri.port}" else ""
                val queryMarker = if (uri.rawQuery != null) "/?[redacted]" else ""
                "${uri.scheme}://$host$port$queryMarker"
            }.getOrDefault("[URL]")
            authority + suffix
        }
        return safe.replace(whitespace, " ").trim().take(180)
    }
}
