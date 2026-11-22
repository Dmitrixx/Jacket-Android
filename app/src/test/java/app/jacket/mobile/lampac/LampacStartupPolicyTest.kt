package app.jacket.mobile.lampac

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LampacStartupPolicyTest {
    @Test
    fun firstRunMayCompileModulesForSeveralMinutes() {
        val startedAt = 10_000L
        assertFalse(
            LampacStartupPolicy.hasTimedOut(
                startedAt,
                startedAt + LampacStartupPolicy.STARTUP_TIMEOUT_MS - 1
            )
        )
    }

    @Test
    fun startupTimesOutAtFiveMinuteBoundary() {
        val startedAt = 10_000L
        assertTrue(
            LampacStartupPolicy.hasTimedOut(
                startedAt,
                startedAt + LampacStartupPolicy.STARTUP_TIMEOUT_MS
            )
        )
    }

    @Test
    fun aClockValueEarlierThanStartCannotCauseAnImmediateTimeout() {
        assertFalse(LampacStartupPolicy.hasTimedOut(20_000L, 10_000L))
    }
}
