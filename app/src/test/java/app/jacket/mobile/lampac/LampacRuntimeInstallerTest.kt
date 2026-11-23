package app.jacket.mobile.lampac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class LampacRuntimeInstallerTest {
    @Test
    fun debianBootstrapUsesTheUpstreamForkyOciImageAndStableAlias() {
        assertEquals(
            listOf("install", "docker.io/library/debian:forky-slim", "--override-alias", "lampac-debian"),
            lampacDebianInstallArguments()
        )
    }

    @Test
    fun oldTrimmedLampacInstallIsMarkedForOneTimeFullReplacement() {
        val root = java.nio.file.Files.createTempDirectory("lampa-runtime-migration").toFile()
        try {
            val oldInstall = File(root, "opt/lampac")
            val dotnet = File(root, "opt/dotnet/dotnet")
            oldInstall.mkdirs()
            dotnet.parentFile?.mkdirs()
            dotnet.writeText("dotnet")
            File(oldInstall, "Core.dll").writeText("core")
            File(oldInstall, "Shared.dll").writeText("shared")
            File(oldInstall, "init.conf").writeText("{}")
            File(oldInstall, ".jacket-lampac-version").writeText("1.90.2")

            assertFalse(isCompleteLampacRuntime(root, "1.90.2"))

            File(oldInstall, ".lampa-lampac-version").writeText("1.90.2")
            assertFalse(isCompleteLampacRuntime(root, "1.90.2"))

            val playwrightIndex = File(oldInstall, ".playwright/package/index.js")
            playwrightIndex.parentFile?.mkdirs()
            playwrightIndex.writeText("// Playwright package index")

            assertFalse(isLampacPlaywrightPackageReady(oldInstall))
            assertFalse(isLampacRuntimePayloadReady(root, "1.90.2"))

            File(oldInstall, ".playwright/package/cli.js").writeText("// Playwright driver entry")
            assertTrue(isLampacPlaywrightPackageReady(oldInstall))
            assertTrue(isLampacRuntimePayloadReady(root, "1.90.2"))
            assertFalse(isLampacNodeReady(root))
            assertFalse(isCompleteLampacRuntime(root, "1.90.2"))

            val node = File(root, "usr/bin/node")
            node.parentFile?.mkdirs()
            node.writeText("#!/bin/sh\necho node")
            assertTrue(node.setExecutable(true))
            assertTrue(isLampacNodeReady(root))
            assertTrue(isCompleteLampacRuntime(root, "1.90.2"))
            assertFalse(isCompleteLampacRuntime(root, "1.90.3"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun archivePathStripsOnlyTheCoreApplicationDirectory() {
        assertTrue(lampacArchiveRelativePath("lampac/Core.dll", "lampac/") == "Core.dll")
        assertTrue(
            lampacArchiveRelativePath("lampac/Modules/Filmix/manifest.json", "lampac/") ==
                listOf("Modules", "Filmix", "manifest.json").joinToString(File.separator)
        )
        assertTrue(
            lampacArchiveRelativePath("server-assets/probe.json", "lampac/") ==
                listOf("server-assets", "probe.json").joinToString(File.separator)
        )
        assertTrue(lampacArchiveRelativePath("lampac/", "lampac/") == null)
        assertTrue(
            lampacArchiveRelativePath("lampac/.playwright/package/index.js", "lampac/") ==
                listOf(".playwright", "package", "index.js").joinToString(File.separator)
        )
        assertTrue(
            lampacArchiveRelativePath("lampac/.playwright/package/cli.js", "lampac/") ==
                listOf(".playwright", "package", "cli.js").joinToString(File.separator)
        )
        assertTrue(lampacArchiveRelativePath("Core.dll", "") == "Core.dll")
    }

    @Test
    fun archivePathRejectsTraversalAndAbsolutePaths() {
        val invalid = listOf("../outside.txt", "lampac/../../outside.txt", "/absolute/file", "lampac/../outside")
        invalid.forEach { path ->
            var rejected = false
            try {
                lampacArchiveRelativePath(path, "lampac/")
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue("Expected unsafe ZIP path to be rejected: $path", rejected)
        }
    }

    @Test
    fun migratesInitConfigFromLegacyOrPreviousOciDebianRootfs() {
        val prefix = java.nio.file.Files.createTempDirectory("lampa-debian-config-migration").toFile()
        try {
            val legacyConfig = File(
                prefix,
                "var/lib/pr/installed-rootfs/debian/opt/lampac/init.conf"
            )
            legacyConfig.parentFile?.mkdirs()
            legacyConfig.writeText("""{"Filmix":{"token":"keep-this"}}""")

            assertEquals(
                """{"Filmix":{"token":"keep-this"}}""",
                readLegacyLampacInitConfig(prefix)
            )

            legacyConfig.delete()
            val previousOciConfig = File(
                prefix,
                "var/lib/pr/containers/debian/rootfs/opt/lampac/init.conf"
            )
            previousOciConfig.parentFile?.mkdirs()
            previousOciConfig.writeText("""{"Filmix":{"token":"keep-this-too"}}""")

            assertEquals(
                """{"Filmix":{"token":"keep-this-too"}}""",
                readLegacyLampacInitConfig(prefix)
            )

            previousOciConfig.delete()
            val currentAliasConfig = File(
                prefix,
                "var/lib/pr/containers/lampac-debian/rootfs/opt/lampac/init.conf"
            )
            currentAliasConfig.parentFile?.mkdirs()
            currentAliasConfig.writeText("""{"Filmix":{"token":"preserve-current-alias"}}""")
            assertEquals(
                """{"Filmix":{"token":"preserve-current-alias"}}""",
                readLegacyLampacInitConfig(prefix)
            )
        } finally {
            prefix.deleteRecursively()
        }
    }

    @Test
    fun rejectsMissingOrPartialDebianRootfs() {
        val root = java.nio.file.Files.createTempDirectory("lampa-debian-partial").toFile()
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
        val root = java.nio.file.Files.createTempDirectory("lampa-debian-ready").toFile()
        try {
            File(root, "etc").mkdirs()
            File(root, "etc/os-release").writeText("ID=debian")
            File(root, "bin").mkdirs()
            val shell = File(root, "bin/sh").apply { writeText("placeholder") }
            File(root, "usr/bin").mkdirs()
            val apt = File(root, "usr/bin/apt-get").apply { writeText("placeholder") }
            assertTrue(shell.setExecutable(true))
            assertTrue(apt.setExecutable(true))

            assertTrue(isDebianRootfsReady(root))
        } finally {
            root.deleteRecursively()
        }
    }
    @Test
    fun rejectsWrongDistributionAndNonExecutableRequiredTools() {
        val root = java.nio.file.Files.createTempDirectory("lampa-debian-tools").toFile()
        try {
            File(root, "etc").mkdirs()
            val osRelease = File(root, "etc/os-release").apply { writeText("ID=ubuntu") }
            File(root, "bin").mkdirs()
            val shell = File(root, "bin/sh").apply { writeText("placeholder") }
            File(root, "usr/bin").mkdirs()
            val apt = File(root, "usr/bin/apt-get").apply { writeText("placeholder") }
            assertTrue(shell.setExecutable(true))
            assertTrue(apt.setExecutable(true))

            assertFalse(isDebianRootfsReady(root))
            osRelease.writeText("ID=debian")
            assertTrue(shell.setExecutable(false))
            assertFalse(isDebianRootfsReady(root))
            assertTrue(shell.setExecutable(true))
            assertTrue(apt.setExecutable(false))
            assertFalse(isDebianRootfsReady(root))
            assertTrue(apt.setExecutable(true))
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
    fun existingLampacCanInstallMissingNodeWithoutDownloadingTheServerAgain() {
        val command = buildLampacNodeInstallCommand()

        assertTrue(command.contains("apt-get install -y --no-install-recommends nodejs"))
        assertTrue(command.contains("test -x /usr/bin/node"))
        assertTrue(command.contains("node --version"))
        assertTrue(command.contains("apt-get clean"))
    }

    @Test
    fun arm64RuntimeUsesDebianNodeForThePortablePlaywrightDriver() {
        val command = buildLampacServerCommand()

        assertTrue(command.contains("PLAYWRIGHT_NODEJS_PATH=/usr/bin/node"))
        assertTrue(command.contains("PATH=/opt/dotnet:\$PATH"))
        assertTrue(command.endsWith("exec /opt/dotnet/dotnet /opt/lampac/Core.dll"))
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
