package app.jacket.mobile.lampac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LampacOnlinePluginCompatibilityPatchesTest {
    private val originalBlock = """resolve(
                    json.online.filter(function (c) {
                      return c.show;
                    })
                  );"""
    private val patchedBlock = "resolve(json.online);"

    @Test
    fun keepsGhostBalancersInTheSourceSelectorForManualRetry() {
        val source = """
            function done(json) {
                $originalBlock
            }
        """.trimIndent()

        val patched = LampacOnlinePluginCompatibilityPatches.patchOnlinePluginSource(source)

        assertTrue(patched.contains(patchedBlock))
        assertTrue(!patched.contains("json.online.filter(function (c)"))
    }

    @Test
    fun patchIsIdempotent() {
        val source = "function done(json) { $patchedBlock }"
        assertEquals(
            source,
            LampacOnlinePluginCompatibilityPatches.patchOnlinePluginSource(source)
        )
    }

    @Test
    fun patchesOnlyTheEmbeddedOnlinePluginFile() {
        val server = Files.createTempDirectory("lampac-online-plugin").toFile()
        try {
            val plugin = File(server, "Online/plugin.js")
            assertTrue(plugin.parentFile!!.mkdirs())
            plugin.writeText("function done(json) { $originalBlock }", Charsets.UTF_8)

            val first = LampacOnlinePluginCompatibilityPatches.apply(server)
            assertEquals(1, first.filesFound)
            assertEquals(1, first.updatedFiles)
            assertEquals(0, first.alreadyPatchedFiles)
            assertTrue(plugin.readText().contains(patchedBlock))

            val second = LampacOnlinePluginCompatibilityPatches.apply(server)
            assertEquals(1, second.filesFound)
            assertEquals(0, second.updatedFiles)
            assertEquals(1, second.alreadyPatchedFiles)
        } finally {
            server.deleteRecursively()
        }
    }
}
