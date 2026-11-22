package app.jacket.mobile.lampac

import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Enumeration
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

data class LampacInstallProgress(
    val phase: String,
    val message: String,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val percent: Int? = null
)

/** Fixed, checksum-pinned upstream binaries. No "latest" URL is resolved at runtime. */
object LampacRuntimeArtifacts {
    const val LAMPAC_VERSION = "1.90.2"
    const val LAMPAC_BYTES = 480_375_787L
    const val LAMPAC_SHA256 = "717e760d03f7c2e338f6cfd65030a222a1e6497dab56a848dff3f773d19d0cc9"
    const val LAMPAC_URL =
        "https://github.com/lampac-nextgen/lampac/releases/download/1.90.2/lampac-nextgen.zip"

    const val DOTNET_VERSION = "10.0.15"
    const val DOTNET_SHA512 =
        "1d82a6822871c0e750d2d3d3b9b27657d24a2083eb999c2f78b39b0cd10862178cb2c6d21f941579cd521baf5738a78d3378e5754c5895a93d7546135eb40ec6"
    const val DOTNET_URL =
        "https://builds.dotnet.microsoft.com/dotnet/aspnetcore/Runtime/10.0.15/aspnetcore-runtime-10.0.15-linux-arm64.tar.gz"
}

private val lampacArtifactAllowedHosts = setOf(
    "github.com",
    "release-assets.githubusercontent.com",
    "objects.githubusercontent.com",
    "builds.dotnet.microsoft.com"
)

/** Accept only pinned HTTPS artifact hosts and the standard HTTPS port, including after redirects. */
internal fun isAllowedLampacArtifactUri(uri: URI): Boolean =
    uri.scheme.equals("https", true) &&
        uri.host?.lowercase()?.let { it in lampacArtifactAllowedHosts } == true &&
        (uri.port == -1 || uri.port == 443) &&
        uri.rawUserInfo == null &&
        uri.rawFragment.isNullOrBlank()

private data class LampacArtifactSpec(
    val fileName: String,
    val url: String,
    val digestAlgorithm: String,
    val expectedDigest: String,
    val expectedBytes: Long? = null,
    val maxBytes: Long
)

