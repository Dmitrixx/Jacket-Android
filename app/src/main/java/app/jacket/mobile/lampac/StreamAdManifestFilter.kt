package app.jacket.mobile.lampac

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

internal data class StreamAdFilterResult(
    val content: String,
    val removedSegments: Int = 0,
    val removedPeriods: Int = 0,
    val removedInterstitialMarkers: Int = 0,
    val markersFound: Boolean = false
) {
    val changed: Boolean
        get() = removedSegments > 0 || removedPeriods > 0 || removedInterstitialMarkers > 0
}

internal object StreamAdManifestFilter {
    /*
     * Token-boundary matching avoids false positives such as "adventure.ts"
     * or "download.mp4", while recognizing names such as /ads/ad-001.ts
     * and /pre-roll/creative.m4s.
     */
    private val adMediaToken = Regex(
        """(?:^|[/?#&=._:\-\s])(?:ad[-_.]?(?:break|segment|spot|pod|marker)|ad|ads|advert|advertisement|advertising|commercial|vast|vmap|interstitial|pre[-_.]?roll|mid[-_.]?roll|post[-_.]?roll|sponsor(?:ed|ship)?|promo(?:tional|tion)?)(?:[-_.]?\d+)?(?=$|[/?#&=._:\-\s])""",
        RegexOption.IGNORE_CASE
    )

    fun filter(content: String, baseUrl: String? = null): StreamAdFilterResult {
        val normalized = content.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        return when {
            normalized.startsWith("#EXTM3U") -> filterHls(content, baseUrl)
            normalized.startsWith("<") && normalized.contains("<MPD", ignoreCase = true) ->
                filterDash(content)
            else -> StreamAdFilterResult(content)
        }
    }

    fun filterHls(content: String, baseUrl: String? = null): StreamAdFilterResult {
        val newline = if (content.contains("\r\n")) "\r\n" else "\n"
        val lines = content.removePrefix("\uFEFF").split(Regex("\\r?\\n"))
        val isVod = lines.any { it.equals("#EXT-X-ENDLIST", true) } ||
            lines.any {
                it.startsWith("#EXT-X-PLAYLIST-TYPE:", true) &&
                    it.substringAfter(':').trim().equals("VOD", true)
            }

        if (!isVod && baseUrl.isNullOrBlank()) return StreamAdFilterResult(content)

        val output = mutableListOf<String>()
        val pendingSegmentTags = mutableListOf<String>()
        var insideAd = false
        var remainingAdSeconds: Double? = null
        var currentSegmentDuration = 0.0
        var removedSegments = 0
        var removedBeforeFirstKept = 0
        var keptAnySegment = false
        var removedInterstitialMarkers = 0
        var markersFound = false
        var adGapPending = false
        var activeAdDateRangeId: String? = null

        fun beginAd(duration: Double? = null) {
            insideAd = true
            remainingAdSeconds = duration?.takeIf { it > 0.0 }
            markersFound = true
        }

        fun endAd() {
            if (insideAd) adGapPending = true
            insideAd = false
            remainingAdSeconds = null
            markersFound = true
        }

        fun flushSegment(uriLine: String) {
            val pathMarkedAsAd = isAdMediaUri(uriLine)
            if (insideAd || pathMarkedAsAd) {
                removedSegments++
                markersFound = true
                if (!keptAnySegment) removedBeforeFirstKept++
                pendingSegmentTags.clear()

                if (insideAd) {
                    val remaining = remainingAdSeconds
                    if (remaining != null) {
                        remainingAdSeconds = (remaining - currentSegmentDuration).coerceAtLeast(0.0)
                        if (remainingAdSeconds == 0.0) {
                            insideAd = false
                            adGapPending = true
                        }
                    }
                } else {
                    adGapPending = true
                }
            } else {
                if (adGapPending && pendingSegmentTags.none {
                        it.equals("#EXT-X-DISCONTINUITY", true)
                    }
                ) {
                    output += "#EXT-X-DISCONTINUITY"
                }
                adGapPending = false
                output += pendingSegmentTags
                output += resolveHlsUri(uriLine, baseUrl)
                pendingSegmentTags.clear()
                keptAnySegment = true
            }
            currentSegmentDuration = 0.0
        }

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty()) {
                if (!insideAd && pendingSegmentTags.isEmpty()) output += rawLine
                continue
            }

            if (line.startsWith("#EXT-X-CUE-IN", true) ||
                line.startsWith("#EXT-X-SCTE35-IN", true) ||
                isScte35InDateRange(line)
            ) {
                endAd()
                activeAdDateRangeId = null
                continue
            }

