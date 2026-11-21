package app.jacket.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameRatePolicyTest {
    @Test
    fun acceptsCommonMovieAndBroadcastRates() {
        assertEquals(23.976f, FrameRatePolicy.normalize(23.976f)!!)
        assertEquals(25f, FrameRatePolicy.normalize(25f)!!)
        assertEquals(30f, FrameRatePolicy.normalize(30f)!!)
        assertEquals(60f, FrameRatePolicy.normalize(60f)!!)
    }

    @Test
    fun rejectsUnknownInvalidAndImplausibleRates() {
        assertNull(FrameRatePolicy.normalize(null))
        assertNull(FrameRatePolicy.normalize(-1f))
        assertNull(FrameRatePolicy.normalize(0f))
        assertNull(FrameRatePolicy.normalize(Float.NaN))
        assertNull(FrameRatePolicy.normalize(Float.POSITIVE_INFINITY))
        assertNull(FrameRatePolicy.normalize(240f))
    }
}