private object LampacArtifactDownloader {
    suspend fun download(
        spec: LampacArtifactSpec,
        directory: File,
        onProgress: (Long, Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Не удалось создать каталог загрузок")
        }
        require(directory.isDirectory) { "Каталог загрузок недоступен" }
        val target = File(directory, spec.fileName)
        if (target.isFile && target.length() <= spec.maxBytes &&
            digest(target, spec.digestAlgorithm).equals(spec.expectedDigest, true)
        ) {
            onProgress(target.length(), spec.expectedBytes ?: target.length())
            return@withContext target
        }

        val part = File(directory, spec.fileName + ".part")
        part.delete()
        var activeUrl = spec.url
        var redirects = 0
        var downloaded = 0L
        var total = spec.expectedBytes ?: -1L
        val digest = MessageDigest.getInstance(spec.digestAlgorithm)

        try {
            while (true) {
                context.ensureActive()
                val uri = URI(activeUrl)
                require(isAllowedLampacArtifactUri(uri)) {
                    "Загрузка заблокирована: недоверенный HTTPS-адрес"
                }

                val connection = (URL(activeUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 20_000
                    readTimeout = 90_000
                    instanceFollowRedirects = false
                    setRequestProperty("Accept", "application/octet-stream")
                    setRequestProperty("User-Agent", "Jacket-Android/2.8 (private Lampac installer)")
                }

                try {
                    val status = connection.responseCode
                    if (status in REDIRECT_CODES) {
                        if (++redirects > 5) throw IOException("Слишком много перенаправлений при загрузке")
                        val location = connection.getHeaderField("Location")
                            ?: throw IOException("Сервер загрузки не указал адрес перенаправления")
                        activeUrl = uri.resolve(location).toString()
                        continue
                    }
                    if (status != HttpURLConnection.HTTP_OK) {
                        throw IOException("Сервер загрузки вернул HTTP $status")
                    }

                    val length = connection.getHeaderFieldLong("Content-Length", -1L)
                    if (length > spec.maxBytes) throw IOException("Файл превышает разрешённый размер")
                    if (spec.expectedBytes != null && length >= 0 && length != spec.expectedBytes) {
                        throw IOException("Размер файла не совпадает с опубликованным размером")
                    }
                    if (length >= 0) total = length
                    if (total > spec.maxBytes) throw IOException("Файл превышает лимит размера")

                    BufferedInputStream(connection.inputStream).use { input ->
                        FileOutputStream(part).use { file ->
                            BufferedOutputStream(file, BUFFER_SIZE).use { output ->
                                val buffer = ByteArray(BUFFER_SIZE)
                                while (true) {
                                    context.ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    downloaded += count
                                    if (downloaded > spec.maxBytes ||
                                        (spec.expectedBytes != null && downloaded > spec.expectedBytes)
                                    ) {
                                        throw IOException("Файл загрузки больше ожидаемого размера")
                                    }
                                    digest.update(buffer, 0, count)
                                    output.write(buffer, 0, count)
                                    if (downloaded % PROGRESS_INTERVAL < count) {
                                        onProgress(downloaded, total)
                                    }
                                }
                                output.flush()
                                file.fd.sync()
                            }
                        }
                    }
                    break
                } finally {
                    connection.disconnect()
                }
            }

            if (spec.expectedBytes != null && downloaded != spec.expectedBytes) {
                throw IOException("Файл загрузился не полностью: $downloaded из ${spec.expectedBytes} байт")
            }
            val actualDigest = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            if (!actualDigest.equals(spec.expectedDigest, true)) {
                throw IOException("Контрольная сумма файла не совпала. Архив удалён.")
            }
            moveAtomically(part, target)
            onProgress(downloaded, total.takeIf { it > 0 } ?: downloaded)
            target
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            part.delete()
            throw cancelled
        } catch (error: Exception) {
            part.delete()
            throw error
        }
    }

    private fun digest(file: File, algorithm: String): String {
        val md = MessageDigest.getInstance(algorithm)
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                md.update(buffer, 0, count)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun moveAtomically(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: Exception) {
            if (!source.renameTo(target)) throw IOException("Не удалось сохранить загруженный файл")
        }
    }

    private const val BUFFER_SIZE = 64 * 1024
    private const val PROGRESS_INTERVAL = 512 * 1024L
    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
}

private object LampacZipInstaller {
    private const val MAX_FILES = 120_000
    private const val MAX_UNCOMPRESSED_BYTES = 3L * 1024L * 1024L * 1024L
    private const val MAX_ENTRY_BYTES = 512L * 1024L * 1024L
    private const val RESERVED_BYTES = 256L * 1024L * 1024L
    private const val MARKER_FILE = ".jacket-lampac-version"

