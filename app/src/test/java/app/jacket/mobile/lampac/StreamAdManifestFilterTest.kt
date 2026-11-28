package app.jacket.mobile.lampac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamAdManifestFilterTest {
    @Test
    fun removesSegmentsBetweenCueOutAndCueIn() {
        val playlist = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXT-X-MEDIA-SEQUENCE:100
            #EXTINF:8.0,
            intro.ts
            #EXT-X-CUE-OUT:12.0
            #EXTINF:6.0,
            ad-one.ts
            #EXTINF:6.0,
            ad-two.ts
            #EXT-X-CUE-IN
            #EXTINF:8.0,
            movie.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val result = StreamAdManifestFilter.filterHls(playlist)

        assertEquals(2, result.removedSegments)
        assertFalse(result.content.contains("ad-one.ts"))
        assertFalse(result.content.contains("ad-two.ts"))
        assertTrue(result.content.contains("intro.ts"))
        assertTrue(result.content.contains("movie.ts"))
        assertTrue(result.content.contains("#EXT-X-DISCONTINUITY"))
        assertTrue(result.changed)
    }

    @Test
    fun usesCueOutDurationWhenCueInIsMissing() {
        val playlist = """
            #EXTM3U
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXTINF:6.0,
            content-before.ts
            #EXT-X-CUE-OUT:12.0
            #EXTINF:6.0,
            ad-one.ts
            #EXTINF:6.0,
            ad-two.ts
            #EXTINF:6.0,
            content-after.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val result = StreamAdManifestFilter.filterHls(playlist)

        assertEquals(2, result.removedSegments)
        assertFalse(result.content.contains("ad-one.ts"))
        assertFalse(result.content.contains("ad-two.ts"))
        assertTrue(result.content.contains("content-after.ts"))
    }

    @Test
    fun supportsScte35DateRangeCueMarkers() {
        val playlist = """
            #EXTM3U
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXTINF:5.0,
            before.ts
            #EXT-X-DATERANGE:ID="break-1",START-DATE="2026-01-01T00:00:05Z",PLANNED-DURATION=10.0,SCTE35-OUT=0xFC
            #EXTINF:5.0,
            ad-one.ts
            #EXTINF:5.0,
            ad-two.ts
            #EXT-X-DATERANGE:ID="break-1",END-DATE="2026-01-01T00:00:15Z",SCTE35-IN=0xFC
            #EXTINF:5.0,
            after.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val result = StreamAdManifestFilter.filterHls(playlist)

        assertEquals(2, result.removedSegments)
        assertFalse(result.content.contains("ad-one.ts"))
        assertFalse(result.content.contains("ad-two.ts"))
        assertTrue(result.content.contains("after.ts"))
    }

    @Test
    fun cueOutContinuationIsHandledBeforeGenericCueOut() {
        val playlist = """
            #EXTM3U
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXTINF:4.0,
            before.ts
            #EXT-X-CUE-OUT-CONT:ElapsedTime=4.0,Duration=12.0
            #EXTINF:4.0,
            ad-one.ts
            #EXTINF:4.0,
            ad-two.ts
            #EXT-X-CUE-IN
            #EXTINF:4.0,
            after.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val result = StreamAdManifestFilter.filterHls(playlist)

        assertEquals(2, result.removedSegments)
        assertFalse(result.content.contains("ad-one.ts"))
        assertFalse(result.content.contains("ad-two.ts"))
    }

    @Test
    fun removesAppleInterstitialInsertionMarker() {
        val playlist = """
            #EXTM3U
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXT-X-DATERANGE:ID="interstitial-1",CLASS="com.apple.hls.interstitial",START-DATE="2026-01-01T00:00:00Z",X-ASSET-URI="ad.json"
            #EXTINF:10.0,
            movie.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val result = StreamAdManifestFilter.filterHls(playlist)

        assertEquals(1, result.removedInterstitialMarkers)
        assertFalse(result.content.contains("ad.json"))
        assertTrue(result.content.contains("movie.ts"))
    }

    @Test
    fun removesProviderDaterangePrerollUsingCommaDelimitedDuration() {
        val playlist = """
            #EXTM3U
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXTINF:4.0,
            movie-before.ts
            #EXT-X-DATERANGE:ID="preroll-01",CLASS="provider.break",PLANNED-DURATION=8.0
            #EXTINF:4.0,
            commercial-one.ts
            #EXTINF:4.0,
            commercial-two.ts
            #EXTINF:4.0,
            movie-after.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val result = StreamAdManifestFilter.filterHls(playlist)

        assertEquals(2, result.removedSegments)
        assertFalse(result.content.contains("commercial-one.ts"))
        assertFalse(result.content.contains("commercial-two.ts"))
        assertTrue(result.content.contains("movie-before.ts"))
        assertTrue(result.content.contains("movie-after.ts"))
    }

    @Test
    fun removesExplicitAdAssetUrisButKeepsOrdinaryWordsContainingAd() {
        val playlist = """
            #EXTM3U
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXTINF:6.0,
            ads/ad-001.ts
            #EXTINF:6.0,
            content/adventure.ts
            #EXTINF:6.0,
            movie.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val result = StreamAdManifestFilter.filterHls(
            playlist,
            "https://cdn.example.test/stream/index.m3u8"
        )

        assertEquals(1, result.removedSegments)
        assertFalse(result.content.contains("ads/ad-001.ts"))
        assertTrue(result.content.contains("adventure.ts"))
        assertTrue(result.content.contains("movie.ts"))
    }

    @Test
    fun removesDashPeriodWhoseBaseUrlClearlyNamesAnAdBreak() {
        val mpd = """
            <?xml version="1.0" encoding="UTF-8"?>
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static">
              <Period id="movie-start"><BaseURL>https://cdn.example.test/movie/</BaseURL><AdaptationSet/></Period>
              <Period id="period-2"><BaseURL>https://cdn.example.test/ads/break-01/</BaseURL><AdaptationSet/></Period>
              <Period id="movie-end"><BaseURL>https://cdn.example.test/movie/end/</BaseURL><AdaptationSet/></Period>
            </MPD>
        """.trimIndent()

        val result = StreamAdManifestFilter.filterDash(mpd)

        assertEquals(1, result.removedPeriods)
        assertFalse(result.content.contains("break-01"))
        assertTrue(result.content.contains("movie-start"))
        assertTrue(result.content.contains("movie-end"))
    }

    @Test
    fun leavesUnmarkedHlsUntouched() {
        val playlist = """
            #EXTM3U
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXTINF:10.0,
            part-one.ts
            #EXTINF:10.0,
            part-two.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val result = StreamAdManifestFilter.filterHls(playlist)

        assertEquals(playlist, result.content)
        assertFalse(result.changed)
    }

    @Test
    fun leavesLivePlaylistUntouchedToAvoidBreakingSequenceNumbers() {
        val playlist = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:20
            #EXT-X-CUE-OUT:6
            #EXTINF:6.0,
            live-ad.ts
            #EXT-X-CUE-IN
            #EXTINF:6.0,
            live-content.ts
        """.trimIndent()

        val result = StreamAdManifestFilter.filterHls(playlist)

        assertEquals(playlist, result.content)
        assertEquals(0, result.removedSegments)
    }

    @Test
    fun removesAdSegmentsNamedByHostAndNestedUrlEncoding() {
        val playlist = """
            #EXTM3U
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXTINF:6.0,
            https://ads.cdn.example.test/%252Fcampaign%252Fads%252Fspot-01.ts
            #EXTINF:6.0,
            https://cdn.example.test/content/download.mp4
            #EXT-X-ENDLIST
        """.trimIndent()

        val result = StreamAdManifestFilter.filterHls(playlist)

        assertEquals(1, result.removedSegments)
        assertFalse(result.content.contains("spot-01.ts"))
        assertTrue(result.content.contains("download.mp4"))
    }

    @Test
    fun removesAppleInterstitialAssetListMarker() {
        val playlist = """
            #EXTM3U
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXT-X-DATERANGE:ID="interstitial-2",CLASS="com.apple.hls.interstitial",START-DATE="2026-01-01T00:00:00Z",X-ASSET-LIST="https://cdn.example.test/ads/interstitial.json"
            #EXTINF:10.0,
            movie.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val result = StreamAdManifestFilter.filterHls(playlist)

        assertEquals(1, result.removedInterstitialMarkers)
        assertFalse(result.content.contains("interstitial.json"))
        assertTrue(result.content.contains("movie.ts"))
    }

    @Test
    fun removesMarkedLiveAdSegmentsWhenManifestUrlIsAvailable() {
        val playlist = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:20
            #EXTINF:6.0,
            live-before.ts
            #EXT-X-CUE-OUT:6
            #EXTINF:6.0,
            live-ad.ts
            #EXT-X-CUE-IN
            #EXTINF:6.0,
            live-after.ts
        """.trimIndent()

        val result = StreamAdManifestFilter.filterHls(
            playlist,
            "https://live.example.test/channel/index.m3u8"
        )

        assertEquals(1, result.removedSegments)
        assertFalse(result.content.contains("live-ad.ts"))
        assertTrue(result.content.contains("live-before.ts"))
        assertTrue(result.content.contains("live-after.ts"))
        assertTrue(result.content.contains("#EXT-X-DISCONTINUITY"))
    }

    @Test
    fun removesExplicitAdPeriodsFromDashManifest() {
        val mpd = """
            <?xml version="1.0" encoding="UTF-8"?>
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static">
              <Period id="content-start"><AdaptationSet/></Period>
              <Period id="ad-001"><AdaptationSet/></Period>
              <Period id="content-end"><AdaptationSet/></Period>
            </MPD>
        """.trimIndent()

        val result = StreamAdManifestFilter.filterDash(mpd)

        assertEquals(1, result.removedPeriods)
        assertFalse(result.content.contains("ad-001"))
        assertTrue(result.content.contains("content-start"))
        assertTrue(result.content.contains("content-end"))
    }

    @Test
    fun removesDashPeriodWithExplicitScte35CueOut() {
        val mpd = """
            <?xml version="1.0" encoding="UTF-8"?>
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static">
              <Period id="content-before"><AdaptationSet/></Period>
              <Period id="ad-break-start">
                <EventStream schemeIdUri="urn:scte:scte35:2013:xml" timescale="90000">
                  <Event id="1" duration="900000">
                    <SpliceInfoSection>
                      <SpliceInsert outOfNetworkIndicator="true"/>
                    </SpliceInfoSection>
                  </Event>
                </EventStream>
                <AdaptationSet/>
              </Period>
              <Period id="content-after"><AdaptationSet/></Period>
            </MPD>
        """.trimIndent()

        val result = StreamAdManifestFilter.filterDash(mpd)

        assertEquals(1, result.removedPeriods)
        assertFalse(result.content.contains("break-start"))
        assertTrue(result.content.contains("content-before"))
        assertTrue(result.content.contains("content-after"))
    }

    @Test
    fun keepsMoviePeriodWhenScte35OnlyMarksAnInsertionPoint() {
        val mpd = """
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static">
              <Period id="feature-film">
                <EventStream schemeIdUri="urn:scte:scte35:2013:xml" timescale="90000">
                  <Event id="1" duration="900000">
                    <SpliceInfoSection>
                      <SpliceInsert outOfNetworkIndicator="true"/>
                    </SpliceInfoSection>
                  </Event>
                </EventStream>
                <AdaptationSet/>
              </Period>
            </MPD>
        """.trimIndent()

        val result = StreamAdManifestFilter.filterDash(mpd)

        assertEquals(0, result.removedPeriods)
        assertTrue(result.content.contains("feature-film"))
        assertTrue(result.markersFound)
        assertFalse(result.changed)
    }

    @Test
    fun removesDashPeriodWithAdvertisementRole() {
        val mpd = """
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static">
              <Period id="main"><AdaptationSet/></Period>
              <Period id="period-2">
                <AdaptationSet>
                  <Role schemeIdUri="urn:mpeg:dash:role:2011" value="advertisement"/>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()

        val result = StreamAdManifestFilter.filterDash(mpd)

        assertEquals(1, result.removedPeriods)
        assertFalse(result.content.contains("period-2"))
        assertTrue(result.content.contains("main"))
    }

    @Test
    fun resolvesRelativeHlsSegmentsAgainstFinalManifestUrl() {
        val playlist = """
            #EXTM3U
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXTINF:10.0,
            segments/001.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val result = StreamAdManifestFilter.filterHls(
            playlist,
            "https://cdn.example.test/movie/master.m3u8"
        )

        assertTrue(result.content.contains("https://cdn.example.test/movie/segments/001.ts"))
    }
}
