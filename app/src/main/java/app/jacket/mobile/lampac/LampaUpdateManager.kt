package app.jacket.mobile.lampac

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipFile

data class LampaWebUpdateInfo(
    val buildId: String,
    val sourceCommit: String,
    val versionName: String,
    val assetUrl: String,
    val sha256: String,
    val sizeBytes: Long
) {
    val displayVersion: String
        get() = buildId.take(7)
}

data class LampacReleaseUpdateInfo(
    val version: String,
    val assetUrl: String,
    val sha256: String,
    val sizeBytes: Long
)

data class UpdateCheckResult(
    val localLampaBuild: String,
    val lampa: LampaWebUpdateInfo?,
    val localLampacVersion: String?,
    val lampac: LampacReleaseUpdateInfo?,
    val lampaError: String? = null,
    val lampacError: String? = null
)

/**
 * Checks only the two pinned upstream repositories and the project's own
 * published Lampa-web update releases. No arbitrary update URLs are accepted.
 */
class LampaUpdateManager(context: Context) {
    private val appContext = context.applicationContext
    private val apiHeaders = mapOf(
        "Accept" to "application/vnd.github+json",
        "X-GitHub-Api-Version" to "2022-11-28",
        "User-Agent" to "Lampa-Android-Updater"
    )

    suspend fun checkUpdates(): UpdateCheckResult = withContext(Dispatchers.IO) {
        val localLampaBuild = readLocalLampaBuild()
        val localLampacVersion = LampacRuntimeInstaller(appContext).installedVersion()

        val lampaResult = runCatching { findLatestLampaWebBuild(localLampaBuild) }
        val lampacResult = runCatching { findLatestLampacRelease(localLampacVersion) }

        if (lampaResult.isFailure && lampacResult.isFailure) {
            throw IOException(
                "Не удалось проверить Lampa и Lampac: " +
                    "${lampaResult.exceptionOrNull()?.message}; ${lampacResult.exceptionOrNull()?.message}"
            )
        }

        UpdateCheckResult(
            localLampaBuild = localLampaBuild,
            lampa = lampaResult.getOrNull(),
            localLampacVersion = localLampacVersion,
            lampac = lampacResult.getOrNull(),
            lampaError = lampaResult.exceptionOrNull()?.message,
            lampacError = lampacResult.exceptionOrNull()?.message
        )
    }

    private fun readLocalLampaBuild(): String {
        val installed = LampaWebAssetUpdater.activeDirectory(appContext)
        val info = if (installed != null) File(installed, "build-info.json") else null
        val json = runCatching {
            if (info?.isFile == true) JSONObject(info.readText(Charsets.UTF_8))
            else JSONObject(appContext.assets.open("lampa/build-info.json").bufferedReader().use { it.readText() })
        }.getOrNull()
        return json?.optString("buildId")?.takeIf { it.matches(Regex("[0-9a-f]{7,40}")) }
            ?: json?.optString("commit")?.takeIf { it.matches(Regex("[0-9a-f]{7,40}")) }
            ?: "bundled"
    }

    private fun findLatestLampaWebBuild(localBuild: String): LampaWebUpdateInfo? {
        val url = "https://api.github.com/repos/$APP_REPOSITORY/releases?per_page=100"
        val releases = JSONArray(readText(url, API_HOSTS, 2 * 1024 * 1024, apiHeaders))
        var latest: JSONObject? = null
        for (index in 0 until releases.length()) {
            val release = releases.optJSONObject(index) ?: continue
            val tag = release.optString("tag_name")
            if (!tag.startsWith(LAMPA_WEB_TAG_PREFIX) ||
                release.optBoolean("draft") ||
                release.optBoolean("prerelease")
            ) continue

            if (latest == null || release.optString("published_at") > latest!!.optString("published_at")) {
                latest = release
            }
        }

        val release = latest ?: return null
        val assets = release.optJSONArray("assets") ?: return null
        var manifestAsset: JSONObject? = null
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            if (asset.optString("name") == LAMPA_MANIFEST_ASSET) {
                manifestAsset = asset
                break
            }
        }
        val manifest = manifestAsset ?: return null
        val manifestUrl = validatedAssetUrl(manifest.optString("browser_download_url"))
        val json = JSONObject(readText(manifestUrl, RELEASE_ASSET_HOSTS, 128 * 1024))
        require(json.optInt("schemaVersion") == 1) { "Неизвестная версия манифеста Lampa" }

