package app.jacket.mobile.data

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.Base64

data class FilmixPendingDevice(
    val code: String,
    val temporaryToken: String,
    val deviceId: String,
    val activationUrl: String = FilmixDeviceAuth.ACTIVATION_URL,
    val apiBase: String = FilmixDeviceAuth.DEFAULT_API_BASE
)

/**
 * Filmix device-code authorization and API transport.
 *
 * The device fingerprint and two-step activation flow match the current Lampa
 * Android Filmix client. The legacy HTTP API host is accessed only through the
 * configured HTTPS CORS proxy, never through a direct cleartext connection.
 */
object FilmixDeviceAuth {
    const val ACTIVATION_URL = "https://filmix.gg/consoles"
    const val SITE_BASE = "https://filmix.gg"
    const val DEFAULT_API_BASE = "https://filmixapp.cyou/api/v2"
    const val ALTERNATE_API_BASE = "https://fxapp.biz/api/v2"
    const val AUTO_API_BASE = "auto"
    const val DEFAULT_CORS_PROXY_BASE = "https://cors.jacred.stream"
    const val API_USER_AGENT = "okhttp/3.10.0"

    private const val API_DEVICE_NAME = "Xiaomi"
    private const val API_DEVICE_VENDOR = "Xiaomi"
    private const val API_DEVICE_OS = "14"
    private const val API_DEVICE_APK = "2.2.0"
    private const val API_LANGUAGE = "ru-rRU"
    private const val CHECK_UPDATE_DEVICE_ID = "abcdef0123456789"
    private const val MAX_RESPONSE_BYTES = 256 * 1024
    private const val MAX_PROBE_BYTES = 16 * 1024
    private val API_BASE_CANDIDATES = listOf(DEFAULT_API_BASE, ALTERNATE_API_BASE)

    fun requestCode(
        deviceId: String,
        apiBase: String = DEFAULT_API_BASE,
        customApiBase: String = "",
        corsProxyBase: String = DEFAULT_CORS_PROXY_BASE
    ): FilmixPendingDevice {
        require(deviceId.matches(Regex("[a-fA-F0-9]{16}"))) { "Некорректный ID устройства" }
        val proxy = normalizeCorsProxyBase(corsProxyBase)
        val (selectedBase, response) = firstSuccessfulApiBase(apiBase, customApiBase) { candidate ->
            probeApiBase(candidate, proxy)
            val tokenResponse = tokenRequest(candidate, deviceId, proxy)
            val code = tokenResponse.optString("user_code").trim()
            val temporaryToken = tokenResponse.optString("code").trim()
                .ifBlank { tokenResponse.optString("user_dev_token").trim() }
            val status = tokenResponse.optString("status").trim()
            if (code.length !in 4..8 || temporaryToken.isBlank() ||
                (status.isNotBlank() && !status.equals("ok", ignoreCase = true))
            ) {
                throw IOException("Filmix не вернул корректный код привязки устройства")
            }
            tokenResponse
        }
        return FilmixPendingDevice(
            code = response.optString("user_code").trim(),
            // Lampa-compatible token_request returns the temporary token in the "code" field.
            temporaryToken = response.optString("code").trim()
                .ifBlank { response.optString("user_dev_token").trim() },
            deviceId = deviceId,
            apiBase = selectedBase
        )
    }

    /**
     * Filmix activation is confirmed by user_profile, not by repeatedly asking
     * token_request for a different token. Once user_data appears, the original
     * temporary token is the device token that must be persisted.
     */
    fun pollActivation(
        pending: FilmixPendingDevice,
        corsProxyBase: String = DEFAULT_CORS_PROXY_BASE
    ): String? {
        val base = normalizeApiBase(pending.apiBase, allowCustomHost = true)
        val proxy = normalizeCorsProxyBase(corsProxyBase)
        val profileUrl = buildAuthenticatedApiUrl(
            apiBase = base,
            endpoint = "user_profile",
            deviceId = pending.deviceId,
            token = pending.temporaryToken
        )
        val profile = getJson(profileUrl, proxy)
        val userData = profile.optJSONObject("user_data") ?: return null
        return pending.temporaryToken.takeIf { userData.length() > 0 }
    }

