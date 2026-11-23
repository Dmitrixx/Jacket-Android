# Lampa-only code audit

Audit baseline: the Lampa-only application at the 3.0.0 / versionCode 25 transition.

## Android application source

The first-party Android source now contains only:

- the launcher activity and Lampa Compose shell;
- the WebView host, fullscreen handling and the small JavaScript bridge required by upstream Lampa;
- Lampac configuration, installation, PRoot process management, foreground-service lifecycle and runtime state;
- the app theme, launcher/TV artwork, network policy and tests for those components.

No previous app-specific catalog/search screens, Filmix/HDRezka native clients, Media3 player, custom libtorrent engine, torrent queue/server, tracker/indexer management UI or source-picker implementation remains in the Android source tree. The Android Gradle dependency set contains Compose/AndroidX, the PRoot module and test dependencies; it does not include Media3 or libtorrent.

## Upstream code intentionally retained

The complete upstream Lampa source is kept intact. Its own torrent components and online plugins are part of Lampa, not the removed Android-native implementation. The WebView bridge methods for torrent links, YouTube, player-reset and advertising-ID requests are retained because upstream Lampa calls them. The advertising-ID bridge deliberately returns an empty value.

The complete Lampac source and full official release archive are also retained. Lampac's JacRed / Jackett-compatible configuration belongs to Lampac's own source/indexer subsystem; it is not the removed app's former Jackett management interface.

Do not delete or prune upstream Lampa plugins or Lampac module directories as part of this cleanup. Doing so would turn the requested complete upstream clients/server into a reduced fork.

## Compatibility items intentionally retained

- Android `applicationId` and Gradle `namespace` remain `app.jacket.mobile`. This is the existing installed package identifier needed for an in-place APK update with private app data preserved. It is not a remaining Jacket feature.
- GitHub Actions signing secret and environment names beginning with `JACKET_ ` remain unchanged so the already configured persistent keystore can still sign updates. The internal Gradle signing-config name and temporary keystore path use Lampa naming.
- Lampac installer migration code continues to recognize previous Debian rootfs layouts and preserve `init.conf` and provider credentials while replacing incomplete/filtered runtime payloads.

## Result

The executable Android client is Lampa plus the local full Lampac runtime. The remaining legacy-looking identifiers are package/signing continuity or data-migration safeguards, not active Jacket catalog, playback, tracker or torrent-engine code.
