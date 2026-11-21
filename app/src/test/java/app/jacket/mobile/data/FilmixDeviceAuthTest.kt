package app.jacket.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilmixDeviceAuthTest {
    @Test
    fun buildsTokenRequestWithKnownCompatibleAndroidFingerprint() {
        val url = FilmixDeviceAuth.buildTokenRequestUrl("0123456789abcdef")
        assertTrue(url.startsWith("https://filmixapp.cyou/api/v2/token_request?"))
        assertTrue(url.contains("user_dev_id=0123456789abcdef"))
        assertTrue(url.contains("user_dev_name=go-filmix"))
        assertTrue(url.contains("user_dev_vendor=Xiaomi"))
        assertTrue(url.contains("user_dev_os=11"))
        assertTrue(url.contains("user_dev_apk=2.0.9"))
    }

    @Test
    fun buildsCheckUpdateWithSeparateNonAccountDeviceId() {
        val url = FilmixDeviceAuth.buildCheckUpdateUrl("https://filmixapp.cyou/api/v2")
        assertTrue(url.startsWith("https://filmixapp.cyou/api/v2/check_update?"))
        assertTrue(url.contains("user_dev_id=abcdef0123456789"))
        assertTrue(url.contains("user_dev_name=go-filmix"))
    }

    @Test
    fun offersOnlyExplicitHttpsApiEndpointsAndOfficialActivationPage() {
        assertTrue(FilmixDeviceAuth.apiBaseCandidates().all { it.startsWith("https://") })
        assertEquals(
            listOf(FilmixDeviceAuth.DEFAULT_API_BASE, FilmixDeviceAuth.ALTERNATE_API_BASE),
            FilmixDeviceAuth.apiBaseCandidates()
        )
        assertEquals(
            FilmixDeviceAuth.ALTERNATE_API_BASE,
            FilmixDeviceAuth.normalizeApiBase("https://fxapp.biz/")
        )
        assertTrue(FilmixDeviceAuth.ACTIVATION_URL.startsWith("https://filmix.my/"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsCleartextFilmixApi() {
        FilmixDeviceAuth.normalizeApiBase("http://fxapp.biz/api/v2")
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
    fun alternateApiUsesSameDeviceFingerprintAndV2Routes() {
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

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMalformedDeviceId() {
        FilmixDeviceAuth.buildTokenRequestUrl("bad-id")
    }
}
