package app.jacket.mobile.data

enum class MediaSourceIntegration {
    DIRECT_ADAPTER,
    AUTH_SETTINGS_ONLY,
    LAMPAC_MANAGED,
    MEDIA_ADAPTER_MISSING
}

/** Curated source metadata. An entry is not considered connected until a real adapter exists. */
data class MediaSourceDescriptor(
    val id: String,
    val name: String,
    val displayName: String,
    val priority: Int,
    val types: List<String>,
    val authRequired: Boolean,
    val supports4k: Boolean,
    val notes: String,
    val integration: MediaSourceIntegration
)

object MediaSourceCatalog {
    val sources: List<MediaSourceDescriptor> = listOf(
        MediaSourceDescriptor(
            id = "hdrezka",
            name = "HDRezka",
            displayName = "HDRezka / Rezka",
            priority = 1,
            types = listOf("movie", "serial", "anime"),
            authRequired = false,
            supports4k = true,
            notes = "Подключён прямой поиск по заданному HTTPS-зеркалу, озвучкам, сериям и поддерживаемым HTTPS-потокам. CAPTCHA/Anubis не обходится.",
            integration = MediaSourceIntegration.DIRECT_ADAPTER
        ),
        MediaSourceDescriptor(
            id = "filmix",
            name = "Filmix",
            displayName = "Filmix",
            priority = 2,
            types = listOf("movie", "serial"),
            authRequired = true,
            supports4k = true,
            notes = "Подключён поиск через Filmix API v2, варианты качества с учётом тарифа аккаунта и прямое воспроизведение поддерживаемых HTTPS-ссылок.",
            integration = MediaSourceIntegration.DIRECT_ADAPTER
        ),
        MediaSourceDescriptor(
            id = "coldfilm",
            name = "ColdFilm",
            displayName = "ColdFilm",
            priority = 3,
            types = listOf("serial"),
            authRequired = false,
            supports4k = false,
            notes = "Сериалы и озвучка доступны через совместимый модуль Lampac, если он установлен и включён.",
            integration = MediaSourceIntegration.LAMPAC_MANAGED
        ),
        MediaSourceDescriptor(
            id = "baskino",
            name = "Baskino",
            displayName = "Baskino",
            priority = 4,
            types = listOf("movie", "serial"),
            authRequired = false,
            supports4k = false,
            notes = "Фильмы и сериалы доступны через совместимый модуль Lampac, если он установлен и включён.",
            integration = MediaSourceIntegration.LAMPAC_MANAGED
        ),
        MediaSourceDescriptor(
            id = "kinogo",
            name = "Kinogo",
            displayName = "Kinogo",
            priority = 5,
            types = listOf("movie", "serial"),
            authRequired = false,
            supports4k = false,
            notes = "Фильмы и сериалы доступны через совместимый модуль Lampac, если он установлен и включён.",
            integration = MediaSourceIntegration.LAMPAC_MANAGED
        ),
        MediaSourceDescriptor(
            id = "videocdn",
            name = "VideoCDN",
            displayName = "VideoCDN",
            priority = 6,
            types = listOf("movie", "serial"),
            authRequired = false,
            supports4k = false,
            notes = "Видеопотоки доступны через совместимый модуль Lampac, если он установлен и включён.",
            integration = MediaSourceIntegration.LAMPAC_MANAGED
        ),
        MediaSourceDescriptor(
            id = "collaps",
            name = "Collaps",
            displayName = "Collaps",
            priority = 7,
            types = listOf("movie", "serial"),
            authRequired = false,
            supports4k = false,
            notes = "Видеопотоки доступны через совместимый модуль Lampac, если он установлен и включён.",
            integration = MediaSourceIntegration.LAMPAC_MANAGED
        ),
        MediaSourceDescriptor(
            id = "kinobase",
            name = "Kinobase",
            displayName = "Kinobase",
            priority = 8,
            types = listOf("movie", "serial"),
            authRequired = false,
            supports4k = false,
            notes = "Фильмы и сериалы доступны через совместимый модуль Lampac, если он установлен и включён.",
            integration = MediaSourceIntegration.LAMPAC_MANAGED
        ),
        MediaSourceDescriptor(
            id = "cdnmovies",
            name = "CDNMovies",
            displayName = "CDNMovies",
            priority = 9,
            types = listOf("movie", "serial"),
            authRequired = false,
            supports4k = false,
            notes = "Резервный источник доступен через Lampac, если соответствующий модуль установлен и включён.",
            integration = MediaSourceIntegration.LAMPAC_MANAGED
        ),
        MediaSourceDescriptor(
            id = "lordfilm",
            name = "Lordfilm",
            displayName = "Lordfilm",
            priority = 10,
            types = listOf("movie", "serial"),
            authRequired = false,
            supports4k = false,
            notes = "Фильмы и сериалы доступны через совместимый модуль Lampac, если он установлен и включён.",
            integration = MediaSourceIntegration.LAMPAC_MANAGED
        )
    )
}
