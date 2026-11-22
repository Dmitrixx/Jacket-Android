package app.jacket.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import java.util.Base64

class FilmixDeviceAuthTest {
    @Test
    fun buildsTokenRequestWithCurrentLampaAndroidFingerprint() {
        val url = FilmixDeviceAuth.buildTokenRequestUrl("0123456789abcdef")
        assertTrue(url.startsWith("https://filmixapp.cyou/api/v2/token_request?"))
        assertTrue(url.contains("user_dev_id=0123456789abcdef"))
        assertTrue(url.contains("user_dev_name=Xiaomi"))
        assertTrue(url.contains("user_dev_vendor=Xiaomi"))
        assertTrue(url.contains("user_dev_os=14"))
        assertTrue(url.contains("user_dev_apk=2.2.0"))
        assertTrue(url.contains("app_lang=ru-RU"))
    }

    @Test
    fun buildsCheckUpdateWithSeparateNonAccountDeviceId() {
        val url = FilmixDeviceAuth.buildCheckUpdateUrl("https://filmixapp.cyou/api/v2")
        assertTrue(url.startsWith("https://filmixapp.cyou/api/v2/check_update?"))
        assertTrue(url.contains("user_dev_id=abcdef0123456789"))
        assertTrue(url.contains("user_dev_name=Xiaomi"))
        assertTrue(url.contains("app_lang=ru-RU"))
    }

    @Test
    fun offersOnlyExplicitHttpsApiEndpointsAndCurrentActivationPage() {
        assertTrue(FilmixDeviceAuth.apiBaseCandidates().all { it.startsWith("https://") })
        assertEquals(
            listOf(FilmixDeviceAuth.DEFAULT_API_BASE, FilmixDeviceAuth.ALTERNATE_API_BASE),
            FilmixDeviceAuth.apiBaseCandidates()
        )
        assertEquals(
            FilmixDeviceAuth.ALTERNATE_API_BASE,
            FilmixDeviceAuth.normalizeApiBase("https://fxapp.biz/")
        )
        assertEquals("https://filmix.gg/consoles", FilmixDeviceAuth.ACTIVATION_URL)
    }

    @Test
    fun buildsLampaCompatibleCorsProxyUrlAndOverridesFilmixUserAgent() {
        val proxyUrl = FilmixDeviceAuth.buildCorsProxyUrl(
            "https://filmixapp.cyou/api/v2/token_request?user_dev_token=private-token"
        )
        assertTrue(proxyUrl.startsWith("https://cors.jacred.stream/enc2/"))
        assertTrue(proxyUrl.endsWith("/token_request?jacred.test"))
        assertFalse(proxyUrl.contains("private-token"))

        val encoded = proxyUrl.substringAfter("/enc2/").substringBefore('/')
        val decoded = String(
            Base64.getDecoder().decode(URLDecoder.decode(encoded, Charsets.UTF_8.name())),
            Charsets.UTF_8
        )
        assertTrue(decoded.startsWith("param/User-Agent=okhttp%2F3.10.0/"))
        assertTrue(decoded.contains("http://filmixapp.cyou/api/v2/token_request?user_dev_token=private-token"))
    }

