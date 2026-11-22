package app.jacket.mobile.lampac

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LampacRuntimeInstallerTest {
    @Test
    fun rejectsMissingOrPartialDebianRootfs() {
        val root = createTempDirectory("jacket-debian-partial").toFile()
        try {
            assertFalse(isDebianRootfsReady(root))

            File(root, "etc").mkdirs()
            File(root, "etc/os-release").writeText("ID=debian")
            File(root, "bin").mkdirs()
            File(root, "bin/sh").writeText("placeholder")

            assertFalse(isDebianRootfsReady(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun acceptsDebianRootfsOnlyWhenTheBaseSystemAndAptArePresent() {
        val root = createTempDirectory("jacket-debian-ready").toFile()
        try {
            File(root, "etc").mkdirs()
            File(root, "etc/os-release").writeText("ID=debian")
            File(root, "bin").mkdirs()
            File(root, "bin/sh").writeText("placeholder")
            File(root, "usr/bin").mkdirs()
            File(root, "usr/bin/apt-get").writeText("placeholder")

            assertTrue(isDebianRootfsReady(root))
        } finally {
            root.deleteRecursively()
        }
    }
}
