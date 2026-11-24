package app.jacket.mobile.lampac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LampacProviderCompatibilityPatchesTest {
    private val oldCall =
        "HostStreamProxy(file, headers: streamHeaders, force_streamproxy: true)"
    private val newCall =
        "HostStreamProxy(file, headers: streamHeaders, force_streamproxy: true, forceMd5: true)"

    @Test
    fun changesAladinToCompactServerStoredProxyKeys() {
        val source = """
            namespace Aladin;
            class AladinController {
                void Build() {
                    streams.Add(new StreamQualityDto($oldCall, "1080p"));
                }
            }
        """.trimIndent()

        val patched = LampacProviderCompatibilityPatches.patchAladinControllerSource(source)

        assertTrue(patched.contains(newCall))
        assertTrue(!patched.contains(oldCall))
    }

    @Test
    fun patchIsIdempotent() {
        val source = "namespace Aladin; $newCall;"
        assertEquals(
            source,
            LampacProviderCompatibilityPatches.patchAladinControllerSource(source)
        )
    }

    @Test
    fun patchesDynamicAladinSourceInExistingLampacInstall() {
        val server = Files.createTempDirectory("lampac-aladin-patch").toFile()
        try {
            val controller = File(server, "module/OnlineRUS/Aladin/Controller.cs")
            assertTrue(controller.parentFile!!.mkdirs())
            controller.writeText(
                "namespace Aladin; class C { void M() { $oldCall; } }",
                Charsets.UTF_8
            )

            val first = LampacProviderCompatibilityPatches.apply(server)
            assertEquals(1, first.filesFound)
            assertEquals(1, first.updatedFiles)
            assertEquals(0, first.alreadyPatchedFiles)
            assertTrue(controller.readText().contains(newCall))

            val second = LampacProviderCompatibilityPatches.apply(server)
            assertEquals(1, second.filesFound)
            assertEquals(0, second.updatedFiles)
            assertEquals(1, second.alreadyPatchedFiles)
        } finally {
            server.deleteRecursively()
        }
    }
}
