package app.jacket.mobile.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexerErrorSanitizerTest {
    @Test
    fun redactsConfiguredSecretsAndCommonCredentialParameters() {
        val apiKey = "super-secret-api-key-93017"
        val cookie = "session-cookie-value-482"
        val raw = IllegalStateException(
            "Request failed: https://indexer.example/api?t=search&apikey=$apiKey&token=another-token " +
                "Cookie: $cookie Authorization: Basic QWxhZGRpbjpvcGVuIHNlc2FtZQ=="
        )

        val safe = IndexerErrorSanitizer.sanitize(raw, listOf(apiKey, cookie, "indexer-password-11"))

        assertFalse(safe.contains(apiKey))
        assertFalse(safe.contains(cookie))
        assertFalse(safe.contains("another-token"))
        assertFalse(safe.contains("QWxhZGRpbjpvcGVuIHNlc2FtZQ=="))
        assertTrue(safe.contains("indexer.example"))
        assertTrue(safe.length <= 180)
    }

    @Test
    fun redactsValuesEmbeddedInUrlsEvenWhenTheirNamesAreUnknown() {
        val safe = IndexerErrorSanitizer.sanitize(
            "Failed at https://indexer.example/path?privateField=do-not-leak&mode=search"
        )
        assertFalse(safe.contains("do-not-leak"))
        assertFalse(safe.contains("privateField"))
        assertTrue(safe.contains("indexer.example"))
    }
}