    suspend fun install(
        archive: File,
        rootfs: File,
        onProgress: (Long, Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        require(archive.isFile) { "Архив Lampac не найден" }
        require(rootfs.isDirectory) { "Сначала установите Debian в PRoot" }

        val optDir = File(rootfs, "opt")
        if (!optDir.exists() && !optDir.mkdirs()) throw IOException("Не удалось создать /opt в Debian")
        val stage = File(optDir, ".lampac-stage")
        val target = File(optDir, "lampac")
        val backup = File(optDir, ".lampac-backup")
        stage.deleteRecursively()
        backup.deleteRecursively()

        try {
            ZipFile(archive).use { zip ->
                val entries = zip.entries()
                val all = ArrayList<ZipEntry>()
                var totalBytes = 0L
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    all += entry
                    if (!entry.isDirectory) {
                        require(entry.size >= 0 && entry.size <= MAX_ENTRY_BYTES) {
                            "В архиве Lampac есть файл с недопустимым размером"
                        }
                        totalBytes = Math.addExact(totalBytes, entry.size)
                    }
                    require(all.size <= MAX_FILES && totalBytes <= MAX_UNCOMPRESSED_BYTES) {
                        "Архив Lampac превышает ограничение распаковки"
                    }
                }

                val coreEntry = all.firstOrNull { !it.isDirectory && it.name.endsWith("/Core.dll") }
                    ?: all.firstOrNull { !it.isDirectory && it.name == "Core.dll" }
                    ?: throw IOException("В архиве Lampac не найден Core.dll")
                val prefix = coreEntry.name.removeSuffix("Core.dll")
                val requiredShared = prefix + "Shared.dll"
                require(all.any { !it.isDirectory && it.name == requiredShared }) {
                    "В архиве Lampac не найден Shared.dll рядом с Core.dll"
                }
                require(all.any { !it.isDirectory && it.name.startsWith(prefix + "runtimes/references/") }) {
                    "В архиве Lampac отсутствуют зависимости runtimes/references"
                }

                val freeSpace = StatFs(rootfs.absolutePath).availableBytes
                require(freeSpace >= totalBytes + RESERVED_BYTES) {
                    "Недостаточно места для установки Lampac. Нужно ещё примерно " +
                        formatBytes(totalBytes + RESERVED_BYTES - freeSpace)
                }

                if (!stage.mkdirs()) throw IOException("Не удалось создать временный каталог Lampac")
                var written = 0L
                all.forEach { entry ->
                    context.ensureActive()
                    if (!entry.name.startsWith(prefix)) return@forEach
                    val relative = entry.name.removePrefix(prefix)
                    if (relative.isBlank()) return@forEach
                    val safePath = safeRelativePath(relative)
                    val output = File(stage, safePath)
                    val rootPath = stage.canonicalPath + File.separator
                    require(output.canonicalPath.startsWith(rootPath)) {
                        "Архив Lampac содержит путь за пределами каталога установки"
                    }
                    if (entry.isDirectory) {
                        if (!output.exists() && !output.mkdirs()) {
                            throw IOException("Не удалось создать каталог Lampac: $safePath")
                        }
                    } else {
                        output.parentFile?.let {
                            if (!it.exists() && !it.mkdirs()) throw IOException("Не удалось создать каталог распаковки")
                        }
                        zip.getInputStream(entry).use { input ->
                            FileOutputStream(output).use { file ->
                                var lastReportedEntryBytes = 0L
                                val copied = copyArchiveEntryBounded(
                                    input = input,
                                    output = file,
                                    declaredSize = entry.size,
                                    maxBytes = MAX_ENTRY_BYTES
                                ) { entryBytes ->
                                    context.ensureActive()
                                    val aggregate = Math.addExact(written, entryBytes)
                                    if (entryBytes - lastReportedEntryBytes >= 512 * 1024L ||
                                        entryBytes == entry.size
                                    ) {
                                        lastReportedEntryBytes = entryBytes
                                        onProgress(aggregate, totalBytes)
                                    }
                                }
                                written = Math.addExact(written, copied)
                                require(written <= totalBytes) {
                                    "Фактический объём архива превышает объявленный размер"
                                }
                                file.fd.sync()
                            }
                        }
                    }
                }

                require(File(stage, "Core.dll").isFile && File(stage, "Shared.dll").isFile) {
                    "После распаковки не найдены Core.dll или Shared.dll"
                }
                require(File(stage, "runtimes/references").isDirectory) {
                    "После распаковки отсутствуют зависимости runtimes/references"
                }
                LampacEmbeddedConfig.writeInitConfig(stage)
                File(stage, MARKER_FILE).writeText(LampacRuntimeArtifacts.LAMPAC_VERSION)
                onProgress(totalBytes, totalBytes)
            }

            if (target.exists() && !target.renameTo(backup)) {
                throw IOException("Не удалось подготовить предыдущую установку Lampac к обновлению")
            }
            if (!stage.renameTo(target)) {
                if (backup.exists()) backup.renameTo(target)
                throw IOException("Не удалось активировать новую установку Lampac")
            }
            backup.deleteRecursively()
            target
        } catch (error: Exception) {
            stage.deleteRecursively()
            if (!target.exists() && backup.exists()) backup.renameTo(target)
            throw error
        }
    }

    private fun safeRelativePath(value: String): String {
        require(!value.startsWith('/') && '\\' !in value && '\u0000' !in value) {
            "Архив Lampac содержит недопустимый путь"
        }
        val segments = value.trimEnd('/').split('/')
        require(segments.none { it.isBlank() || it == "." || it == ".." }) {
            "Архив Lampac содержит traversal-путь"
        }
        return segments.joinToString(File.separator)
    }

    private fun formatBytes(value: Long): String =
        String.format("%.1f ГБ", value / (1024.0 * 1024.0 * 1024.0))
}

/**
 * Copies one ZIP entry while enforcing the uncompressed size declared in its
 * central directory. ZIP metadata is untrusted: both overruns and truncated
 * entries abort the staged install before the directory can be activated.
 */
internal fun copyArchiveEntryBounded(
    input: InputStream,
    output: OutputStream,
    declaredSize: Long,
    maxBytes: Long,
    onProgress: (Long) -> Unit = {}
): Long {
    require(declaredSize >= 0L && declaredSize <= maxBytes) {
        "Размер файла в архиве превышает ограничение"
    }
    require(maxBytes >= 0L) { "Некорректный лимит распаковки" }

    val buffer = ByteArray(64 * 1024)
    var copied = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) continue
        if (count.toLong() > declaredSize - copied || count.toLong() > maxBytes - copied) {
            throw IOException("Фактический размер файла в архиве превышает объявленный")
        }
        output.write(buffer, 0, count)
        copied += count.toLong()
        onProgress(copied)
    }
    if (copied != declaredSize) {
        throw IOException("Файл в архиве повреждён: распаковано $copied из $declaredSize байт")
    }
    return copied
}

