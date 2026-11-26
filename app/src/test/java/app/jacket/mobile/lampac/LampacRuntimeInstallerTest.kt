package app.jacket.mobile.lampac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files

class LampacRuntimeInstallerTest {
    @Test
    fun debianBootstrapUsesTheUpstreamForkyOciImageAndStableAlias() {
        assertEquals(
            listOf("install", "docker.io/library/debian:forky-slim", "--override-alias", "lampac-debian"),
            lampacDebianInstallArguments()
        )
    }

    @Test
    fun arm32DebianUsesTrixieBecauseForkyNoLongerPublishesChromiumArmhf() {
        assertEquals(
            listOf("install", "docker.io/library/debian:trixie-slim", "--override-alias", "lampac-debian"),
            lampacDebianInstallArguments(LampacRuntimeArchitecture.ARM32)
        )
        assertEquals(
            listOf("install", "docker.io/library/debian:forky-slim", "--override-alias", "lampac-debian"),
            lampacDebianInstallArguments(LampacRuntimeArchitecture.ARM64)
        )
    }

    @Test
    fun selectsRuntimeByTheActualAndroidProcessBitness() {
        assertEquals(
            LampacRuntimeArchitecture.ARM32,
            selectLampacRuntimeArchitecture(false, listOf("armeabi-v7a", "armeabi"))
        )
        // A 32-bit app may run on a 64-bit-capable Android device.
        assertEquals(
            LampacRuntimeArchitecture.ARM32,
            selectLampacRuntimeArchitecture(false, listOf("arm64-v8a", "armeabi-v7a"))
        )
        assertEquals(
            LampacRuntimeArchitecture.ARM64,
            selectLampacRuntimeArchitecture(true, listOf("arm64-v8a", "armeabi-v7a"))
        )
        assertEquals(null, selectLampacRuntimeArchitecture(false, listOf("x86")))
        assertEquals(null, selectLampacRuntimeArchitecture(true, listOf("x86_64")))
    }

    @Test
    fun arm32InstallUsesDebianChromiumAndTheVerifiedArm32DotnetRuntime() {
        val command = buildLampacSystemPackagesInstallCommand(LampacRuntimeArchitecture.ARM32)

        assertTrue(command.contains("apt-get install -y --no-install-recommends chromium"))
        assertTrue(command.contains("ln -sf /usr/bin/chromium /usr/bin/google-chrome-stable"))
        assertFalse(command.contains("google-chrome-stable_current_arm64.deb"))
        assertTrue(command.contains("fontconfig libicu76 zlib1g"))
        assertFalse(command.contains("libicu78"))
        assertTrue(LampacRuntimeArtifacts.DOTNET_ARM32_URL.endsWith("aspnetcore-runtime-10.0.15-linux-arm.tar.gz"))
        assertTrue(LampacRuntimeArtifacts.DOTNET_ARM32_SHA512.matches(Regex("[0-9a-f]{128}")))
    }

