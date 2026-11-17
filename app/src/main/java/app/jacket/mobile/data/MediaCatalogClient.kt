package app.jacket.mobile.data

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import android.util.Xml
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app catalog and indexer clients.
 *
 * The application talks directly to public catalog/indexer endpoints. It does not require a
 * separately-installed Jackett server or a Jackett Torznab API key.
 */
class MediaCatalogClient {
    fun loadHome(tmdbToken: String): List<MediaSection> {
        if (tmdbToken.isNotBlank()) {
            return listOf(
                MediaSection("Популярные фильмы", tmdbList("/3/movie/popular", tmdbToken, MediaKind.MOVIE)),
                MediaSection("Сейчас в тренде", tmdbList("/3/trending/all/week", tmdbToken, MediaKind.MOVIE)),
                MediaSection("Популярные сериалы", tmdbList("/3/tv/popular", tmdbToken, MediaKind.SERIES))
            ).filter { it.items.isNotEmpty() }
        }

        return listOf(
            MediaSection("Открытая коллекция фильмов", archiveSearch("", "mediatype:movies")),
            MediaSection("Документальное кино", archiveSearch("documentary", "mediatype:movies AND subject:documentary")),
            MediaSection("Классика кино", archiveSearch("", "mediatype:movies AND year:[1900 TO 1970]"))
        ).filter { it.items.isNotEmpty() }
    }

    fun movies(tmdbToken: String): List<CatalogItem> =
        if (tmdbToken.isNotBlank()) tmdbList("/3/movie/popular", tmdbToken, MediaKind.MOVIE)
        else archiveSearch("", "mediatype:movies")

    fun series(tmdbToken: String): List<CatalogItem> =
        if (tmdbToken.isNotBlank()) tmdbList("/3/tv/popular", tmdbToken, MediaKind.SERIES)
        else archiveSearch("television", "mediatype:movies AND subject:television")

    fun search(query: String, tmdbToken: String): List<CatalogItem> {
        val clean = query.trim()
        if (clean.isBlank()) return emptyList()
        val archive = archiveSearch(clean, "mediatype:movies AND (${escapeLucene(clean)})")
        if (tmdbToken.isBlank()) return archive

        val metadata = tmdbSearch(clean, tmdbToken)
        return (metadata + archive).distinctBy { it.id }.take(MAX_RESULTS)
    }

    fun archiveSources(query: String): List<SourceResult> {
        val items = archiveSearch(query, "mediatype:movies AND (${escapeLucene(query)})")
        return items.take(MAX_SOURCE_DETAILS).mapNotNull { item ->
            runCatching { archiveDetails(item).toSourceResult() }.getOrNull()
        }
    }

    fun details(item: CatalogItem): MediaDetails {
        if (item.providerId == ARCHIVE_PROVIDER) return archiveDetails(item)
        val token = ""
        return MediaDetails(
            item = item,
            description = item.overview,
            pageUrl = item.catalogUrl
        )
    }

    fun tmdbDetails(item: CatalogItem, tmdbToken: String): MediaDetails {
        if (item.providerId == ARCHIVE_PROVIDER) return archiveDetails(item)
        if (tmdbToken.isBlank()) return details(item)
        val mediaPath = if (item.kind == MediaKind.SERIES) "tv" else "movie"
        val json = requestJson("https://api.themoviedb.org/3/$mediaPath/${item.providerItemId}?language=ru-RU", bearer = tmdbToken)
        val overview = json.optString("overview").ifBlank { item.overview }
        val runtime = when (item.kind) {
            MediaKind.MOVIE -> json.optInt("runtime").takeIf { it > 0 }?.let { "${it} мин." }
            MediaKind.SERIES -> json.optJSONObject("episode_run_time")?.optInt("0")?.takeIf { it > 0 }?.let { "${it} мин. серия" }
                ?: json.optJSONArray("episode_run_time")?.optInt(0)?.takeIf { it > 0 }?.let { "${it} мин. серия" }
            else -> null
        }
        val genres = json.optJSONArray("genres")?.joinStrings("name")
        val summary = listOfNotNull(overview, genres?.takeIf { it.isNotBlank() }?.let { "Жанры: $it" }).joinToString("\n\n")
        return MediaDetails(item, summary, runtime = runtime, pageUrl = item.catalogUrl)
    }

