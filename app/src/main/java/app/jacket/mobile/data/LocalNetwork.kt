package app.jacket.mobile.data

import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * URL policy shared by direct indexers and an optional self-hosted Jackett server.
 *
 * HTTPS may target public providers. Cleartext HTTP is restricted to literal private/loopback/
 * link-local addresses and explicitly local DNS suffixes; generic single-label hosts are rejected
 * because they can resolve outside the local network on some devices.
 */
object LocalNetwork {
    private val localSuffixes = listOf(".local", ".lan", ".home.arpa", ".localhost")

    fun isAllowedUrl(value: String): Boolean = runCatching {
        val uri = URI(value.trim())
        if (uri.rawUserInfo != null || uri.host.isNullOrBlank() || uri.rawFragment != null) return false
        when (uri.scheme?.lowercase()) {
            "https" -> true
            "http" -> isLocalHost(uri.host)
            else -> false
        }
    }.getOrDefault(false)

    fun isLocalHost(rawHost: String): Boolean {
        val host = rawHost.trim().removePrefix("[").removeSuffix("]").trimEnd('.').lowercase()
        if (host.isBlank()) return false
        if (host == "localhost" || host.endsWith(".localhost")) return true
        parseIpv4(host)?.let { return isPrivateIpv4(it) }
        if (host.contains(':')) return isPrivateIpv6(host)
        return localSuffixes.any(host::endsWith)
    }

    private fun parseIpv4(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val values = IntArray(4)
        for (index in 0..3) {
            val part = parts[index]
            if (part.isEmpty() || part.length > 3 || !part.all(Char::isDigit)) return null
            val value = part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
            values[index] = value
        }
        return values
    }

    private fun isPrivateIpv4(o: IntArray): Boolean =
        o[0] == 10 ||
            o[0] == 127 ||
            (o[0] == 172 && o[1] in 16..31) ||
            (o[0] == 192 && o[1] == 168) ||
            (o[0] == 169 && o[1] == 254) ||
            (o[0] == 100 && o[1] in 64..127)

    private fun isPrivateIpv6(rawHost: String): Boolean = runCatching {
        // The host contains ':' so it is an IPv6 literal, not a DNS name.
        val literal = rawHost.substringBefore('%')
        val address = InetAddress.getByName(literal)
        if (address !is Inet6Address) return false
        val bytes = address.address
        val loopback = bytes.dropLast(1).all { it == 0.toByte() } && bytes.last() == 1.toByte()
        val uniqueLocal = (bytes[0].toInt() and 0xfe) == 0xfc // fc00::/7
        val linkLocal = (bytes[0].toInt() and 0xff) == 0xfe &&
            (bytes[1].toInt() and 0xc0) == 0x80 // fe80::/10
        loopback || uniqueLocal || linkLocal
    }.getOrDefault(false)
}

/** Normalizes a Jackett dashboard or Torznab URL into its server origin and indexer filter. */
object JackettUrl {
    const val DEFAULT_INDEXER = "all"
    private const val API_PATH = "/api/v2.0/indexers/"
    private const val API_TAIL = "/results/torznab/api"

    data class Parsed(val baseUrl: String, val indexer: String)

    /**
     * Accepts a bare host, a dashboard URL, or a copied Torznab API URL.
     * Local hosts default to HTTP; public hosts default to HTTPS.
     */
    fun normalizeBase(input: String): String? = runCatching {
        var text = input.trim().substringBefore('#').substringBefore('?')
        if (text.isBlank()) return null
        if (!text.contains("://")) {
            val authority = text.substringBefore('/')
            val host = if (authority.startsWith("[")) {
                authority.substringAfter('[').substringBefore(']')
            } else {
                authority.substringBefore(':')
            }
            val scheme = if (LocalNetwork.isLocalHost(host)) "http" else "https"
            text = "$scheme://$text"
        }
        val uri = URI(text)
        if (uri.rawUserInfo != null || uri.host.isNullOrBlank()) return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme !in setOf("http", "https")) return null

        val rawPath = uri.rawPath.orEmpty().trimEnd('/')
        val lowerPath = rawPath.lowercase()
        val apiAt = lowerPath.indexOf(API_PATH)
        val dashboardAt = listOf("/ui/dashboard", "/ui/").map { lowerPath.indexOf(it) }
            .filter { it >= 0 }.minOrNull()
        val cutAt = listOfNotNull(apiAt.takeIf { it >= 0 }, dashboardAt).minOrNull()
        val basePath = if (cutAt != null) rawPath.substring(0, cutAt) else rawPath

        val normalized = buildString {
            append(scheme).append("://").append(uri.rawAuthority)
            if (basePath.isNotBlank()) append(basePath.trimEnd('/'))
        }
        normalized.takeIf(LocalNetwork::isAllowedUrl)
    }.getOrNull()

    fun searchTemplate(baseUrl: String, indexer: String = DEFAULT_INDEXER): String {
        val id = sanitizeIndexer(indexer)
        return baseUrl.trimEnd('/') + API_PATH + id + API_TAIL + "?t=search&q={query}"
    }

    /** A cheap connection test; the registry preserves this t=caps when adding common parameters. */
    fun capabilitiesTemplate(baseUrl: String, indexer: String = DEFAULT_INDEXER): String {
        val id = sanitizeIndexer(indexer)
        return baseUrl.trimEnd('/') + API_PATH + id + API_TAIL + "?t=caps&q={query}"
    }

    fun parseTemplate(template: String): Parsed? {
        val at = template.indexOf(API_PATH)
        if (at <= 0) return null
        val suffix = template.substring(at + API_PATH.length)
        val indexer = suffix.substringBefore('/')
        val base = template.substring(0, at).trimEnd('/')
        return Parsed(base, indexer.ifBlank { DEFAULT_INDEXER })
    }

    private fun sanitizeIndexer(value: String): String {
        val clean = value.trim().filter { it.isLetterOrDigit() || it in "-_.:" }.take(100)
        return clean.takeUnless { it.isBlank() || it.all { ch -> ch == '.' } } ?: DEFAULT_INDEXER
    }
}