/** True only when the PRoot Debian root contains the required base system tools. */
internal fun isDebianRootfsReady(root: File): Boolean =
    root.isDirectory &&
        File(root, "etc/os-release").isFile &&
        File(root, "bin/sh").exists() &&
        File(root, "usr/bin/apt-get").isFile

/**
 * Installs a private ARM64 Debian rootfs, ASP.NET Core 10 and a checksum-pinned
 * Lampac release. It intentionally does not call Lampac's systemd installer.
 */
class LampacRuntimeInstaller(context: android.content.Context) {
    private val appContext = context.applicationContext
    private val host = JacketProotHost(appContext)
    private val commands = LampacProotCommands(host)
    private val commandRunner = LampacProotCommandRunner(commands)
    private val downloads = File(appContext.filesDir, "lampac/downloads")

    fun isInstalled(): Boolean {
        val root = host.debianRootfsDir
        return isDebianRootfsReady(root) &&
            File(root, "opt/dotnet/dotnet").isFile &&
            File(root, "opt/lampac/Core.dll").isFile &&
            File(root, "opt/lampac/Shared.dll").isFile &&
            File(root, "opt/lampac/init.conf").isFile
    }

    fun availableBytes(): Long = StatFs(appContext.filesDir.absolutePath).availableBytes