    fun archiveDetails(item: CatalogItem): MediaDetails {
        val json = requestJson("https://archive.org/metadata/${Uri.encode(item.providerItemId)}")
        val metadata = json.optJSONObject("metadata") ?: JSONObject()
        val files = json.optJSONArray("files") ?: JSONArray()
        val license = metadata.optString("licenseurl").takeIf(String::isNotBlank) ?: item.licenseUrl
        val creator = metadata.firstText("creator", "artist", "director")
        val description = metadata.firstText("description", "abstract").ifBlank { item.overview }
        val runtime = metadata.firstText("runtime", "length").takeIf(String::isNotBlank)
        val mediaFile = findVideoFile(files)
        val torrentFile = findTorrentFile(files)
        val streamUrl = if (isKnownOpenLicense(license) && mediaFile != null) {
            downloadUrl(item.providerItemId, mediaFile.first)
        } else null
        val torrentUrl = if (isKnownOpenLicense(license) && torrentFile != null) {
            downloadUrl(item.providerItemId, torrentFile.first)
        } else null

        return MediaDetails(
            item = item.copy(licenseUrl = license),
            description = description,
            creator = creator,
            runtime = runtime,
            licenseUrl = license,
            streamUrl = streamUrl,
            torrentUrl = torrentUrl,
            pageUrl = "https://archive.org/details/${Uri.encode(item.providerItemId)}",
            fileSizeBytes = mediaFile?.second
        )
    }

    private fun tmdbList(path: String, token: String, defaultKind: MediaKind): List<CatalogItem> {
        val json = requestJson("https://api.themoviedb.org$path?language=ru-RU&page=1", bearer = token)
        val array = json.optJSONArray("results") ?: return emptyList()
        return buildList {
            for (i in 0 until minOf(array.length(), MAX_RESULTS)) {
                val item = array.optJSONObject(i) ?: continue
                val actualKind = when (item.optString("media_type")) {
                    "tv" -> MediaKind.SERIES
                    "movie" -> MediaKind.MOVIE
                    else -> defaultKind
                }
                tmdbItem(item, actualKind)?.let(::add)
            }
        }
    }

    private fun tmdbSearch(query: String, token: String): List<CatalogItem> {
        val uri = Uri.parse("https://api.themoviedb.org/3/search/multi")
            .buildUpon()
            .appendQueryParameter("query", query)
            .appendQueryParameter("language", "ru-RU")
            .appendQueryParameter("include_adult", "false")
            .build()
        val json = requestJson(uri.toString(), bearer = token)
        val array = json.optJSONArray("results") ?: return emptyList()
        return buildList {
            for (i in 0 until minOf(array.length(), MAX_RESULTS)) {
                val obj = array.optJSONObject(i) ?: continue
                val kind = when (obj.optString("media_type")) {
                    "tv" -> MediaKind.SERIES
                    "movie" -> MediaKind.MOVIE
                    else -> continue
                }
                tmdbItem(obj, kind)?.let(::add)
            }
        }
    }

