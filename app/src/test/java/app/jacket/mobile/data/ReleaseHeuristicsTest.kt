package app.jacket.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseHeuristicsTest {
    @Test
    fun classifiesResolutionAndCommonVideoTags() {
        val release = ReleaseHeuristics.classify("Film.2160p.WEB-DL.HEVC.x265.HDR10.DV")
        assertEquals("2160p", release.quality)
        assertEquals("HEVC / H.265", release.videoCodec)
        assertTrue(release.hdr)
        assertTrue(release.dolbyVision)
        assertTrue(release.sourceTags.contains("WEB-DL"))
    }

    @Test
    fun classifies1080pAndAudio() {
        val release = ReleaseHeuristics.classify("Movie.1080p.BluRay.DTS-HD.MA.Russian.MVO")
        assertEquals("1080p", release.quality)
        assertTrue(release.audioTags.contains("DTS-HD MA"))
        assertTrue(release.sourceTags.contains("BluRay"))
        assertTrue(release.sourceTags.contains("MVO"))
    }

    @Test
    fun extractsDimensionsAudioChannelsLanguagesSubtitlesAndBitrate() {
        val release = ReleaseHeuristics.classify(
            "Resident.Evil.2026.1920x800.5.1.WEB-DL.RUS-MovieDalen.UKR-LeDoyen.ENG.SUB.10.55 Mbps"
        )
        assertEquals("FHD", release.quality)
        assertEquals("1920x800", release.resolutionDimensions)
        assertEquals("5.1", release.audioChannels)
        assertTrue(release.audioLanguages.contains("RUS"))
        assertTrue(release.audioLanguages.contains("UKR"))
        assertTrue(release.subtitleLanguages.contains("ENG"))
        assertEquals("10.55 Мбит/с", release.bitrate)
    }

    @Test
    fun doesNotInventMetadataForPlainTitle() {
        val release = ReleaseHeuristics.classify("A Simple Film Title")
        assertEquals(null, release.quality)
        assertEquals(null, release.videoCodec)
        assertFalse(release.hdr)
        assertFalse(release.dolbyVision)
    }
}
