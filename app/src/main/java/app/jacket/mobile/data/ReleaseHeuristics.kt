package app.jacket.mobile.data

/** Small title-only release classifier. These tags are heuristics, not tracker-specific metadata. */
object ReleaseHeuristics {
    private val resolutionPatterns = listOf(
        "2160p" to Regex("""(?i)(?<![a-z0-9])(?:2160p|4k|uhd)(?![a-z0-9])"""),
        "1080p" to Regex("""(?i)(?<![a-z0-9])1080[pi]?(?![a-z0-9])"""),
        "720p" to Regex("""(?i)(?<![a-z0-9])720[pi]?(?![a-z0-9])""")
    )
    private val audioPatterns = listOf(
        "DTS-HD MA" to Regex("""(?i)dts[ ._-]?hd[ ._-]?(?:ma|master)"""),
        "TrueHD" to Regex("""(?i)true[ ._-]?hd"""),
        "Atmos" to Regex("""(?i)\batmos\b"""),
        "DTS" to Regex("""(?i)\bdts\b"""),
        "EAC3" to Regex("""(?i)eac3|ddp(?:\d\.\d)?"""),
        "AC3" to Regex("""(?i)\bac3\b|dd(?:\d\.\d)?"""),
        "AAC" to Regex("""(?i)\baac\b"""),
        "FLAC" to Regex("""(?i)\bflac\b""")
    )
    private val sourcePatterns = listOf(
        "WEB-DL" to Regex("""(?i)web[ ._-]?dl"""),
        "WEBRip" to Regex("""(?i)web[ ._-]?rip"""),
        "BluRay" to Regex("""(?i)blu[ ._-]?ray|bdrip|brrip"""),
        "HDR" to Regex("""(?i)(?<![a-z0-9])hdr(?:10\+?)?(?![a-z0-9])"""),
        "DV" to Regex("""(?i)(?<![a-z0-9])(?:dolby[ ._-]?vision|dv)(?![a-z0-9])""")
    )

    private val dimensionPattern = Regex(
        """(?i)(?<![0-9])([0-9]{3,4})\\s*[x×]\\s*([0-9]{3,4})(?![0-9])"""
    )
    private val channelPattern = Regex(
        """(?i)(?<![0-9])([1-8]\\.[0-2])(?![0-9])"""
    )
    private val languageNames = linkedMapOf(
        "RUS" to Regex("""(?i)(?<![a-z0-9])(?:rus|russian)(?![a-z0-9])"""),
        "UKR" to Regex("""(?i)(?<![a-z0-9])(?:ukr|ukrainian)(?![a-z0-9])"""),
        "ENG" to Regex("""(?i)(?<![a-z0-9])(?:eng|english)(?![a-z0-9])"""),
        "GER" to Regex("""(?i)(?<![a-z0-9])(?:ger|german)(?![a-z0-9])"""),
        "FRE" to Regex("""(?i)(?<![a-z0-9])(?:fre|french)(?![a-z0-9])"""),
        "SPA" to Regex("""(?i)(?<![a-z0-9])(?:spa|spanish)(?![a-z0-9])"""),
        "ITA" to Regex("""(?i)(?<![a-z0-9])(?:ita|italian)(?![a-z0-9])"""),
        "POR" to Regex("""(?i)(?<![a-z0-9])(?:por|portuguese)(?![a-z0-9])"""),
        "POL" to Regex("""(?i)(?<![a-z0-9])(?:pol|polish)(?![a-z0-9])"""),
        "JPN" to Regex("""(?i)(?<![a-z0-9])(?:jpn|japanese)(?![a-z0-9])"""),
        "CHI" to Regex("""(?i)(?<![a-z0-9])(?:chi|chinese)(?![a-z0-9])"""),
        "HEB" to Regex("""(?i)(?<![a-z0-9])(?:heb|hebrew)(?![a-z0-9])""")
    )
    private val subtitlePattern = Regex(
        """(?i)(?:sub(?:title)?s?[ ._-]*[:=]?[ ._-]*(rus|ukr|eng|ger|fre|spa|ita|por|pol|jpn|chi|heb))|(?:(rus|ukr|eng|ger|fre|spa|ita|por|pol|jpn|chi|heb)[ ._-]*sub(?:title)?s?)"""
    )
    private val bitratePattern = Regex(
        """(?i)([0-9]+(?:[.,][0-9]+)?)\\s*(?:mbit/s|мбит/с|mbps)"""
    )

    fun classify(title: String): ReleaseMetadata {
        val quality = resolutionPatterns.firstOrNull { it.second.containsMatchIn(title) }?.first
        val hdr = Regex("""(?i)(?<![a-z0-9])hdr(?:10\+?)?(?![a-z0-9])""").containsMatchIn(title)
        val dolbyVision = Regex("""(?i)(?<![a-z0-9])(?:dolby[ ._-]?vision|dv)(?![a-z0-9])""").containsMatchIn(title)
        val codec = when {
            Regex("""(?i)(?<![a-z0-9])(?:hevc|h\.265|x265)(?![a-z0-9])""").containsMatchIn(title) -> "HEVC / H.265"
            Regex("""(?i)(?<![a-z0-9])(?:av1)(?![a-z0-9])""").containsMatchIn(title) -> "AV1"
            Regex("""(?i)(?<![a-z0-9])(?:avc|h\.264|x264)(?![a-z0-9])""").containsMatchIn(title) -> "AVC / H.264"
            else -> null
        }
        val audio = audioPatterns.filter { it.second.containsMatchIn(title) }.map { it.first }
        val source = sourcePatterns.filter { it.second.containsMatchIn(title) }.map { it.first } +
            listOf("DUB", "MVO", "AVO", "VO", "LE", "LostFilm", "NewStudio").filter {
                Regex("""(?i)(?<![a-z0-9])${Regex.escape(it)}(?![a-z0-9])""").containsMatchIn(title)
            }
        val dimensions = dimensionPattern.find(title)?.let { "${it.groupValues[1]}x${it.groupValues[2]}" }
        val channelCount = channelPattern.find(title)?.groupValues?.getOrNull(1)
        val subtitleLanguages = subtitlePattern.findAll(title).mapNotNull { match ->
            (match.groupValues.getOrNull(1).orEmpty().ifBlank { match.groupValues.getOrNull(2).orEmpty() })
                .uppercase().takeIf(String::isNotBlank)
        }.distinct().toList()
        val foundLanguages = languageNames.mapNotNull { (code, pattern) ->
            code.takeIf { pattern.containsMatchIn(title) }
        }
        val audioLanguages = foundLanguages.filterNot { it in subtitleLanguages }
        val bitrate = bitratePattern.find(title)?.groupValues?.getOrNull(1)?.replace(',', '.')?.let { "$it Мбит/с" }
        val displayQuality = quality ?: when {
            dimensions?.startsWith("3840") == true -> "2160p"
            dimensions?.startsWith("1920") == true -> "FHD"
            dimensions?.startsWith("1280") == true -> "720p"
            else -> null
        }
        return ReleaseMetadata(
            quality = displayQuality,
            hdr = hdr,
            dolbyVision = dolbyVision,
            videoCodec = codec,
            audioTags = audio.distinct(),
            sourceTags = source.distinct(),
            resolutionDimensions = dimensions,
            audioChannels = channelCount,
            audioLanguages = audioLanguages,
            subtitleLanguages = subtitleLanguages,
            bitrate = bitrate
        )
    }
}
