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
    fun detectsScreenCopyTypesAndMarksThemForFiltering() {
        val cam = ReleaseHeuristics.classify("Film.1080p.CAMRip")
        val telesync = ReleaseHeuristics.classify("Film.1080p.TS.x264")
        val telecine = ReleaseHeuristics.classify("Film.TC.720p")
        val screener = ReleaseHeuristics.classify("Film.1080p.SCREENER")

        assertTrue(cam.sourceTags.contains("CAMRip"))
        assertTrue(telesync.sourceTags.contains("TeleSync"))
        assertTrue(telecine.sourceTags.contains("TeleCine"))
        assertTrue(screener.sourceTags.contains("SCR"))
        assertTrue(cam.screenCopy)
        assertTrue(telesync.screenCopy)
        assertTrue(telecine.screenCopy)
        assertTrue(screener.screenCopy)
    }

    @Test
    fun detectsHi10pHlgAndHdr10PlusWithoutCollapsingHdr10Plus() {
        val release = ReleaseHeuristics.classify("Film.Hi10P.HLG.HDR10+.WEB-DL")

        assertTrue(release.sourceTags.contains("10-bit"))
        assertTrue(release.sourceTags.contains("HLG"))
        assertTrue(release.sourceTags.contains("HDR10+"))
        assertFalse(release.sourceTags.contains("HDR"))
        assertTrue(release.hdr)
    }

    @Test
    fun detectsKnownRussianDubbingStudiosWithUnicodeCaseFolding() {
        val release = ReleaseHeuristics.classify(
            "Film.RED_HEAD_SOUND.HD-REZKA.КУБИК_В_КУБЕ.Jaskier.TV-Shows.Alex-Film.Ani-Libria.ДУБЛЯЖ"
        )

        assertTrue(release.sourceTags.contains("Red Head Sound"))
        assertTrue(release.sourceTags.contains("HDRezka"))
        assertTrue(release.sourceTags.contains("Кубик в кубе"))
        assertTrue(release.sourceTags.contains("Jaskier"))
        assertTrue(release.sourceTags.contains("TVShows"))
        assertTrue(release.sourceTags.contains("AlexFilm"))
        assertTrue(release.sourceTags.contains("AniLibria"))
        assertTrue(release.sourceTags.contains("Дубляж"))
    }

    @Test
    fun doesNotClassifyWordsThatMerelyContainScreenCopyAbbreviations() {
        val release = ReleaseHeuristics.classify("The Last Summer")

        assertFalse(release.screenCopy)
        assertFalse(release.sourceTags.contains("TeleSync"))
        assertFalse(release.sourceTags.contains("TeleCine"))
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
