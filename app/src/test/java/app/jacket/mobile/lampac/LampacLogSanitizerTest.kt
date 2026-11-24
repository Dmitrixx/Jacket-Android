package app.jacket.mobile.lampac

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LampacLogSanitizerTest {
    @Test
    fun stripsAnsiEscapeSequencesAndBracketOnlyFragments() {
        val source = "\u001B[1;34m[\u001B[32m*\u001B[0m Debian [1;34m[0m"
        val sanitized = LampacLogSanitizer.sanitize(source)

        assertTrue(sanitized.contains("* Debian"))
        assertTrue(!sanitized.contains("[1;34m"))
        assertTrue(!sanitized.contains("[32m"))
        assertTrue(!sanitized.contains("[0m"))
    }

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
    fun redactsSessionAndCookieValues() {
        val source = "session_id=private-session&auth_token=private-auth • Cookie: session=private; refresh=hidden"

        val result = LampacLogSanitizer.sanitize(source)

        assertFalse(result.contains("private-session"))
        assertFalse(result.contains("private-auth"))
        assertFalse(result.contains("session=private"))
        assertFalse(result.contains("refresh=hidden"))
        assertTrue(result.contains("session_id=[REDACTED]"))
        assertTrue(result.contains("auth_token=[REDACTED]"))
        assertTrue(result.contains("Cookie: [REDACTED]"))
    }

    @Test
    fun keepsNonSensitiveDiagnosticsAndNormalizesControlCharacters() {
        assertTrue(LampacLogSanitizer.sanitize("Lampac startup completed").contains("Lampac startup completed"))
        assertTrue(LampacLogSanitizer.sanitize("ready\r\nlistening\t9118") == "ready listening 9118")
    }
}
