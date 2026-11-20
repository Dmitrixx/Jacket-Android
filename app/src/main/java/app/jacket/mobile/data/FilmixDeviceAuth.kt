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
 * The request fingerprint follows the known-compatible Android mobile API
 * profile used by the upstream go-filmix client. All token-bearing requests
 * still require normal HTTPS certificate validation.
 */
object FilmixDeviceAuth {
    const val ACTIVATION_URL = "https://filmix.my/consoles"
    const val DEFAULT_API_BASE = "https://filmixapp.cyou/api/v2"

    // The upstream reference client documents this Android API-compatible
    // fingerprint. The installation's unique device ID remains separate.
    private const val API_DEVICE_NAME = "go-filmix"
    private const val API_DEVICE_VENDOR = "Xiaomi"
    private const val API_DEVICE_OS = "11"
    private const val API_DEVICE_APK = "2.0.9"

    // Keep only the HTTPS API host documented by the reference client.
    // Do not add undocumented mirrors or downgrade to cleartext HTTP.
    private val API_BASE_CANDIDATES = listOf(DEFAULT_API_BASE)
    private const val CHECK_UPDATE_DEVICE_ID = "abcdef0123456789"
    private const val MAX_RESPONSE_BYTES = 256 * 1024
    private const val MAX_PROBE_BYTES = 16 * 1024

    fun requestCode(deviceId: String): FilmixPendingDevice {
        require(deviceId.matches(Regex("[a-fA-F0-9]{16}"))) { "Некорректный ID устройства" }
        val failures = ArrayList<String>(API_BASE_CANDIDATES.size)
        for (apiBase in API_BASE_CANDIDATES) {
            val host = runCatching { URL(apiBase).host }.getOrDefault("Filmix API")
            try {
                probeApiBase(apiBase)
                val response = tokenRequest(apiBase, deviceId)
                val code = response.optString("user_code").trim()
                if (code.length !in 4..8) {
                    throw IOException("token_request не вернул корректный код активации")
                }
                return FilmixPendingDevice(
                    code = code,
                    temporaryToken = response.optString("user_dev_token").trim(),
                    deviceId = deviceId,
                    apiBase = apiBase
                )
            } catch (error: Exception) {
                failures += "$host: ${describeFailure(error)}"
            }
        }
        throw IOException(
            "Filmix API недоступен по HTTPS (${failures.joinToString("; ")}). " +
                "Проверка TLS не отключалась. Проверьте доступность сервиса из вашей сети."
        )
    }

    /** Returns the permanent token once Filmix confirms the code, otherwise null. */
    fun pollActivation(pending: FilmixPendingDevice): String? {
        val response = tokenRequest(pending.apiBase, pending.deviceId)
        val token = response.optString("user_dev_token").trim()
        return token.takeIf { it.isNotBlank() && it != pending.temporaryToken }
    }

    private fun probeApiBase(apiBase: String) {
        val url = buildCheckUpdateUrl(apiBase)
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
            if (status !in 200..299) throw IOException("HTTP $status на /check_update")
            val body = readLimited(connection.inputStream, MAX_PROBE_BYTES).toString(Charsets.UTF_8)
            if (!isRecognizedCheckUpdateResponse(body)) {
                throw IOException("HTTP $status, но ответ не похож на API Filmix")
            }
        } finally {
            connection.disconnect()
        }
    }

    internal fun isRecognizedCheckUpdateResponse(body: String): Boolean =
        body.contains("\"version\"")

    internal fun apiBaseCandidates(): List<String> = API_BASE_CANDIDATES

    internal fun buildCheckUpdateUrl(apiBase: String): String = buildApiUrl(
        apiBase,
        "check_update",
        CHECK_UPDATE_DEVICE_ID
    )

    internal fun buildTokenRequestUrl(deviceId: String): String =
        buildTokenRequestUrl(DEFAULT_API_BASE, deviceId)

    internal fun buildTokenRequestUrl(apiBase: String, deviceId: String): String =
        buildApiUrl(apiBase, "token_request", deviceId)

    private fun buildApiUrl(apiBase: String, endpoint: String, deviceId: String): String {
        require(apiBase.startsWith("https://", true)) { "Filmix API должен использовать HTTPS" }
        require(deviceId.matches(Regex("[a-fA-F0-9]{16}"))) { "Некорректный ID устройства" }
        val params = linkedMapOf(
            "user_dev_id" to deviceId,
            "user_dev_name" to API_DEVICE_NAME,
            "user_dev_vendor" to API_DEVICE_VENDOR,
            "user_dev_os" to API_DEVICE_OS,
            "user_dev_apk" to API_DEVICE_APK
        )
        return apiBase.trimEnd('/') + "/" + endpoint.trimStart('/') + "?" +
            params.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
    }

    private fun tokenRequest(apiBase: String, deviceId: String): JSONObject {
        val url = buildTokenRequestUrl(apiBase, deviceId)
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
            if (status !in 200..299) throw IOException("HTTP $status на /token_request")
            return runCatching { JSONObject(body.toString(Charsets.UTF_8)) }
                .getOrElse { throw IOException("Filmix вернул некорректный JSON", it) }
        } finally {
            connection.disconnect()
        }
    }

    private fun describeFailure(error: Throwable): String {
        val chain = generateSequence(error) { it.cause }.take(8).toList()
        return when {
            chain.any {
                it is javax.net.ssl.SSLHandshakeException ||
                    it is javax.net.ssl.SSLPeerUnverifiedException ||
                    it is javax.net.ssl.SSLException
            } -> "ошибка TLS-сертификата"
            chain.any { it is java.net.UnknownHostException } -> "ошибка DNS"
            chain.any { it is java.net.SocketTimeoutException } -> "тайм-аут"
            else -> error.message
                ?.replace(Regex("[\\r\\n\\t]+"), " ")
                ?.take(120)
                ?.ifBlank { error.javaClass.simpleName }
                ?: error.javaClass.simpleName
        }
    }

    private fun readLimited(input: java.io.InputStream, maxBytes: Int): ByteArray {
        input.use { source ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                if (output.size() + count > maxBytes) throw IOException("Ответ Filmix слишком большой")
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }

    /** Hide raw certificate diagnostics while preserving safe per-host API status details. */
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
        val message = error.message.orEmpty()
        if (message.startsWith("Filmix API недоступен по HTTPS")) {
            return message.replace(Regex("[\\r\\n\\t]+"), " ").take(300)
        }
        return message.replace(Regex("[\\r\\n\\t]+"), " ").take(240)
            .ifBlank { "Не удалось подключить Filmix" }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
}
