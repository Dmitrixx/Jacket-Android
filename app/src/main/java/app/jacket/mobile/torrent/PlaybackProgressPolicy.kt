package app.jacket.mobile.torrent

/** Decides whether a saved playback offset should be retained or cleared. */
internal enum class PlaybackProgressAction {
    CLEAR,
    SAVE
}

internal object PlaybackProgressPolicy {
    private const val WATCHED_PERCENT = 90L

    fun action(positionMs: Long, durationMs: Long): PlaybackProgressAction {
        val position = positionMs.coerceAtLeast(0L)
        if (position == 0L) return PlaybackProgressAction.CLEAR
        if (durationMs > 0L && position >= watchedThreshold(durationMs)) {
            return PlaybackProgressAction.CLEAR
        }
        return PlaybackProgressAction.SAVE
    }

    /** Computes floor(duration * 90 / 100) without overflowing Long. */
    private fun watchedThreshold(durationMs: Long): Long {
        val duration = durationMs.coerceAtLeast(0L)
        val whole = duration / 10L * (WATCHED_PERCENT / 10L)
        val remainder = duration % 10L * WATCHED_PERCENT / 100L
        return whole + remainder
    }
}