        val buildId = json.optString("buildId")
        val sourceCommit = json.optString("sourceCommit")
        val versionName = json.optString("versionName")
        val assetName = json.optString("assetName")
        val sha256 = json.optString("sha256").lowercase()
        val sizeBytes = json.optLong("sizeBytes")
        require(buildId.matches(Regex("[0-9a-f]{40}")) &&
            sourceCommit.matches(Regex("[0-9a-f]{40}")) &&
            versionName.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")) &&
            assetName == LAMPA_WEB_ASSET &&
            sha256.matches(Regex("[0-9a-f]{64}")) &&
            sizeBytes in 1..MAX_LAMPA_WEB_ARCHIVE_BYTES
        ) { "Манифест обновления Lampa некорректен" }

        if (buildId == localBuild) return null

        var archiveAsset: JSONObject? = null
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            if (asset.optString("name") == LAMPA_WEB_ASSET) {
                archiveAsset = asset
                break
            }
        }
        val archive = archiveAsset ?: return null
        require(archive.optLong("size") == sizeBytes) { "Размер архива Lampa не совпадает с манифестом" }
        val apiDigest = archive.optString("digest").removePrefix("sha256:").lowercase()
        require(apiDigest.isBlank() || apiDigest == sha256) { "Контрольная сумма релиза Lampa не совпадает с API GitHub" }

        return LampaWebUpdateInfo(
            buildId = buildId,
            sourceCommit = sourceCommit,
            versionName = versionName,
            assetUrl = validatedAssetUrl(archive.optString("browser_download_url")),
            sha256 = sha256,
            sizeBytes = sizeBytes
        )
    }

    private fun findLatestLampacRelease(localVersion: String?): LampacReleaseUpdateInfo? {
        val json = JSONObject(
            readText(
                "https://api.github.com/repos/$LAMPAC_REPOSITORY/releases/latest",
                API_HOSTS,
                2 * 1024 * 1024,
                apiHeaders
            )
        )
        if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null

        val version = normalizeVersion(json.optString("tag_name"))
        if (!isSemanticVersion(version) || localVersion == null ||
            compareVersions(version, normalizeVersion(localVersion)) <= 0
        ) return null

        val assets = json.optJSONArray("assets") ?: return null
        var archive: JSONObject? = null
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            if (asset.optString("name") == LAMPAC_ARCHIVE_ASSET) {
                archive = asset
                break
            }
        }
        val asset = archive ?: return null
        val digest = asset.optString("digest").removePrefix("sha256:").lowercase()
        // Never install an upstream update without an independently published digest.
        require(digest.matches(Regex("[0-9a-f]{64}"))) {
            "У релиза Lampac нет SHA-256 в метаданных GitHub; обновление заблокировано."
        }
        val sizeBytes = asset.optLong("size")
        require(sizeBytes in 1..MAX_LAMPAC_ARCHIVE_BYTES) { "Размер архива Lampac превышает лимит обновления" }

        return LampacReleaseUpdateInfo(
            version = version,
            assetUrl = validatedAssetUrl(asset.optString("browser_download_url")),
            sha256 = digest,
            sizeBytes = sizeBytes
        )
    }

    suspend fun updateLampa(
        update: LampaWebUpdateInfo,
        onProgress: (Int, String) -> Unit = {}
    ) = LampaWebAssetUpdater.install(appContext, update) { percent, message ->
        // Asset installation runs on Dispatchers.IO; Compose state callbacks
        // must be delivered on Android's main thread.
        appContext.mainExecutor.execute { onProgress(percent, message) }
    }

    private fun readText(
        url: String,
        allowedHosts: Set<String>,
        maxBytes: Int,
        headers: Map<String, String> = emptyMap()
    ): String {
        val bytes = requestBytes(url, allowedHosts, maxBytes, headers)
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun requestBytes(
        initialUrl: String,
        allowedHosts: Set<String>,
        maxBytes: Int,
        headers: Map<String, String> = emptyMap()
    ): ByteArray {
        var currentUrl = initialUrl
        repeat(MAX_REDIRECTS + 1) { attempt ->
            val uri = URI(currentUrl)
            require(isSafeHttpsUrl(uri, allowedHosts)) { "Загрузка с недоверенного адреса запрещена" }
            val connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
            }
            try {
                val status = connection.responseCode
                if (status in REDIRECT_CODES) {
                    require(attempt < MAX_REDIRECTS) { "Слишком много перенаправлений GitHub" }
                    val location = connection.getHeaderField("Location")
                        ?: throw IOException("GitHub не указал адрес перенаправления")
                    currentUrl = uri.resolve(location).toString()
                    return@repeat
                }
                if (status != HttpURLConnection.HTTP_OK) {
                    throw IOException("GitHub вернул HTTP $status")
                }
                val declared = connection.getHeaderFieldLong("Content-Length", -1L)
                require(declared <= maxBytes) { "Ответ GitHub превышает разрешённый размер" }
                return BufferedInputStream(connection.inputStream).use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size().toLong() + count <= maxBytes) {
                            "Ответ GitHub превышает разрешённый размер"
                        }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Не удалось загрузить данные GitHub")
    }

    private fun validatedAssetUrl(raw: String): String {
        val uri = URI(raw)
        require(isSafeHttpsUrl(uri, RELEASE_ASSET_HOSTS)) { "Ссылка на архив релиза недоверенная" }
        return raw
    }

    private fun isSafeHttpsUrl(uri: URI, hosts: Set<String>): Boolean =
        uri.scheme.equals("https", true) &&
            uri.host?.lowercase()?.let { it in hosts } == true &&
            (uri.port == -1 || uri.port == 443) &&
            uri.rawUserInfo == null &&
            uri.rawFragment.isNullOrBlank()

    private fun normalizeVersion(value: String): String = value.trim().removePrefix("v")

    private fun isSemanticVersion(value: String): Boolean =
        value.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))

    private fun compareVersions(first: String, second: String): Int {
        val a = first.split('.').map { it.toIntOrNull() ?: 0 }
        val b = second.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0..2) {
            val compare = a[i].compareTo(b[i])
            if (compare != 0) return compare
        }
        return 0
    }

    private companion object {
        const val APP_REPOSITORY = "Dmitrixx/Jacket-Android"
        const val LAMPAC_REPOSITORY = "lampac-nextgen/lampac"
        const val LAMPA_WEB_TAG_PREFIX = "lampa-web-"
        const val LAMPA_WEB_ASSET = "lampa-web-update.zip"
        const val LAMPA_MANIFEST_ASSET = "lampa-web-update.json"
        const val LAMPAC_ARCHIVE_ASSET = "lampac-nextgen.zip"
        const val MAX_LAMPA_WEB_ARCHIVE_BYTES = 150L * 1024L * 1024L
        const val MAX_LAMPAC_ARCHIVE_BYTES = 1024L * 1024L * 1024L
        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 90_000
        const val MAX_REDIRECTS = 5
        const val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        val API_HOSTS = setOf("api.github.com")
        val RELEASE_ASSET_HOSTS = setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
    }
}

