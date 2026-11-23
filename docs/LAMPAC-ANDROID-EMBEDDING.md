# Full Lampac NextGen on Android

## Architecture

The app contains the complete upstream Lampa web client and a minimal Android WebView host. Lampac NextGen runs as a local .NET 10 process inside an app-private ARM64 Debian Forky-slim OCI root filesystem, matching the distribution used by the upstream Lampac runtime image.

- Lampa UI: third_party/lampa-source, compiled into APK assets.
- Lampac source: third_party/lampac-source, pinned to the commit for release 1.90.2.
- Runtime payload: official full lampac-nextgen.zip release asset, checksum-verified before extraction.
- Local endpoint: http://127.0.0.1:9118.
- PRoot OCI alias: lampac-debian; the app does not overwrite or remove another Debian container.
- Runtime data and init.conf: filesDir/lampac, preserved across app upgrades.
- Process lifecycle: user-started foreground LampacRuntimeService.

The Android application ID remains app.jacket.mobile so the new build updates the existing installation instead of creating a second app or losing private data.

The POCO F3 (M2012K11AG / alioth) uses an Android 4.19 kernel. The Debian Forky systemd 260 tools require the newer `STATX_MNT_ID` statx feature; on this kernel `systemd-sysusers` can report `Failed to read 'basic.conf'` even when that packaged file exists. The installer diverts `/usr/bin/systemd-sysusers` to a no-op because this rootfs is not booted with systemd and no systemd-managed daemons are started. Debian's base-passwd package supplies the standard base groups. The application and Lampac run as Android-managed processes. The installer uses Debian's `opensysusers` package as the `systemd-sysusers` provider: this processes package-supplied `/usr/lib/sysusers.d/*.conf` files to create service users and groups (for example, D-Bus's `messagebus`). Only systemd's service manager and service-start helpers are diverted; account provisioning remains enabled. The Android 4.19 kernel cannot run systemd 260's `STATX_MNT_ID`-dependent sysusers implementation, so the compatible opensysusers implementation is installed before pending dpkg packages are configured. The displayed name is Lampa.

## Complete upstream payload

The installer retrieves the full official Lampac release archive and does not exclude or delete upstream module directories. The archive is checked for size, SHA-256, traversal paths, excessive expansion and incomplete ZIP entries before activation.

The ARM64 Debian Forky-slim environment is installed as an OCI rootfs and then receives ASP.NET Core Runtime 10, Node.js, Google Chrome/Chromium, FFmpeg/FFprobe, GStreamer plugins and tools, ImageMagick, Xvfb, fonts and the native libraries used by the upstream deployment. Because this rootfs runs under PRoot rather than as a booted Linux system, apt/dpkg transactions set `DPKG_ROOT=/` and `SYSTEMD_OFFLINE=1`, then run `dpkg --root=/ --configure -a` to recover interrupted transactions. Before dpkg runs, the Android host provisions a persistent 32-hex machine-id in the private rootfs. For the entire dpkg/apt transaction, the installer temporarily places a no-op `systemd-machine-id-setup` shim in `/usr/local/bin` and prepends that directory to `PATH`. Debian's `systemd.postinst` invokes the helper by command name; inside Android PRoot it fails with EUNATCH (reported as `Protocol driver not attached`) because systemd is not PID 1. Keeping the shim active through both `dpkg --configure -a` and `apt-get install` also covers a systemd postinst re-run during a package upgrade. Any pre-existing helper is backed up and restored by an exit trap on success or failure; the Debian maintainer script itself is not edited. Chromium receives the upstream-documented `--no-sandbox` flag because the server runs as root inside Android PRoot, where Chromium's normal namespace sandbox is unavailable.

The installer does not depend on the platform-specific Node executable bundled in the generic Lampac release. It verifies that both architecture-independent Playwright JavaScript entrypoints are present, installs ARM64 Node.js from Debian and sets `PLAYWRIGHT_NODEJS_PATH=/usr/bin/node` at server startup. This gives Playwright a known ARM64 Node runtime instead of assuming the archive's bundled executable matches the phone.

A fresh init.conf loads every module with an empty SkipModules array. Existing configs retain provider credentials and custom sections; during the Debian alias migration the installer checks both the current runtime root and both previous Debian rootfs layouts before any incomplete container is replaced. Previous app-added module exclusions are cleared. Explicit choices such as chromium.enable=false remain intact. The local HTTP listener is kept on loopback.

The module package is complete. Individual features can still require user accounts, provider availability, Linux networking, multicast, browser sandbox support or native services that vary between Android devices.

## Device constraints

- Embedded PRoot is ARM64-only.
- The server is loopback-only; it is not advertised as a LAN server.
- Several gigabytes of free internal storage are required for Debian, Lampac, .NET, Chromium and media dependencies.
- Playback remains in Lampa's own WebView player. No Jacket-native Media3 player or custom torrent engine is included.

## Validation

CI can build the Android shell and test configuration helpers. A physical ARM64 Android device must still be used to verify Debian setup, .NET startup, Chromium automation, GStreamer, FFmpeg, module loading, provider requests, playback, stop/restart, storage pressure and battery/RAM use. Do not claim every module works on Android until these tests pass.