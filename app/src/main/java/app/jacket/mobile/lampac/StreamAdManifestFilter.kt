package app.jacket.mobile.lampac

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Locale
import javax.xml.XMLConstants
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

/**
 * Removes ad media that is explicitly marked in HLS or DASH manifests.
 *
 * This is deliberately marker-driven. A stitched advertisement with no cue,
 * daterange, SCTE-35 or identifiable DASH Period metadata is indistinguishable
 * from ordinary video at the manifest level and is left untouched.
 */
internal object StreamAdManifestFilter {
    private val adPeriodId = Regex(
        """(^|[-_.])(ad|ads|advert|advertisement|commercial|preroll|midroll|postroll)([-_.]|$)""",
        RegexOption.IGNORE_CASE
    )

    fun filter(content: String, baseUrl: String? = null): StreamAdFilterResult {
        val normalized = content.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        return when {
            normalized.startsWith("#EXTM3U") -> filterHls(content, baseUrl)
            normalized.startsWith("<") && normalized.contains("<MPD", ignoreCase = true) ->
                filterDash(content, baseUrl)
            else -> StreamAdFilterResult(content)
        }
    }

    fun filterHls(content: String, baseUrl: String? = null): StreamAdFilterResult {
        val newline = if (content.contains("\r\n")) "\r\n" else "\n"
        val lines = content.removePrefix("\uFEFF").split(Regex("\\r?\\n"))
        val isVod = lines.any { it.equals("#EXT-X-ENDLIST", true) } ||
            lines.any { it.startsWith("#EXT-X-PLAYLIST-TYPE:", true) &&
                it.substringAfter(':').trim().equals("VOD", true) }

        if (!isVod) return StreamAdFilterResult(content)

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
            if (insideAd) {
                removedSegments++
                if (!keptAnySegment) removedBeforeFirstKept++
                pendingSegmentTags.clear()
                val remaining = remainingAdSeconds
                if (remaining != null) {
                    remainingAdSeconds = (remaining - currentSegmentDuration).coerceAtLeast(0.0)
                    if (remainingAdSeconds == 0.0) {
                        insideAd = false
                        adGapPending = true
                    }
                }
            } else {
                if (adGapPending && pendingSegmentTags.none {
                        it.equals("#EXT-X-DISCONTINUITY", true)
                    }) {
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

            if (line.startsWith("#EXT-X-CUE-OUT-CONT", true)) {
                markersFound = true
                val duration = attributeNumber(line, "Duration")
                val elapsed = attributeNumber(line, "ElapsedTime") ?: 0.0
                if (!insideAd) beginAd(duration?.minus(elapsed))
                else if (duration != null) remainingAdSeconds = (duration - elapsed).coerceAtLeast(0.0)
                continue
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

            if (isSegmentScopedTag(line)) {
                if (pendingSegmentTags.isNotEmpty() || line.startsWith("#EXT-X-PROGRAM-DATE-TIME", true)) {
                    pendingSegmentTags += rawLine
                } else if (!insideAd) {
                    output += rawLine
                }
                continue
            }

            if (line.startsWith("#EXT-X-PART:", true) ||
                line.startsWith("#EXT-X-PRELOAD-HINT:", true)
            ) {
                if (!insideAd) output += resolveHlsUriAttribute(rawLine, baseUrl)
                continue
            }

            // Key/map and playlist-level control tags are retained. This keeps the
            // decoder state intact when the next non-ad segment is reached.
            output += resolveHlsUriAttribute(rawLine, baseUrl)
        }

        // Keep any trailing playlist-level tags, but never emit a dangling EXTINF.
        if (pendingSegmentTags.isNotEmpty() && !insideAd) {
            output += pendingSegmentTags.filterNot { it.trim().startsWith("#EXTINF:", true) }
        }

        if (!markersFound) return StreamAdFilterResult(content)
        val rewritten = output.toMutableList()
        if (removedBeforeFirstKept > 0) {
            val sequenceIndex = rewritten.indexOfFirst { it.startsWith("#EXT-X-MEDIA-SEQUENCE:", true) }
            if (sequenceIndex >= 0) {
                val original = rewritten[sequenceIndex].substringAfter(':').trim().toLongOrNull()
                if (original != null) {
                    rewritten[sequenceIndex] = "#EXT-X-MEDIA-SEQUENCE:${original + removedBeforeFirstKept}"
                }
            }
        }

        return StreamAdFilterResult(
            content = rewritten.joinToString(newline).let { if (content.endsWith("\n")) it + newline else it },
            removedSegments = removedSegments,
            removedInterstitialMarkers = removedInterstitialMarkers,
            markersFound = true
        )
    }

    fun filterDash(content: String, baseUrl: String? = null): StreamAdFilterResult {
        val document = runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            }
            factory.newDocumentBuilder().parse(ByteArrayInputStream(content.toByteArray(StandardCharsets.UTF_8)))
        }.getOrNull() ?: return StreamAdFilterResult(content)

        val root = document.documentElement ?: return StreamAdFilterResult(content)
        val periods = mutableListOf<Element>()
        val children = root.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index)
            if (child is Element && localName(child) == "Period") periods += child
        }

        var removedPeriods = 0
        var markersFound = false
        periods.forEach { period ->
            val id = period.getAttribute("id")
            val adLabel = period.getAttribute("contentType") +
                " " + period.getAttribute("assetIdentifier") +
                " " + period.getAttribute("role") +
                " " + id
            if (adPeriodId.containsMatchIn(adLabel)) {
                period.parentNode.removeChild(period)
                removedPeriods++
                markersFound = true
            } else {
                if (baseUrl != null) resolveDashReferences(period, baseUrl)
            }
        }

        if (removedPeriods == 0) return StreamAdFilterResult(content)

        val serialized = runCatching {
            val transformerFactory = TransformerFactory.newInstance().apply {
                setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "")
            }
            val transformer = transformerFactory.newTransformer().apply {
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
            line.startsWith("#EXT-X-DATERANGE:", true)

    private fun isScte35OutDateRange(line: String): Boolean =
        line.startsWith("#EXT-X-DATERANGE:", true) &&
            line.contains("SCTE35-OUT=", true)

    private fun isScte35InDateRange(line: String): Boolean =
        line.startsWith("#EXT-X-DATERANGE:", true) &&
            line.contains("SCTE35-IN=", true)

    private fun isAppleInterstitial(line: String): Boolean =
        line.startsWith("#EXT-X-DATERANGE:", true) &&
            line.contains("CLASS=\"com.apple.hls.interstitial\"", true) &&
            line.contains("X-ASSET-URI=", true)

    private fun cueOutDuration(line: String): Double? {
        if (line.startsWith("#EXT-X-CUE-OUT:", true)) {
            return line.substringAfter(':').substringBefore(',').toDoubleOrNull()
        }
        return attributeNumber(line, "PLANNED-DURATION")
            ?: attributeNumber(line, "DURATION")
    }

    private fun attributeNumber(line: String, name: String): Double? {
        val match = Regex("""(?:^|[,;])\\s*${Regex.escape(name)}\\s*=\\s*\\"?([0-9]+(?:\\.[0-9]+)?)""",
            RegexOption.IGNORE_CASE
        ).find(line) ?: return null
        return match.groupValues[1].toDoubleOrNull()
    }

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

    private fun resolveDashReferences(period: Element, baseUrl: String) {
        val elements = period.getElementsByTagName("*")
        for (index in 0 until elements.length) {
            val element = elements.item(index) as? Element ?: continue
            when (localName(element)) {
                "BaseURL", "Location" -> {
                    val value = element.textContent?.trim().orEmpty()
                    if (value.isNotEmpty()) element.textContent = resolveUri(value, baseUrl)
                }
                "SegmentTemplate" -> {
                    listOf("media", "initialization").forEach { attribute ->
                        if (element.hasAttribute(attribute)) {
                            element.setAttribute(attribute, resolveUri(element.getAttribute(attribute), baseUrl))
                        }
                    }
                }
                "SegmentURL" -> {
                    listOf("media", "index", "sourceURL").forEach { attribute ->
                        if (element.hasAttribute(attribute)) {
                            element.setAttribute(attribute, resolveUri(element.getAttribute(attribute), baseUrl))
                        }
                    }
                }
            }
        }
    }

    private fun localName(element: Element): String =
        (element.localName ?: element.tagName.substringAfter(':')).lowercase(Locale.ROOT)
}
