package app.jacket.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class HdRezkaLoginClientTest {
    @Test
    fun normalizesSecureMirror() {
        assertEquals("https://hdrezka.tv", HdRezkaLoginClient.validateMirror("https://hdrezka.tv/"))
    }

    @Test
    fun acceptsRezkaZipAndAllowlistedUkraineAlternateMirror() {
        assertEquals("https://rezka.zip", HdRezkaLoginClient.validateMirror("https://rezka.zip"))
        assertEquals("https://rezkify.com", HdRezkaLoginClient.validateMirror("https://rezkify.com"))
    }

    @Test
    fun acceptsExactHostsFromTheMaintainedRezkaMirrorList() {
        listOf(
            "hdrezka.cm",
            "hdrezka.la",
            "standby-rezka.tv",
            "hdrezka.tv",
            "hdrezka-home.tv",
            "rezka.ag",
            "rezka-ua.net",
            "rezka.zip",
            "rezkify.com"
        ).forEach { host ->
            assertTrue("$host should be recognized", HdRezkaLoginClient.isKnownRezkaMirror(host))
            assertTrue(
                "www.$host should be recognized",
                HdRezkaLoginClient.isKnownRezkaMirror("www.$host")
            )
        }
    }

    @Test
    fun rejectsLookalikeDomainsFromTheKnownMirrorAllowlist() {
        listOf(
            "hdrezka.attacker.example",
            "hdrezka-fake.example",
            "rezka.attacker.example",
            "rezka-fake.example",
            "standby-rezka.attacker.example"
        ).forEach { host ->
            assertFalse("$host must not be trusted automatically", HdRezkaLoginClient.isKnownRezkaMirror(host))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonStandardHttpsPorts() {
        HdRezkaLoginClient.validateMirror("https://hdrezka.tv:8443")
    }

    @Test(expected = IllegalArgumentException::class)
    fun customMirrorTrustDoesNotAllowNonStandardHttpsPorts() {
        HdRezkaLoginClient.validateMirror(
            "https://media.home.example:8443",
            allowCustomMirror = true
        )
    }

    @Test
    fun recognizesOnlyACompleteDleAuthenticationCookiePair() {
        assertTrue(HdRezkaLoginClient.hasAuthenticatedCookie(
            "dle_user_id=12345; dle_password=secret-value; other=value"
        ))
        assertTrue(HdRezkaLoginClient.hasAuthenticatedCookie(
            "DLE_USER_ID=42; DLE_PASSWORD=abc123"
        ))
        assertFalse(HdRezkaLoginClient.hasAuthenticatedCookie("session=opaque-token"))
        assertFalse(HdRezkaLoginClient.hasAuthenticatedCookie("dle_user_id=123; dle_password="))
        assertFalse(HdRezkaLoginClient.hasAuthenticatedCookie("dle_user_id=0; dle_password=secret"))
        assertFalse(HdRezkaLoginClient.hasAuthenticatedCookie("dle_user_id=1; dle_password=x\r\nInjected: yes"))
    }

    @Test
    fun identifiesAnubisBrowserChallenge() {
        val html = """
            <html><head><title>О нет!</title></head>
            <body>Доступ запрещён: код ошибки. Защищено Anubis.</body></html>
        """.trimIndent()
        assertEquals(true, HdRezkaLoginClient.containsBrowserChallenge(html))
    }

    @Test(expected = IllegalArgumentException::class)
    fun requiresAnExplicitSourceAddress() {
        HdRezkaLoginClient.validateMirror("")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInsecureMirror() {
        HdRezkaLoginClient.validateMirror("http://hdrezka.example")
    }

    @Test
    fun usesSafeMethodForPostRedirects() {
        assertEquals("GET", HdRezkaLoginClient.loginRedirectMethod(301))
        assertEquals("GET", HdRezkaLoginClient.loginRedirectMethod(302))
        assertEquals("GET", HdRezkaLoginClient.loginRedirectMethod(303))
        assertEquals(null, HdRezkaLoginClient.loginRedirectMethod(307))
        assertEquals(null, HdRezkaLoginClient.loginRedirectMethod(308))
        assertEquals("", HdRezkaLoginClient.loginRedirectMethod(200))
    }

    @Test
    fun largeLandingPageCanBeInspectedWithoutBufferingTheWholeResponse() {
        val page = ByteArray(512 * 1024) { (it % 251).toByte() }
        val preview = HdRezkaLoginClient.readResponsePreview(ByteArrayInputStream(page), 64 * 1024)
        assertEquals(64 * 1024, preview.size)
        assertTrue(preview.contentEquals(page.copyOf(64 * 1024)))
    }

    @Test
    fun mirrorPreviewHandlesSmallResponsesWithoutPadding() {
        val bytes = "<html><title>OK</title></html>".toByteArray()
        val preview = HdRezkaLoginClient.readResponsePreview(ByteArrayInputStream(bytes), 64 * 1024)
        assertTrue(preview.contentEquals(bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnrelatedHostWithoutExplicitTrust() {
        HdRezkaLoginClient.validateMirror("https://example.com")
    }

    @Test
    fun acceptsCustomHttpsMirrorOnlyAfterExplicitTrust() {
        assertEquals(
            "https://media.home.example",
            HdRezkaLoginClient.validateMirror(
                "https://media.home.example/",
                allowCustomMirror = true
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun customMirrorCannotRedirectToAnotherOrigin() {
        HdRezkaLoginClient.validateRedirectTarget(
            "https://attacker.example",
            "https://media.home.example",
            allowCustomMirror = true
        )
    }

    @Test
    fun customMirrorAllowsOnlySameOriginRedirects() {
        assertEquals(
            "https://media.home.example",
            HdRezkaLoginClient.validateRedirectTarget(
                "https://media.home.example/",
                "https://media.home.example",
                allowCustomMirror = true
            )
        )
    }

    @Test
    fun knownRezkaMirrorRedirectsStayWithinKnownHostPatterns() {
        assertEquals(
            "https://rezka.zip",
            HdRezkaLoginClient.validateRedirectTarget(
                "https://rezka.zip",
                "https://hdrezka.tv",
                allowCustomMirror = false
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun explicitCustomMirrorTrustDoesNotAllowCleartextHttp() {
        HdRezkaLoginClient.validateMirror(
            "http://media.home.example",
            allowCustomMirror = true
        )
    }
}
