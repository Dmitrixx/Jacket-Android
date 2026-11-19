package app.jacket.mobile.data

import org.json.JSONObject

enum class MediaKind(val label: String) {
    MOVIE("Фильм"),
    SERIES("Сериал"),
    VIDEO("Видео")
}

enum class IndexerProtocol(val label: String) {
    RSS("RSS / Atom"),
    TORZNAB("Torznab / Newznab"),
    JACKETT("Jackett")
}

data class CatalogItem(
    val id: String,
    val providerId: String,
    val providerItemId: String,
    val kind: MediaKind,
    val title: String,
    val overview: String = "",
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val releaseDate: String? = null,
    val rating: Double? = null,
    val popularity: Double = 0.0,
    val licenseUrl: String? = null,
    val catalogUrl: String? = null
) {
    val year: String
        get() = releaseDate?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) } ?: "Год неизвестен"
}

data class MediaSection(
    val title: String,
    val items: List<CatalogItem>
)

data class MediaDetails(
    val item: CatalogItem,
    val description: String,
    val creator: String? = null,
    val runtime: String? = null,
    val licenseUrl: String? = item.licenseUrl,
    val streamUrl: String? = null,
    val torrentUrl: String? = null,
    val pageUrl: String? = item.catalogUrl,
    val fileSizeBytes: Long? = null
)

/** User-owned indexer. Secrets are encrypted before writing them to preferences. */
data class IndexerConfig(
    val id: String,
    val name: String,
    val searchUrlTemplate: String,
    val protocol: IndexerProtocol = IndexerProtocol.RSS,
    val apiKey: String = "",
    val username: String = "",
    val password: String = "",
    val cookie: String = "",
    val categories: String = "",
    val tags: String = "",
    val freeleechOnly: Boolean = false,
    val sortBy: String = "created",
    val sortOrder: String = "desc",
    val enabled: Boolean = true
)

/** A user-configured website search link. Results open in the external browser. */
data class WebSearchProvider(
    val id: String,
    val name: String,
    val searchUrlTemplate: String,
    val enabled: Boolean = true,
    val cookie: String = ""
)

/** Private HDRezka account data. Password and session cookies are encrypted in local storage. */
data class HdRezkaSettings(
    val mirror: String = "",
    val login: String = "",
    val password: String = "",
    val cookie: String = "",
    val connected: Boolean = false
)

data class SourceResult(
    val id: String,
    val title: String,
    val providerName: String,
    val magnetUri: String? = null,
    val downloadUrl: String? = null,
    val streamUrl: String? = null,
    val sizeBytes: Long? = null,
    val seeders: Int? = null,
    val leechers: Int? = null,
    val licenseUrl: String? = null,
    val pageUrl: String? = null,
    val category: String? = null,
    val indexerId: String? = null,
    val tracker: String? = null
)

fun CatalogItem.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("providerId", providerId)
    .put("providerItemId", providerItemId)
    .put("kind", kind.name)
    .put("title", title)
    .put("overview", overview)
    .put("posterUrl", posterUrl)
    .put("backdropUrl", backdropUrl)
    .put("releaseDate", releaseDate)
    .put("rating", rating)
    .put("popularity", popularity)
    .put("licenseUrl", licenseUrl)
    .put("catalogUrl", catalogUrl)

fun catalogItemFromJson(json: JSONObject): CatalogItem? = runCatching {
    CatalogItem(
        id = json.getString("id"),
        providerId = json.optString("providerId", "archive"),
        providerItemId = json.optString("providerItemId"),
        kind = runCatching { MediaKind.valueOf(json.optString("kind", "MOVIE")) }.getOrDefault(MediaKind.MOVIE),
        title = json.getString("title"),
        overview = json.optString("overview"),
        posterUrl = json.optString("posterUrl").takeIf(String::isNotBlank),
        backdropUrl = json.optString("backdropUrl").takeIf(String::isNotBlank),
        releaseDate = json.optString("releaseDate").takeIf(String::isNotBlank),
        rating = if (json.isNull("rating")) null else json.optDouble("rating"),
        popularity = json.optDouble("popularity", 0.0),
        licenseUrl = json.optString("licenseUrl").takeIf(String::isNotBlank),
        catalogUrl = json.optString("catalogUrl").takeIf(String::isNotBlank)
    )
}.getOrNull()


data class IndexerFailure(
    val indexerId: String,
    val indexerName: String,
    val message: String
)

data class IndexerSearchOutcome(
    val results: List<SourceResult>,
    val failures: List<IndexerFailure>
)

data class ReleaseMetadata(
    val quality: String?,
    val hdr: Boolean,
    val dolbyVision: Boolean,
    val videoCodec: String?,
    val audioTags: List<String>,
    val sourceTags: List<String>
)


data class MediaSeason(
    val seasonNumber: Int,
    val name: String,
    val episodeCount: Int,
    val posterUrl: String? = null,
    val overview: String = ""
)

data class MediaEpisode(
    val seasonNumber: Int,
    val episodeNumber: Int,
    val name: String,
    val overview: String = "",
    val airDate: String? = null,
    val stillUrl: String? = null
) {
    val releaseCode: String
        get() = "S${seasonNumber.toString().padStart(2, '0')}E${episodeNumber.toString().padStart(2, '0')}"
}
