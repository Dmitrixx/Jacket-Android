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
        return ReleaseMetadata(
            quality = quality,
            hdr = hdr,
            dolbyVision = dolbyVision,
            videoCodec = codec,
            audioTags = audio.distinct(),
            sourceTags = source.distinct()
        )
    }
}