    private fun probeApiBase(apiBase: String, corsProxyBase: String) {
        val connection = openApiConnection(buildCheckUpdateUrl(apiBase), corsProxyBase, 5_000, 5_000)
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

    internal fun apiBaseCandidates(selection: String, customApiBase: String = ""): List<String> {
        val normalized = normalizeApiSelection(selection)
        if (normalized != AUTO_API_BASE) return listOf(normalized)
        val custom = customApiBase.trim().takeIf(String::isNotBlank)
            ?.let { normalizeApiBase(it, allowCustomHost = true) }
        return (listOfNotNull(custom) + API_BASE_CANDIDATES).distinct()
    }

    internal fun <T> firstSuccessfulApiBase(
        selection: String,
        customApiBase: String = "",
        attempt: (String) -> T
    ): Pair<String, T> {
        val candidates = apiBaseCandidates(selection, customApiBase)
        var lastError: Exception? = null
        candidates.forEach { candidate ->
            try {
                return candidate to attempt(candidate)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                lastError = error
            }
        }
        val hosts = candidates.joinToString(", ") { URI(it).host }
        val reason = lastError?.let { describeFailure(it) } ?: "нет ответа"
        throw IOException("Не удалось подключиться к API Filmix ($hosts). Проверьте соединение: $reason", lastError)
    }

    fun normalizeApiSelection(value: String): String {
        if (value.trim().equals(AUTO_API_BASE, ignoreCase = true)) return AUTO_API_BASE
        return normalizeApiBase(value, allowCustomHost = true)
    }

    fun normalizeApiBase(value: String, allowCustomHost: Boolean = false): String {
        val uri = runCatching { URI(value.trim()) }
            .getOrElse { throw IllegalArgumentException("Некорректный адрес API Filmix", it) }
        val host = uri.host?.lowercase()
            ?: throw IllegalArgumentException("В адресе API Filmix отсутствует домен")
        require(uri.scheme.equals("https", true)) { "API Filmix должен использовать HTTPS" }
        require(uri.port == -1 || uri.port == 443) { "Для API Filmix разрешён только стандартный HTTPS-порт" }
        require(uri.rawUserInfo == null && uri.rawQuery.isNullOrBlank() && uri.rawFragment.isNullOrBlank()) {
            "Адрес API Filmix не должен содержать логин, пароль, query или fragment"
        }
        if (host !in setOf("filmixapp.cyou", "fxapp.biz")) {
            require(allowCustomHost && isPublicDnsHost(host)) {
                "Для пользовательского API нужен публичный HTTPS-домен, не локальный адрес или IP"
            }
        }
        require(uri.rawPath.isNullOrBlank() || uri.rawPath == "/" || uri.rawPath.trimEnd('/') == "/api/v2") {
            "Путь API Filmix должен быть /api/v2"
        }
        return "https://$host/api/v2"
    }

    fun normalizeCorsProxyBase(value: String): String {
        if (value.isBlank()) return ""
        val uri = runCatching { URI(value.trim()) }
            .getOrElse { throw IllegalArgumentException("Некорректный адрес CORS-прокси Filmix", it) }
        val host = uri.host?.lowercase()
            ?: throw IllegalArgumentException("В адресе CORS-прокси отсутствует домен")
        require(uri.scheme.equals("https", true)) { "CORS-прокси Filmix должен использовать HTTPS" }
        require(uri.port == -1 || uri.port == 443) { "Для CORS-прокси разрешён только HTTPS-порт 443" }
        require(uri.rawUserInfo == null && uri.rawQuery.isNullOrBlank() && uri.rawFragment.isNullOrBlank()) {
            "Адрес CORS-прокси не должен содержать логин, пароль, query или fragment"
        }
        require(uri.rawPath.isNullOrBlank() || uri.rawPath == "/") {
            "CORS-прокси должен указывать на корень сервера"
        }
        require(isPublicDnsHost(host)) { "CORS-прокси должен использовать публичный DNS-домен" }
        return "https://$host"
    }

    internal fun buildCheckUpdateUrl(apiBase: String): String =
        buildApiUrl(apiBase, "check_update", CHECK_UPDATE_DEVICE_ID)

    internal fun buildTokenRequestUrl(deviceId: String): String =
        buildTokenRequestUrl(DEFAULT_API_BASE, deviceId)

    internal fun buildTokenRequestUrl(apiBase: String, deviceId: String): String =
        buildApiUrl(apiBase, "token_request", deviceId)

    internal fun buildAuthenticatedApiUrl(
        apiBase: String,
        endpoint: String,
        deviceId: String,
        token: String,
        extraQuery: Map<String, String> = emptyMap()
    ): String {
        require(token.isNotBlank() && token.length <= 4096) { "Токен Filmix отсутствует или имеет неверный размер" }
        val base = normalizeApiBase(apiBase, allowCustomHost = true)
        require(endpoint.matches(Regex("[a-zA-Z0-9_/-]{1,80}")) && !endpoint.contains("..")) {
            "Некорректный endpoint Filmix"
        }
        val params = deviceParameters(deviceId).toMutableMap()
        params["user_dev_token"] = token
        extraQuery.forEach { (key, value) ->
            require(key.matches(Regex("[a-zA-Z0-9_]{1,40}"))) { "Некорректный параметр Filmix" }
            require(key !in params) { "Переопределение параметров авторизации запрещено" }
            params[key] = value.take(300)
        }
        return buildUrl(base, endpoint, params)
    }

    private fun buildApiUrl(apiBase: String, endpoint: String, deviceId: String): String {
        val base = normalizeApiBase(apiBase, allowCustomHost = true)
        return buildUrl(base, endpoint, deviceParameters(deviceId))
    }

    private fun deviceParameters(deviceId: String): LinkedHashMap<String, String> {
        require(deviceId.matches(Regex("[a-fA-F0-9]{16}"))) { "Некорректный ID устройства" }
        return linkedMapOf(
            "user_dev_id" to deviceId,
            "user_dev_name" to API_DEVICE_NAME,
            "user_dev_vendor" to API_DEVICE_VENDOR,
            "user_dev_os" to API_DEVICE_OS,
            "user_dev_apk" to API_DEVICE_APK,
            "app_lang" to API_LANGUAGE
        )
    }

    private fun buildUrl(base: String, endpoint: String, params: Map<String, String>): String =
        base + "/" + endpoint.trimStart('/') + "?" +
            params.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }

