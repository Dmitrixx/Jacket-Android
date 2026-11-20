package app.jacket.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Test

class HdRezkaLoginClientTest {
    @Test
    fun normalizesSecureMirror() {
        assertEquals("https://hdrezka.example", HdRezkaLoginClient.validateMirror("https://hdrezka.example/"))
    }

    @Test
    fun acceptsRezkaZipAndAllowlistedUkraineAlternateMirror() {
        assertEquals("https://rezka.zip", HdRezkaLoginClient.validateMirror("https://rezka.zip"))
        assertEquals("https://rezkify.com", HdRezkaLoginClient.validateMirror("https://rezkify.com"))
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

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnrelatedHost() {
        HdRezkaLoginClient.validateMirror("https://example.com")
    }
}
