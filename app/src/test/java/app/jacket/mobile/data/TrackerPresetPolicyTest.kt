package app.jacket.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerPresetPolicyTest {
    @Test
    fun explicitlyRestoringPresetsReEnablesPreviouslyDisabledBuiltIns() {
        val disabled = WebSearchProvider(
            id = "tracker-rutor",
            name = "Rutor",
            searchUrlTemplate = "https://rutor.info/search/{query}",
            enabled = false
        )
        val added = WebSearchProvider(
            id = "tracker-rutracker",
            name = "RuTracker",
            searchUrlTemplate = "https://rutracker.org/forum/tracker.php?nm={query}"
        )
        val custom = WebSearchProvider(
            id = "custom",
            name = "My indexer",
            searchUrlTemplate = "https://example.org/search?q={query}",
            enabled = false
        )

        val merged = mergeTrackerShortcuts(
            current = listOf(disabled, custom),
            defaults = listOf(disabled.copy(enabled = true), added),
            limit = 50
        )

        assertEquals(3, merged.size)
        assertTrue(merged.first { it.id == "tracker-rutor" }.enabled)
        assertTrue(merged.first { it.id == "tracker-rutracker" }.enabled)
        assertEquals(false, merged.first { it.id == "custom" }.enabled)
        assertEquals("My indexer", merged.first { it.id == "custom" }.name)
    }
}