/** Installs a verified Lampa web bundle without modifying the installed APK. */
internal object LampaWebAssetUpdater {
    private const val MAX_FILES = 20_000
    private const val MAX_ENTRY_BYTES = 64L * 1024L * 1024L
    private const val MAX_EXPANDED_BYTES = 256L * 1024L * 1024L
    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 90_000
    private const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
    private val allowedHosts = setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")

    fun activeDirectory(context: Context): File? =
        File(context.filesDir, "lampa-web/current").takeIf { it.isDirectory }

    fun openAsset(context: Context, relativePath: String): java.io.InputStream? {
        val active = activeDirectory(context)
        if (active != null) {
            val root = active.canonicalFile
            val candidate = File(active, relativePath).canonicalFile
            if (candidate.path.startsWith(root.path + File.separator) && candidate.isFile) {
                return candidate.inputStream()
            }
        }
        return runCatching { context.assets.open("lampa/$relativePath") }.getOrNull()
    }

    suspend fun install(
        context: Context,
        update: LampaWebUpdateInfo,
        onProgress: (Int, String) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        val updateRoot = File(context.filesDir, "lampa-web")
        if (!updateRoot.exists() && !updateRoot.mkdirs()) {
            throw IOException("Не удалось создать каталог обновления Lampa")
        }
        val archive = File(updateRoot, "lampa-web-update.zip.part")
        val stage = File(updateRoot, "stage")
        val current = File(updateRoot, "current")
        val previous = File(updateRoot, "previous")
        archive.delete()
        stage.deleteRecursively()

        try {
            downloadArchive(update, archive) { done ->
                val percent = ((done * 100L) / update.sizeBytes).toInt().coerceIn(0, 100)
                onProgress(percent, "Загрузка Lampa: $percent%")
            }
            val actualDigest = sha256(archive)
            require(actualDigest.equals(update.sha256, true)) {
                "SHA-256 архива Lampa не совпала. Обновление отменено."
            }

            extractBundle(archive, stage) { done, total ->
                val percent = if (total > 0) ((done * 100L) / total).toInt().coerceIn(0, 100) else 100
                onProgress(percent, "Распаковка Lampa: $percent%")
            }
            validateBundle(stage, update)

            previous.deleteRecursively()
            if (current.exists() && !current.renameTo(previous)) {
                throw IOException("Не удалось сохранить предыдущую версию Lampa")
            }
            if (!stage.renameTo(current)) {
                if (!current.exists() && previous.exists()) previous.renameTo(current)
                throw IOException("Не удалось активировать обновление Lampa")
            }
            archive.delete()
            onProgress(100, "Lampa обновлена до сборки ${update.displayVersion}")
        } catch (error: Exception) {
            stage.deleteRecursively()
            archive.delete()
            if (!current.exists() && previous.exists()) previous.renameTo(current)
            throw error
        }
    }

