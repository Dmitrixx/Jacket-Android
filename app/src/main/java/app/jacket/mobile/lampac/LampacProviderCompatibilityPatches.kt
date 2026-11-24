package app.jacket.mobile.lampac

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Small compatibility patch for the dynamic Aladin module bundled with Lampac 1.90.2.
 *
 * Aladin can attach a large Accepts-Controls value to every HLS request. Serializing
 * those headers into the public /proxy URL can exceed Kestrel's request-line limit.
 * forceMd5 stores the header set server-side and keeps the player URL short.
 *
 * The provider is marked dynamic in its manifest, so Lampac recompiles this source
 * folder at the next server start after the source file is changed.
 */
internal object LampacProviderCompatibilityPatches {
    private const val ORIGINAL_CALL =
        "HostStreamProxy(file, headers: streamHeaders, force_streamproxy: true)"
    private const val PATCHED_CALL =
        "HostStreamProxy(file, headers: streamHeaders, force_streamproxy: true, forceMd5: true)"

    data class Report(
        val filesFound: Int,
        val updatedFiles: Int,
        val alreadyPatchedFiles: Int
    )

    fun apply(serverDirectory: File): Report {
        val roots = listOf(
            File(serverDirectory, "module"),
            File(serverDirectory, "mods")
        ).filter { it.isDirectory }

        val controllers = roots.flatMap { root ->
            root.walkTopDown()
                .maxDepth(5)
                .filter { file ->
                    file.isFile &&
                        file.name == "Controller.cs" &&
                        file.parentFile?.name.equals("Aladin", ignoreCase = true)
                }
                .toList()
        }.distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }

        var updated = 0
        var alreadyPatched = 0

        controllers.forEach { controller ->
            val original = controller.readText(Charsets.UTF_8)
            val patched = patchAladinControllerSource(original)
            if (patched == original) {
                alreadyPatched += 1
            } else {
                writeAtomically(controller, patched)
                updated += 1
            }
        }

        return Report(
            filesFound = controllers.size,
            updatedFiles = updated,
            alreadyPatchedFiles = alreadyPatched
        )
    }

    internal fun patchAladinControllerSource(source: String): String {
        val originalCount = source.windowed(ORIGINAL_CALL.length)
            .count { it == ORIGINAL_CALL }
        val patchedCount = source.windowed(PATCHED_CALL.length)
            .count { it == PATCHED_CALL }

        require(!(originalCount > 0 && patchedCount > 0)) {
            "Исходник Aladin содержит одновременно старую и исправленную ссылки /proxy."
        }
        require(originalCount <= 1 && patchedCount <= 1) {
            "В исходнике Aladin изменилось количество вызовов HostStreamProxy."
        }
        if (patchedCount == 1) return source

        require(originalCount == 1) {
            "Не найден ожидаемый вызов HostStreamProxy в исходнике Aladin."
        }
        return source.replace(ORIGINAL_CALL, PATCHED_CALL)
    }

    private fun writeAtomically(target: File, content: String) {
        val temporary = File(target.parentFile, target.name + ".lampa-tmp")
        try {
            temporary.writeText(content, Charsets.UTF_8)
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: Exception) {
                if (!temporary.renameTo(target)) {
                    throw IOException("Не удалось атомарно обновить исходник Aladin")
                }
            }
        } finally {
            temporary.delete()
        }
    }
}
