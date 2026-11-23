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

The Android application ID remains app.jacket.mobile so the new build updates the existing installation instead of creating a second app or losing private data. The displayed name is Lampa.

## Complete upstream payload

The installer retrieves the full official Lampac release archive and does not exclude or delete upstream module directories. The archive is checked for size, SHA-256, traversal paths, excessive expansion and incomplete ZIP entries before activation.

The ARM64 Debian Forky-slim environment is installed as an OCI rootfs and then receives ASP.NET Core Runtime 10, Google Chrome/Chromium, FFmpeg/FFprobe, GStreamer plugins and tools, ImageMagick, Xvfb, fonts and the native libraries used by the upstream deployment. Chromium receives the upstream-documented `--no-sandbox` flag because the server runs as root inside Android PRoot, where Chromium's normal namespace sandbox is unavailable.

A fresh init.conf loads every module with an empty SkipModules array. Existing configs retain provider credentials and custom sections; during the Debian alias migration the installer checks both the current runtime root and both previous Debian rootfs layouts before any incomplete container is replaced. Previous app-added module exclusions are cleared. Explicit choices such as chromium.enable=false remain intact. The local HTTP listener is kept on loopback.

The module package is complete. Individual features can still require user accounts, provider availability, Linux networking, multicast, browser sandbox support or native services that vary between Android devices.

## Device constraints

- Embedded PRoot is ARM64-only.
- The server is loopback-only; it is not advertised as a LAN server.
- Several gigabytes of free internal storage are required for Debian, Lampac, .NET, Chromium and media dependencies.
- Playback remains in Lampa's own WebView player. No Jacket-native Media3 player or custom torrent engine is included.

## Validation

CI can build the Android shell and test configuration helpers. A physical ARM64 Android device must still be used to verify Debian setup, .NET startup, Chromium automation, GStreamer, FFmpeg, module loading, provider requests, playback, stop/restart, storage pressure and battery/RAM use. Do not claim every module works on Android until these tests pass.