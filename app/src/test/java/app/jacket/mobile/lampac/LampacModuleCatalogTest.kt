package app.jacket.mobile.lampac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LampacModuleCatalogTest {
    @Test
    fun discoversOnlyLampacModuleManifestLocations() {
        val root = Files.createTempDirectory("lampac-module-catalog").toFile()
        try {
            writeManifest(File(root, "module/OnlineRUS/Kinogo/manifest.json"), true)
            writeManifest(File(root, "module/OnlineRUS/Mirage/manifest.json"), false)
            writeManifestWithoutEnable(File(root, "module/OnlineRUS/NoEnableFlag/manifest.json"))
            writeManifest(File(root, "module/TorrServer/manifest.json"), true)
            writeManifest(File(root, "mods/CustomModule/manifest.json"), true)
            File(root, "module/OnlineRUS/WithoutManifest").mkdirs()
            File(root, "module/not-a-module/file.txt").apply {
                parentFile?.mkdirs()
                writeText("ignored")
            }

            val modules = LampacModuleCatalog.discover(root)

            assertEquals(
                setOf("Kinogo", "Mirage", "NoEnableFlag", "TorrServer", "CustomModule"),
                modules.map { it.id }.toSet()
            )
            assertEquals("OnlineRUS", modules.first { it.id == "Kinogo" }.groupId)
            assertEquals(null, modules.first { it.id == "TorrServer" }.groupId)
            assertEquals("mods", modules.first { it.id == "CustomModule" }.sourceRoot)
            assertFalse(modules.first { it.id == "Mirage" }.enabledInManifest)
            assertFalse(modules.first { it.id == "NoEnableFlag" }.enabledInManifest)
            assertTrue(modules.first { it.id == "Kinogo" }.enabledInManifest)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun writeManifest(file: File, enabled: Boolean) {
        file.parentFile?.mkdirs()
        file.writeText("""{"enable":$enabled,"tree":["Controller.cs"]}""")
    }

    private fun writeManifestWithoutEnable(file: File) {
        file.parentFile?.mkdirs()
        file.writeText("""{"tree":["Controller.cs"]}""")
    }
}
