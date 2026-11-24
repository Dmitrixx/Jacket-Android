# Lampa for Android

A personal Android and Android TV shell for the complete upstream Lampa web application and the full Lampac NextGen server.

## Included

- Complete upstream Lampa source pinned in third_party/lampa-source.
- Complete Lampac NextGen source pinned in third_party/lampac-source.
- Complete Lampa UI and its playback flow, built into private APK assets; the Android app sends online and torrent video streams (resolved through TorrServer/GStreamer) to the system external-player chooser by default, with HTML5 fallback when no compatible player is installed.
- Official full Lampac release archive, checksum-verified and extracted without filtering module files.
- App-private ARM64 Debian Forky-slim PRoot runtime with .NET 10, Google Chrome/Chromium, FFmpeg, GStreamer and the dependencies used by the upstream Lampac image.
- Installer removes the OCI download-layer cache after Debian is installed and omits unused development-header packages; all Lampac modules and runtime dependencies are retained.
- User-started local server on 127.0.0.1:9118.
- Loopback-only external-media compatibility proxy for Lampac online VOD (`/proxy/`, `/proxy-dash/`) and TorrServer/GStreamer (`/ts/stream`, `/ts/gst`): external players can probe media with HEAD, seek with HTTP Range requests, and receive the original Content-Length/chunked streaming framing.
- In-app module manager discovers the actual installed module manifests, lets users enable/disable individual modules, and applies changes through `BaseModule.SkipModules` without reinstalling Lampac.
- Applies an idempotent compatibility patch to the dynamic Aladin module before Lampac starts, preventing oversized `/proxy/` URLs while preserving the existing server installation and provider configuration.
- Keeps all checked online balancers available for manual retry in Lampa's source selector and records safe HTTP diagnostics when VLC/MX requests a proxied media URL.

## First start

1. Install the signed APK over the existing app. Do not uninstall it or clear app data.
2. Open Lampa and select Lampac in the upper-right corner.
3. Select Install full Lampac. The first installation downloads the full upstream server archive (about 480 MB), .NET Runtime and native dependencies. Several gigabytes of internal storage are required.
4. Keep the foreground service notification active while the server installs and starts.

The server data, providers and init.conf are stored in the app-private directory and survive APK upgrades.

The embedded WebView identifies itself with a private `Lampa-Android` marker. The build suppresses the upstream Android-APK recommendation only inside this installed wrapper; browser and Media Station X users continue to see the original recommendation.


## Updating

The application ID intentionally remains app.jacket.mobile. A newer APK signed with the same persistent key updates the existing installation and preserves app data.

## Scope

This repository has been reduced to Lampa, its Android WebView host and the full Lampac runtime. Jacket catalog/search screens, Jacket-native player, tracker UI, custom torrent engine and media catalog modules have been removed.

The complete upstream Lampac package is installed. Its module manifests and the user's configuration still determine each module's default state. Runtime dependencies used by the upstream deployment are installed, but some integrations require Linux services, browser sandbox capabilities, multicast/network features or device resources that differ under Android PRoot. Physical-device validation is required before claiming every feature works on every handset.

The HTTP listener is loopback-only by default. Do not expose this unauthenticated local server to a LAN or public network without reviewing upstream access controls.

## Build

Clone with submodules enabled, install npm dependencies in third_party/lampa-source, then run Gradle lint, unit tests, debug and release build tasks.

Release builds use the existing persistent signing credentials configured in GitHub Actions or local environment variables. Never commit the keystore or passwords.