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
    private const val MAX_RESPONSE_BYTES = 128 * 1024
    private const val MAX_REDIRECTS = 6

    fun validateMirror(value: String): String {
        val clean = value.trim().ifBlank { DEFAULT_REDIRECT }.trimEnd('/') + "/"
        val uri = runCatching { URI(clean) }
            .getOrElse { throw IllegalArgumentException("Некорректный адрес зеркала HDRezka", it) }
        require(uri.scheme.equals("https", true)) { "Зеркало должно использовать HTTPS" }
        require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null) {
            "Укажите HTTPS-домен зеркала без логина и пароля в URL"
        }
        val host = uri.host.lowercase().removePrefix("www.")
        val isRezkaMirror = host.startsWith("hdrezka.") || host.startsWith("hdrezka-") ||
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
        val bootstrap = requestFollowingGetRedirects("$initialMirror/", cookies)
        val activeBase = validateMirror(originOf(bootstrap.finalUrl))

        val form = listOf(
            "login_name" to username,
            "login_password" to password,
            "login_not_save" to "0",
            "login" to "submit"
        ).joinToString("&") { "${encode(it.first)}=${encode(it.second)}" }

        val response = request(
            url = "$activeBase/ajax/login/",
            method = "POST",
            body = form,
            cookies = cookies,
            referer = "$activeBase/"
        )
        if (response.status == 403 || response.body.contains("cf-chl", true) ||
            response.body.contains("Just a moment", true)
        ) {
            throw IOException(
                "HDRezka показала защитную проверку. Jacket не обходит CAPTCHA или Cloudflare; " +
                    "войдите через обычный браузер и вставьте свою Cookie-сессию."
            )
        }
        if (response.status !in 200..299) {
            throw IOException("HDRezka вернула HTTP ${response.status}")
        }

        val cookieHeader = cookies.get(URI("$activeBase/"), emptyMap())
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
        return HdRezkaLoginResult(activeBase, cookieHeader)
    }

    private data class HttpResult(
        val status: Int,
        val finalUrl: String,
        val body: String,
        val location: String?
    )

    private fun requestFollowingGetRedirects(startUrl: String, cookies: CookieManager): HttpResult {
        var currentUrl = startUrl
        repeat(MAX_REDIRECTS + 1) {
            val response = request(currentUrl, "GET", null, cookies, currentUrl)
            if (response.status in setOf(301, 302, 303, 307, 308)) {
                val location = response.location
                    ?: throw IOException("HDRezka вернула перенаправление без адреса")
                currentUrl = resolveRedirect(currentUrl, location)
            } else {
                if (response.status == 403 ||
                    response.body.contains("cf-chl", true) ||
                    response.body.contains("Just a moment", true)
                ) {
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
            setRequestProperty("User-Agent", "Jacket-Android/2.4")
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
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var size = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    size += count
                    if (size > MAX_RESPONSE_BYTES) throw IOException("Ответ HDRezka слишком большой")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: ByteArray(0)
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
