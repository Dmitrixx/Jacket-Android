package app.jacket.mobile.data

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder

data class FilmixPendingDevice(
    val code: String,
    val temporaryToken: String,
    val deviceId: String,
    val activationUrl: String = ACTIVATION_URL
)

/**
 * Filmix device-code authorization.
 *
 * The user enters the short-lived code on Filmix's own device page. Jacket only
 * polls the HTTPS token endpoint; it never asks the user for a Filmix password.
 */
object FilmixDeviceAuth {
    const val ACTIVATION_URL = "https://filmix.gg/consoles"
    private const val API_BASE = "https://filmixapp.cyou/api/v2"
    private const val MAX_RESPONSE_BYTES = 256 * 1024

    fun requestCode(
        deviceId: String,
        deviceName: String,
        vendor: String,
        osVersion: String,
        appVersion: String
    ): FilmixPendingDevice {
        require(deviceId.matches(Regex("[a-fA-F0-9]{16}"))) { "Некорректный ID устройства" }
        val response = tokenRequest(deviceId, deviceName, vendor, osVersion, appVersion)
        val code = response.optString("user_code").trim()
        require(code.length in 4..8) { "Filmix не вернул код активации" }
        return FilmixPendingDevice(
            code = code,
            temporaryToken = response.optString("user_dev_token"),
            deviceId = deviceId
        )
    }

    /** Returns the permanent token once Filmix confirms the code, otherwise null. */
    fun pollActivation(
        pending: FilmixPendingDevice,
        deviceName: String,
        vendor: String,
        osVersion: String,
        appVersion: String
    ): String? {
        val response = tokenRequest(pending.deviceId, deviceName, vendor, osVersion, appVersion)
        val token = response.optString("user_dev_token").trim()
        return token.takeIf { it.isNotBlank() && it != pending.temporaryToken }
    }

    internal fun buildTokenRequestUrl(
        deviceId: String,
        deviceName: String,
        vendor: String,
        osVersion: String,
        appVersion: String
    ): String {
        require(deviceId.matches(Regex("[a-fA-F0-9]{16}"))) { "Некорректный ID устройства" }
        val params = linkedMapOf(
            "user_dev_id" to deviceId,
            "user_dev_name" to deviceName,
            "user_dev_vendor" to vendor,
            "user_dev_os" to osVersion,
            "user_dev_apk" to appVersion
        )
        return API_BASE + "/token_request?" + params.entries.joinToString("&") {
            "${encode(it.key)}=${encode(it.value)}"
        }
    }

    private fun tokenRequest(
        deviceId: String,
        deviceName: String,
        vendor: String,
        osVersion: String,
        appVersion: String
    ): JSONObject {
        val url = buildTokenRequestUrl(deviceId, deviceName, vendor, osVersion, appVersion)
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 12_000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Jacket-Android/$appVersion")
        }
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_RESPONSE_BYTES) throw IOException("Ответ Filmix слишком большой")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }.orEmpty()
            if (status !in 200..299) throw IOException("Filmix API вернул HTTP $status")
            return runCatching { JSONObject(body.toString(Charsets.UTF_8)) }
                .getOrElse { throw IOException("Filmix вернул некорректный ответ", it) }
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
}
