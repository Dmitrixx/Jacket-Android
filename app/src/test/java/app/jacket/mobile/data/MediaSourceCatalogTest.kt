package app.jacket.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSourceCatalogTest {
    @Test
    fun preservesRequestedProviderOrderAndCapabilities() {
        val sources = MediaSourceCatalog.sources

        assertEquals(10, sources.size)
        assertEquals(
            listOf("hdrezka", "filmix", "coldfilm", "baskino", "kinogo",
                "videocdn", "collaps", "kinobase", "cdnmovies", "lordfilm"),
            sources.sortedBy { it.priority }.map { it.id }
        )
        assertEquals(listOf("movie", "serial", "anime"), sources.first().types)
        assertTrue(sources.first().supports4k)
        assertTrue(sources[1].authRequired)
        assertTrue(sources[1].supports4k)
        assertEquals(listOf("serial"), sources[2].types)
    }

    @Test
    fun marksOnlyExistingAuthenticationFlowsAsAvailable() {
        val integrated = MediaSourceCatalog.sources
            .filter { it.integration == MediaSourceIntegration.AUTH_SETTINGS_ONLY }
            .map { it.id }

        assertEquals(listOf("hdrezka", "filmix"), integrated)
        assertTrue(MediaSourceCatalog.sources
            .filter { it.integration == MediaSourceIntegration.MEDIA_ADAPTER_MISSING }
            .all { it.notes.contains("адаптер") || it.notes.contains("API") ||
                it.notes.contains("источник") || it.notes.contains("плагин") })
        assertFalse(MediaSourceCatalog.sources.any { it.id == "coldfilm" &&
            it.integration == MediaSourceIntegration.AUTH_SETTINGS_ONLY })
    }
}
