# Changelog

## 2.8.7

- Enable Lampac's built-in JacRed module in local Jackett-compatible mode; existing installations are migrated by removing only the JacRed exclusion and adding defaults only when the corresponding settings are absent.
- Add `/api/v2.0/indexers/all/results` search to Jacket's unified source pipeline so Lampac's configured Rutor/Kinozal/NNMClub/RuTracker/Toloka and other JacRed adapters contribute magnets or same-origin direct `.torrent` download links.
- Keep JacRed failures isolated from VOD results; validate resolver links against the configured Lampac origin and reject malformed magnets or foreign resolver URLs.
- Add config-migration and JacRed response-parser tests.

## 2.8.6

- Fix the local Lampac auto-connect retry state: an earlier startup error can no longer cancel a new pending connection before the Android service has entered INSTALLING/STARTING.
- Clear the pending connection only after the current startup attempt reaches RUNNING or fails after startup has been observed.

## 2.8.5

- Automatically connect the embedded loopback Lampac server to Jacket's unified source search after its health check succeeds; persist the local address and enable the Lampac search provider without a separate manual step.
- Raise the dynamic Lampac route ceiling from 48 to 96 while retaining four concurrent provider requests, covering the documented 70+ provider catalogue.
- Add persistent Release APK signing through a user-owned keystore supplied from GitHub Actions secrets. Once configured, APKs can update in place without uninstalling or deleting the embedded Debian/.NET/Lampac data.

## 2.8.4

- Fix embedded Lampac startup on ARM64: skip its optional GStreamer module, whose startup unconditionally initializes native GLib/GStreamer libraries that are not part of the minimal Android Debian rootfs.
- Migrate existing installations before every server launch by adding only `GStreamer` to `BaseModule.SkipModules`; preserve other `init.conf` values and keep the migration idempotent.
- Add unit coverage for the module exclusion and non-destructive config migration.

## 2.8.3

- Expand the embedded Lampac diagnostic panel to show the last 24 sanitized process-output lines in a scrollable log. This preserves the original .NET exception and inner socket/host error, which were hidden when only the last four stack frames were shown.
- Keep diagnostic output in the app's existing redacted log buffer; credentials and cookies remain sanitized.

## 2.8.2

- Fix embedded Lampac cold startup on Android: allow five minutes for .NET provider-module compilation instead of interrupting it after 20 seconds with Ctrl+C / exit code 130.
- Probe the documented local Lampac health endpoint `/version?type=hash`; retain the actual startup error instead of replacing it with the intentional shutdown signal.
- Show the first-start compilation window in Lampac settings and add monotonic timeout policy tests.

## 2.8.1

- Add a one-time upgrade migration that re-enables existing built-in tracker/site profiles. User-edited names, encrypted Cookies and custom sites are preserved; later manual enable/disable choices remain respected.
- Add a universal debug-signed APK artifact to each successful main-branch CI build, with a 14-day retention period and a download link from the repository README.

## 2.8.0

- Seed the built-in tracker/site list on first launch and one-time migration. Existing custom entries, disabled presets and encrypted Cookie sessions are preserved; manual restore remains explicit and re-enables built-in presets.
- Update Filmix device pairing to the current Lampa-compatible Android fingerprint and profile-confirmed activation; guard replacement/cancelled pairing operations against stale responses.
- Make the optional HTTPS Filmix relay user-configured and blank by default. The settings screen warns that a relay operator can see the decoded device token and profile requests.

- Add an optional on-device Lampac NextGen runtime for ARM64: private Debian + .NET 10 rootfs, checksum-pinned artifacts, bounded extraction, secure log redaction, start/stop controls and local-only binding at 127.0.0.1:9118.
- Add PRoot JNI packaging, NDK/CMake CI setup and tests for artifact URL policy, runtime config, sanitized output and cancellation-safe state. Non-ARM64 devices keep the remote HTTPS Lampac connector.

- Expand Lampac federation to dynamically discovered routes (up to 48) with four concurrent workers; retain per-provider errors and successful partial results while displaying the real provider name.
- Route the eight optional video-source catalogue entries through Lampac modules when installed and enabled, without claiming every module exists on every server.

- Add consistent three-dot action menus to provider, tracker, direct-indexer and Jackett catalogue cards, with test/search/configure/edit/delete actions where supported.
- Add a configurable peer-connection cap for the built-in torrent engine (automatic device-aware defaults or a user-selected 20–500 limit).

- Restrict automatic HDRezka mirror redirects to exact known HTTPS hosts and port 443; prevent stale login or mirror-check jobs from restoring a disconnected session or overwriting a newer operation.

- Consolidated native Filmix and HDRezka search/player adapters, encrypted account state, provider diagnostics and the unified detail-card source picker.
- Filmix AUTO mode checks the explicitly saved custom HTTPS API first (when present), then the official primary and reserve hosts; activation polling and catalog requests stay pinned to the host that issued the device code.
- Allow an explicitly user-entered HTTPS Filmix API host with strict URL/host validation, normal certificate validation and no cross-host token forwarding.