    private fun tmdbItem(obj: JSONObject, kind: MediaKind): CatalogItem? {
        val id = obj.optLong("id")
        if (id <= 0L) return null
        val title = obj.optString(if (kind == MediaKind.SERIES) "name" else "title")
            .ifBlank { obj.optString("original_title").ifBlank { obj.optString("original_name") } }
            .takeIf(String::isNotBlank) ?: return null
        val poster = obj.optString("poster_path").takeIf(String::isNotBlank)
            ?.let { "https://image.tmdb.org/t/p/w500$it" }
        val backdrop = obj.optString("backdrop_path").takeIf(String::isNotBlank)
            ?.let { "https://image.tmdb.org/t/p/w1280$it" }
        val date = obj.optString(if (kind == MediaKind.SERIES) "first_air_date" else "release_date")
            .takeIf(String::isNotBlank)
        return CatalogItem(
            id = "tmdb:${kind.name.lowercase()}:$id",
            providerId = TMDB_PROVIDER,
            providerItemId = id.toString(),
            kind = kind,
            title = title,
            overview = obj.optString("overview"),
            posterUrl = poster,
            backdropUrl = backdrop,
            releaseDate = date,
            rating = if (obj.isNull("vote_average")) null else obj.optDouble("vote_average"),
            popularity = obj.optDouble("popularity"),
            catalogUrl = "https://www.themoviedb.org/${if (kind == MediaKind.SERIES) "tv" else "movie"}/$id"
        )
    }

    private fun archiveSearch(searchText: String, query: String): List<CatalogItem> {
        val uriBuilder = Uri.parse("https://archive.org/advancedsearch.php").buildUpon()
            .appendQueryParameter("q", query)
            .appendQueryParameter("rows", MAX_RESULTS.toString())
            .appendQueryParameter("page", "1")
            .appendQueryParameter("output", "json")
            .appendQueryParameter("sort[]", "downloads desc")
        ARCHIVE_FIELDS.forEach { uriBuilder.appendQueryParameter("fl[]", it) }
        val json = requestJson(uriBuilder.build().toString())
        val docs = json.optJSONObject("response")?.optJSONArray("docs") ?: return emptyList()
        return buildList {
            for (i in 0 until minOf(docs.length(), MAX_RESULTS)) {
                val doc = docs.optJSONObject(i) ?: continue
                val id = doc.optString("identifier").takeIf(String::isNotBlank) ?: continue
                val title = doc.optString("title").ifBlank { id }
                val license = doc.firstText("licenseurl").takeIf(String::isNotBlank)
                val description = doc.firstText("description")
                val creator = doc.firstText("creator")
                val year = doc.firstText("year", "date").take(4)
                val subjects = doc.joinStrings("subject")
                val overview = listOf(description, creator.takeIf(String::isNotBlank)?.let { "Автор: $it" }, subjects.takeIf(String::isNotBlank))
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
                add(
                    CatalogItem(
                        id = "archive:$id",
                        providerId = ARCHIVE_PROVIDER,
                        providerItemId = id,
                        kind = MediaKind.MOVIE,
                        title = title,
                        overview = overview,
                        posterUrl = "https://archive.org/services/img/${Uri.encode(id)}",
                        releaseDate = year.takeIf { it.length == 4 },
                        popularity = doc.optDouble("downloads"),
                        licenseUrl = license,
                        catalogUrl = "https://archive.org/details/${Uri.encode(id)}"
                    )
                )
            }
        }.distinctBy { it.id }
    }

    private fun isKnownOpenLicense(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        val path = uri.path.orEmpty().lowercase()
        return host == "creativecommons.org" &&
            (path.startsWith("/licenses/") || path.startsWith("/publicdomain/"))
    }

    private fun findVideoFile(files: JSONArray): Pair<String, Long?>? {
        val preferred = listOf(".mp4", ".m4v", ".webm", ".ogv", ".mov", ".mkv")
        val candidates = buildList {
            for (i in 0 until files.length()) {
                val file = files.optJSONObject(i) ?: continue
                val name = file.optString("name")
                val format = file.optString("format")
                if (name.substringAfterLast('.', "").lowercase().let { ext -> ".$ext" in preferred } ||
                    format.contains("mpeg4", true) || format.contains("h.264", true) || format.contains("ogg video", true)
                ) {
                    val size = file.optString("size").toLongOrNull()?.takeIf { it > 0L }
                    add(name to size)
                }
            }
        }
        return candidates.minByOrNull { candidate ->
            val ext = candidate.first.substringAfterLast('.', "").lowercase()
            preferred.indexOf(".$ext").let { if (it < 0) Int.MAX_VALUE else it }
        }
    }

