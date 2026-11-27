package app.jacket.mobile.adblock

import android.content.Context
import android.net.Uri

/**
 * Conservative WebView request filter for known third-party ad and analytics hosts.
 *
 * This filter is intentionally domain-based and does not rewrite media playlists.
 * It cannot remove server-side ad insertion or ad segments hosted on the same
 * domain as a movie stream.
 */
object AdBlockPolicy {
    const val PREFERENCES_NAME = "lampa_adblock"
    const val KEY_BLOCK_AD_DOMAINS = "block_ad_domains"
    const val KEY_BLOCK_TRACKERS = "block_trackers"
    const val KEY_REMOVE_STREAM_ADS = "remove_stream_ads"

    private val adDomains = setOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "adservice.google.com",
        "adnxs.com",
        "taboola.com",
        "outbrain.com",
        "criteo.com",
        "rubiconproject.com",
        "pubmatic.com",
        "openx.net",
        "adsrvr.org",
        "adform.net",
        "smartadserver.com",
        "scorecardresearch.com",
        "amazon-adsystem.com"
    )

    private val trackerDomains = setOf(
        "googletagmanager.com",
        "google-analytics.com",
        "analytics.google.com",
        "hotjar.com",
        "segment.io",
        "mixpanel.com"
    )

    fun shouldBlock(
        context: Context,
        uri: Uri,
        isForMainFrame: Boolean
    ): Boolean {
        if (isForMainFrame) return false
        if (!uri.scheme.equals("http", true) && !uri.scheme.equals("https", true)) return false

        val host = uri.host?.trimEnd('.')?.lowercase() ?: return false
        if (host.isBlank() || host == "localhost" || host == "127.0.0.1" || host == "::1") {
            return false
        }

        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val blockAds = preferences.getBoolean(KEY_BLOCK_AD_DOMAINS, true)
        val blockTrackers = preferences.getBoolean(KEY_BLOCK_TRACKERS, false)

        return (blockAds && adDomains.any { matchesDomain(host, it) }) ||
            (blockTrackers && trackerDomains.any { matchesDomain(host, it) })
    }

    internal fun matchesDomain(host: String, domain: String): Boolean =
        host.equals(domain, ignoreCase = true) ||
            host.endsWith(".$domain", ignoreCase = true)

    internal fun isKnownAdDomain(host: String): Boolean =
        adDomains.any { matchesDomain(host, it) }

    internal fun isKnownTrackerDomain(host: String): Boolean =
        trackerDomains.any { matchesDomain(host, it) }
}
