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

    @Test
    fun boundedArchiveCopyPropagatesCancellationFromProgressCallback() {
        var checks = 0
        var cancelled = false
        try {
            copyArchiveEntryBounded(
                input = ByteArrayInputStream(ByteArray(128 * 1024)),
                output = ByteArrayOutputStream(),
                declaredSize = 128L * 1024L,
                maxBytes = 256L * 1024L
            ) {
                checks++
                throw kotlinx.coroutines.CancellationException("cancel extraction")
            }
        } catch (_: kotlinx.coroutines.CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertTrue(checks == 1)
    }


    @Test
    fun dotnetInstallValidatesStagingBeforeReplacingCurrentRuntime() {
        val command = buildDotnetInstallCommand("/data/user/0/app.jacket.mobile/files/lampac/dotnet runtime.tar.gz")

        val stagedValidation = command.indexOf("/opt/dotnet.stage/dotnet --info")
        val previousMove = command.indexOf("mv /opt/dotnet /opt/dotnet.previous")
        val activation = command.indexOf("mv /opt/dotnet.stage /opt/dotnet")
        val finalValidation = command.lastIndexOf("/opt/dotnet/dotnet --info")

        assertTrue(stagedValidation >= 0)
        assertTrue(previousMove > stagedValidation)
        assertTrue(activation > previousMove)
        assertTrue(finalValidation > activation)
        assertTrue(command.contains("mv /opt/dotnet.previous /opt/dotnet"))
        assertTrue(command.contains("tar -xzf '/data/user/0/app.jacket.mobile/files/lampac/dotnet runtime.tar.gz'"))
    }

    @Test
    fun dotnetInstallCommandRejectsEmptyOrNullBytePaths() {
        var emptyRejected = false
        try {
            buildDotnetInstallCommand(" ")
        } catch (_: IllegalArgumentException) {
            emptyRejected = true
        }
        assertTrue(emptyRejected)

        var nullByteRejected = false
        try {
            buildDotnetInstallCommand("runtime\u0000.tar.gz")
        } catch (_: IllegalArgumentException) {
            nullByteRejected = true
        }
        assertTrue(nullByteRejected)
    }

}
