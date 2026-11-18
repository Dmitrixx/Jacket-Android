package app.jacket.mobile.data

import java.net.URI
import java.net.URLEncoder

/**
 * Safe URL builder for user-configured website search links.
 * Results are opened in the external browser; arbitrary pages are not scraped.
 */
object WebSearchUrlBuilder {
    fun validateTemplate(template: String): String {
        val clean = template.trim()
        require(clean.isNotBlank()) { "Укажите URL поиска сайта" }
        require(clean.length <= MAX_TEMPLATE_LENGTH) { "URL шаблон слишком длинный" }
        require(clean.contains(QUERY_TOKEN)) { "В URL должен быть маркер {query}" }

        val probe = clean.replace(QUERY_TOKEN, "search-query")
        val uri = runCatching { URI(probe) }
            .getOrElse { throw IllegalArgumentException("Некорректный URL поиска", it) }
        require(uri.scheme.equals("https", ignoreCase = true)) {
            "Для сайта поиска требуется HTTPS"
        }
        require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null) {
            "URL должен содержать домен и не должен содержать логин или пароль"
        }
        require(uri.fragment.isNullOrBlank()) { "Уберите фрагмент # из URL шаблона" }
        return clean
    }

    fun build(template: String, query: String): String {
        require(query.isNotBlank()) { "Введите название для поиска" }
        val clean = validateTemplate(template)
        val encoded = URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
            .replace("+", "%20")
        val result = clean.replace(QUERY_TOKEN, encoded)
        require(result.length <= MAX_RESULT_LENGTH) { "Ссылка поиска слишком длинная" }
        val uri = URI(result)
        require(uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() && uri.rawUserInfo == null
        ) { "Некорректная ссылка поиска" }
        return result
    }

    private const val QUERY_TOKEN = "{query}"
    private const val MAX_TEMPLATE_LENGTH = 2_048
    private const val MAX_RESULT_LENGTH = 4_096
}
