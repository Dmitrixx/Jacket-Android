# Lampa for Android

A personal Android and Android TV shell for the complete upstream Lampa web application and the full Lampac NextGen server.

## Included

- Complete upstream Lampa source pinned in third_party/lampa-source.
- Complete Lampac NextGen source pinned in third_party/lampac-source.
- Unmodified Lampa UI and its own HTML5 player, built into private APK assets.
- Official full Lampac release archive, checksum-verified and extracted without filtering module files.
- App-private ARM64 Debian Forky-slim PRoot runtime with .NET 10, Google Chrome/Chromium, FFmpeg, GStreamer and the dependencies used by the upstream Lampac image.
- User-started local server on 127.0.0.1:9118.

## First start

1. Install the signed APK over the existing app. Do not uninstall it or clear app data.
2. Open Lampa and select Lampac in the upper-right corner.
3. Select Install full Lampac. The first installation downloads the full upstream server archive (about 480 MB), .NET Runtime and native dependencies. Several gigabytes of internal storage are required.
4. Keep the foreground service notification active while the server installs and starts.

The server data, providers and init.conf are stored in the app-private directory and survive APK upgrades.

## Updating

The Android application ID intentionally remains `app.jacket.mobile`. This is a package-compatibility identifier, not a remaining Jacket feature: changing it would make Android treat this build as a different app and prevent an in-place update of the existing installation and its private Lampac data. The same persistent signing certificate must also be used for every update.

## Scope

The Android-specific catalog/search screens, direct provider clients, native media player, tracker/indexer interface, and custom torrent engine/server from the previous client have been removed. The remaining Android code is the Lampa WebView host, its small platform bridge, and the local Lampac runtime controller.

The complete upstream Lampa source and complete Lampac package are intentionally retained, including their own plugins and source modules. These are upstream Lampa/Lampac functionality, not the removed Android-specific Jacket implementation.

The complete upstream Lampac package is installed. Its module manifests and the user's configuration still determine each module's default state. Runtime dependencies used by the upstream deployment are installed, but some integrations require Linux services, browser sandbox capabilities, multicast/network features or device resources that differ under Android PRoot. Physical-device validation is required before claiming every feature works on every handset.

The HTTP listener is loopback-only by default. Do not expose this unauthenticated local server to a LAN or public network without reviewing upstream access controls.

## Build

Clone with submodules enabled, install npm dependencies in third_party/lampa-source, then run Gradle lint, unit tests, debug and release build tasks.

Release builds use the existing persistent signing credentials configured in GitHub Actions or local environment variables. The legacy `JACKET_*` secret/environment-variable names are retained only so the existing private signing key continues to work; they do not enable any Jacket application features. Never commit the keystore or passwords.
