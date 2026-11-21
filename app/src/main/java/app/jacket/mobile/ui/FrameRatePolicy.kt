package app.jacket.mobile.ui

/** Accept only finite, plausible video frame rates before requesting a display mode change. */
internal object FrameRatePolicy {
    fun normalize(frameRate: Float?): Float? = frameRate
        ?.takeIf { it.isFinite() && it in MIN_FRAME_RATE..MAX_FRAME_RATE }
    
    private const val MIN_FRAME_RATE = 1f
    private const val MAX_FRAME_RATE = 120f
}
