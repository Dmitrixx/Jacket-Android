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
import java.security.SecureRandom
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
    const val DOTNET_ARM32_SHA512 =
        "487a707e8e16080424e239fb8eeabccaa9d91a520197322d28de8d1dc15ed21edcce2de15f77ee8fa776e27f14708afdc5fa2dfabc75cef459872197de60a589"
    const val DOTNET_ARM32_URL =
        "https://builds.dotnet.microsoft.com/dotnet/aspnetcore/Runtime/10.0.15/aspnetcore-runtime-10.0.15-linux-arm.tar.gz"
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
                    setRequestProperty("User-Agent", "Lampa-Android/3.0 (private Lampac installer)")
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

/**
 * Converts every ZIP entry to a safe relative destination.
 *
 * Lampac release archives may contain files next to the directory that contains
 * Core.dll. Strip the common application directory only for entries beneath it;
 * entries outside it remain part of the installed full distribution.
 */
internal fun lampacArchiveRelativePath(entryName: String, corePrefix: String): String? {
    val relative = if (corePrefix.isNotEmpty() && entryName.startsWith(corePrefix)) {
        entryName.removePrefix(corePrefix)
    } else {
        entryName
    }
    if (relative.isBlank()) return null
    require(!relative.startsWith('/') && '\\' !in relative && '\u0000' !in relative) {
        "Архив Lampac содержит недопустимый путь"
    }
    val segments = relative.trimEnd('/').split('/')
    require(segments.none { it.isBlank() || it == "." || it == ".." }) {
        "Архив Lampac содержит traversal-путь"
    }
    return segments.joinToString(File.separator)
}

private object LampacZipInstaller {
    private const val MAX_FILES = 120_000
    private const val MAX_UNCOMPRESSED_BYTES = 10L * 1024L * 1024L * 1024L
    private const val MAX_ENTRY_BYTES = 512L * 1024L * 1024L
    private const val RESERVED_BYTES = 256L * 1024L * 1024L
    private const val MARKER_FILE = ".lampa-lampac-version"

