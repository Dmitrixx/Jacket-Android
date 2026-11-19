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
        assertTrue(url.startsWith("https://api.filmix.tv/api/v2/token_request?"))
        assertTrue(url.contains("user_dev_id=0123456789abcdef"))
        assertTrue(url.contains("user_dev_name=Jacket%20Phone"))
        assertTrue(url.contains("user_dev_vendor=Xiaomi"))
        assertTrue(url.contains("user_dev_os=Android%2012"))
        assertTrue(url.contains("user_dev_apk=2.4.0"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMalformedDeviceId() {
        FilmixDeviceAuth.buildTokenRequestUrl("bad-id", "Jacket", "Android", "12", "2.4.0")
    }
}