            if (line.startsWith("#EXT-X-CUE-OUT-CONT", true)) {
                markersFound = true
                val duration = attributeNumber(line, "Duration")
                val elapsed = attributeNumber(line, "ElapsedTime") ?: 0.0
                if (!insideAd) beginAd(duration?.minus(elapsed))
                else if (duration != null) {
                    remainingAdSeconds = (duration - elapsed).coerceAtLeast(0.0)
                }
                continue
            }

            val cueOutDuration = cueOutDuration(line)
            if (line.startsWith("#EXT-X-CUE-OUT", true) ||
                line.startsWith("#EXT-X-SCTE35-OUT", true) ||
                isScte35OutDateRange(line)
            ) {
                beginAd(cueOutDuration)
                continue
            }

            if (isAppleInterstitial(line)) {
                removedInterstitialMarkers++
                markersFound = true
                continue
            }

            if (line.startsWith("#EXT-X-DATERANGE:", true)) {
                val rangeId = attributeValue(line, "ID")
                val endDate = attributeValue(line, "END-DATE")
                if (activeAdDateRangeId != null &&
                    rangeId == activeAdDateRangeId &&
                    !endDate.isNullOrBlank()
                ) {
                    endAd()
                    activeAdDateRangeId = null
                    continue
                }
                if (isAdDateRange(line)) {
                    beginAd(
                        attributeNumber(line, "PLANNED-DURATION")
                            ?: attributeNumber(line, "DURATION")
                    )
                    activeAdDateRangeId = rangeId
                    continue
                }
            }

            if (!line.startsWith("#")) {
                flushSegment(rawLine.trim())
                continue
            }

            if (line.startsWith("#EXTINF:", true)) {
                currentSegmentDuration = line.substringAfter(':')
                    .substringBefore(',')
                    .toDoubleOrNull() ?: 0.0
                pendingSegmentTags += rawLine
                continue
            }

            if ((line.startsWith("#EXT-X-MEDIA:", true) ||
                    line.startsWith("#EXT-X-I-FRAME-STREAM-INF:", true) ||
                    line.startsWith("#EXT-X-RENDITION-REPORT:", true) ||
                    line.startsWith("#EXT-X-SESSION-DATA:", true) ||
                    line.startsWith("#EXT-X-MAP:", true)) &&
                (hasAdUriAttribute(line) ||
                    (line.startsWith("#EXT-X-MAP:", true) && insideAd))
            ) {
                removedSegments++
                markersFound = true
                continue
            }

            if (isSegmentScopedTag(line)) {
                if (pendingSegmentTags.isNotEmpty() ||
                    line.startsWith("#EXT-X-PROGRAM-DATE-TIME", true) ||
                    line.equals("#EXT-X-DISCONTINUITY", true)
                ) {
                    pendingSegmentTags += rawLine
                } else if (!insideAd) {
                    output += rawLine
                }
                continue
            }

            if (line.startsWith("#EXT-X-PART:", true) ||
                line.startsWith("#EXT-X-PRELOAD-HINT:", true)
            ) {
                if (insideAd || hasAdUriAttribute(line)) {
                    removedSegments++
                    markersFound = true
                } else {
                    output += resolveHlsUriAttribute(rawLine, baseUrl)
                }
                continue
            }

