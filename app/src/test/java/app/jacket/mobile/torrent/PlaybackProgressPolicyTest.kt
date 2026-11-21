package app.jacket.mobile.torrent

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackProgressPolicyTest {
    @Test
    fun explicitZeroPositionClearsSavedOffset() {
        assertEquals(PlaybackProgressAction.CLEAR, PlaybackProgressPolicy.action(0L, 10_000L))
    }

    @Test
    fun negativePositionIsNormalizedToZeroAndCleared() {
        assertEquals(PlaybackProgressAction.CLEAR, PlaybackProgressPolicy.action(-1L, 10_000L))
    }

    @Test
    fun positionBelowWatchedThresholdIsSaved() {
        assertEquals(PlaybackProgressAction.SAVE, PlaybackProgressPolicy.action(8_999L, 10_000L))
    }

    @Test
    fun positionAtNinetyPercentClearsOffset() {
        assertEquals(PlaybackProgressAction.CLEAR, PlaybackProgressPolicy.action(9_000L, 10_000L))
    }

    @Test
    fun positivePositionWithUnknownDurationIsSaved() {
        assertEquals(PlaybackProgressAction.SAVE, PlaybackProgressPolicy.action(500L, 0L))
    }

    @Test
    fun largeDurationDoesNotOverflowThresholdCalculation() {
        assertEquals(PlaybackProgressAction.SAVE, PlaybackProgressPolicy.action(Long.MAX_VALUE / 2L, Long.MAX_VALUE))
        assertEquals(PlaybackProgressAction.CLEAR, PlaybackProgressPolicy.action(Long.MAX_VALUE, Long.MAX_VALUE))
    }
}