- Added the native optional Jackett indexer catalogue, dynamic per-indexer configuration/testing, private-indexer support and richer torrent release cards. Jackett remains user-managed and is not bundled.
- Added per-tracker in-app connection/parser checks for RuTracker, NNM-Club, Rutor, Kinozal, 1337x and The Pirate Bay; the settings screen now uses an editable test query and shows each provider's last result or error.
- Made the sites-and-sources settings easier to navigate: direct links to the dynamic Jackett/RSS/Torznab catalogue and to Lampac settings, with explicit descriptions of Filmix/HDRezka/Lampac versus browser-only shortcuts.
- Added Jackett WebUI password login; the administrator password is transient, while only the returned session Cookie is stored in encrypted local settings.
- Extended release-title labels with CAMRip/TeleSync/TeleCine/SCR, 10-bit/Hi10P, HLG, HDR10+ and recognized dubbing studios; added an optional filter to hide screen-copy releases.
- Added a ten-provider catalogue in priority order. Only Filmix and HDRezka have direct adapters; ColdFilm, Baskino, Kinogo, VideoCDN, Collaps, Kinobase, CDNMovies and Lordfilm remain unconnected descriptors pending a compatible verified integration.
- Added completion-order partial tracker results, bounded detail-page resolution, a 25-second aggregate deadline and protection against stale search callbacks.
- Run torrent indexers, built-in HTML trackers, Filmix, HDRezka and Lampac detail-card searches concurrently; publish each provider's results as soon as they arrive, with a shared 60-second deadline and late-callback protection.
- Match the Android 12+ display refresh rate to the active video frame rate on SurfaceView playback; clear the request when playback pauses or stops and when the surface is detached. Older Android versions remain unchanged.
- Replace torrent piece polling with a condition signal driven by libtorrent piece-finished alerts; pause/removal, session shutdown and superseded seek windows wake waiting stream workers, cancelling stale HTTP Range work. Clear active stream leases before shutdown notification and check the stop state immediately before condition waits.
- Add a tail-piece deadline to the initial torrent byte-range plan, after the bounded forward window, and lower the global peer-connection ceiling on low-RAM Android devices.
- Add the Media3 1.11.1-matched LGPL FFmpeg audio extension for AC-3, E-AC-3, DTS, MLP and TrueHD fallback. Prefer extension renderers, preserve bundled license metadata, and expose the complete notices, LGPL text and exact source archive in the app.
- Shrink release resources and produce ABI-specific APKs for arm64-v8a, armeabi-v7a, x86 and x86_64 while retaining a universal APK.

- Restricted built-in HTML tracker requests, redirects and parsed download URLs to public HTTPS targets, including non-public IPv6 rejection. DNS rebinding is not fully prevented.
- Made initial torrent byte-range failures retryable with a sanitized HTTP 503 and Retry-After header before a successful response is committed.
- Hardened torrent persistence with bounded validation, per-record queue recovery, legacy migration, serialized resume checkpoints and AtomicFile backup recovery.
- Preserved saveable state across main, details, settings, downloads, player and individual navigation tabs.
- Clear stale saved resume offsets when playback is explicitly reset to 0 ms; keep the 90% watched cleanup threshold overflow-safe.

## 2.7.0

- Fullscreen cinema player for phone and Android TV. Controls sit on top of the video and hide while playback continues.
- D-pad left/right seeks by 10 seconds when the overlay is hidden. Back hides the overlay first, then leaves the player. Center and media keys play and pause.
- Playback speed from 0.5× to 2×, picture fit / fill / zoom, and a sleep timer at 15, 30, 45 and 60 minutes. Speed and fit are remembered on the device.
- Phone picture-in-picture so a title can stay on screen after leaving Jacket. The player keeps the activity across rotation and PiP.
- Seek bar, elapsed time and remaining time in the overlay. Tap the left or right side of a hidden player to jump 10 seconds.
- Continue watching now shows the remaining time from the saved position.
- Includes the previously unreleased fixes already on main: bounded HDRezka page preview, Filmix device-authorization diagnostics, and explicit in-app torrent search on movie and episode cards.
- Picture-in-picture state is observed through the activity callback, so the build does not depend on an ActivityX type missing from this SDK line.

## 2.6.0

- Added a Lampa-style source picker sheet that opens automatically from movie and episode playback actions.
- Replaced global sequential torrent mode during playback with HTTP Range-driven piece deadlines, starting at the exact requested byte position.
- Added an ordered forward prefetch window capped at 8 MiB / 32 pieces; seek requests move the priority window instead of restarting from byte zero.
- Added a 32 MiB LRU disk ring for recently served byte ranges, including overlapping-range reuse and automatic eviction.
- Kept playback cache buffers bounded to 256 KiB per active stream worker; cache eviction never deletes verified torrent payload data.
- Added JVM tests for range-to-piece mapping, prefetch bounds, LRU eviction, overlapping byte-range hits and loopback Range delivery.

## 2.5.0

- Added an optional Jackett connector for a user-managed LAN or HTTPS server.
- Added `t=caps` connectivity checks, indexer filter selection and tracker attribution in source results.
- Improved Torznab parsing for Jackett tracker tags, comments, sizes, total peers and API error responses.
- Added a strict local-network HTTP policy; public indexers continue to require HTTPS.
- Enabled authenticated .torrent downloads from a local Jackett endpoint with origin-restricted credentials and redirect handling.
- Increased ExoPlayer's read timeout for loopback torrent streams so slow swarms have time to provide requested pieces.
- Preserved the existing in-process libtorrent engine, tokenized loopback stream server, Filmix pairing, HDRezka settings and built-in tracker shortcuts.

## Compatibility notes

- Jackett is not bundled and is not required. Direct RSS/Atom and Torznab sources continue to work without it.
- The uploaded Jacket 2.1.0 source set was not copied over the current application wholesale. Older duplicate torrent server, player, activity, manifest and Gradle files were superseded by newer implementations in the current main branch. Compatible Jackett functionality was merged into the current architecture instead.
