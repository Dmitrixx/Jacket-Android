package app.jacket.mobile.lampac

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LampacLogSanitizerTest {
    @Test
    fun redactsSensitiveQueryParametersAndAccountIdentifiers() {
        val source = "GET /lite/events?token=secret-token&uid=user-123&account_email=me@example.com"

        val result = LampacLogSanitizer.sanitize(source)

        assertFalse(result.contains("secret-token"))
        assertFalse(result.contains("user-123"))
        assertFalse(result.contains("me@example.com"))
        assertTrue(result.contains("token=[REDACTED]"))
        assertTrue(result.contains("uid=[REDACTED]"))
        assertTrue(result.contains("account_email=[REDACTED]"))
    }

    @Test
    fun redactsJsonCredentialsAndBearerAuthorization() {
        val source = """{"password":"super-secret","api_key":"key-value"} Authorization: Bearer abc.def.ghi"""

        val result = LampacLogSanitizer.sanitize(source)

        assertFalse(result.contains("super-secret"))
        assertFalse(result.contains("key-value"))
        assertFalse(result.contains("abc.def.ghi"))
        assertTrue(result.contains("[REDACTED]"))
    }

    @Test
    fun redactsEntireCookieHeader() {
        val result = LampacLogSanitizer.sanitize("Cookie: session=private; refresh=hidden")

        assertFalse(result.contains("session=private"))
        assertFalse(result.contains("refresh=hidden"))
        assertTrue(result.contains("Cookie: [REDACTED]"))
    }

    @Test
    fun keepsNonSensitiveDiagnosticsAndNormalizesControlCharacters() {
        assertTrue(LampacLogSanitizer.sanitize("Lampac startup completed").contains("Lampac startup completed"))
        assertTrue(LampacLogSanitizer.sanitize("ready\r\nlistening\t9118") == "ready listening 9118")
    }
}
