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
    fun separatesDirectAdaptersFromLampacManagedOptionalProviders() {
        val directAdapters = MediaSourceCatalog.sources
            .filter { it.integration == MediaSourceIntegration.DIRECT_ADAPTER }
            .map { it.id }
        val lampacManaged = MediaSourceCatalog.sources
            .filter { it.integration == MediaSourceIntegration.LAMPAC_MANAGED }
            .map { it.id }

        assertEquals(listOf("hdrezka", "filmix"), directAdapters)
        assertEquals(
            listOf("coldfilm", "baskino", "kinogo", "videocdn", "collaps", "kinobase", "cdnmovies", "lordfilm"),
            lampacManaged
        )
        assertTrue(MediaSourceCatalog.sources.none {
            it.integration == MediaSourceIntegration.AUTH_SETTINGS_ONLY ||
                it.integration == MediaSourceIntegration.MEDIA_ADAPTER_MISSING
        })
        assertTrue(lampacManaged.all { id ->
            MediaSourceCatalog.sources.first { it.id == id }.notes.contains("Lampac")
        })
        assertFalse(MediaSourceCatalog.sources.any { it.id == "coldfilm" &&
            it.integration == MediaSourceIntegration.DIRECT_ADAPTER })
    }
}
