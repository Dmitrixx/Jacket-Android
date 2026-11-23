package app.jacket.mobile.torrent

/**
 * Restricts cross-origin playback access to the embedded Lampa WebView.
 * The stream server remains loopback-only and each media URL still carries
 * its per-stream access token.
 */
internal object LocalStreamCorsPolicy {
    const val LAMPA_ORIGIN = "http://127.0.0.1:9118"

    fun responseHeaders(origin: String?): List<String> {
        if (origin != LAMPA_ORIGIN) return emptyList()
        return listOf(
            "Access-Control-Allow-Origin: $LAMPA_ORIGIN",
            "Access-Control-Allow-Methods: GET, HEAD, OPTIONS",
            "Access-Control-Allow-Headers: Range, Content-Type",
            "Access-Control-Expose-Headers: Accept-Ranges, Content-Length, Content-Range",
            "Access-Control-Max-Age: 600",
            "Vary: Origin"
        )
    }
}
