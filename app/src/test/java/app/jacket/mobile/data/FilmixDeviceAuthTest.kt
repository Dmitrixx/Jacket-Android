package app.jacket.mobile.data

import org.junit.Assert.assertTrue
import org.junit.Test

class FilmixDeviceAuthTest {
    @Test
    fun buildsHttpsTokenRequestWithEncodedDeviceMetadata() {
        val url = FilmixDeviceAuth.buildTokenRequestUrl(
            deviceId = "0123456789abcdef",
            deviceName = "Jacket Phone",
            vendor = "Xiaomi",
            osVersion = "Android 12",
            appVersion = "2.4.0"
        )
        assertTrue(url.startsWith("https://filmixapp.cyou/api/v2/token_request?"))
        assertTrue(url.contains("user_dev_id=0123456789abcdef"))
        assertTrue(url.contains("user_dev_name=Jacket%20Phone"))
        assertTrue(url.contains("user_dev_vendor=Xiaomi"))
        assertTrue(url.contains("user_dev_os=Android%2012"))
        assertTrue(url.contains("user_dev_apk=2.4.0"))
    }

    @Test
    fun tlsCertificateFailureIsShownAsActionableMessage() {
        val error = javax.net.ssl.SSLPeerUnverifiedException(
            "Hostname filmixapp.cyou not verified: certificate subject api.filmix.tv"
        )
        assertTrue(FilmixDeviceAuth.userFriendlyError(error).contains("HTTPS-сертификата"))
    }

    @Test
    fun buildsCheckUpdateUrlWithTheSameDeviceFingerprint() {
        val url = FilmixDeviceAuth.buildCheckUpdateUrl(
            "https://filmixapp.cyou/api/v2",
            "Jacket Phone",
            "Xiaomi",
            "Android 12",
            "2.4.0"
        )
        assertTrue(url.startsWith("https://filmixapp.cyou/api/v2/check_update?"))
        assertTrue(url.contains("user_dev_id=abcdef0123456789"))
        assertTrue(url.contains("user_dev_name=Jacket%20Phone"))
    }

    @Test
    fun recognizesFilmixUpdateResponses() {
        assertTrue(FilmixDeviceAuth.isRecognizedCheckUpdateResponse("""{"version":"2.6.0"}"""))
    }

    @Test
    fun onlyUsesKnownHttpsApiCandidates() {
        assertTrue(FilmixDeviceAuth.apiBaseCandidates().all { it.startsWith("https://") })
        assertTrue(FilmixDeviceAuth.apiBaseCandidates().contains("https://filmixapp.cyou/api/v2"))
        assertTrue(FilmixDeviceAuth.apiBaseCandidates().contains("https://api.filmix.tv/api/v2"))
        assertTrue(FilmixDeviceAuth.apiBaseCandidates().none { it.contains("fxapp.biz") })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMalformedDeviceId() {
        FilmixDeviceAuth.buildTokenRequestUrl("bad-id", "Jacket", "Android", "12", "2.4.0")
    }
}
