package app.jacket.mobile.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder

data class HdRezkaLoginResult(
    val activeMirror: String,
    val cookieHeader: String
)

/**
 * Standard HTTPS sign-in for an HDRezka account.
 *
 * This client does not spoof TLS fingerprints, solve JavaScript challenges,
 * bypass CAPTCHA, or retry through proxy networks. If the site presents an
 * anti-bot challenge, sign-in is reported as unavailable and users may supply
 * their own session cookie obtained through the normal browser login.
 */
object HdRezkaLoginClient {
    private const val DEFAULT_REDIRECT = "https://standby-rezka.tv/"
    private const val MAX_RESPONSE_BYTES = 512 * 1024
    private const val MAX_RESPONSE_PREVIEW_BYTES = 64 * 1024
    private const val MAX_REDIRECTS = 6
    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

    fun validateMirror(value: String): String {
        val clean = value.trim().ifBlank { DEFAULT_REDIRECT }.trimEnd('/') + "/"
        val uri = runCatching { URI(clean) }
            .getOrElse { throw IllegalArgumentException("Некорректный адрес зеркала HDRezka", it) }
        require(uri.scheme.equals("https", true)) { "Зеркало должно использовать HTTPS" }
        require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null) {
            "Укажите HTTPS-домен зеркала без логина и пароля в URL"
        }
        val host = uri.host.lowercase().removePrefix("www.")
        // rezkify.com is a user-reported alternate mirror; keep it as an exact
        // allowlisted host rather than trusting arbitrary redirect domains.
        val isRezkaMirror = host == "rezkify.com" ||
            host.startsWith("hdrezka.") || host.startsWith("hdrezka-") ||
            host.startsWith("rezka.") || host.startsWith("rezka-") ||
            host.startsWith("standby-rezka.")
        require(isRezkaMirror) {
            "Адрес должен относиться к зеркалу HDRezka"
        }
        require(uri.rawQuery.isNullOrBlank() && uri.rawFragment.isNullOrBlank() &&
            (uri.rawPath.isNullOrBlank() || uri.rawPath == "/")
        ) { "Укажите только домен зеркала, без пути и параметров" }
        return clean.trimEnd('/')
    }

    fun login(mirror: String, username: String, password: String): HdRezkaLoginResult {
        require(username.isNotBlank()) { "Введите логин или email HDRezka" }
        require(password.isNotBlank()) { "Введите пароль HDRezka" }

        val initialMirror = validateMirror(mirror)
        val cookies = CookieManager(null, CookiePolicy.ACCEPT_ALL)
        // The landing page is only needed for redirects and Set-Cookie headers.
        // Avoid buffering its often-large HTML during login.
        val bootstrap = requestFollowingGetRedirects("$initialMirror/", cookies, captureBody = false)
        val activeBase = validateMirror(originOf(bootstrap.finalUrl))

        val form = listOf(
            "login_name" to username,
            "login_password" to password,
            "login_not_save" to "0",
            "login" to "submit"
        ).joinToString("&") { "${encode(it.first)}=${encode(it.second)}" }

        val response = requestFollowingLoginRedirects(
            startUrl = "$activeBase/ajax/login/?t=${System.currentTimeMillis()}",
            body = form,
            cookies = cookies,
            referer = "$activeBase/"
        )
        val confirmedBase = validateMirror(originOf(response.finalUrl))
        if (response.status == 403 || containsChallenge(response.body)) {
            throw IOException(
                "HDRezka показала защитную проверку. Jacket не обходит CAPTCHA или Cloudflare; " +
                    "войдите через обычный браузер и вставьте свою Cookie-сессию."
            )
        }
        if (response.status !in 200..299) {
            throw IOException("HDRezka вернула HTTP ${response.status}")
        }

        val cookieHeader = cookies.get(URI("$confirmedBase/"), emptyMap())
            .entries.firstOrNull { it.key.equals("Cookie", true) }
            ?.value.orEmpty().joinToString("; ")
        val cookieValues = cookieHeader.split(';').mapNotNull { part ->
            val pair = part.trim().split('=', limit = 2)
            pair.takeIf { it.size == 2 }?.let { it[0].lowercase() to it[1] }
        }.toMap()
        val userId = cookieValues["dle_user_id"].orEmpty()
        val sessionPassword = cookieValues["dle_password"].orEmpty()
        if (userId.isBlank() || userId == "0" || sessionPassword.isBlank()) {
            throw IOException("HDRezka не подтвердила вход. Проверьте логин и пароль.")
        }
        return HdRezkaLoginResult(confirmedBase, cookieHeader)
    }

    /** Checks a mirror without sending account credentials. */
    fun probeMirror(mirror: String): String {
        val initialMirror = validateMirror(mirror)
        val cookies = CookieManager(null, CookiePolicy.ACCEPT_ALL)
        val response = requestFollowingGetRedirects(
            "$initialMirror/",
            cookies,
            captureBody = true,
            previewOnly = true
        )
        if (containsChallenge(response.body)) {
            throw IOException("Зеркало доступно, но требует браузерную проверку CAPTCHA/Cloudflare.")
        }
        return validateMirror(originOf(response.finalUrl))
    }

    /** Converts network failures into useful diagnostics without exposing cookies or headers. */
    fun userFriendlyError(error: Throwable): String {
        val chain = generateSequence(error) { it.cause }.take(8).toList()
        return when {
            chain.any { it is javax.net.ssl.SSLException } ->
                "Ошибка HTTPS-сертификата HDRezka. Попробуйте другое актуальное зеркало."
            chain.any { it is java.net.UnknownHostException } ->
                "Не удалось найти домен HDRezka. Проверьте зеркало и DNS/доступ к сайту."
            chain.any { it is java.net.SocketTimeoutException } ->
                "HDRezka не ответила вовремя. Зеркало перегружено или недоступно из вашей сети."
            chain.any { it is java.net.ConnectException } ->
                "Не удалось подключиться к HDRezka. Проверьте доступность зеркала или сеть."
            else -> error.message?.replace(Regex("[\\r\\n\\t]+"), " ")?.take(240)
                ?.ifBlank { "Не удалось подключиться к HDRezka." } ?: "Не удалось подключиться к HDRezka."
        }
    }

    internal fun loginRedirectMethod(status: Int): String? = when (status) {
        301, 302, 303 -> "GET"
        307, 308 -> null
        else -> ""
    }

    private data class HttpResult(
        val status: Int,
        val finalUrl: String,
        val body: String,
        val location: String?
    )

    private fun requestFollowingLoginRedirects(
        startUrl: String,
        body: String,
        cookies: CookieManager,
        referer: String,
        captureBody: Boolean = true,
        previewOnly: Boolean = false
    ): HttpResult {
        var currentUrl = startUrl
        var method = "POST"
        var currentBody: String? = body
        var currentReferer = referer
        repeat(MAX_REDIRECTS + 1) {
            val response = request(currentUrl, method, currentBody, cookies, currentReferer)
            if (response.status !in REDIRECT_CODES) return response

            val location = response.location
                ?: throw IOException("HDRezka перенаправила вход без адреса (HTTP ${response.status})")
            val nextUrl = resolveRedirect(currentUrl, location)
            val nextMethod = loginRedirectMethod(response.status)
                ?: method.takeIf { sameOrigin(currentUrl, nextUrl) }
                ?: throw IOException("HDRezka перенаправила вход на другой домен; повторная отправка пароля заблокирована.")
            if (nextMethod.isEmpty()) {
                throw IOException("HDRezka вернула неожиданный код перенаправления")
            }
            if (nextMethod == "GET") currentBody = null
            currentUrl = nextUrl
            method = nextMethod
            currentReferer = originOf(currentUrl) + "/"
        }
        throw IOException("Слишком много перенаправлений при входе в HDRezka")
    }

    private fun sameOrigin(first: String, second: String): Boolean = runCatching {
        val a = URI(first)
        val b = URI(second)
        a.scheme.equals(b.scheme, true) &&
            a.host.equals(b.host, true) &&
            effectivePort(a) == effectivePort(b)
    }.getOrDefault(false)

    private fun effectivePort(uri: URI): Int =
        if (uri.port >= 0) uri.port else 443

    internal fun containsBrowserChallenge(body: String): Boolean =
        body.contains("cf-chl", true) ||
            body.contains("Just a moment", true) ||
            body.contains("challenge-platform", true) ||
            (body.contains("anubis", true) &&
                (body.contains("Доступ запрещён", true) || body.contains("Access denied", true)))

    private fun containsChallenge(body: String): Boolean = containsBrowserChallenge(body)

    private fun requestFollowingGetRedirects(
        startUrl: String,
        cookies: CookieManager,
        captureBody: Boolean = true,
        previewOnly: Boolean = false
    ): HttpResult {
        var currentUrl = startUrl
        repeat(MAX_REDIRECTS + 1) {
            val response = request(
                currentUrl, "GET", null, cookies, currentUrl,
                captureBody = captureBody, previewOnly = previewOnly
            )
            if (response.status in setOf(301, 302, 303, 307, 308)) {
                val location = response.location
                    ?: throw IOException("HDRezka вернула перенаправление без адреса")
                currentUrl = resolveRedirect(currentUrl, location)
            } else {
                if (response.status == 403 || containsChallenge(response.body)) {
                    throw IOException("HDRezka требует браузерную проверку. Автоматический обход не выполняется.")
                }
                if (response.status !in 200..299) {
                    throw IOException("Не удалось определить рабочее зеркало HDRezka: HTTP ${response.status}")
                }
                return response
            }
        }
        throw IOException("Слишком много перенаправлений при поиске зеркала HDRezka")
    }

    private fun request(
        url: String,
        method: String,
        body: String?,
        cookies: CookieManager,
        referer: String
    ): HttpResult {
        val uri = URI(url)
        validateMirror(originOf(url))
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = 15_000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json, text/plain, */*")
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")
            setRequestProperty("User-Agent", "Jacket-Android/2.6")
            setRequestProperty("Referer", referer)
            setRequestProperty("X-Requested-With", "XMLHttpRequest")
            setRequestProperty("Origin", originOf(url))
            cookies.get(uri, emptyMap()).forEach { (name, values) ->
                if (name.equals("Cookie", true)) setRequestProperty(name, values.joinToString("; "))
            }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            }
        }
        try {
            if (body != null) {
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val responseHeaders = connection.headerFields.entries
                .filter { it.key != null }
                .associate { it.key!! to it.value.orEmpty() }
            cookies.put(uri, responseHeaders)
            // Redirect and mirror-bootstrap bodies are not needed to resolve the
            // active host. Skipping them avoids buffering the full HDRezka homepage.
            val shouldReadBody = captureBody && status !in REDIRECT_CODES
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = if (!shouldReadBody) {
                ByteArray(0)
            } else {
                stream?.use { input ->
                    if (previewOnly) {
                        readResponsePreview(input, MAX_RESPONSE_PREVIEW_BYTES)
                    } else {
                        readResponseBody(input, MAX_RESPONSE_BYTES)
                    }
                } ?: ByteArray(0)
            }
            return HttpResult(
                status = status,
                finalUrl = connection.url.toString(),
                body = bytes.toString(Charsets.UTF_8),
                location = connection.getHeaderField("Location")
            )
        } finally {
            connection.disconnect()
        }
    }

    internal fun readResponsePreview(input: java.io.InputStream, maxBytes: Int): ByteArray {
        require(maxBytes > 0)
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        while (output.size() < maxBytes) {
            val count = input.read(buffer, 0, minOf(buffer.size, maxBytes - output.size()))
            if (count < 0) break
            if (count > 0) output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun readResponseBody(input: java.io.InputStream, maxBytes: Int): ByteArray {
        input.use { source ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                if (output.size() + count > maxBytes) throw IOException("Ответ HDRezka слишком большой")
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }

    private fun resolveRedirect(baseUrl: String, location: String): String {
        val resolved = URL(URL(baseUrl), location).toString()
        validateMirror(originOf(resolved))
        return resolved
    }

    private fun originOf(value: String): String {
        val uri = URI(value)
        return "${uri.scheme}://${uri.rawAuthority}"
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
}
