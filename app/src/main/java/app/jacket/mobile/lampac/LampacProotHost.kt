package app.jacket.mobile.lampac

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.system.Os
import id.or.oo.pr.engine.ProotHost
import id.or.oo.pr.engine.ProotLauncher
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * App-private Android host paths for the embedded PRoot runtime.
 *
 * The PRoot executable and loader stay in ApplicationInfo.nativeLibraryDir:
 * Android's W^X policy does not allow executing downloaded binaries from filesDir.
 */
class JacketProotHost(context: Context) : ProotHost {
    private val appContext = context.applicationContext

    override val prefixDir: File = File(appContext.filesDir, "lampac/pr")
    override val homeDir: File = File(appContext.filesDir, "lampac/home")
    override val packageName: String = appContext.packageName
    override val cacheDir: File = File(appContext.cacheDir, "lampac-pr")

    override val dnsServers: List<String>
        get() {
            val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager ?: return emptyList()
            val network = connectivity.activeNetwork ?: return emptyList()
            return connectivity.getLinkProperties(network)?.dnsServers
                .orEmpty()
                .mapNotNull { it.hostAddress }
                .distinct()
                .take(3)
        }

    val nativeLibraryDir: File
        get() = File(appContext.applicationInfo.nativeLibraryDir)

    /** OCI rootfs path created by `pr-cli install debian`; do not create it before install. */
    val debianRootfsDir: File
        get() = File(prefixDir, "var/lib/pr/containers/debian/rootfs")
}

data class ProotBootstrapResult(
    val prefixDir: File,
    val homeDir: File,
    val cacheDir: File,
    val nativeLibraryDir: File,
    val debianRootfsDir: File
)

/**
 * Prepares pr-cli's documented app-private prefix layout and links its
 * executable entry points to the ARM64 binaries extracted by Android.
 */
object LampacProotBootstrap {
    private val requiredLibraries = listOf(
        "libpr-cli.so",
        "libproot.so",
        "libproot-loader.so",
        "libptyjni.so"
    )

    fun isSupportedOnThisDevice(): Boolean =
        Build.SUPPORTED_ABIS.any { it.equals("arm64-v8a", ignoreCase = true) }

    fun prepare(host: JacketProotHost): ProotBootstrapResult {
        check(isSupportedOnThisDevice()) {
            "Встроенный Lampac пока поддерживается только на ARM64. Можно подключить удалённый Lampac по сети."
        }

        val nativeDir = host.nativeLibraryDir
        requiredLibraries.forEach { name ->
            require(File(nativeDir, name).isFile) {
                "Не найдена библиотека $name. Проверьте ARM64-сборку Jacket."
            }
        }

        val directories = listOf(
            host.prefixDir,
            host.homeDir,
            host.cacheDir,
            File(host.prefixDir, "bin"),
            File(host.prefixDir, "tmp"),
            File(host.prefixDir, "etc/pr"),
            File(host.prefixDir, "var/lib/pr/installed-rootfs"),
            File(host.prefixDir, "var/lib/pr/dlcache")
        )
        directories.forEach { directory ->
            if (!directory.exists() && !directory.mkdirs()) {
                throw IOException("Не удалось создать каталог PRoot: ${directory.absolutePath}")
            }
            require(directory.isDirectory) {
                "Путь PRoot занят не каталогом: ${directory.absolutePath}"
            }
        }

        linkNativeExecutable(nativeDir, host.prefixDir, "libpr-cli.so", "bin/pr-cli")
        linkNativeExecutable(nativeDir, host.prefixDir, "libproot.so", "bin/proot")

        return ProotBootstrapResult(
            prefixDir = host.prefixDir,
            homeDir = host.homeDir,
            cacheDir = host.cacheDir,
            nativeLibraryDir = nativeDir,
            debianRootfsDir = host.debianRootfsDir
        )
    }

    private fun linkNativeExecutable(
        nativeDir: File,
        prefixDir: File,
        libraryName: String,
        relativeLink: String
    ) {
        val target = File(nativeDir, libraryName)
        val link = File(prefixDir, relativeLink)
        val parent = link.parentFile ?: throw IOException("Некорректный путь PRoot-ссылки")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Не удалось создать каталог ${parent.absolutePath}")
        }

        if (Files.isSymbolicLink(link.toPath())) {
            val current = runCatching { Files.readSymbolicLink(link.toPath()).toString() }.getOrNull()
            if (current == target.absolutePath) return
            Files.deleteIfExists(link.toPath())
        } else if (link.exists()) {
            if (!link.delete()) throw IOException("Не удалось заменить ${link.absolutePath}")
        }

        Os.symlink(target.absolutePath, link.absolutePath)
        require(Files.isSymbolicLink(link.toPath())) {
            "Не удалось создать системную ссылку для ${libraryName}"
        }
    }
}

/**
 * Small command facade used by the future runtime installer/service.
 * Output is read from the PTY continuously so pr-cli cannot block on a full
 * output buffer. The caller owns command sequencing and service lifetime.
 */
class LampacProotCommands(host: JacketProotHost) {
    private val launcher = ProotLauncher(host)

    fun start(args: List<String>): ProotLauncher.Session {
        require(args.isNotEmpty() && args.all { '\u0000' !in it }) {
            "Команда PRoot пуста или содержит недопустимые символы"
        }
        return launcher.runCommand(args)
            ?: throw IOException("Не удалось запустить pr-cli")
    }
}