    private suspend fun downloadArchive(
        update: LampaWebUpdateInfo,
        target: File,
        onProgress: (Long) -> Unit
    ) {
        var currentUrl = update.assetUrl
        repeat(6) { attempt ->
            val uri = URI(currentUrl)
            require(isAllowed(uri)) { "Загрузка архива Lampa с этого адреса запрещена" }
            val connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/octet-stream")
                setRequestProperty("User-Agent", "Lampa-Android-Updater")
            }
            try {
                val status = connection.responseCode
                if (status in setOf(301, 302, 303, 307, 308)) {
                    require(attempt < 5) { "Слишком много перенаправлений при загрузке Lampa" }
                    val location = connection.getHeaderField("Location")
                        ?: throw IOException("GitHub не указал адрес архива Lampa")
                    currentUrl = uri.resolve(location).toString()
                    return@repeat
                }
                if (status != HttpURLConnection.HTTP_OK) throw IOException("Загрузка Lampa: HTTP $status")
                require(connection.getHeaderFieldLong("Content-Length", -1L) in -1L..update.sizeBytes) {
                    "Размер архива Lampa превышает размер в манифесте"
                }

                BufferedInputStream(connection.inputStream).use { input ->
                    FileOutputStream(target).use { file ->
                        BufferedOutputStream(file, DOWNLOAD_BUFFER_BYTES).use { output ->
                            val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                            var total = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= update.sizeBytes) { "Архив Lampa больше объявленного размера" }
                                output.write(buffer, 0, count)
                                onProgress(total)
                            }
                            require(total == update.sizeBytes) { "Архив Lampa загружен не полностью" }
                            output.flush()
                            file.fd.sync()
                        }
                    }
                }
                return
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Не удалось загрузить архив Lampa")
    }

    private fun extractBundle(archive: File, target: File, onProgress: (Long, Long) -> Unit) {
        require(target.mkdirs()) { "Не удалось создать временный каталог Lampa" }
        ZipFile(archive).use { zip ->
            val entries = zip.entries()
            var count = 0
            var totalDeclared = 0L
            val all = ArrayList<java.util.zip.ZipEntry>()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                require(++count <= MAX_FILES) { "В архиве Lampa слишком много файлов" }
                if (!entry.isDirectory) {
                    require(entry.size in 0..MAX_ENTRY_BYTES) { "Файл в архиве Lampa превышает лимит" }
                    totalDeclared = Math.addExact(totalDeclared, entry.size)
                    require(totalDeclared <= MAX_EXPANDED_BYTES) { "Архив Lampa превышает лимит распаковки" }
                }
                all += entry
            }

            var copiedTotal = 0L
            all.forEach { entry ->
                val relative = safeRelativePath(entry.name) ?: return@forEach
                val output = File(target, relative)
                require(output.canonicalPath.startsWith(target.canonicalPath + File.separator)) {
                    "Архив Lampa содержит недопустимый путь"
                }
                if (entry.isDirectory) {
                    if (!output.exists() && !output.mkdirs()) throw IOException("Не удалось создать каталог Lampa")
                } else {
                    output.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IOException("Не удалось создать каталог Lampa") }
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(output).use { file ->
                            val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                            var entryBytes = 0L
                            while (true) {
                                val bytes = input.read(buffer)
                                if (bytes < 0) break
                                entryBytes += bytes
                                copiedTotal += bytes
                                require(entryBytes <= entry.size && copiedTotal <= MAX_EXPANDED_BYTES) {
                                    "Данные в архиве Lampa превышают объявленные размеры"
                                }
                                file.write(buffer, 0, bytes)
                                if (entryBytes % (512 * 1024L) < bytes || entryBytes == entry.size) {
                                    onProgress(copiedTotal, totalDeclared)
                                }
                            }
                            require(entryBytes == entry.size) { "Файл архива Lampa повреждён" }
                            file.fd.sync()
                        }
                    }
                }
            }
        }
    }

    private fun validateBundle(root: File, update: LampaWebUpdateInfo) {
        val required = listOf("index.html", "app.js", "css/app.css", "plugins/modification.js", "build-info.json")
        required.forEach { path ->
            require(File(root, path).isFile) { "В обновлении Lampa отсутствует $path" }
        }
        val buildInfo = JSONObject(File(root, "build-info.json").readText(Charsets.UTF_8))
        require(buildInfo.optString("buildId") == update.buildId) {
            "Идентификатор распакованной сборки Lampa не совпадает с манифестом"
        }
    }

    private fun safeRelativePath(raw: String): String? {
        require(raw.isNotBlank() && !raw.startsWith('/') && '\\' !in raw && '\u0000' !in raw) {
            "Недопустимый путь в архиве Lampa"
        }
        val normalized = raw.removePrefix("./").trimEnd('/')
        if (normalized.isBlank() || normalized == ".") return null
        val parts = normalized.split('/')
        require(parts.none { it.isBlank() || it == "." || it == ".." }) {
            "Архив Lampa содержит traversal-путь"
        }
        return parts.joinToString(File.separator)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun isAllowed(uri: URI): Boolean =
        uri.scheme.equals("https", true) &&
            uri.host?.lowercase()?.let { it in allowedHosts } == true &&
            (uri.port == -1 || uri.port == 443) &&
            uri.rawUserInfo == null &&
            uri.rawFragment.isNullOrBlank()
}
