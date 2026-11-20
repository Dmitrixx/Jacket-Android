package app.jacket.mobile.data

enum class MediaSourceIntegration {
    IN_APP_CONNECTOR,
    NOT_CONNECTED
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
            notes = "Основной источник. Много озвучек. Доступность зависит от региона и сети.",
            integration = MediaSourceIntegration.IN_APP_CONNECTOR
        ),
        MediaSourceDescriptor(
            id = "filmix",
            name = "Filmix",
            displayName = "Filmix",
            priority = 2,
            types = listOf("movie", "serial"),
            authRequired = true,
            supports4k = true,
            notes = "Поддерживается привязка устройства по одноразовому коду; качество зависит от тарифа аккаунта.",
            integration = MediaSourceIntegration.IN_APP_CONNECTOR
        ),
        MediaSourceDescriptor(
            id = "coldfilm",
            name = "ColdFilm",
            displayName = "ColdFilm",
            priority = 3,
            types = listOf("serial"),
            authRequired = false,
            supports4k = false,
            notes = "Сериалы и собственная озвучка. Проверенный адаптер пока не подключён.",
            integration = MediaSourceIntegration.NOT_CONNECTED
        ),
        MediaSourceDescriptor(
            id = "baskino",
            name = "Baskino",
            displayName = "Baskino",
            priority = 4,
            types = listOf("movie", "serial"),
            authRequired = false,
            supports4k = false,
            notes = "Нужен проверенный адаптер и подтверждённый формат источника.",
            integration = MediaSourceIntegration.NOT_CONNECTED
        ),
        MediaSourceDescriptor(
            id = "kinogo",
            name = "Kinogo",
            displayName = "Kinogo",
            priority = 5,
            types = listOf("movie", "serial"),
            authRequired = false,
            supports4k = false,
            notes = "Нужен проверенный адаптер и подтверждённый формат источника.",
            integration = MediaSourceIntegration.NOT_CONNECTED
        ),
        MediaSourceDescriptor(
            id = "videocdn",
            name = "VideoCDN",
            displayName = "VideoCDN",
            priority = 6,
            types = listOf("movie", "serial"),
            authRequired = false,
            supports4k = false,
            notes = "Требуется документированный API или совместимый плагин-поставщик.",
            integration = MediaSourceIntegration.NOT_CONNECTED
        ),
        MediaSourceDescriptor(
            id = "collaps",
            name = "Collaps",
            displayName = "Collaps",
            priority = 7,
            types = listOf("movie", "serial"),
            authRequired = false,
            supports4k = false,
            notes = "Требуется документированный API или совместимый плагин-поставщик.",
            integration = MediaSourceIntegration.NOT_CONNECTED
        ),
        MediaSourceDescriptor(
            id = "kinobase",
            name = "Kinobase",
            displayName = "Kinobase",
            priority = 8,
            types = listOf("movie", "serial"),
            authRequired = false,
            supports4k = false,
            notes = "Дополнительный источник. Проверенный адаптер пока не подключён.",
            integration = MediaSourceIntegration.NOT_CONNECTED
        ),
        MediaSourceDescriptor(
            id = "cdnmovies",
            name = "CDNMovies",
            displayName = "CDNMovies",
            priority = 9,
            types = listOf("movie", "serial"),
            authRequired = false,
            supports4k = false,
            notes = "Резервный источник. Проверенный адаптер пока не подключён.",
            integration = MediaSourceIntegration.NOT_CONNECTED
        ),
        MediaSourceDescriptor(
            id = "lordfilm",
            name = "Lordfilm",
            displayName = "Lordfilm",
            priority = 10,
            types = listOf("movie", "serial"),
            authRequired = false,
            supports4k = false,
            notes = "Требуется проверенный источник и поддержка смены URL через настройки, не через обход блокировок.",
            integration = MediaSourceIntegration.NOT_CONNECTED
        )
    )
}
