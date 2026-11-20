package app.jacket.mobile.data

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class FilmixPendingDevice(
    val code: String,
    val temporaryToken: String,
    val deviceId: String,
    val activationUrl: String = FilmixDeviceAuth.ACTIVATION_URL,
    val apiBase: String = FilmixDeviceAuth.DEFAULT_API_BASE
)

/**
 * Filmix device-code authorization.
 *
 * API hosts are discovered over HTTPS using the documented check_update
 * response before requesting a one-time code. TLS validation remains enabled.
 */
object FilmixDeviceAuth {
    const val ACTIVATION_URL = "https://filmix.gg/consoles"
    const val DEFAULT_API_BASE = "https://filmixapp.cyou/api/v2"
    private val API_BASE_CANDIDATES = listOf(
        DEFAULT_API_BASE,
        "https://api.filmix.tv/api/v2",
        "https://fxapp.biz/api/v2"
    )
    private const val MAX_RESPONSE_BYTES = 256 * 1024
    private const val MAX_PROBE_BYTES = 16 * 1024

    fun requestCode(
        deviceId: String,
        deviceName: String,
        vendor: String,
        osVersion: String,
        appVersion: String
    ): FilmixPendingDevice {
        require(deviceId.matches(Regex("[a-fA-F0-9]{16}"))) { "Некорректный ID устройства" }
        var lastError: IOException? = null
        for (apiBase in API_BASE_CANDIDATES) {
            try {
                if (!probeApiBase(apiBase, deviceId, deviceName, vendor, osVersion, appVersion)) {
                    lastError = IOException("Сервер не подтвердил API Filmix: $apiBase")
                    continue
                }
                val response = tokenRequest(apiBase, deviceId, deviceName, vendor, osVersion, appVersion)
                val code = response.optString("user_code").trim()
                require(code.length in 4..8) { "Filmix не вернул код активации" }
                return FilmixPendingDevice(
                    code = code,
                    temporaryToken = response.optString("user_dev_token").trim(),
                    deviceId = deviceId,
                    apiBase = apiBase
                )
            } catch (error: Exception) {
                lastError = if (error is IOException) error else IOException(
                    "Ошибка API Filmix (${error.javaClass.simpleName})", error
                )
            }
        }
        throw IOException(
            "Не удалось подключить Filmix: все проверенные HTTPS API отклонили проверку или запрос кода. " +
                "Сертификаты TLS не отключались.",
            lastError
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
        val response = tokenRequest(
            pending.apiBase,
            pending.deviceId,
            deviceName,
            vendor,
            osVersion,
            appVersion
        )
        val token = response.optString("user_dev_token").trim()
        return token.takeIf { it.isNotBlank() && it != pending.temporaryToken }
    }

    private fun probeApiBase(
        apiBase: String,
        deviceId: String,
        deviceName: String,
        vendor: String,
        osVersion: String,
        appVersion: String
    ): Boolean {
        val url = buildApiUrl(
            apiBase, "check_update", deviceId, deviceName, vendor, osVersion, appVersion
        )
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 5_000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "okhttp/3.10.0")
        }
        try {
            val status = connection.responseCode
            if (status == 405) throw IOException("Filmix API вернул HTTP 405 на /check_update")
            if (status !in 200..299) return false
            val body = readLimited(connection.inputStream, MAX_PROBE_BYTES)
                .toString(Charsets.UTF_8)
            return isRecognizedCheckUpdateResponse(body)
        } finally {
            connection.disconnect()
        }
    }

    internal fun isRecognizedCheckUpdateResponse(body: String): Boolean =
        body.contains("\"version\"")

    internal fun apiBaseCandidates(): List<String> = API_BASE_CANDIDATES

    internal fun buildCheckUpdateUrl(
        apiBase: String,
        deviceId: String,
        deviceName: String,
        vendor: String,
        osVersion: String,
        appVersion: String
    ): String = buildApiUrl(
        apiBase, "check_update", deviceId, deviceName, vendor, osVersion, appVersion
    )

    internal fun buildTokenRequestUrl(
        deviceId: String,
        deviceName: String,
        vendor: String,
        osVersion: String,
        appVersion: String
    ): String = buildTokenRequestUrl(
        DEFAULT_API_BASE, deviceId, deviceName, vendor, osVersion, appVersion
    )

    internal fun buildTokenRequestUrl(
        apiBase: String,
        deviceId: String,
        deviceName: String,
        vendor: String,
        osVersion: String,
        appVersion: String
    ): String = buildApiUrl(
        apiBase, "token_request", deviceId, deviceName, vendor, osVersion, appVersion
    )

    private fun buildApiUrl(
        apiBase: String,
        endpoint: String,
        deviceId: String,
        deviceName: String,
        vendor: String,
        osVersion: String,
        appVersion: String
    ): String {
        require(apiBase.startsWith("https://", true)) { "Filmix API должен использовать HTTPS" }
        require(deviceId.matches(Regex("[a-fA-F0-9]{16}"))) { "Некорректный ID устройства" }
        val params = linkedMapOf(
            "user_dev_id" to deviceId,
            "user_dev_name" to deviceName,
            "user_dev_vendor" to vendor,
            "user_dev_os" to osVersion,
            "user_dev_apk" to appVersion
        )
        return apiBase.trimEnd('/') + "/" + endpoint.trimStart('/') + "?" +
            params.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
    }

    private fun tokenRequest(
        apiBase: String,
        deviceId: String,
        deviceName: String,
        vendor: String,
        osVersion: String,
        appVersion: String
    ): JSONObject {
        val url = buildTokenRequestUrl(
            apiBase, deviceId, deviceName, vendor, osVersion, appVersion
        )
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 12_000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "okhttp/3.10.0")
        }
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { readLimited(it, MAX_RESPONSE_BYTES) } ?: ByteArray(0)
            if (status !in 200..299) {
                throw IOException("Filmix API вернул HTTP $status")
            }
            return runCatching { JSONObject(body.toString(Charsets.UTF_8)) }
                .getOrElse { throw IOException("Filmix вернул некорректный ответ", it) }
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimited(input: java.io.InputStream, maxBytes: Int): ByteArray {
        input.use { source ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            var total = 0
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                total += count
                if (total > maxBytes) throw IOException("Ответ Filmix слишком большой")
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }

    /** Hide raw certificate diagnostics while keeping HTTPS validation strict. */
    fun userFriendlyError(error: Throwable): String {
        val chain = generateSequence(error) { it.cause }.take(8).toList()
        if (chain.any {
                it is javax.net.ssl.SSLHandshakeException ||
                    it is javax.net.ssl.SSLPeerUnverifiedException ||
                    it is javax.net.ssl.SSLException
            } || chain.any {
                it.message.orEmpty().contains("not verified", ignoreCase = true) ||
                    it.message.orEmpty().contains("certificate", ignoreCase = true)
            }
        ) {
            return "Filmix API: ошибка проверки HTTPS-сертификата. Проверьте адрес API или попробуйте позже."
        }
        if (chain.any { it.message.orEmpty().contains("HTTP 405", ignoreCase = true) }) {
            return "Filmix API отклонил запрос (HTTP 405). Не удалось подобрать совместимый HTTPS-адрес API."
        }
        return error.message?.replace(Regex("[\\r\\n\\t]+"), " ")?.take(240)
            ?.ifBlank { "Не удалось подключить Filmix" } ?: "Не удалось подключить Filmix"
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
}