    suspend fun install(
        archive: File,
        rootfs: File,
        version: String = LampacRuntimeArtifacts.LAMPAC_VERSION,
        retainBackup: Boolean = false,
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
        // A Lampac runtime refresh must not silently discard the user's provider
        // credentials, priorities, JacRed adapter configuration or custom sections.
        val previousInitConfig = File(target, "init.conf").takeIf { it.isFile }?.let { file ->
            require(file.length() in 1..LampacEmbeddedConfig.MAX_INIT_CONFIG_BYTES.toLong()) {
                "Существующий init.conf Lampac пуст или превышает лимит 2 МиБ; обновление отменено для защиты настроек."
            }
            file.readText(Charsets.UTF_8)
        }
        stage.deleteRecursively()
        if (backup.exists()) {
            require(!retainBackup) {
                "Найдена неподтверждённая резервная копия Lampac. Сначала восстановите или подтвердите предыдущее обновление."
            }
            backup.deleteRecursively()
        }

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
                val requiredPlaywrightFiles = listOf(
                    prefix + ".playwright/package/index.js",
                    prefix + ".playwright/package/cli.js"
                )
                requiredPlaywrightFiles.forEach { required ->
                    require(all.any { !it.isDirectory && it.name == required }) {
                        "В полном архиве Lampac отсутствует файл Playwright: ${required.removePrefix(prefix)}"
                    }
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
                    // Strip the common application folder only where it actually
                    // prefixes the entry. Keep every other archive entry too.
                    val safePath = lampacArchiveRelativePath(entry.name, prefix) ?: return@forEach
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
                require(isLampacPlaywrightPackageReady(stage)) {
                    "После распаковки отсутствует JavaScript-драйвер Playwright"
                }
                if (previousInitConfig == null) {
                    LampacEmbeddedConfig.writeInitConfig(stage)
                } else {
                    LampacEmbeddedConfig.importInitConfig(stage, previousInitConfig)
                }
                File(stage, MARKER_FILE).writeText(version)
                onProgress(totalBytes, totalBytes)
            }

            // Keep private runtime data and user-managed overrides across a
            // server release refresh. Official code/modules are replaced by the
            // new archive; only data, local databases, credentials and overrides
            // are merged from the previous tree.
            preserveUserRuntimeData(target, stage)

            if (target.exists() && !target.renameTo(backup)) {
                throw IOException("Не удалось подготовить предыдущую установку Lampac к обновлению")
            }
            if (!stage.renameTo(target)) {
                if (backup.exists()) backup.renameTo(target)
                throw IOException("Не удалось активировать новую установку Lampac")
            }
            if (!retainBackup) backup.deleteRecursively()
            target
        } catch (error: Exception) {
            stage.deleteRecursively()
            if (!target.exists() && backup.exists()) backup.renameTo(target)
            throw error
        }
    }

    private fun preserveUserRuntimeData(previous: File, staged: File) {
        if (!previous.isDirectory) return

        val preservedPaths = listOf(
            "data",
            "database",
            "cache",
            "logs",
            "mods",
            "torrserver",
            "TorrServer",
            "wwwroot",
            "plugins/override",
            "module/NextHUB/override",
            "module/Catalog/override"
        )
        preservedPaths.forEach { relative ->
            val source = File(previous, relative)
            if (!source.exists()) return@forEach
            val destination = File(staged, relative)
            destination.parentFile?.let { if (!it.exists()) it.mkdirs() }
            if (relative == "mods" && source.isDirectory) {
                // Preserve user-added modules without copying old official binaries
                // over the newer release's module implementations.
                mergeMissingUserFiles(source, destination, source.canonicalFile)
            } else if (source.isDirectory) {
                source.copyRecursively(destination, overwrite = true)
            } else {
                source.copyTo(destination, overwrite = true)
            }
        }

        // Preserve override directories for any installed module, not only
        // the currently known provider modules.
        val previousModules = File(previous, "module")
        if (previousModules.isDirectory) {
            previousModules.listFiles()?.filter { it.isDirectory }?.forEach { module ->
                val override = File(module, "override")
                if (override.isDirectory) {
                    val destination = File(staged, "module/${module.name}/override")
                    destination.parentFile?.let { if (!it.exists()) it.mkdirs() }
                    override.copyRecursively(destination, overwrite = true)
                }
            }
        }

        listOf("users.json", "passwd", "current.conf", "excludes.conf").forEach { name ->
            val source = File(previous, name)
            if (source.isFile) source.copyTo(File(staged, name), overwrite = true)
        }
    }

    private fun mergeMissingUserFiles(source: File, destination: File, sourceRoot: File) {
        require(destination.canonicalPath.startsWith(destination.parentFile!!.canonicalPath + File.separator) ||
            destination.canonicalFile == destination.parentFile!!.canonicalFile) {
            "Недопустимый путь пользовательского модуля"
        }
        if (!destination.exists() && !destination.mkdirs()) {
            throw IOException("Не удалось создать каталог пользовательских модулей")
        }

        source.walkTopDown().forEach { item ->
            val relative = item.relativeTo(sourceRoot).path
            val target = if (relative.isBlank()) destination else File(destination, relative)
            require(target.canonicalPath.startsWith(destination.canonicalPath + File.separator) ||
                target.canonicalFile == destination.canonicalFile) {
                "Путь пользовательского модуля выходит за пределы каталога"
            }
            if (item.isDirectory) {
                if (!target.exists() && !target.mkdirs()) {
                    throw IOException("Не удалось восстановить каталог пользовательского модуля")
                }
            } else if (!target.exists()) {
                target.parentFile?.let {
                    if (!it.exists() && !it.mkdirs()) {
                        throw IOException("Не удалось восстановить каталог пользовательского модуля")
                    }
                }
                item.copyTo(target, overwrite = false)
            }
        }
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

/**
 * A complete install is only accepted when it carries the new Lampa marker.
 * Older Jacket/Lampac installs are intentionally reinstalled once so the
 * official full archive and all module files replace any previously filtered
 * payload. The updater still carries forward init.conf and provider settings.
 */
internal fun isLampacRuntimePayloadReady(root: File, expectedVersion: String): Boolean {
    val lampac = File(root, "opt/lampac")
    val marker = File(lampac, ".lampa-lampac-version")
    val markerVersion = runCatching { marker.readText(Charsets.UTF_8).trim() }.getOrNull()
    return File(root, "opt/dotnet/dotnet").isFile &&
        File(lampac, "Core.dll").isFile &&
        File(lampac, "Shared.dll").isFile &&
        File(lampac, "init.conf").isFile &&
        isLampacPlaywrightPackageReady(lampac) &&
        markerVersion == expectedVersion
}

internal fun isLampacNodeReady(root: File): Boolean =
    File(root, "usr/bin/node").canExecute()

internal fun isCompleteLampacRuntime(root: File, expectedVersion: String): Boolean =
    isLampacRuntimePayloadReady(root, expectedVersion) && isLampacNodeReady(root)

/**
 * Debian Forky runs under PRoot, not as Android's init.
 *
 * systemd 260 also uses statx(STATX_MNT_ID), which requires a newer kernel
 * than the Android 4.19 kernel used by the POCO F3 (alioth). On that kernel,
 * systemd-sysusers reports the misleading "Failed to read 'basic.conf'"
 * message even though the packaged config exists.
 *
 * Maintainer scripts invoke service helpers by absolute path, so these tools
 * must be diverted before dpkg configures packages. They are not needed:
 * Lampac is started by Android, not by systemd.
 *
 * Do NOT disable systemd-sysusers with a success-only stub. Debian packages
 * such as dbus-system-bus-common rely on it to create required service users
 * (notably messagebus). systemd 260's implementation needs STATX_MNT_ID,
 * unavailable on this Android kernel, so we install Debian's opensysusers
 * compatibility provider and forward /usr/bin/systemd-sysusers to its diverted
 * executable. policy-rc.d exits 101 so invoke-rc.d skips service starts.
 */
private const val LAMPAC_OFFLINE_DPKG_ENV =
    "export DEBIAN_FRONTEND=noninteractive DPKG_ROOT=/ SYSTEMD_OFFLINE=1 SYSTEMD_IN_CHROOT=1 LANG=C LC_ALL=C && "

internal fun buildLampacOfflineDpkgCommand(command: String): String {
    require(command.isNotBlank() && '\u0000' !in command) {
        "Команда Debian пуста или содержит нулевой байт"
    }
    return LAMPAC_OFFLINE_DPKG_ENV +
        "set -e; " +
        "mkdir -p /var/lib/lampa-disabled-init /usr/bin /usr/sbin; " +
        "rm -f /var/lib/dpkg/lock /var/lib/dpkg/lock-frontend /var/lib/apt/lists/lock /var/cache/apt/archives/lock; " +
        "lampa_divert_stub() { " +
        "target=\$1; code=\$2; " +
        "tag=\$(printf '%s' \"\$target\" | tr '/' '_'); " +
        "divert=\"/var/lib/lampa-disabled-init/\${tag}\"; " +
        "mkdir -p \"\$(dirname \"\$target\")\" /var/lib/lampa-disabled-init; " +
        "if ! dpkg-divert --list \"\$target\" 2>/dev/null | grep -q 'local diversion of'; then " +
        "dpkg-divert --local --rename --divert \"\$divert\" --add \"\$target\"; " +
        "fi; " +
        "if [ -L \"\$target\" ]; then rm -f \"\$target\"; " +
        "elif [ -f \"\$target\" ] && ! head -n 5 \"\$target\" | grep -q 'lampa-proot-stub'; then " +
        "if [ ! -e \"\$divert\" ]; then mv \"\$target\" \"\$divert\"; else rm -f \"\$target\"; fi; " +
        "fi; " +
        "if [ ! -f \"\$target\" ] || ! head -n 5 \"\$target\" | grep -q 'lampa-proot-stub'; then " +
        "printf '%s\\n' '#!/bin/sh' '# lampa-proot-stub' \"exit \$code\" > \"\$target\"; " +
        "chmod 755 \"\$target\"; " +
        "fi; " +
        "}; " +
        "lampa_divert_stub /usr/bin/systemctl 0; " +
        "lampa_divert_stub /usr/bin/systemd-machine-id-setup 0; " +
        "lampa_divert_stub /usr/bin/systemd-sysusers 127; " +
        "printf '%s\\n' '#!/bin/sh' 'exec /var/lib/lampa-disabled-init/_usr_bin_systemd-sysusers \"\$@\"' > /usr/bin/systemd-sysusers; " +
        "chmod 755 /usr/bin/systemd-sysusers; " +
        "lampa_divert_stub /usr/bin/deb-systemd-invoke 0; " +
        "lampa_divert_stub /usr/bin/deb-systemd-helper 0; " +
        "lampa_divert_stub /usr/sbin/policy-rc.d 101; " +
        // This is an Android-hosted Debian userspace, not a bootable Debian system.
        // Prevent an inherited systemd-sysv selection from pulling the full init manager.
        // /tmp is bind-mounted to Android app cache and may carry setgid mode 2777.
        // Build the tiny init-provider package on the Debian rootfs instead.
        "umask 022; mkdir -p /etc/apt/preferences.d /root/lampa-proot-init-provider/DEBIAN; " +
        "printf '%s\\n' 'Package: systemd systemd-sysv' 'Pin: version *' 'Pin-Priority: -1' > /etc/apt/preferences.d/99-lampac-no-systemd; " +
        // This rootfs is never booted: Android owns PID 1. Register a minimal local
        // provider for Debian's virtual init package so APT cannot select systemd-sysv.
        "printf '%s\\n' 'Package: lampa-proot-init-provider' 'Version: 1.0' 'Architecture: all' 'Provides: init' 'Conflicts: systemd-sysv, sysvinit-core' 'Description: Virtual init provider for Android-managed PRoot' > /root/lampa-proot-init-provider/DEBIAN/control; " +
        // Explicitly clear inherited special mode bits as PRoot can expose setgid directories.
        "chmod 0755 /root/lampa-proot-init-provider/DEBIAN; chmod g-s /root/lampa-proot-init-provider/DEBIAN; " +
        "chmod 0644 /root/lampa-proot-init-provider/DEBIAN/control; chmod g-s /root/lampa-proot-init-provider/DEBIAN/control; " +
        "test \"$(stat -c '%a' /root/lampa-proot-init-provider/DEBIAN)\" = 755; " +
        "dpkg-deb --build /root/lampa-proot-init-provider /root/lampa-proot-init-provider.deb >/dev/null; " +
        "dpkg --purge --force-all systemd-sysv sysvinit-core >/dev/null 2>&1 || true; " +
        "dpkg -i /root/lampa-proot-init-provider.deb; " +
        "rm -rf /root/lampa-proot-init-provider /root/lampa-proot-init-provider.deb; " +
        "set +e; { " + command +
        "; } > /tmp/lampa-dpkg-install.log 2>&1; status=\$?; " +
        "if [ \"\$status\" -eq 0 ]; then " +
        "echo '--- apt/dpkg output (last 120 lines) ---'; tail -n 120 /tmp/lampa-dpkg-install.log; " +
        "rm -f /tmp/lampa-dpkg-install.log; else " +
        "echo '--- failed Debian package stage ---'; " +
        "grep '^__LAMPAC_STAGE=' /tmp/lampa-dpkg-install.log | tail -n 1 || true; " +
        "echo '--- apt/dpkg errors and context ---'; " +
        "grep -Ein -B 3 -A 5 '(^E:|^Err:|dpkg: error|error processing archive|sub-process .* returned|failed to|dependency problems)' /tmp/lampa-dpkg-install.log | tail -n 70 || true; " +
        "echo '--- apt/dpkg output (last 120 lines) ---'; tail -n 120 /tmp/lampa-dpkg-install.log; " +
        "echo '--- apt/dpkg failure log preserved for Android diagnostics ---'; " +
        "apt-get clean >/dev/null 2>&1 || true; " +
        "rm -rf /var/lib/apt/lists/* /var/cache/apt/archives/*.deb; " +
        "exit \"\$status\"; fi"
}

/** Ensure the private Debian rootfs has a persistent, standard 32-hex machine-id. */
internal fun ensureLampacDebianMachineId(rootfs: File) {
    require(rootfs.isDirectory) { "Debian rootfs недоступен" }
    val root = rootfs.canonicalFile
    val etc = File(root, "etc")
    if (!etc.exists() && !etc.mkdirs()) throw IOException("Не удалось создать /etc в Debian")
    require(etc.canonicalFile == etc) { "Каталог /etc в Debian указывает за пределы rootfs" }

    val machineIdFile = File(etc, "machine-id")
    val dbusDirectory = File(root, "var/lib/dbus")
    val dbusMachineId = File(dbusDirectory, "machine-id")

    fun readRootRelativeFile(file: File): String? = runCatching {
        val path = file.toPath()
        val resolved = if (Files.isSymbolicLink(path)) {
            val linkTarget = Files.readSymbolicLink(path)
            if (linkTarget.isAbsolute) {
                File(root, linkTarget.toString().trimStart(File.separatorChar))
            } else {
                File(file.parentFile, linkTarget.toString())
            }
        } else {
            file
        }.canonicalFile
        resolved.takeIf { it.toPath().startsWith(root.toPath()) && it.isFile }
            ?.readText(Charsets.UTF_8)?.trim()
    }.getOrNull()

    val validId = listOf(machineIdFile, dbusMachineId)
        .asSequence()
        .mapNotNull(::readRootRelativeFile)
        .firstOrNull { it.matches(Regex("(?i)^[0-9a-f]{32}$")) }

    val machineId = validId ?: ByteArray(16).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    if (Files.isSymbolicLink(machineIdFile.toPath())) Files.deleteIfExists(machineIdFile.toPath())
    if (validId == null || readRootRelativeFile(machineIdFile) != machineId) {
        machineIdFile.writeText("$machineId\n", Charsets.UTF_8)
    }

    if (!dbusDirectory.exists() && !dbusDirectory.mkdirs()) {
        throw IOException("Не удалось создать /var/lib/dbus в Debian")
    }
    val dbusPath = dbusMachineId.toPath()
    val correctDbusLink = Files.isSymbolicLink(dbusPath) &&
        Files.readSymbolicLink(dbusPath).toString() == "/etc/machine-id"
    if (!correctDbusLink) {
        Files.deleteIfExists(dbusPath)
        Files.createSymbolicLink(dbusPath, java.nio.file.Paths.get("/etc/machine-id"))
    }
}

/**
 * The PRoot OCI downloader keeps verified Debian image layers in var/lib/pr/dlcache
 * after the rootfs has been extracted. Those files are only a re-download cache:
 * once Debian's rootfs is ready, the installed filesystem no longer depends on them.
 *
 * Delete only direct regular files, matching pr-cli clear-cache semantics. Do not
 * follow symlinks or recurse into unexpected directories in this app-private path.
 */
internal fun clearLampacProotDownloadCache(prefixDir: File): Long {
    if (!prefixDir.isDirectory) return 0L
    val canonicalPrefix = runCatching { prefixDir.canonicalFile.toPath() }.getOrNull() ?: return 0L
    val cacheDir = File(prefixDir, "var/lib/pr/dlcache")
    val cachePath = cacheDir.toPath()
    if (!Files.isDirectory(cachePath, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return 0L

    val canonicalCache = runCatching { cacheDir.canonicalFile.toPath() }.getOrNull() ?: return 0L
    if (!canonicalCache.startsWith(canonicalPrefix) || canonicalCache == canonicalPrefix) return 0L

    var reclaimedBytes = 0L
    return try {
        Files.newDirectoryStream(cachePath).use { entries ->
            for (entry in entries) {
                if (!Files.isRegularFile(entry, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue
                val size = runCatching { Files.size(entry) }.getOrDefault(0L)
                val deleted = runCatching { Files.deleteIfExists(entry) }.getOrDefault(false)
                if (deleted) reclaimedBytes += size
            }
            reclaimedBytes
        }
    } catch (_: IOException) {
        reclaimedBytes
    }
}

internal fun buildLampacSystemPackagesInstallCommand(
    architecture: LampacRuntimeArchitecture = LampacRuntimeArchitecture.ARM64
): String {
    // Debian Forky publishes libicu78; ARM32 uses Trixie, whose runtime ABI is libicu76.
    val icuPackage = when (architecture) {
        LampacRuntimeArchitecture.ARM64 -> "libicu78"
        LampacRuntimeArchitecture.ARM32 -> "libicu76"
    }
    val browserInstall = when (architecture) {
        LampacRuntimeArchitecture.ARM64 ->
            "curl -fSL --retry 3 https://dl.google.com/linux/direct/google-chrome-stable_current_arm64.deb -o /tmp/google-chrome-stable.deb && " +
                "test \"$(dpkg-deb -f /tmp/google-chrome-stable.deb Architecture)\" = arm64 && " +
                "apt-get install -y --no-install-recommends /tmp/google-chrome-stable.deb && " +
                "ln -sf /usr/bin/google-chrome-stable /usr/bin/chromium && " +
                "rm -f /tmp/google-chrome-stable.deb && "
        LampacRuntimeArchitecture.ARM32 ->
            "apt-get install -y --no-install-recommends chromium && " +
                "ln -sf /usr/bin/chromium /usr/bin/google-chrome-stable && "
    }

    return buildLampacOfflineDpkgCommand(
        "echo '__LAMPAC_STAGE=apt-update' && apt-get update && " +
            // The init metapackage defaults to systemd-sysv. PRoot is not PID 1,
            // so explicitly provide the init alternative without systemd.
            "echo '__LAMPAC_STAGE=bootstrap-packages' && apt-get install -y --no-install-recommends opensysusers && " +
            "echo '__LAMPAC_STAGE=dpkg-recovery' && dpkg --root=/ --configure -a && " +
            "echo '__LAMPAC_STAGE=runtime-packages' && apt-get install -y --no-install-recommends ca-certificates curl fontconfig $icuPackage zlib1g tar nodejs " +
            "ffmpeg imagemagick xvfb libnspr4 " +
            "gstreamer1.0-libav gstreamer1.0-plugins-bad gstreamer1.0-plugins-base gstreamer1.0-plugins-base-apps " +
            "gstreamer1.0-plugins-good gstreamer1.0-plugins-ugly gstreamer1.0-tools " +
            "libgstreamer-plugins-base1.0-0 libgstreamer1.0-0 && " +
            "echo '__LAMPAC_STAGE=browser-package' && " + browserInstall +
            "test -x /usr/bin/node && node --version && " +
            "echo '__LAMPAC_STAGE=runtime-validation' && " +
            "test -x /usr/bin/chromium && chromium --version && ffmpeg -version && gst-inspect-1.0 --version && " +
            "echo '__LAMPAC_STAGE=apt-cleanup' && apt-get clean && rm -rf /var/lib/apt/lists/*"
    )
}

/** A lightweight repair for older installs: it adds only the Debian-architecture Node.js package. */
internal fun buildLampacNodeInstallCommand(): String =
    buildLampacOfflineDpkgCommand(
        "apt-get update && " +
            "apt-get install -y --no-install-recommends opensysusers && " +
            "dpkg --root=/ --configure -a && " +
            "apt-get install -y --no-install-recommends nodejs && " +
            "test -x /usr/bin/node && node --version && " +
            "apt-get clean && rm -rf /var/lib/apt/lists/*"
    )

/**
 * Playwright's JS package is architecture-independent. The runtime uses
 * Debian's architecture-matched Node.js through PLAYWRIGHT_NODEJS_PATH rather than assuming
 * that a platform-specific executable in the release archive matches Android.
 */
internal fun isLampacPlaywrightPackageReady(lampacDirectory: File): Boolean {
    val packageDirectory = File(lampacDirectory, ".playwright/package")
    // index.js is checked by Lampac's startup guard; cli.js is the actual
    // Playwright driver entrypoint launched by Microsoft.Playwright.
    return File(packageDirectory, "index.js").isFile &&
        File(packageDirectory, "cli.js").isFile
}


/**
 * Finds the previous app's Debian alias config before a new OCI runtime is
 * created. The old legacy and old OCI locations are both checked, but neither
 * rootfs is removed automatically.
 */
internal fun readLegacyLampacInitConfig(prefixDir: File): String? {
    val candidates = listOf(
        File(
            prefixDir,
            "var/lib/pr/containers/$LAMPAC_DEBIAN_ALIAS/rootfs/opt/lampac/init.conf"
        ),
        File(prefixDir, "var/lib/pr/installed-rootfs/debian/opt/lampac/init.conf"),
        File(prefixDir, "var/lib/pr/containers/debian/rootfs/opt/lampac/init.conf")
    )
    val source = candidates.firstOrNull { it.isFile } ?: return null
    require(source.length() in 1..LampacEmbeddedConfig.MAX_INIT_CONFIG_BYTES.toLong()) {
        "Существующий init.conf Lampac пуст или превышает лимит 2 МиБ; миграция настроек отменена."
    }
    return source.readText(Charsets.UTF_8)
}

/** True only when the PRoot Debian root contains the required base system tools. */
internal fun isDebianRootfsReady(
    root: File,
    architecture: LampacRuntimeArchitecture? = null
): Boolean {
    if (!root.isDirectory) return false
    val osRelease = File(root, "etc/os-release")
    if (!osRelease.isFile) return false

    val osFields = runCatching { osRelease.readLines() }.getOrNull() ?: return false
    fun field(name: String): String? = osFields
        .firstOrNull { it.startsWith("$name=") }
        ?.substringAfter('=')
        ?.trim()
        ?.removeSurrounding("\"")
        ?.removeSurrounding("'")

    if (!field("ID").equals("debian", ignoreCase = true)) return false
    val expectedCodename = when (architecture) {
        LampacRuntimeArchitecture.ARM64 -> "forky"
        LampacRuntimeArchitecture.ARM32 -> "trixie"
        null -> null
    }
    if (expectedCodename != null &&
        !field("VERSION_CODENAME").equals(expectedCodename, ignoreCase = true)
    ) return false

    return File(root, "bin/sh").canExecute() &&
        File(root, "usr/bin/apt-get").canExecute()
}

/**
 * Installs a private ABI-matched Debian rootfs, ASP.NET Core 10 and a checksum-pinned
 * Lampac release. It intentionally does not call Lampac's systemd installer.
 */
class LampacRuntimeInstaller(context: android.content.Context) {
    private val appContext = context.applicationContext
    private val host = LampaProotHost(appContext)
    private val commands = LampacProotCommands(host)
    private val commandRunner = LampacProotCommandRunner(commands)
    private val downloads = File(appContext.filesDir, "lampac/downloads")

    fun installedVersion(): String? {
        val opt = File(host.debianRootfsDir, "opt")
        val target = File(opt, "lampac")
        val backup = File(opt, ".lampac-backup")
        // Recover the previous server if the process died between the two
        // directory renames that activate an update.
        if (!target.exists() && backup.isDirectory) backup.renameTo(target)
        val marker = File(host.debianRootfsDir, "opt/lampac/.lampa-lampac-version")
        val version = runCatching { marker.readText(Charsets.UTF_8).trim() }.getOrNull()
        return version?.takeIf { it.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")) }
    }

    fun isInstalled(): Boolean {
        val root = host.debianRootfsDir
        val version = installedVersion()
        val architecture = LampacProotBootstrap.runtimeArchitecture()
        if (version != null && architecture != null &&
            isDebianRootfsReady(root, architecture) && isCompleteLampacRuntime(root, version)
        ) {
            return true
        }

        // If activation completed but the process was interrupted before the
        // new tree could be used, restore the staged backup before reporting
        // the runtime as missing. Also handle a missing marker in a partial tree.
        if (hasPendingUpdateBackup()) {
            rollbackPendingUpdateBackup()
            val restoredVersion = installedVersion()
            return restoredVersion != null && architecture != null &&
                isDebianRootfsReady(root, architecture) &&
                isCompleteLampacRuntime(root, restoredVersion)
        }
        return false
    }

    private fun lampacInstallBackup(): File =
        File(host.debianRootfsDir, "opt/.lampac-backup")

    fun hasPendingUpdateBackup(): Boolean = lampacInstallBackup().isDirectory

    fun commitPendingUpdateBackup() {
        val backup = lampacInstallBackup()
        if (backup.exists() && !backup.deleteRecursively()) {
            LampacRuntimeState.appendLog("Не удалось удалить резервную копию предыдущего Lampac.")
        }
    }

    fun rollbackPendingUpdateBackup(): Boolean {
        val opt = File(host.debianRootfsDir, "opt")
        val target = File(opt, "lampac")
        val backup = lampacInstallBackup()
        if (!backup.isDirectory) return false

        val failed = File(opt, ".lampac-failed")
        failed.deleteRecursively()
        if (target.exists() && !target.renameTo(failed)) {
            throw IOException("Не удалось переместить неудачную версию Lampac")
        }
        if (!backup.renameTo(target)) {
            if (failed.exists()) failed.renameTo(target)
            throw IOException("Не удалось восстановить предыдущую версию Lampac")
        }
        failed.deleteRecursively()
        return true
    }

    fun availableBytes(): Long = StatFs(appContext.filesDir.absolutePath).availableBytes

    /** Read the private Lampac configuration for an explicit user export. */
    fun readInitConfig(): String {
        check(isInstalled()) { "Сначала установите встроенный Lampac" }
        val file = File(host.debianRootfsDir, "opt/lampac/init.conf")
        require(file.length() in 1..LampacEmbeddedConfig.MAX_INIT_CONFIG_BYTES.toLong()) {
            "init.conf пуст или превышает лимит 2 МиБ"
        }
        return file.readText(Charsets.UTF_8)
    }

    /** Import the user\'s Lampac settings without changing the private listener. */
    /** Discover modules and read their effective enabled state from init.conf. */
    fun readModuleSettings(): LampacModuleSettings {
        check(isInstalled()) { "Сначала установите встроенный Lampac" }
        val serverDirectory = File(host.debianRootfsDir, "opt/lampac")
        val modules = LampacModuleCatalog.discover(serverDirectory)
        val config = readInitConfig()
        return LampacModuleSettings(
            modules = modules,
            enabledModuleIds = LampacEmbeddedConfig.readEnabledModules(config, modules)
        )
    }

    /** Persist individual module choices without changing provider credentials or listener settings. */
    fun saveModuleSettings(enabledModuleIds: Set<String>): File {
        check(isInstalled()) { "Сначала установите встроенный Lampac" }
        val serverDirectory = File(host.debianRootfsDir, "opt/lampac")
        val modules = LampacModuleCatalog.discover(serverDirectory)
        val updated = LampacEmbeddedConfig.prepareModuleSelection(
            content = readInitConfig(),
            enabledModuleIds = enabledModuleIds,
            modules = modules
        )
        return LampacEmbeddedConfig.importInitConfig(serverDirectory, updated)
    }

    fun importInitConfig(content: String): File {
        check(isInstalled()) { "Сначала установите встроенный Lampac" }
        val serverDirectory = File(host.debianRootfsDir, "opt/lampac")
        return LampacEmbeddedConfig.importInitConfig(serverDirectory, content)
    }

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

        val runtimeArchitecture = LampacProotBootstrap.runtimeArchitecture()
            ?: throw IllegalStateException(
                "Встроенный Lampac поддерживает ARM64 и ARMv7 Android. Для этой архитектуры доступно подключение удалённого сервера."
            )

        send("prepare", "Подготавливаем PRoot (" + runtimeArchitecture.name + ")…", 0, 0)
        val prepared = LampacProotBootstrap.prepare(host)

        // Once the OCI rootfs exists, its verified layer blobs are no longer
        // needed. Remove this re-download cache before any repair or install.
        if (isDebianRootfsReady(prepared.debianRootfsDir, runtimeArchitecture)) {
            val reclaimed = clearLampacProotDownloadCache(host.prefixDir)
            if (reclaimed > 0L) {
                send(
                    "storage-cleanup",
                    "Освобождено ${formatBytes(reclaimed)} из кэша слоёв Debian",
                    0,
                    0
                )
            }
        }

        // Newer APKs may add a runtime prerequisite without changing the
        // Lampac payload. Repair that in place before requiring space for a
        // second 480 MB archive or replacing the user's existing init.conf.
        val existingVersion = installedVersion()
        if (isDebianRootfsReady(prepared.debianRootfsDir, runtimeArchitecture) &&
            existingVersion != null &&
            isLampacRuntimePayloadReady(prepared.debianRootfsDir, existingVersion) &&
            !isLampacNodeReady(prepared.debianRootfsDir)
        ) {
            ensureLampacDebianMachineId(prepared.debianRootfsDir)
            send("nodejs-migration", "Добавляем Node.js для Playwright без повторной загрузки Lampac…", 0, 0)
            val nodeInstall = commandRunner.runAsRoot(buildLampacNodeInstallCommand()) { line ->
                send("nodejs-migration", line.take(220), 0, 0)
            }
            check(nodeInstall == 0 && isLampacNodeReady(prepared.debianRootfsDir)) {
                "Не удалось добавить Node.js для Playwright (код $nodeInstall)"
            }
            val existingServer = File(prepared.debianRootfsDir, "opt/lampac")
            send("installed", "Node.js добавлен. Существующий Lampac и настройки сохранены.", 1, 1)
            return@withContext existingServer
        }

        // Do not apply the former fixed 4.5 GiB preflight threshold here.
        // On some Android TV sticks it can exceed the entire /data partition.
        // The exact Lampac ZIP expansion size is checked after its central
        // directory has been read and before extraction; temporary archives
        // are removed in finally blocks so a failed low-space attempt is retryable.

        val legacyInitConfig = readLegacyLampacInitConfig(host.prefixDir)
        if (!isDebianRootfsReady(prepared.debianRootfsDir, runtimeArchitecture)) {
            val containerDir = prepared.debianRootfsDir.parentFile
            if (containerDir?.exists() == true) {
                send("debian-repair", "Обнаружена незавершённая установка Debian. Удаляем повреждённый контейнер…", 0, 0)
                val removeCode = commandRunner.execute(listOf("remove", LAMPAC_DEBIAN_ALIAS)) { line ->
                    send("debian-repair", line.take(220), 0, 0)
                }
                check(removeCode == 0 || !containerDir.exists()) {
                    "Не удалось безопасно очистить незавершённый контейнер Debian (код $removeCode). Повторите установку после освобождения памяти."
                }
            }

            send("debian", "Загружаем и устанавливаем Debian " + runtimeArchitecture.name + "…", 0, 0)
            val result = commandRunner.installDebian(runtimeArchitecture) { line ->
                send("debian", line.take(220), 0, 0)
            }
            check(result == 0 && isDebianRootfsReady(prepared.debianRootfsDir, runtimeArchitecture)) {
                "Не удалось установить Debian " + runtimeArchitecture.name + " в PRoot (код $result)"
            }
            val reclaimed = clearLampacProotDownloadCache(host.prefixDir)
            if (reclaimed > 0L) {
                send(
                    "storage-cleanup",
                    "Освобождено ${formatBytes(reclaimed)} из кэша слоёв Debian",
                    0,
                    0
                )
            }
        }

        ensureLampacDebianMachineId(prepared.debianRootfsDir)
        // Remove diagnostics left by earlier installs. Otherwise old SIGSYS
        // records (e.g. rseq/fchmodat2 from an older PRoot binary) are shown
        // together with a new failure and make the active cause ambiguous.
        listOf("sigsys-log.txt", "lampa-dpkg-install.log").forEach { name ->
            val staleLog = File(host.cacheDir, name)
            if (staleLog.exists() || java.nio.file.Files.isSymbolicLink(staleLog.toPath())) {
                staleLog.delete()
            }
        }
        send("packages", "Подготавливаем системные библиотеки Debian…", 0, 0)
        val apt = commandRunner.runAsRoot(buildLampacSystemPackagesInstallCommand(runtimeArchitecture)) { line ->
            send("packages", line.take(220), 0, 0)
        }
        if (apt != 0) {
            send(
                "packages-diagnostic",
                if (apt == 134) {
                    "PRoot завершился с кодом 134 (SIGABRT). Считываем сохранённый журнал до аварии…"
                } else {
                    "Установка пакетов завершилась с кодом $apt. Считываем сохранённый журнал…"
                },
                0,
                0
            )
            // /tmp inside Debian is bound to this app-private cache directory.
            // If PRoot aborts, the shell cannot run its tail/rm cleanup, so recover
            // the package log directly from the host filesystem after the PTY exits.
            listOf(
                "lampa-dpkg-install.log" to "Журнал apt/dpkg",
                "sigsys-log.txt" to "Журнал заблокированных системных вызовов PRoot"
            ).forEach { (name, label) ->
                val diagnosticFile = File(host.cacheDir, name)
                if (diagnosticFile.isFile) {
                    runCatching {
                        val tail = java.util.ArrayDeque<String>()
                        diagnosticFile.bufferedReader(Charsets.UTF_8).useLines { source ->
                            source.forEach { row ->
                                if (tail.size >= 100) tail.removeFirst()
                                tail.addLast(row)
                            }
                        }
                        send("packages-diagnostic", "$label:", 0, 0)
                        tail.forEach { row ->
                            send("packages-diagnostic", row.take(220), 0, 0)
                        }
                        diagnosticFile.delete()
                    }.onFailure { error ->
                        send(
                            "packages-diagnostic",
                            "Не удалось прочитать $name: ${error.javaClass.simpleName}",
                            0,
                            0
                        )
                    }
                } else {
                    send("packages-diagnostic", "$label: файл не создан.", 0, 0)
                }
            }
        }
        check(apt == 0) { "Не удалось установить системные библиотеки Debian (код $apt)" }

        val dotnetRid = if (runtimeArchitecture == LampacRuntimeArchitecture.ARM32) "linux-arm" else "linux-arm64"
        val dotnetUrl = if (runtimeArchitecture == LampacRuntimeArchitecture.ARM32) {
            LampacRuntimeArtifacts.DOTNET_ARM32_URL
        } else {
            LampacRuntimeArtifacts.DOTNET_URL
        }
        val dotnetSha512 = if (runtimeArchitecture == LampacRuntimeArchitecture.ARM32) {
            LampacRuntimeArtifacts.DOTNET_ARM32_SHA512
        } else {
            LampacRuntimeArtifacts.DOTNET_SHA512
        }
        send("dotnet-download", "Загружаем ASP.NET Core Runtime ${LampacRuntimeArtifacts.DOTNET_VERSION} " + runtimeArchitecture.name + "…", 0, 0)
        val dotnetArchive = LampacArtifactDownloader.download(
            LampacArtifactSpec(
                fileName = "aspnetcore-runtime-${LampacRuntimeArtifacts.DOTNET_VERSION}-${dotnetRid}.tar.gz",
                url = dotnetUrl,
                digestAlgorithm = "SHA-512",
                expectedDigest = dotnetSha512,
                maxBytes = 160L * 1024L * 1024L
            ),
            downloads
        ) { done, total -> send("dotnet-download", "Загрузка .NET: ${formatBytes(done)}", done, total) }

        try {
            send("dotnet-install", "Устанавливаем .NET в Debian…", 0, 0)
            val dotnetInstall = commandRunner.runAsRoot(
                buildDotnetInstallCommand(dotnetArchive.absolutePath)
            ) { line -> send("dotnet-install", line.take(220), 0, 0) }
            check(dotnetInstall == 0) { "Не удалось установить .NET Runtime (код $dotnetInstall)" }
        } finally {
            // The verified .NET archive is only a temporary installation input.
            // Free it even when extraction or validation fails on a small /data partition.
            dotnetArchive.delete()
        }

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

        try {
            if (legacyInitConfig != null) {
                val newServerDirectory = File(prepared.debianRootfsDir, "opt/lampac")
                if (!File(newServerDirectory, "init.conf").isFile) {
                    LampacEmbeddedConfig.importInitConfig(newServerDirectory, legacyInitConfig)
                    send("config-migration", "Сохранены прежние настройки и ключи источников Lampac.", 1, 1)
                }
            }

            send("lampac-extract", "Проверяем архив и устанавливаем Lampac…", 0, 0)
            val serverDir = LampacZipInstaller.install(
                archive = lampacArchive,
                rootfs = prepared.debianRootfsDir,
                version = LampacRuntimeArtifacts.LAMPAC_VERSION
            ) { done, total ->
                send("lampac-extract", "Распаковка Lampac: ${formatBytes(done)}", done, total)
            }

            check(File(serverDir, "Core.dll").isFile && File(prepared.debianRootfsDir, "opt/dotnet/dotnet").isFile) {
                "Установка Lampac завершилась без обязательных файлов"
            }
            send("installed", "Lampac установлен. Сервер пока остановлен.", 1, 1)
            serverDir
        } finally {
            // Keep /data recoverable after a failed extraction: the archive is
            // downloaded again on retry, while the ZIP installer removes its stage.
            lampacArchive.delete()
        }
    }

    suspend fun updateFromRelease(
        version: String,
        url: String,
        sha256: String,
        expectedBytes: Long,
        onProgress: (LampacInstallProgress) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        require(version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) {
            "Версия Lampac в релизе некорректна"
        }
        require(sha256.matches(Regex("[0-9a-fA-F]{64}"))) {
            "В релизе Lampac отсутствует корректная SHA-256"
        }
        require(expectedBytes in 1..1024L * 1024L * 1024L) {
            "Размер архива Lampac превышает лимит обновления"
        }
        val current = installedVersion() ?: throw IOException("Не найдена установленная версия Lampac")
        require(compareSemanticVersions(version, current) > 0) {
            "Релиз Lampac не новее установленной версии"
        }
        check(isInstalled()) { "Текущая установка Lampac неполная; обновление отменено" }
        check(!hasPendingUpdateBackup()) {
            "Предыдущее обновление Lampac ещё не подтверждено запуском. Перезапустите сервер и повторите проверку."
        }

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
        send("update-download", "Загружаем Lampac $version…", 0, expectedBytes)
        val archive = LampacArtifactDownloader.download(
            LampacArtifactSpec(
                fileName = "lampac-nextgen-$version.zip",
                url = url,
                digestAlgorithm = "SHA-256",
                expectedDigest = sha256.lowercase(),
                expectedBytes = expectedBytes,
                maxBytes = expectedBytes
            ),
            downloads
        ) { done, total ->
            send("update-download", "Загрузка Lampac: ${formatBytes(done)}", done, total)
        }

        try {
            send("update-extract", "Проверяем и устанавливаем Lampac $version…", 0, expectedBytes)
            val updated = LampacZipInstaller.install(
                archive = archive,
                rootfs = host.debianRootfsDir,
                version = version,
                retainBackup = true
            ) { done, total ->
                send("update-extract", "Установка Lampac: ${formatBytes(done)}", done, total)
            }
            check(isInstalled() && installedVersion() == version) {
                "После обновления проверка целостности Lampac не пройдена"
            }
            send("updated", "Lampac обновлён до $version. Настройки и данные сохранены.", expectedBytes, expectedBytes)
            updated
        } finally {
            archive.delete()
        }
    }

    private fun compareSemanticVersions(first: String, second: String): Int {
        val a = first.split('.').map { it.toInt() }
        val b = second.split('.').map { it.toInt() }
        for (index in 0..2) {
            val compare = a[index].compareTo(b[index])
            if (compare != 0) return compare
        }
        return 0
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