    /** Lampa-compatible enc2t route with an upstream User-Agent override. */
    internal fun buildCorsProxyUrl(targetUrl: String, proxyBase: String = DEFAULT_CORS_PROXY_BASE): String {
        val proxy = normalizeCorsProxyBase(proxyBase)
        require(proxy.isNotBlank()) { "CORS-прокси Filmix не задан" }
        val target = URI(targetUrl)
        require(target.scheme.equals("https", true) && !target.host.isNullOrBlank() &&
            target.rawUserInfo == null && target.rawFragment.isNullOrBlank()
        ) { "Некорректный HTTPS URL Filmix для прокси" }
        val host = target.host.lowercase()
        require(host == "filmixapp.cyou" || host == "fxapp.biz" || isPublicDnsHost(host)) {
            "Недопустимый домен API Filmix для прокси"
        }
        // The current Lampa API host is HTTP; it is carried only inside the HTTPS proxy payload.
        val proxiedTarget = if (host == "filmixapp.cyou") {
            "http://" + target.rawAuthority + target.rawPath +
                (target.rawQuery?.let { "?$it" } ?: "")
        } else targetUrl
        val prefix = "param/User-Agent=${encode(API_USER_AGENT)}/"
        val payload = Base64.getEncoder().encodeToString((prefix + proxiedTarget).toByteArray(Charsets.UTF_8))
        val endpointName = target.rawPath.substringAfterLast('/').ifBlank { "api" }
        return "$proxy/enc2t/${encode(payload)}/$endpointName?jacred.test"
    }

    private fun isPublicDnsHost(host: String): Boolean =
        host.contains('.') &&
            !host.matches(Regex("""\d{1,3}(?:\.\d{1,3}){3}""")) &&
            !host.endsWith(".local") && !host.endsWith(".lan") &&
            !host.endsWith(".internal") && !host.endsWith(".localhost") &&
            host.matches(Regex("""(?i)(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}"""))

    private fun openApiConnection(
        targetUrl: String,
        corsProxyBase: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int
    ): HttpURLConnection {
        val requestUrl = if (corsProxyBase.isBlank()) targetUrl
        else buildCorsProxyUrl(targetUrl, corsProxyBase)
        return (URL(requestUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", API_USER_AGENT)
        }
    }

    private fun tokenRequest(apiBase: String, deviceId: String, corsProxyBase: String): JSONObject {
        val connection = openApiConnection(buildTokenRequestUrl(apiBase, deviceId), corsProxyBase, 12_000, 12_000)
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

    private fun getJson(targetUrl: String, corsProxyBase: String): JSONObject {
        val connection = openApiConnection(targetUrl, corsProxyBase, 8_000, 15_000)
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { readLimited(it, MAX_RESPONSE_BYTES) } ?: ByteArray(0)
            if (status !in 200..299) throw IOException("Filmix API: HTTP $status")
            return runCatching { JSONObject(body.toString(Charsets.UTF_8)) }
                .getOrElse { throw IOException("Filmix вернул некорректный JSON", it) }
        } finally {
            connection.disconnect()
        }
    }

    private fun describeFailure(error: Throwable): String {
        val chain = generateSequence(error) { it.cause }.take(8).toList()
        return when {
            chain.any { it is javax.net.ssl.SSLPeerUnverifiedException } ->
                "TLS: сертификат не соответствует имени домена"
            chain.any { it is java.security.cert.CertificateExpiredException } ->
                "TLS: срок действия сертификата истёк"
            chain.any {
                it is java.security.cert.CertificateException ||
                    it is java.security.cert.CertPathValidatorException
            } -> "TLS: цепочка сертификатов не доверена или неполная"
            chain.any { it is javax.net.ssl.SSLException } ->
                "TLS: ошибка рукопожатия или сертификата"
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

    fun userFriendlyError(error: Throwable): String {
        val message = error.message.orEmpty()
        if (message.startsWith("Filmix API недоступен по HTTPS")) {
            return message.replace(Regex("[\\r\\n\\t]+"), " ").take(320)
        }
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
        return message.replace(Regex("[\\r\\n\\t]+"), " ").take(240)
            .ifBlank { "Не удалось подключить Filmix" }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
}
