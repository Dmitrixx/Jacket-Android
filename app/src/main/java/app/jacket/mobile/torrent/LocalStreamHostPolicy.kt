package app.jacket.mobile.torrent

/** Strict Host header policy for the loopback-only media endpoint. */
object LocalStreamHostPolicy {
    fun accepts(hostHeader: String?, boundPort: Int): Boolean {
        if (hostHeader.isNullOrBlank() || boundPort !in 1..65535) return false
        val parts = hostHeader.trim().split(':', limit = 2)
        if (parts[0].trim().lowercase() != "127.0.0.1") return false
        if (parts.size == 1) return true
        val requestedPort = parts[1].toIntOrNull() ?: return false
        return requestedPort == boundPort
    }
}
