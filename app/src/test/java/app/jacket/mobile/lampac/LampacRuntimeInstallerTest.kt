package app.jacket.mobile.lampac

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class LampacRuntimeInstallerTest {
    @Test
    fun rejectsMissingOrPartialDebianRootfs() {
        val root = java.nio.file.Files.createTempDirectory("jacket-debian-partial").toFile()
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
        val root = java.nio.file.Files.createTempDirectory("jacket-debian-ready").toFile()
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
    @Test
    fun boundedArchiveCopyAcceptsExactDeclaredSize() {
        val payload = "lampac-runtime".toByteArray()
        val output = ByteArrayOutputStream()

        val copied = copyArchiveEntryBounded(
            input = ByteArrayInputStream(payload),
            output = output,
            declaredSize = payload.size.toLong(),
            maxBytes = 1024L
        )

        assertTrue(copied == payload.size.toLong())
        assertTrue(output.toByteArray().contentEquals(payload))
    }

    @Test
    fun boundedArchiveCopyRejectsExpandedEntryLargerThanDeclaredSize() {
        val output = ByteArrayOutputStream()
        var rejected = false
        try {
            copyArchiveEntryBounded(
                input = ByteArrayInputStream("expanded-payload".toByteArray()),
                output = output,
                declaredSize = 4L,
                maxBytes = 1024L
            )
        } catch (_: IOException) {
            rejected = true
        }

        assertTrue(rejected)
        assertTrue(output.size() <= 4)
    }

    @Test
    fun boundedArchiveCopyRejectsTruncatedEntry() {
        var rejected = false
        try {
            copyArchiveEntryBounded(
                input = ByteArrayInputStream(byteArrayOf(1, 2, 3)),
                output = ByteArrayOutputStream(),
                declaredSize = 4L,
                maxBytes = 1024L
            )
        } catch (_: IOException) {
            rejected = true
        }

        assertTrue(rejected)
    }

    @Test
    fun boundedArchiveCopyRejectsDeclaredSizeAboveEntryLimit() {
        var rejected = false
        try {
            copyArchiveEntryBounded(
                input = ByteArrayInputStream(ByteArray(0)),
                output = ByteArrayOutputStream(),
                declaredSize = 1025L,
                maxBytes = 1024L
            )
        } catch (_: IllegalArgumentException) {
            rejected = true
        }

        assertTrue(rejected)
    }

}
