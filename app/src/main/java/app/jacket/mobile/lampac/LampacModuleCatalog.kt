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

data class LampacModuleSettings(
    val modules: List<LampacModuleInfo>,
    val enabledModuleIds: Set<String>
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
            JSONObject(manifest.readText(Charsets.UTF_8)).optBoolean("enable", false)
        }.getOrNull() ?: return null

        return LampacModuleInfo(
            id = id,
            groupId = groupId,
            sourceRoot = sourceRoot,
            enabledInManifest = enabled
        )
    }
}

/**
 * Selects the small set of optional, user-facing media modules that should be
 * ready in the Android bundle. Administrative, account-security and legacy
 * server modules deliberately remain disabled in their upstream manifests.
 */
object LampacCoreModuleManifestPolicy {
    val DEFAULT_ENABLED_MEDIA_MODULES: Set<String> =
        linkedSetOf("Music", "Tracks", "WatchTogether")

    /**
     * Enables selected modules from the installed official module tree.
     * User-added modules under mods/ are never modified. Other manifest fields
     * and every non-selected upstream module keep their original values.
     *
     * @return ids whose manifest changed during this call.
     */
    fun enableDefaultMediaModules(serverDirectory: File): Set<String> {
        val moduleRoot = File(serverDirectory, "module")
        if (!moduleRoot.isDirectory) return emptySet()

        val enabled = LinkedHashSet<String>()
        val modules = LampacModuleCatalog.discover(serverDirectory)
            .filter { it.sourceRoot == "module" && it.id in DEFAULT_ENABLED_MEDIA_MODULES }

        modules.forEach { module ->
            val relativePath = buildString {
                append(module.id)
                if (module.groupId != null) {
                    insert(0, module.groupId + File.separator)
                }
                append(File.separator)
                append("manifest.json")
            }
            val manifest = File(moduleRoot, relativePath)
            val path = manifest.toPath()
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return@forEach

            val rootPath = moduleRoot.canonicalFile.toPath()
            val canonicalManifest = manifest.canonicalFile.toPath()
            require(canonicalManifest.startsWith(rootPath)) {
                "Путь manifest.json модуля выходит за пределы каталога Lampac"
            }

            val json = try {
                JSONObject(manifest.readText(Charsets.UTF_8))
            } catch (error: Exception) {
                throw IOException("Не удалось прочитать manifest.json модуля ${module.id}", error)
            }
            if (json.optBoolean("enable", false)) return@forEach

            json.put("enable", true)
            writeManifestAtomically(manifest, json.toString(2))
            enabled += module.id
        }

        return enabled
    }

    private fun writeManifestAtomically(target: File, content: String) {
        val temporary = Files.createTempFile(target.parentFile.toPath(), ".manifest-", ".tmp")
        try {
            Files.write(temporary, content.toByteArray(Charsets.UTF_8))
            try {
                Files.move(
                    temporary,
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