            output += resolveHlsUriAttribute(rawLine, baseUrl)
        }

        if (pendingSegmentTags.isNotEmpty() && !insideAd) {
            output += pendingSegmentTags.filterNot {
                it.trim().startsWith("#EXTINF:", true)
            }
        }

        val rewritten = output.toMutableList()
        if (removedBeforeFirstKept > 0) {
            val sequenceIndex = rewritten.indexOfFirst {
                it.startsWith("#EXT-X-MEDIA-SEQUENCE:", true)
            }
            if (sequenceIndex >= 0) {
                val original = rewritten[sequenceIndex].substringAfter(':').trim().toLongOrNull()
                if (original != null) {
                    rewritten[sequenceIndex] =
                        "#EXT-X-MEDIA-SEQUENCE:${original + removedBeforeFirstKept}"
                }
            }
        }

        return StreamAdFilterResult(
            content = rewritten.joinToString(newline).let {
                if (content.endsWith("\n")) it + newline else it
            },
            removedSegments = removedSegments,
            removedInterstitialMarkers = removedInterstitialMarkers,
            markersFound = markersFound
        )
    }

    fun filterDash(content: String): StreamAdFilterResult {
        val document = runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }
            factory.newDocumentBuilder().parse(
                ByteArrayInputStream(content.toByteArray(StandardCharsets.UTF_8))
            )
        }.getOrNull() ?: return StreamAdFilterResult(content)

        val root = document.documentElement ?: return StreamAdFilterResult(content)
        val periods = mutableListOf<Element>()
        val allPeriods = document.getElementsByTagNameNS("*", "Period")
        for (index in 0 until allPeriods.length) {
            val period = allPeriods.item(index) as? Element ?: continue
            if (period.parentNode === root) periods += period
        }
        if (periods.isEmpty()) {
            val fallbackPeriods = document.getElementsByTagName("Period")
            for (index in 0 until fallbackPeriods.length) {
                val period = fallbackPeriods.item(index) as? Element ?: continue
                if (period.parentNode === root) periods += period
            }
        }

        var removedPeriods = 0
        var markersFound = false
        periods.forEach { period ->
            val adLabel = buildString {
                append(period.getAttribute("contentType"))
                append(' ')
                append(period.getAttribute("assetIdentifier"))
                append(' ')
                append(period.getAttribute("role"))
                append(' ')
                append(period.getAttribute("id"))

                val descendants = period.getElementsByTagName("*")
                for (index in 0 until descendants.length) {
                    val element = descendants.item(index) as? Element ?: continue
                    val name = localName(element)
                    if (name in setOf(
                            "role", "supplementalproperty", "essentialproperty",
                            "assetidentifier", "contentprotection", "baseurl",
                            "segmenttemplate", "segmenturl", "initialization"
                        )
                    ) {
                        append(' ')
                        append(element.textContent.orEmpty().take(2_048))
                        listOf(
                            "value", "schemeIdUri", "id", "contentType", "media",
                            "sourceURL", "initialization", "name", "label"
                        ).forEach { attribute ->
                            append(' ')
                            append(attributeIgnoreCase(element, attribute))
                        }
                    }
                }
            }

            val hasExplicitAdLabel = adMediaToken.containsMatchIn(adLabel)
            if (hasScte35CueOut(period)) markersFound = true

            // SCTE-35 can mark an insertion point inside the actual movie Period.
            // Only remove a Period when its own metadata/identity clearly labels it as an ad.
            if (hasExplicitAdLabel) {
                period.parentNode.removeChild(period)
                removedPeriods++
                markersFound = true
            }
        }

        if (removedPeriods == 0) {
            return StreamAdFilterResult(content, markersFound = markersFound)
        }
        val serialized = runCatching {
            val transformer = TransformerFactory.newInstance().newTransformer().apply {
                setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
                setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            }
            val bytes = ByteArrayOutputStream()
            transformer.transform(DOMSource(document), StreamResult(bytes))
            bytes.toString(StandardCharsets.UTF_8.name())
        }.getOrNull() ?: return StreamAdFilterResult(content)

        return StreamAdFilterResult(
            content = serialized,
            removedPeriods = removedPeriods,
            markersFound = markersFound
        )
    }

    private fun isSegmentScopedTag(line: String): Boolean =
        line.startsWith("#EXT-X-BYTERANGE", true) ||
            line.startsWith("#EXT-X-PROGRAM-DATE-TIME", true) ||
            line.startsWith("#EXT-X-GAP", true) ||
            line.equals("#EXT-X-DISCONTINUITY", true) ||
            line.startsWith("#EXT-X-DATERANGE:", true)

    private fun isScte35OutDateRange(line: String): Boolean =
        line.startsWith("#EXT-X-DATERANGE:", true) &&
            line.contains("SCTE35-OUT=", true)

    private fun isScte35InDateRange(line: String): Boolean =
        line.startsWith("#EXT-X-DATERANGE:", true) &&
            line.contains("SCTE35-IN=", true)

    private fun isAppleInterstitial(line: String): Boolean =
        line.startsWith("#EXT-X-DATERANGE:", true) &&
            line.contains("com.apple.hls.interstitial", true) &&
            (line.contains("X-ASSET-URI=", true) ||
                line.contains("X-ASSET-LIST=", true))

    private fun isAdDateRange(line: String): Boolean {
        if (!line.startsWith("#EXT-X-DATERANGE:", true)) return false
        val label = listOf(
            "ID", "CLASS", "X-ASSET-URI", "X-ASSET-LIST", "ASSET-URI",
            "X-AD-ID", "X-AD-URL", "SCTE35-CMD"
        ).mapNotNull { attributeValue(line, it) }.joinToString(" ")
        return adMediaToken.containsMatchIn(label)
    }

    private fun hasAdUriAttribute(line: String): Boolean =
        listOf("URI", "X-ASSET-URI", "X-ASSET-LIST", "ASSET-URI")
            .mapNotNull { attributeValue(line, it) }
            .any(::isAdMediaUri)

    private fun isAdMediaUri(value: String): Boolean {
        val parsed = runCatching { URI(value.trim()) }.getOrNull()
        val candidate = if (parsed != null) {
            parsed.host.orEmpty() + parsed.rawPath.orEmpty() +
                parsed.rawQuery?.let { "?$it" }.orEmpty()
        } else {
            value
        }
        var decoded = candidate
        repeat(2) {
            val next = runCatching {
                URLDecoder.decode(decoded, StandardCharsets.UTF_8.name())
            }.getOrDefault(decoded)
            if (next == decoded) return@repeat
            decoded = next
        }
        return adMediaToken.containsMatchIn(decoded)
    }

    private fun cueOutDuration(line: String): Double? {
        if (line.startsWith("#EXT-X-CUE-OUT:", true)) {
            return line.substringAfter(':').substringBefore(',').toDoubleOrNull()
        }
        return attributeNumber(line, "PLANNED-DURATION")
            ?: attributeNumber(line, "DURATION")
    }

    private fun attributeValue(line: String, name: String): String? {
        val key = Regex.escape(name)
        val quoted = Regex(
            """(?:^|[:,;])\s*$key\s*=\s*"([^"]*)"""",
            RegexOption.IGNORE_CASE
        ).find(line)
        if (quoted != null) return quoted.groupValues[1]

        return Regex(
            """(?:^|[:,;])\s*$key\s*=\s*([^,;]+)""",
            RegexOption.IGNORE_CASE
        ).find(line)?.groupValues?.get(1)?.trim()
    }

    private fun attributeNumber(line: String, name: String): Double? =
        attributeValue(line, name)?.toDoubleOrNull()

    private fun resolveHlsUri(line: String, baseUrl: String?): String {
        if (baseUrl.isNullOrBlank()) return line
        return resolveUri(line, baseUrl)
    }

    private fun resolveHlsUriAttribute(line: String, baseUrl: String?): String {
        if (baseUrl.isNullOrBlank() || !line.contains("URI=", true)) return line
        val regex = Regex("""URI="([^"]+)"""", RegexOption.IGNORE_CASE)
        return regex.replace(line) { match ->
            val value = match.groupValues[1]
            """URI="${resolveUri(value, baseUrl)}""""
        }
    }

    private fun resolveUri(value: String, baseUrl: String): String {
        val candidate = value.trim()
        if (candidate.isEmpty() || candidate.startsWith("data:", true)) return value
        return runCatching {
            val resolved = URI(baseUrl).resolve(candidate)
            if (resolved.scheme.equals("http", true) || resolved.scheme.equals("https", true)) {
                resolved.toASCIIString()
            } else {
                value
            }
        }.getOrDefault(value)
    }

    private fun hasScte35CueOut(period: Element): Boolean {
        val descendants = period.getElementsByTagName("*")
        for (index in 0 until descendants.length) {
            val stream = descendants.item(index) as? Element ?: continue
            if (localName(stream) != "eventstream") continue
            if (!attributeIgnoreCase(stream, "schemeIdUri").contains("scte35", true)) continue

            val events = stream.getElementsByTagName("*")
            for (eventIndex in 0 until events.length) {
                val event = events.item(eventIndex) as? Element ?: continue
                when (localName(event)) {
                    "spliceinsert" -> {
                        val outOfNetwork = attributeIgnoreCase(event, "outOfNetworkIndicator")
                        if (outOfNetwork.equals("true", true) || outOfNetwork == "1") return true
                    }
                    "segmentationdescriptor" -> {
                        val rawType = attributeIgnoreCase(event, "segmentationTypeId")
                        val type = if (rawType.startsWith("0x", true)) {
                            rawType.substring(2).toIntOrNull(16)
                        } else {
                            rawType.toIntOrNull()
                        }
                        if (type in setOf(34, 48, 52)) return true
                    }
                }
            }
        }
        return false
    }

    private fun attributeIgnoreCase(element: Element, name: String): String {
        val attributes = element.attributes
        for (index in 0 until attributes.length) {
            val attribute = attributes.item(index)
            if (attribute.nodeName.equals(name, true) ||
                attribute.localName?.equals(name, true) == true
            ) {
                return attribute.nodeValue.orEmpty()
            }
        }
        return ""
    }

    private fun localName(element: Element): String =
        (element.localName ?: element.tagName.substringAfter(':')).lowercase(Locale.ROOT)
}
