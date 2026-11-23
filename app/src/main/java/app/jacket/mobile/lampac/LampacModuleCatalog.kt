package app.jacket.mobile.lampac

import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * One Lampac Roslyn module discovered from its manifest.json.
 *
 * groupId is the immediate parent folder when a module is nested one level
 * below module/ or mods/. Lampac SkipModules accepts either the module folder
 * name or its parent group folder name.
 */
data class LampacModuleInfo(
    val id: String,
    val groupId: String?,
    val sourceRoot: String,
    val enabledInManifest: Boolean
)

object LampacModuleCatalog {
    /**
     * Mirrors Lampac 1.90.2 Startup: enumerate direct folders in mods/ and
     * module/, then direct child folders when the parent has no manifest.
     * No directory outside the installed Lampac tree is inspected.
     */
    fun discover(serverDirectory: File): List<LampacModuleInfo> {
        val found = LinkedHashMap<String, LampacModuleInfo>()
        for (sourceRoot in listOf("mods", "module")) {
            val root = File(serverDirectory, sourceRoot)
            if (!Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS)) continue

            val topFolders = root.listFiles()
                ?.filter { Files.isDirectory(it.toPath(), LinkOption.NOFOLLOW_LINKS) }
                ?.sortedBy { it.name }
                .orEmpty()

            for (top in topFolders) {
                val topManifest = File(top, "manifest.json")
                if (topManifest.isFile) {
                    parseModule(topManifest, top.name, null, sourceRoot)?.let { info ->
                        found.putIfAbsent(info.id, info)
                    }
                    continue
                }

                val children = top.listFiles()
                    ?.filter { Files.isDirectory(it.toPath(), LinkOption.NOFOLLOW_LINKS) }
                    ?.sortedBy { it.name }
                    .orEmpty()

                for (child in children) {
                    val childManifest = File(child, "manifest.json")
                    if (!childManifest.isFile) continue
                    parseModule(childManifest, child.name, top.name, sourceRoot)?.let { info ->
                        found.putIfAbsent(info.id, info)
                    }
                }
            }
        }

        return found.values.sortedWith(
            compareBy<LampacModuleInfo> { it.groupId ?: "" }
                .thenBy { it.id.lowercase() }
        )
    }

    private fun parseModule(
        manifest: File,
        id: String,
        groupId: String?,
        sourceRoot: String
    ): LampacModuleInfo? {
        if (id.isBlank() || id == "." || id == "..") return null
        val enabled = runCatching {
            JSONObject(manifest.readText(Charsets.UTF_8)).optBoolean("enable", true)
        }.getOrNull() ?: return null

        return LampacModuleInfo(
            id = id,
            groupId = groupId,
            sourceRoot = sourceRoot,
            enabledInManifest = enabled
        )
    }
}
