package app.jacket.mobile.ui

import kotlin.math.abs

/** Pure player-chrome decisions. No Android or Media3 types, so JVM tests can cover them. */
object PlaybackChrome {
    val SPEEDS: FloatArray = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
    val SLEEP_MINUTES: IntArray = intArrayOf(0, 15, 30, 45, 60)
    const val SEEK_STEP_MS = 10_000L

    enum class Fit { Fit, Fill, Zoom }

    fun nextSpeed(current: Float): Float {
        val index = SPEEDS.indexOfFirst { abs(it - current) < 0.02f }
        return SPEEDS[if (index < 0) 0 else (index + 1) % SPEEDS.size]
    }

    fun speedLabel(speed: Float): String = when {
        abs(speed - 0.5f) < 0.02f -> "0.5×"
        abs(speed - 0.75f) < 0.02f -> "0.75×"
        abs(speed - 1.25f) < 0.02f -> "1.25×"
        abs(speed - 1.5f) < 0.02f -> "1.5×"
        abs(speed - 1.75f) < 0.02f -> "1.75×"
        abs(speed - 2f) < 0.02f -> "2×"
        else -> "1×"
    }

    fun nextSleepMinutes(current: Int): Int {
        val index = SLEEP_MINUTES.indexOf(current)
        return SLEEP_MINUTES[if (index < 0) 1 else (index + 1) % SLEEP_MINUTES.size]
    }

    fun sleepLabel(minutes: Int): String = when (minutes) {
        15 -> "Сон 15"
        30 -> "Сон 30"
        45 -> "Сон 45"
        60 -> "Сон 60"
        else -> "Сон выкл"
    }

    fun nextFit(current: Fit): Fit = when (current) {
        Fit.Fit -> Fit.Fill
        Fit.Fill -> Fit.Zoom
        Fit.Zoom -> Fit.Fit
    }

    fun fitLabel(fit: Fit): String = when (fit) {
        Fit.Fit -> "Вписать"
        Fit.Fill -> "Заполнить"
        Fit.Zoom -> "Крупно"
    }

    fun formatClock(ms: Long): String {
        if (ms <= 0L) return "0:00"
        val totalSeconds = ms / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, seconds)
        else "%d:%02d".format(minutes, seconds)
    }

    fun remainingPhrase(positionMs: Long, durationMs: Long): String {
        if (durationMs <= 0L) return "Продолжить с сохранённой позиции"
        val left = (durationMs - positionMs).coerceAtLeast(0L)
        return "Осталось ${formatClock(left)}"
    }
}