    suspend fun install(
        onProgress: (LampacInstallProgress) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        val send = { phase: String, message: String, done: Long, total: Long ->
            onProgress(
                LampacInstallProgress(
                    phase = phase,
                    message = message,
                    downloadedBytes = done,
                    totalBytes = total,
                    percent = if (total > 0) ((done * 100L) / total).toInt().coerceIn(0, 100) else null
                )
            )
        }

        check(LampacProotBootstrap.isSupportedOnThisDevice()) {
            "Встроенный Lampac пока доступен только на ARM64. Удалённый сервер можно подключить по HTTPS."
        }
        val requiredBeforeDownload = LampacRuntimeArtifacts.LAMPAC_BYTES +
            1024L * 1024L * 1024L
        check(availableBytes() >= requiredBeforeDownload) {
            "Для установки Lampac нужно не менее 1,5 ГБ свободного места во внутренней памяти телефона."
        }

        send("prepare", "Подготавливаем PRoot…", 0, 0)
        val prepared = LampacProotBootstrap.prepare(host)
        if (!isDebianRootfsReady(prepared.debianRootfsDir)) {
            val containerDir = prepared.debianRootfsDir.parentFile
            if (containerDir?.exists() == true) {
                send("debian-repair", "Обнаружена незавершённая установка Debian. Удаляем повреждённый контейнер…", 0, 0)
                val removeCode = commandRunner.execute(listOf("remove", "debian")) { line ->
                    send("debian-repair", line.take(220), 0, 0)
                }
                check(removeCode == 0 || !containerDir.exists()) {
                    "Не удалось безопасно очистить незавершённый контейнер Debian (код $removeCode). Повторите установку после освобождения памяти."
                }
            }

            send("debian", "Загружаем и устанавливаем Debian ARM64…", 0, 0)
            val result = commandRunner.installDebian { line ->
                send("debian", line.take(220), 0, 0)
            }
            check(result == 0 && isDebianRootfsReady(prepared.debianRootfsDir)) {
                "Не удалось установить полноценный Debian ARM64 в PRoot (код $result)"
            }
        }

        send("packages", "Подготавливаем системные библиотеки Debian…", 0, 0)
        val apt = commandRunner.runAsRoot(
            "export DEBIAN_FRONTEND=noninteractive && " +
                "apt-get update && " +
                "apt-get install -y --no-install-recommends ca-certificates curl libicu-dev libssl-dev zlib1g tar && " +
                "apt-get clean && rm -rf /var/lib/apt/lists/*"
        ) { line -> send("packages", line.take(220), 0, 0) }
        check(apt == 0) { "Не удалось установить системные библиотеки Debian (код $apt)" }

        send("dotnet-download", "Загружаем ASP.NET Core Runtime ${LampacRuntimeArtifacts.DOTNET_VERSION} ARM64…", 0, 0)
        val dotnetArchive = LampacArtifactDownloader.download(
            LampacArtifactSpec(
                fileName = "aspnetcore-runtime-${LampacRuntimeArtifacts.DOTNET_VERSION}-linux-arm64.tar.gz",
                url = LampacRuntimeArtifacts.DOTNET_URL,
                digestAlgorithm = "SHA-512",
                expectedDigest = LampacRuntimeArtifacts.DOTNET_SHA512,
                maxBytes = 160L * 1024L * 1024L
            ),
            downloads
        ) { done, total -> send("dotnet-download", "Загрузка .NET: ${formatBytes(done)}", done, total) }

        send("dotnet-install", "Устанавливаем .NET в Debian…", 0, 0)
        val dotnetPath = shellQuote(dotnetArchive.absolutePath)
        val dotnetInstall = commandRunner.runAsRoot(
            buildDotnetInstallCommand(dotnetArchive.absolutePath)
        ) { line -> send("dotnet-install", line.take(220), 0, 0) }
        check(dotnetInstall == 0) { "Не удалось установить .NET Runtime (код $dotnetInstall)" }
        dotnetArchive.delete()

        send("lampac-download", "Загружаем Lampac NextGen ${LampacRuntimeArtifacts.LAMPAC_VERSION} (~480 МБ)…", 0, 0)
        val lampacArchive = LampacArtifactDownloader.download(
            LampacArtifactSpec(
                fileName = "lampac-nextgen-${LampacRuntimeArtifacts.LAMPAC_VERSION}.zip",
                url = LampacRuntimeArtifacts.LAMPAC_URL,
                digestAlgorithm = "SHA-256",
                expectedDigest = LampacRuntimeArtifacts.LAMPAC_SHA256,
                expectedBytes = LampacRuntimeArtifacts.LAMPAC_BYTES,
                maxBytes = LampacRuntimeArtifacts.LAMPAC_BYTES
            ),
            downloads
        ) { done, total -> send("lampac-download", "Загрузка Lampac: ${formatBytes(done)}", done, total) }

        send("lampac-extract", "Проверяем архив и устанавливаем Lampac…", 0, 0)
        val serverDir = LampacZipInstaller.install(lampacArchive, prepared.debianRootfsDir) { done, total ->
            send("lampac-extract", "Распаковка Lampac: ${formatBytes(done)}", done, total)
        }
        lampacArchive.delete()

        check(File(serverDir, "Core.dll").isFile && File(prepared.debianRootfsDir, "opt/dotnet/dotnet").isFile) {
            "Установка Lampac завершилась без обязательных файлов"
        }
        send("installed", "Lampac установлен. Сервер пока остановлен.", 1, 1)
        serverDir
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun formatBytes(value: Long): String =
        String.format("%.0f МБ", value / (1024.0 * 1024.0))
}

/**
 * Build a recoverable .NET runtime swap. Validate the staged runtime before
 * moving the current runtime, and restore the previous directory on a failed
 * activation or an interrupted swap.
 */
internal fun buildDotnetInstallCommand(archivePath: String): String {
    require(archivePath.isNotBlank() && '\u0000' !in archivePath) {
        "Некорректный путь к архиву .NET"
    }
    val quotedArchive = "'" + archivePath.replace("'", "'\\''") + "'"
    return """
        set -e
        if [ -d /opt/dotnet.previous ]; then
          if [ -x /opt/dotnet/dotnet ] && /opt/dotnet/dotnet --info >/dev/null 2>&1; then
            rm -rf /opt/dotnet.previous
          else
            rm -rf /opt/dotnet
            mv /opt/dotnet.previous /opt/dotnet
          fi
        fi
        rm -rf /opt/dotnet.stage
        mkdir -p /opt/dotnet.stage
        tar -xzf $quotedArchive -C /opt/dotnet.stage
        /opt/dotnet.stage/dotnet --info
        if [ -d /opt/dotnet ]; then
          rm -rf /opt/dotnet.previous
          mv /opt/dotnet /opt/dotnet.previous
        fi
        if ! mv /opt/dotnet.stage /opt/dotnet; then
          if [ -d /opt/dotnet.previous ]; then mv /opt/dotnet.previous /opt/dotnet; fi
          exit 1
        fi
        if ! /opt/dotnet/dotnet --info; then
          rm -rf /opt/dotnet
          if [ -d /opt/dotnet.previous ]; then mv /opt/dotnet.previous /opt/dotnet; fi
          exit 1
        fi
        rm -rf /opt/dotnet.previous
    """.trimIndent()
}
