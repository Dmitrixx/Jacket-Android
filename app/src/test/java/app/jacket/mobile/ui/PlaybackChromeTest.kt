package app.jacket.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackChromeTest {
    @Test
    fun cyclesPlaybackSpeedAndLabels() {
        assertEquals(1.25f, PlaybackChrome.nextSpeed(1f), 0.001f)
        assertEquals(0.5f, PlaybackChrome.nextSpeed(2f), 0.001f)
        assertEquals(0.5f, PlaybackChrome.nextSpeed(9f), 0.001f)
        assertEquals("1×", PlaybackChrome.speedLabel(1f))
        assertEquals("1.5×", PlaybackChrome.speedLabel(1.5f))
    }

    @Test
    fun cyclesSleepTimerAndFit() {
        assertEquals(15, PlaybackChrome.nextSleepMinutes(0))
        assertEquals(0, PlaybackChrome.nextSleepMinutes(60))
        assertEquals("Сон выкл", PlaybackChrome.sleepLabel(0))
        assertEquals("Сон 30", PlaybackChrome.sleepLabel(30))
        assertEquals(PlaybackChrome.Fit.Fill, PlaybackChrome.nextFit(PlaybackChrome.Fit.Fit))
        assertEquals(PlaybackChrome.Fit.Fit, PlaybackChrome.nextFit(PlaybackChrome.Fit.Zoom))
        assertEquals("Крупно", PlaybackChrome.fitLabel(PlaybackChrome.Fit.Zoom))
    }

    @Test
    fun formatsClocksWithoutPaddingHours() {
        assertEquals("0:00", PlaybackChrome.formatClock(0L))
        assertEquals("1:05", PlaybackChrome.formatClock(65_000L))
        assertEquals("1:01:01", PlaybackChrome.formatClock(3_661_000L))
        assertEquals("Осталось 5:00", PlaybackChrome.remainingPhrase(60_000L, 360_000L))
        assertEquals(
            "Продолжить с сохранённой позиции",
            PlaybackChrome.remainingPhrase(10_000L, 0L)
        )
    }
}
