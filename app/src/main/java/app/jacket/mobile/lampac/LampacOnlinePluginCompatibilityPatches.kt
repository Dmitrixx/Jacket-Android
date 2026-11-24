package app.jacket.mobile.lampac

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Compatibility adjustments for the embedded Lampac Online UI.
 *
 * Lampac's online-source popup includes every checked balancer, but the stock
 * plugin passes only positive matches into startSource(). That makes the grey
 * entries in its Sort menu impossible to force-open, even though the user may
 * want to try a provider after a false-negative search check.
 */
internal object LampacOnlinePluginCompatibilityPatches {
    private const val ORIGINAL_BLOCK = """resolve(
                    json.online.filter(function (c) {
                      return c.show;
                    })
                  );"""
    private const val PATCHED_BLOCK = "resolve(json.online);"

    data class Report(
        val filesFound: Int,
        val updatedFiles: Int,
        val alreadyPatchedFiles: Int
    )

    fun apply(serverDirectory: File): Report {
        val plugin = File(serverDirectory, "Online/plugin.js")
        if (!plugin.isFile) return Report(0, 0, 0)

        val original = plugin.readText(Charsets.UTF_8)
        val patched = patchOnlinePluginSource(original)
        if (patched == original) return Report(1, 0, 1)

        writeAtomically(plugin, patched)
        return Report(1, 1, 0)
    }

    internal fun patchOnlinePluginSource(source: String): String {
        val originalCount = source.windowed(ORIGINAL_BLOCK.length)
            .count { it == ORIGINAL_BLOCK }
        val patchedCount = source.windowed(PATCHED_BLOCK.length)
            .count { it == PATCHED_BLOCK }

        require(!(originalCount > 0 && patchedCount > 0)) {
            "Online/plugin.js содержит одновременно старую и исправленную фильтрацию балансеров."
        }
        require(originalCount <= 1 && patchedCount <= 1) {
            "В Online/plugin.js изменилось количество блоков фильтрации балансеров."
        }
        if (patchedCount == 1) return source

        require(originalCount == 1) {
            "Не найден ожидаемый фильтр только рабочих балансеров в Online/plugin.js."
        }
        return source.replace(ORIGINAL_BLOCK, PATCHED_BLOCK)
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
                    throw IOException("Не удалось атомарно обновить Online/plugin.js")
                }
            }
        } finally {
            temporary.delete()
        }
    }
}
