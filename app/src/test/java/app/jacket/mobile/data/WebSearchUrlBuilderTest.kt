package app.jacket.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WebSearchUrlBuilderTest {
    @Test
    fun encodesQueryAndPreservesTemplateParameters() {
        assertEquals(
            "https://media.example/search/?do=search&q=The%20Matrix%201999",
            WebSearchUrlBuilder.build(
                "https://media.example/search/?do=search&q={query}",
                "The Matrix 1999"
            )
        )
    }

    @Test
    fun rejectsHttpAndMissingQueryToken() {
        assertThrows(IllegalArgumentException::class.java) {
            WebSearchUrlBuilder.validateTemplate("http://media.example/search?q={query}")
        }
        assertThrows(IllegalArgumentException::class.java) {
            WebSearchUrlBuilder.validateTemplate("https://media.example/search")
        }
    }

    @Test
    fun rejectsCredentialsAndFragments() {
        assertThrows(IllegalArgumentException::class.java) {
            WebSearchUrlBuilder.validateTemplate("https://user:pass@media.example/search?q={query}")
        }
        assertThrows(IllegalArgumentException::class.java) {
            WebSearchUrlBuilder.validateTemplate("https://media.example/search?q={query}#results")
        }
    }

    @Test
    fun rejectsEmptyQuery() {
        assertThrows(IllegalArgumentException::class.java) {
            WebSearchUrlBuilder.build("https://media.example/search?q={query}", "  ")
        }
    }
}
