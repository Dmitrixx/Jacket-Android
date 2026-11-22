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

    @Test
    fun firstRunSeedingAddsMissingPresetsWithoutOverwritingExistingSettings() {
        val savedDisabled = WebSearchProvider(
            id = "tracker-rutor",
            name = "My Rutor profile",
            searchUrlTemplate = "https://rutor.info/search/{query}",
            enabled = false,
            cookie = "saved-session"
        )
        val custom = WebSearchProvider(
            id = "custom",
            name = "My site",
            searchUrlTemplate = "https://example.org/search?q={query}",
            enabled = false
        )
        val defaults = listOf(
            savedDisabled.copy(name = "Rutor default", enabled = true, cookie = ""),
            WebSearchProvider(
                id = "tracker-kinozal",
                name = "Kinozal",
                searchUrlTemplate = "https://kinozal.tv/browse.php?s={query}"
            )
        )
        val seeded = seedTrackerShortcutsOnce(
            current = listOf(savedDisabled, custom),
            defaults = defaults,
            limit = 50,
            alreadySeeded = false
        )

        assertEquals(3, seeded.size)
        assertEquals("My Rutor profile", seeded.first { it.id == "tracker-rutor" }.name)
        assertEquals(false, seeded.first { it.id == "tracker-rutor" }.enabled)
        assertEquals("saved-session", seeded.first { it.id == "tracker-rutor" }.cookie)
        assertEquals(false, seeded.first { it.id == "custom" }.enabled)
        assertTrue(seeded.any { it.id == "tracker-kinozal" })
    }

    @Test
    fun oneTimeUpgradeReEnablesExistingBuiltInsButPreservesCustomSettings() {
        val builtIn = WebSearchProvider(
            id = "tracker-rutor",
            name = "My Rutor profile",
            searchUrlTemplate = "https://rutor.info/search/{query}",
            enabled = false,
            cookie = "saved-session"
        )
        val custom = WebSearchProvider(
            id = "custom",
            name = "My private site",
            searchUrlTemplate = "https://example.org/search?q={query}",
            enabled = false,
            cookie = "custom-cookie"
        )
        val defaults = listOf(
            builtIn.copy(name = "Rutor", enabled = true, cookie = "")
        )

        val migrated = reEnableExistingBuiltInTrackerShortcutsOnce(
            current = listOf(builtIn, custom),
            defaults = defaults,
            limit = 50
        )

        assertTrue(migrated.first { it.id == "tracker-rutor" }.enabled)
        assertEquals("My Rutor profile", migrated.first { it.id == "tracker-rutor" }.name)
        assertEquals("saved-session", migrated.first { it.id == "tracker-rutor" }.cookie)
        assertEquals(false, migrated.first { it.id == "custom" }.enabled)
        assertEquals("custom-cookie", migrated.first { it.id == "custom" }.cookie)
    }

    @Test
    fun oneTimeUpgradeDoesNotReAddBuiltInsPreviouslyRemovedByTheUser() {
        val remaining = listOf(
            WebSearchProvider(
                id = "custom",
                name = "My site",
                searchUrlTemplate = "https://example.org/search?q={query}"
            )
        )
        val defaults = listOf(
            WebSearchProvider(
                id = "tracker-rutor",
                name = "Rutor",
                searchUrlTemplate = "https://rutor.info/search/{query}"
            )
        )

        assertEquals(
            remaining,
            reEnableExistingBuiltInTrackerShortcutsOnce(
                current = remaining,
                defaults = defaults,
                limit = 50
            )
        )
    }

    @Test
    fun completedSeedDoesNotSilentlyReAddSitesRemovedByTheUser() {
        val remaining = listOf(
            WebSearchProvider(
                id = "custom",
                name = "My site",
                searchUrlTemplate = "https://example.org/search?q={query}"
            )
        )
        val defaults = listOf(
            WebSearchProvider(
                id = "tracker-rutor",
                name = "Rutor",
                searchUrlTemplate = "https://rutor.info/search/{query}"
            )
        )

        assertEquals(
            remaining,
            seedTrackerShortcutsOnce(
                current = remaining,
                defaults = defaults,
                limit = 50,
                alreadySeeded = true
            )
        )
    }

}