    @Test
    fun clearsOnlyRegularFilesFromCompletedDebianImageLayerCache() {
        val prefix = Files.createTempDirectory("lampa-proot-cache").toFile()
        try {
            val cache = File(prefix, "var/lib/pr/dlcache")
            assertTrue(cache.mkdirs())

            val layer = File(cache, "sha256_layer").apply { writeBytes(ByteArray(1024)) }
            val partial = File(cache, "sha256_partial.partial").apply { writeBytes(ByteArray(256)) }
            val nestedDirectory = File(cache, "unexpected-directory").apply { mkdirs() }
            val nestedFile = File(nestedDirectory, "keep.bin").apply { writeText("keep") }
            val outside = File(prefix, "outside.bin").apply { writeText("outside") }
            val link = File(cache, "external-link")
            Files.createSymbolicLink(link.toPath(), outside.toPath())

            assertEquals(1280L, clearLampacProotDownloadCache(prefix))
            assertFalse(layer.exists())
            assertFalse(partial.exists())
            assertTrue(nestedFile.isFile)
            assertTrue(Files.isSymbolicLink(link.toPath()))
            assertTrue(outside.isFile)
            assertEquals(0L, clearLampacProotDownloadCache(prefix))
        } finally {
            prefix.deleteRecursively()
        }
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
    fun arm32RejectsOldForkyRootfsAndRequiresTrixieBeforePackageInstall() {
        val root = java.nio.file.Files.createTempDirectory("lampa-debian-arm32-codename").toFile()
        try {
            File(root, "etc").mkdirs()
            File(root, "bin").mkdirs()
            File(root, "usr/bin").mkdirs()
            val shell = File(root, "bin/sh").apply { writeText("placeholder") }
            val apt = File(root, "usr/bin/apt-get").apply { writeText("placeholder") }
            assertTrue(shell.setExecutable(true))
            assertTrue(apt.setExecutable(true))

            val osRelease = File(root, "etc/os-release")
            osRelease.writeText("ID=debian\nVERSION_CODENAME=forky\n")
            assertFalse(isDebianRootfsReady(root, LampacRuntimeArchitecture.ARM32))
            assertTrue(isDebianRootfsReady(root, LampacRuntimeArchitecture.ARM64))

            osRelease.writeText("ID=debian\nVERSION_CODENAME=trixie\n")
            assertTrue(isDebianRootfsReady(root, LampacRuntimeArchitecture.ARM32))
            assertFalse(isDebianRootfsReady(root, LampacRuntimeArchitecture.ARM64))
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
    fun debianPackageInstallsUseOfflineDpkgRootAndRecoverInterruptedConfiguration() {
        val packageCommand = buildLampacSystemPackagesInstallCommand()
        val nodeRepairCommand = buildLampacNodeInstallCommand()
        val offlineEnvironment =
            "export DEBIAN_FRONTEND=noninteractive DPKG_ROOT=/ SYSTEMD_OFFLINE=1 SYSTEMD_IN_CHROOT=1 LANG=C LC_ALL=C && "

        listOf(packageCommand, nodeRepairCommand).forEach { command ->
            assertTrue(command.startsWith(offlineEnvironment))
            assertTrue(command.contains("dpkg-divert --local --rename --divert"))
            assertTrue(command.contains("lampa_divert_stub /usr/bin/systemctl 0"))
            assertTrue(command.contains("lampa_divert_stub /usr/bin/systemd-machine-id-setup 0"))
            assertTrue(command.contains("lampa_divert_stub /usr/bin/systemd-sysusers 127"))
            assertTrue(command.contains("exec /var/lib/lampa-disabled-init/_usr_bin_systemd-sysusers"))
            assertTrue(command.contains("apt-get install -y --no-install-recommends opensysusers"))
            assertTrue(command.contains("dpkg --root=/ --configure -a"))
            assertTrue(command.contains("lampa_divert_stub /usr/bin/deb-systemd-invoke 0"))
            assertTrue(command.contains("lampa_divert_stub /usr/bin/deb-systemd-helper 0"))
            assertTrue(command.contains("lampa_divert_stub /usr/sbin/policy-rc.d 101"))
            assertTrue(command.contains("# lampa-proot-stub"))
            assertTrue(command.contains("rm -f /var/lib/dpkg/lock /var/lib/dpkg/lock-frontend"))
            assertFalse(command.contains("trap 'restore_systemd_machine_id_setup'"))
            assertFalse(command.contains("/usr/local/bin/systemd-machine-id-setup"))
            assertFalse(command.contains("sed -i"))
        }
        listOf(packageCommand, nodeRepairCommand).forEach { command ->
            val opensysusersInstall = command.indexOf("apt-get install -y --no-install-recommends opensysusers")
            val dpkgRecovery = command.indexOf("dpkg --root=/ --configure -a")
            assertTrue(opensysusersInstall >= 0)
            assertTrue(dpkgRecovery > opensysusersInstall)
        }

        listOf(packageCommand, nodeRepairCommand).forEach { command ->
            assertTrue(command.contains("/tmp/lampa-dpkg-install.log 2>&1"))
            assertTrue(command.contains("tail -n 120 /tmp/lampa-dpkg-install.log"))
            assertTrue(command.contains("status=\$?"))
            assertTrue(command.contains("if [ \"\$status\" -eq 0 ]; then"))
            assertTrue(command.contains("grep '^__LAMPAC_STAGE=' /tmp/lampa-dpkg-install.log"))
            assertTrue(command.contains("apt/dpkg errors and context"))
            assertTrue(command.contains("grep -Ein -B 3 -A 5"))
            assertTrue(command.contains("apt/dpkg failure log preserved for Android diagnostics"))
            assertTrue(command.contains("apt-get clean >/dev/null 2>&1 || true"))
            assertTrue(command.contains("rm -rf /var/lib/apt/lists/* /var/cache/apt/archives/*.deb"))
        }
        assertTrue(packageCommand.contains("echo '__LAMPAC_STAGE=apt-update' && apt-get update"))
        assertTrue(packageCommand.contains("echo '__LAMPAC_STAGE=opensysusers' && apt-get install -y --no-install-recommends opensysusers"))
        assertTrue(packageCommand.contains("echo '__LAMPAC_STAGE=dpkg-recovery' && dpkg --root=/ --configure -a"))
        assertTrue(packageCommand.contains("echo '__LAMPAC_STAGE=runtime-packages' && apt-get install"))
        assertTrue(packageCommand.contains("echo '__LAMPAC_STAGE=browser-package'"))
        assertTrue(packageCommand.contains("echo '__LAMPAC_STAGE=runtime-validation'"))
        assertTrue(packageCommand.contains("echo '__LAMPAC_STAGE=apt-cleanup'"))
        assertTrue(packageCommand.contains("apt-get install -y --no-install-recommends opensysusers"))
        assertTrue(packageCommand.contains("apt-get install -y --no-install-recommends ca-certificates"))
        listOf("libssl-dev", "libjpeg-dev", "libpng-dev", "libwebp-dev").forEach { packageName ->
            assertFalse("Build-only package should not be installed: $packageName", packageCommand.contains(packageName))
        }
        assertTrue(packageCommand.contains("google-chrome-stable_current_arm64.deb"))
        assertTrue(packageCommand.contains("gst-inspect-1.0 --version"))

        assertTrue(nodeRepairCommand.contains("apt-get install -y --no-install-recommends nodejs"))
        assertTrue(nodeRepairCommand.contains("test -x /usr/bin/node"))
        assertTrue(nodeRepairCommand.contains("node --version"))
        assertTrue(nodeRepairCommand.contains("apt-get clean"))
    }

    @Test
    fun provisionsPersistentDebianMachineIdWithoutOverwritingValidId() {
        val root = java.nio.file.Files.createTempDirectory("lampa-machine-id").toFile()
        try {
            val etc = File(root, "etc")
            assertTrue(etc.mkdirs())
            val machineIdFile = File(etc, "machine-id")

            ensureLampacDebianMachineId(root)
            val generated = machineIdFile.readText().trim()
            assertTrue(generated.matches(Regex("(?i)^[0-9a-f]{32}$")))

            ensureLampacDebianMachineId(root)
            assertEquals(generated, machineIdFile.readText().trim())
            assertTrue(Files.isSymbolicLink(File(root, "var/lib/dbus/machine-id").toPath()))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun replacesInvalidDebianMachineIdAndResolvesRootRelativeSymlinks() {
        val root = java.nio.file.Files.createTempDirectory("lampa-machine-id-link").toFile()
        try {
            val etc = File(root, "etc")
            val dbus = File(root, "var/lib/dbus")
            assertTrue(etc.mkdirs())
            assertTrue(dbus.mkdirs())
            File(dbus, "machine-id").writeText("invalid")
            Files.createSymbolicLink(File(etc, "machine-id").toPath(), java.nio.file.Paths.get("/var/lib/dbus/machine-id"))

            ensureLampacDebianMachineId(root)
            assertFalse(Files.isSymbolicLink(File(etc, "machine-id").toPath()))
            assertTrue(File(etc, "machine-id").readText().trim().matches(Regex("(?i)^[0-9a-f]{32}$")))
            assertTrue(Files.isSymbolicLink(File(dbus, "machine-id").toPath()))
            assertEquals("/etc/machine-id", Files.readSymbolicLink(File(dbus, "machine-id").toPath()).toString())
        } finally {
            root.deleteRecursively()
        }
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
