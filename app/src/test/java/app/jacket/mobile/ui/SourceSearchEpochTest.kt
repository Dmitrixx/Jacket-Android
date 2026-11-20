package app.jacket.mobile.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSearchEpochTest {
    @Test
    fun newerSearchInvalidatesCompletionFromOlderSearch() {
        val epoch = SourceSearchEpoch()
        val olderSearch = epoch.next()
        val newerSearch = epoch.next()

        assertFalse(epoch.isCurrent(olderSearch))
        assertTrue(epoch.isCurrent(newerSearch))
    }

    @Test
    fun clearingSearchInvalidatesPreviouslyStartedWork() {
        val epoch = SourceSearchEpoch()
        val activeSearch = epoch.next()
        val clearedState = epoch.next()

        assertFalse(epoch.isCurrent(activeSearch))
        assertTrue(epoch.isCurrent(clearedState))
    }
}