    private fun findTorrentFile(files: JSONArray): Pair<String, Long?>? {
        for (i in 0 until files.length()) {
            val file = files.optJSONObject(i) ?: continue
            val name = file.optString("name")
            if (name.endsWith(".torrent", ignoreCase = true)) {
                return name to file.optString("size").toLongOrNull()
            }
        }
        return null
    }

    private fun downloadUrl(identifier: String, filename: String): String =
        "https://archive.org/download/${Uri.encode(identifier)}/${Uri.encode(filename)}"

    private fun requestJson(url: String, bearer: String? = null): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json, application/rss+xml;q=0.8")
            setRequestProperty("User-Agent", USER_AGENT)
            if (!bearer.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $bearer")
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("Источник вернул HTTP $status")
            val body = readBounded(connection.inputStream, MAX_RESPONSE_BYTES)
            return JSONObject(body.toString(Charsets.UTF_8))
        } finally {
            connection.disconnect()
        }
    }

    private fun readBounded(stream: java.io.InputStream, maxBytes: Int): ByteArray =
        stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > maxBytes) throw IOException("Ответ источника слишком большой")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }

    private fun JSONArray.joinStrings(key: String): String = buildList {
        for (i in 0 until length()) {
            val value = opt(i)
            when (value) {
                is JSONObject -> value.optString(key).takeIf(String::isNotBlank)?.let(::add)
                is String -> value.takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }.joinToString(", ")

    private fun JSONObject.joinStrings(key: String): String {
        val value = opt(key)
        return when (value) {
            is JSONArray -> (0 until value.length()).mapNotNull { value.optString(it).takeIf(String::isNotBlank) }.joinToString(", ")
            is String -> value
            else -> ""
        }
    }

    private fun JSONObject.firstText(vararg keys: String): String {
        keys.forEach { key ->
            val value = opt(key)
            val text = when (value) {
                is JSONArray -> (0 until value.length()).mapNotNull { value.optString(it).takeIf(String::isNotBlank) }.joinToString(", ")
                is String -> value
                else -> ""
            }
            if (text.isNotBlank()) return text
        }
        return ""
    }

    private fun MediaDetails.toSourceResult(): SourceResult? {
        val detail = this
        if (detail.streamUrl == null && detail.torrentUrl == null) return null
        return SourceResult(
            id = detail.item.id,
            title = detail.item.title,
            providerName = "Internet Archive",
            downloadUrl = detail.torrentUrl,
            streamUrl = detail.streamUrl,
            sizeBytes = detail.fileSizeBytes,
            licenseUrl = detail.licenseUrl,
            pageUrl = detail.pageUrl
        )
    }

    private fun escapeLucene(value: String): String =
        value.replace(Regex("([+\\-!(){}\\[\\]^\"~*?:\\\\/])"), "\\\\$1").take(120)

    private fun JSONObject.firstText(key: String): String = opt(key).let { value ->
        when (value) {
            is JSONArray -> (0 until value.length()).joinToString(", ") { value.optString(it) }
            is String -> value
            else -> ""
        }
    }

    private companion object {
        const val TMDB_PROVIDER = "tmdb"
        const val ARCHIVE_PROVIDER = "archive"
        const val MAX_RESULTS = 24
        const val MAX_SOURCE_DETAILS = 8
        const val CONNECT_TIMEOUT_MS = 12_000
        const val READ_TIMEOUT_MS = 25_000
        const val MAX_RESPONSE_BYTES = 6 * 1024 * 1024
        const val USER_AGENT = "JacketAndroid/2.0 (Android media client)"
        val ARCHIVE_FIELDS = listOf(
            "identifier", "title", "description", "year", "date", "creator",
            "licenseurl", "downloads", "subject", "runtime", "collection"
        )
    }
}
