package app.jacket.mobile.lampac

/**
 * Removes credentials and account identifiers from Lampac output before they
 * enter the in-app log buffer, status text, or foreground notification.
 *
 * Lampac modules may include request URLs and HTTP headers in diagnostic output.
 * Never assume upstream log lines are safe to display verbatim.
 */
internal object LampacLogSanitizer {
    private val bearerCredential = Regex("""(?i)\b(Bearer|Basic)\s+[A-Za-z0-9._~+/=-]+""")
    private val cookieHeader = Regex("""(?i)\b(?:cookie|set-cookie)\s*:\s*[^\r\n]*""")
    private val credentialAssignment = Regex(
        """(?i)(["']?(?:authorization|proxy-authorization|cookie|set-cookie|x-api-key|x-auth-token|api[_-]?key|access[_-]?token|refresh[_-]?token|token|password|passwd|secret|account[_-]?email|uid|device[_-]?id)["']?\s*[:=]\s*)(?:"[^"]*"|'[^']*'|[^\s,;&}\]]+)"""
    )

    fun sanitize(value: String): String {
        var result = value.replace(bearerCredential) { match ->
            "${match.groupValues[1]} [REDACTED]"
        }
        result = result.replace(cookieHeader) { match ->
            "${match.value.substringBefore(':')}: [REDACTED]"
        }
        result = result.replace(credentialAssignment) { match ->
            "${match.groupValues[1]}[REDACTED]"
        }
        return result.replace(Regex("[\\r\\n\\t]+"), " ").trim()
    }
}