    @Test
    fun normalizesTheScreenshotCorsProxyAndRejectsCleartextProxy() {
        assertEquals(
            "https://cors.jacred.stream",
            FilmixDeviceAuth.normalizeCorsProxyBase("https://cors.jacred.stream/")
        )
        assertEquals("", FilmixDeviceAuth.normalizeCorsProxyBase(""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsCleartextCorsProxy() {
        FilmixDeviceAuth.normalizeCorsProxyBase("http://cors.jacred.stream/")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsProxyUrlsContainingCredentials() {
        FilmixDeviceAuth.normalizeCorsProxyBase("https://user:pass@cors.jacred.stream/")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDirectCleartextFilmixApi() {
        FilmixDeviceAuth.normalizeApiBase("http://filmixapp.cyou/api/v2", allowCustomHost = true)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownFilmixApiHost() {
        FilmixDeviceAuth.normalizeApiBase("https://unknown.example/api/v2")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnexpectedApiPath() {
        FilmixDeviceAuth.normalizeApiBase("https://fxapp.biz/custom")
    }

    @Test
    fun alternateApiUsesTheSameDeviceFingerprintAndV2Routes() {
        val url = FilmixDeviceAuth.buildTokenRequestUrl(
            FilmixDeviceAuth.ALTERNATE_API_BASE,
            "0123456789abcdef"
        )
        assertTrue(url.startsWith("https://fxapp.biz/api/v2/token_request?"))
        assertTrue(url.contains("user_dev_id=0123456789abcdef"))
        assertTrue(
            FilmixDeviceAuth.buildCheckUpdateUrl(FilmixDeviceAuth.ALTERNATE_API_BASE)
                .startsWith("https://fxapp.biz/api/v2/check_update?")
        )
    }

    @Test
    fun recognizesFilmixUpdateResponses() {
        assertTrue(FilmixDeviceAuth.isRecognizedCheckUpdateResponse("""{"version":"2.2.13"}"""))
        assertFalse(FilmixDeviceAuth.isRecognizedCheckUpdateResponse("""{"status":"ok"}"""))
    }

    @Test
    fun reportsPerHostFailureDetailsWithoutLeakingRequestParameters() {
        val message = FilmixDeviceAuth.userFriendlyError(
            java.io.IOException(
                "Filmix API недоступен по HTTPS (filmixapp.cyou: HTTP 405 на /check_update)."
            )
        )
        assertTrue(message.contains("filmixapp.cyou: HTTP 405"))
        assertFalse(message.contains("api.filmix.tv"))
        assertFalse(message.contains("user_dev_id="))
    }

    @Test
    fun tlsCertificateFailureIsShownAsActionableMessage() {
        val error = javax.net.ssl.SSLPeerUnverifiedException(
            "Hostname filmixapp.cyou not verified: certificate subject api.filmix.tv"
        )
        val message = FilmixDeviceAuth.userFriendlyError(
            java.io.IOException(
                "Filmix API недоступен по HTTPS (filmixapp.cyou: TLS: сертификат не соответствует имени домена)",
                error
            )
        )
        assertTrue(message.contains("TLS: сертификат не соответствует имени домена"))
        assertFalse(message.contains("certificate subject"))
    }

    @Test
    fun supportsAutoSelectionAndExplicitCustomHttpsHost() {
        val custom = "https://media.example/api/v2"
        assertEquals(FilmixDeviceAuth.AUTO_API_BASE, FilmixDeviceAuth.normalizeApiSelection("AUTO"))
        assertEquals(custom, FilmixDeviceAuth.normalizeApiSelection(custom))
        assertEquals(custom, FilmixDeviceAuth.normalizeApiBase(custom, allowCustomHost = true))
    }

    @Test
    fun autoCandidatesProbeCustomThenOfficialEndpoints() {
        val custom = "https://media.example/api/v2"
        assertEquals(
            listOf(custom, FilmixDeviceAuth.DEFAULT_API_BASE, FilmixDeviceAuth.ALTERNATE_API_BASE),
            FilmixDeviceAuth.apiBaseCandidates(FilmixDeviceAuth.AUTO_API_BASE, custom)
        )
    }

    @Test
    fun explicitOfficialSelectionDoesNotProbeCustomHost() {
        assertEquals(
            listOf(FilmixDeviceAuth.ALTERNATE_API_BASE),
            FilmixDeviceAuth.apiBaseCandidates(
                FilmixDeviceAuth.ALTERNATE_API_BASE,
                "https://media.example/api/v2"
            )
        )
    }

    @Test
    fun autoFallsBackWhenCustomEndpointProbeOrTokenRequestFails() {
        val custom = "https://media.example/api/v2"
        val attempted = mutableListOf<String>()
        val (activeBase, value) = FilmixDeviceAuth.firstSuccessfulApiBase(
            FilmixDeviceAuth.AUTO_API_BASE,
            custom
        ) { candidate ->
            attempted += candidate
            if (candidate == custom) throw java.io.IOException("custom API offline")
            "activation-response"
        }
        assertEquals(FilmixDeviceAuth.DEFAULT_API_BASE, activeBase)
        assertEquals("activation-response", value)
        assertEquals(listOf(custom, FilmixDeviceAuth.DEFAULT_API_BASE), attempted)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsCleartextCustomFilmixApi() {
        FilmixDeviceAuth.normalizeApiBase("http://media.example/api/v2", allowCustomHost = true)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsIpLiteralAsCustomFilmixApi() {
        FilmixDeviceAuth.normalizeApiBase("https://192.168.1.10/api/v2", allowCustomHost = true)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsLocalDomainAsCustomFilmixApi() {
        FilmixDeviceAuth.normalizeApiBase("https://filmix.local/api/v2", allowCustomHost = true)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsCredentialsInCustomFilmixApi() {
        FilmixDeviceAuth.normalizeApiBase("https://user:pass@media.example/api/v2", allowCustomHost = true)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsQueryInCustomFilmixApi() {
        FilmixDeviceAuth.normalizeApiBase("https://media.example/api/v2?token=secret", allowCustomHost = true)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMalformedDeviceId() {
        FilmixDeviceAuth.buildTokenRequestUrl("bad-id")
    }
}
